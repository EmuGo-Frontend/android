#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "libretro.h"
#include "ra.h"
#include "vfs.h"

static JavaVM* vm = nullptr;
static jobject activity = nullptr;
static jmethodID frameMethod = nullptr, audioMethod = nullptr, audioFormatMethod = nullptr,
                 savedMethod = nullptr, geometryMethod = nullptr;
static std::atomic<bool> running{false};
static std::atomic<bool> paused{false};
static std::atomic<bool> saveStatesEnabled{false};
static std::atomic<bool> flushRequested{false};
static std::atomic<unsigned> buttons{0};
static std::atomic<int16_t> axes[4];
// Touch screen as the libretro pointer (DS): -0x7fff..0x7fff across the picture.
static std::atomic<int16_t> pointerX{0}, pointerY{0};
static std::atomic<bool> pointerDown{false};
// workDir holds EmuGo!'s own battery and state files and is the core's system folder; saveDir
// is where the core writes save files of its own.
static std::string workDir, systemDir, saveDir;
static std::atomic<double> targetFps{60.0};
static unsigned pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
static void* handle = nullptr;
static retro_memory_map memoryMap{};
static std::vector<retro_memory_descriptor> memoryDescriptors;
static std::mutex variableLock;
static std::map<std::string, std::string> variables;
static std::map<std::string, std::string> variableValues;  // keeps returned pointers alive

static void RETRO_CALLCONV coreLog(enum retro_log_level level, const char* format, ...) {
  va_list args;
  va_start(args, format);
  int priority = level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR
                 : level == RETRO_LOG_WARN ? ANDROID_LOG_WARN
                 : level == RETRO_LOG_INFO ? ANDROID_LOG_INFO
                                           : ANDROID_LOG_DEBUG;
  __android_log_vprint(priority, "EmuGo-core", format, args);
  va_end(args);
}
// Hardware rendering: cores that draw with OpenGL ES (N64, Dreamcast, PSP, Saturn) render into an
// offscreen framebuffer on the core thread. Each finished frame is read back and shown through
// the same path as software frames, so scaling, screenshots and the pause menu work unchanged.
static retro_hw_render_callback hw{};
static bool hwRequested = false;
static EGLDisplay eglDisplay = EGL_NO_DISPLAY;
static EGLContext eglContext = EGL_NO_CONTEXT;
static EGLSurface eglSurface = EGL_NO_SURFACE;
static GLuint fbo = 0, fboColor = 0, fboDepth = 0;
static unsigned fboWidth = 0, fboHeight = 0;
static void* glesLibrary = nullptr;
static std::vector<uint32_t> readback;

static uintptr_t RETRO_CALLCONV currentFramebuffer() { return fbo; }
static retro_proc_address_t RETRO_CALLCONV procAddress(const char* name) {
  if (!glesLibrary) glesLibrary = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
  if (!glesLibrary) glesLibrary = dlopen("libGLESv2.so", RTLD_NOW | RTLD_LOCAL);
  void* function = glesLibrary ? dlsym(glesLibrary, name) : nullptr;
  if (!function) function = (void*)eglGetProcAddress(name);
  return (retro_proc_address_t)function;
}
static void deleteFramebuffer() {
  if (fbo) glDeleteFramebuffers(1, &fbo);
  if (fboColor) glDeleteTextures(1, &fboColor);
  if (fboDepth) glDeleteRenderbuffers(1, &fboDepth);
  fbo = fboColor = fboDepth = 0;
  fboWidth = fboHeight = 0;
}
static bool createFramebuffer(unsigned width, unsigned height) {
  deleteFramebuffer();
  glGenTextures(1, &fboColor);
  glBindTexture(GL_TEXTURE_2D, fboColor);
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
  glGenFramebuffers(1, &fbo);
  glBindFramebuffer(GL_FRAMEBUFFER, fbo);
  glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboColor, 0);
  if (hw.depth) {
    glGenRenderbuffers(1, &fboDepth);
    glBindRenderbuffer(GL_RENDERBUFFER, fboDepth);
    glRenderbufferStorage(GL_RENDERBUFFER, hw.stencil ? GL_DEPTH24_STENCIL8 : GL_DEPTH_COMPONENT24,
                          width, height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, fboDepth);
    if (hw.stencil)
      glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, fboDepth);
  }
  bool complete = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
  glClearColor(0, 0, 0, 1);
  glClear(GL_COLOR_BUFFER_BIT);
  fboWidth = width;
  fboHeight = height;
  return complete;
}
static void destroyContext() {
  if (eglDisplay == EGL_NO_DISPLAY) return;
  if (eglContext != EGL_NO_CONTEXT) deleteFramebuffer();
  eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  if (eglContext != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay, eglContext);
  if (eglSurface != EGL_NO_SURFACE) eglDestroySurface(eglDisplay, eglSurface);
  eglContext = EGL_NO_CONTEXT;
  eglSurface = EGL_NO_SURFACE;
  eglDisplay = EGL_NO_DISPLAY;
}
// An OpenGL ES 3 context (ES 2 as a fallback) current on the core thread, drawing offscreen.
static bool createContext(unsigned width, unsigned height) {
  eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (eglDisplay == EGL_NO_DISPLAY || !eglInitialize(eglDisplay, nullptr, nullptr)) {
    eglDisplay = EGL_NO_DISPLAY;
    return false;
  }
  for (int version : {3, 2}) {
    const EGLint configAttributes[] = {EGL_RENDERABLE_TYPE,
                                       version == 3 ? EGL_OPENGL_ES3_BIT_KHR : EGL_OPENGL_ES2_BIT,
                                       EGL_SURFACE_TYPE,
                                       EGL_PBUFFER_BIT,
                                       EGL_RED_SIZE,
                                       8,
                                       EGL_GREEN_SIZE,
                                       8,
                                       EGL_BLUE_SIZE,
                                       8,
                                       EGL_ALPHA_SIZE,
                                       8,
                                       EGL_DEPTH_SIZE,
                                       24,
                                       EGL_STENCIL_SIZE,
                                       8,
                                       EGL_NONE};
    EGLConfig config;
    EGLint count = 0;
    if (!eglChooseConfig(eglDisplay, configAttributes, &config, 1, &count) || count < 1) continue;
    const EGLint surfaceAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, version, EGL_NONE};
    eglSurface = eglCreatePbufferSurface(eglDisplay, config, surfaceAttributes);
    eglContext = eglCreateContext(eglDisplay, config, EGL_NO_CONTEXT, contextAttributes);
    if (eglSurface != EGL_NO_SURFACE && eglContext != EGL_NO_CONTEXT &&
        eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
      return createFramebuffer(width, height);
    if (eglContext != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay, eglContext);
    if (eglSurface != EGL_NO_SURFACE) eglDestroySurface(eglDisplay, eglSurface);
    eglContext = EGL_NO_CONTEXT;
    eglSurface = EGL_NO_SURFACE;
  }
  eglDisplay = EGL_NO_DISPLAY;
  return false;
}
static void notifyGeometry(const retro_game_geometry& geometry) {
  float aspect = geometry.aspect_ratio > 0 ? geometry.aspect_ratio
                 : geometry.base_height    ? (float)geometry.base_width / geometry.base_height
                                           : 0.0f;
  JNIEnv* env = nullptr;
  if (!vm || !activity || !geometryMethod) return;
  vm->GetEnv((void**)&env, JNI_VERSION_1_6);
  if (!env) return;
  env->CallVoidMethod(activity, geometryMethod, (jfloat)aspect);
  if (env->ExceptionCheck()) env->ExceptionClear();
}
static bool environment(unsigned command, void* data) {
  switch (command) {
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
      *static_cast<bool*>(data) = true;
      return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
      pixelFormat = *static_cast<retro_pixel_format*>(data);
      return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
      *static_cast<const char**>(data) = systemDir.c_str();
      return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
      *static_cast<const char**>(data) = saveDir.c_str();
      return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
      static_cast<retro_log_callback*>(data)->log = coreLog;
      return true;
    case RETRO_ENVIRONMENT_GET_VFS_INTERFACE: {
      auto* info = static_cast<retro_vfs_interface_info*>(data);
      if (info->required_interface_version > 3) return false;
      info->required_interface_version = 3;
      info->iface = vfs::interface();
      return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
      *static_cast<bool*>(data) = false;
      return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
      auto* variable = static_cast<retro_variable*>(data);
      if (!variable || !variable->key) return false;
      std::lock_guard<std::mutex> lock(variableLock);
      auto found = variables.find(variable->key);
      if (found == variables.end()) {
        variable->value = nullptr;
        return false;
      }
      variableValues[found->first] = found->second;
      variable->value = variableValues[found->first].c_str();
      return true;
    }
    case RETRO_ENVIRONMENT_SET_VARIABLES:
      return true;
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
      return true;
    case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
      return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
      auto* callback = static_cast<retro_hw_render_callback*>(data);
      if (callback->context_type != RETRO_HW_CONTEXT_OPENGLES2 &&
          callback->context_type != RETRO_HW_CONTEXT_OPENGLES3 &&
          callback->context_type != RETRO_HW_CONTEXT_OPENGLES_VERSION)
        return false;
      callback->get_current_framebuffer = currentFramebuffer;
      callback->get_proc_address = procAddress;
      hw = *callback;
      hwRequested = true;
      return true;
    }
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
      *static_cast<unsigned*>(data) = RETRO_HW_CONTEXT_OPENGLES3;
      return true;
    case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT:
      return true;
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
      notifyGeometry(*static_cast<const retro_game_geometry*>(data));
      return true;
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
      auto* info = static_cast<const retro_system_av_info*>(data);
      if (info->timing.fps > 1) targetFps = info->timing.fps;
      if (fbo && (info->geometry.max_width > fboWidth || info->geometry.max_height > fboHeight))
        createFramebuffer(std::max(info->geometry.max_width, fboWidth),
                          std::max(info->geometry.max_height, fboHeight));
      notifyGeometry(info->geometry);
      return true;
    }
    case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:
      *static_cast<uint64_t*>(data) =
          (1 << RETRO_DEVICE_JOYPAD) | (1 << RETRO_DEVICE_ANALOG) | (1 << RETRO_DEVICE_POINTER);
      return true;
    case RETRO_ENVIRONMENT_SET_MEMORY_MAPS: {
      auto* map = static_cast<const retro_memory_map*>(data);
      memoryDescriptors.clear();
      if (map && map->descriptors)
        memoryDescriptors.assign(map->descriptors, map->descriptors + map->num_descriptors);
      memoryMap.descriptors = memoryDescriptors.data();
      memoryMap.num_descriptors = (unsigned)memoryDescriptors.size();
      return true;
    }
    default:
      return false;
  }
}
static void video(const void* data, unsigned width, unsigned height, size_t pitch) {
  if (!data || !activity) return;
  JNIEnv* env = nullptr;
  vm->GetEnv((void**)&env, JNI_VERSION_1_6);
  if (!env) return;
  std::vector<jint> argb;
  if (data == RETRO_HW_FRAME_BUFFER_VALID) {
    if (!fbo) return;
    width = std::min(width, fboWidth);
    height = std::min(height, fboHeight);
    if (!width || !height) return;
    readback.resize(width * height);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, readback.data());
    argb.resize(width * height);
    for (unsigned y = 0; y < height; y++) {
      // OpenGL rows start at the bottom unless the core says otherwise.
      const uint32_t* row = &readback[(hw.bottom_left_origin ? height - 1 - y : y) * width];
      for (unsigned x = 0; x < width; x++) {
        uint32_t p = row[x];  // bytes R, G, B, A
        argb[y * width + x] =
            (jint)(0xff000000u | ((p & 0xff) << 16) | (p & 0xff00) | ((p >> 16) & 0xff));
      }
    }
    jintArray pixels = env->NewIntArray(argb.size());
    env->SetIntArrayRegion(pixels, 0, argb.size(), argb.data());
    env->CallVoidMethod(activity, frameMethod, pixels, (jint)width, (jint)height);
    env->DeleteLocalRef(pixels);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return;
  }
  argb.resize(width * height);
  for (unsigned y = 0; y < height; y++)
    for (unsigned x = 0; x < width; x++) {
      unsigned p = 0;
      if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
        p = ((const uint32_t*)((const uint8_t*)data + y * pitch))[x] | 0xff000000;
      } else {
        uint16_t raw = ((const uint16_t*)((const uint8_t*)data + y * pitch))[x];
        if (pixelFormat == RETRO_PIXEL_FORMAT_RGB565)
          p = 0xff000000 | ((raw & 0xf800) << 8) | ((raw & 0x07e0) << 5) | ((raw & 0x001f) << 3);
        else
          p = 0xff000000 | ((raw & 0x7c00) << 9) | ((raw & 0x03e0) << 6) | ((raw & 0x001f) << 3);
      }
      argb[y * width + x] = p;
    }
  jintArray pixels = env->NewIntArray(argb.size());
  env->SetIntArrayRegion(pixels, 0, argb.size(), argb.data());
  env->CallVoidMethod(activity, frameMethod, pixels, (jint)width, (jint)height);
  env->DeleteLocalRef(pixels);
  if (env->ExceptionCheck()) env->ExceptionClear();
}
static void audioSample(int16_t left, int16_t right) {
  int16_t pair[] = {left, right};
  JNIEnv* env = nullptr;
  vm->GetEnv((void**)&env, JNI_VERSION_1_6);
  if (!env || !activity) return;
  jshortArray a = env->NewShortArray(2);
  env->SetShortArrayRegion(a, 0, 2, pair);
  env->CallVoidMethod(activity, audioMethod, a);
  env->DeleteLocalRef(a);
}
static size_t audioBatch(const int16_t* data, size_t frames) {
  JNIEnv* env = nullptr;
  vm->GetEnv((void**)&env, JNI_VERSION_1_6);
  if (!env || !activity) return frames;
  jshortArray a = env->NewShortArray(frames * 2);
  env->SetShortArrayRegion(a, 0, frames * 2, data);
  env->CallVoidMethod(activity, audioMethod, a);
  env->DeleteLocalRef(a);
  return frames;
}
static void inputPoll() {}
static int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
  if (port) return 0;
  if (device == RETRO_DEVICE_POINTER) {
    if (index) return 0;
    switch (id) {
      case RETRO_DEVICE_ID_POINTER_X:
        return pointerX.load();
      case RETRO_DEVICE_ID_POINTER_Y:
        return pointerY.load();
      case RETRO_DEVICE_ID_POINTER_PRESSED:
        return pointerDown.load() ? 1 : 0;
      case RETRO_DEVICE_ID_POINTER_COUNT:
        return pointerDown.load() ? 1 : 0;
      default:
        return 0;
    }
  }
  if (device == RETRO_DEVICE_ANALOG) {
    if (index > RETRO_DEVICE_INDEX_ANALOG_RIGHT || id > RETRO_DEVICE_ID_ANALOG_Y) return 0;
    return axes[index * 2 + id].load();
  }
  if (device != RETRO_DEVICE_JOYPAD) return 0;
  if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(buttons.load() & 0xffff);
  if (id > 15) return 0;
  return (buttons.load() & (1u << id)) ? 1 : 0;
}
// Battery saves (save RAM) and the cartridge clock (RTC) live in core memory. They are written to
// the work folder whenever they change, so a killed process or a crash loses at most a few
// seconds, never a whole session. Writes go to a temp file first and are renamed into place.
struct BatteryFile {
  unsigned id;
  std::string path;
  std::vector<uint8_t> written;
};
static void loadBattery(BatteryFile& file, void* (*data)(unsigned), size_t (*size)(unsigned)) {
  if (!data || !size) return;
  void* memory = data(file.id);
  size_t length = size(file.id);
  if (!memory || !length) return;
  std::ifstream in(file.path, std::ios::binary);
  if (in) in.read((char*)memory, length);
  file.written.assign((uint8_t*)memory, (uint8_t*)memory + length);
}
// Returns true when the file on disk changed.
static bool flushBattery(BatteryFile& file, void* (*data)(unsigned), size_t (*size)(unsigned)) {
  if (!data || !size) return false;
  void* memory = data(file.id);
  size_t length = size(file.id);
  if (!memory || !length) return false;
  if (file.written.size() == length && std::memcmp(file.written.data(), memory, length) == 0)
    return false;
  std::string temp = file.path + ".tmp";
  {
    std::ofstream out(temp, std::ios::binary | std::ios::trunc);
    out.write((const char*)memory, length);
    out.flush();
    if (!out) return false;
  }
  if (std::rename(temp.c_str(), file.path.c_str()) != 0) return false;
  file.written.assign((uint8_t*)memory, (uint8_t*)memory + length);
  return true;
}
static void notifySaved(JNIEnv* env) {
  if (env && activity && savedMethod) {
    env->CallVoidMethod(activity, savedMethod);
    if (env->ExceptionCheck()) env->ExceptionClear();
  }
}
template <typename T>
static T symbol(const char* name) {
  return reinterpret_cast<T>(dlsym(handle, name));
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeButtons(JNIEnv*,
                                                                                     jclass,
                                                                                     jint value) {
  buttons.store((unsigned)value);
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeSetVariable(
    JNIEnv* env, jclass, jstring key, jstring value) {
  const char* k = env->GetStringUTFChars(key, nullptr);
  const char* v = env->GetStringUTFChars(value, nullptr);
  {
    std::lock_guard<std::mutex> lock(variableLock);
    variables[k] = v;
  }
  env->ReleaseStringUTFChars(key, k);
  env->ReleaseStringUTFChars(value, v);
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeAxes(
    JNIEnv*, jclass, jint lx, jint ly, jint rx, jint ry) {
  axes[0] = (int16_t)lx;
  axes[1] = (int16_t)ly;
  axes[2] = (int16_t)rx;
  axes[3] = (int16_t)ry;
}
extern "C" JNIEXPORT jint JNICALL Java_org_emugo_frontend_CoreActivity_nativeMapFile(JNIEnv* env,
                                                                                     jclass,
                                                                                     jstring path,
                                                                                     jint fd) {
  const char* p = env->GetStringUTFChars(path, nullptr);
  int copy = vfs::map(p, fd);
  env->ReleaseStringUTFChars(path, p);
  return copy;
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativePointer(
    JNIEnv*, jclass, jint x, jint y, jboolean pressed) {
  pointerX = (int16_t)x;
  pointerY = (int16_t)y;
  pointerDown = pressed;
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeClearFiles(JNIEnv*,
                                                                                        jclass) {
  vfs::clear();
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeFlush(JNIEnv*,
                                                                                    jclass) {
  flushRequested = true;
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeStop(JNIEnv*, jclass) {
  running = false;
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativePause(JNIEnv*, jclass,
                                                                                   jboolean value) {
  paused = value;
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeAutoStates(
    JNIEnv*, jclass, jboolean value) {
  saveStatesEnabled = value;
}
extern "C" JNIEXPORT jstring JNICALL Java_org_emugo_frontend_CoreActivity_nativeRun(
    JNIEnv* env, jclass, jobject owner, jstring corePath, jstring romPath, jstring workPath,
    jstring savePath, jboolean autoStates) {
  saveStatesEnabled = autoStates;
  const char* cp = env->GetStringUTFChars(corePath, nullptr);
  const char* rp = env->GetStringUTFChars(romPath, nullptr);
  const char* wp = env->GetStringUTFChars(workPath, nullptr);
  const char* sp = env->GetStringUTFChars(savePath, nullptr);
  std::string core(cp), rom(rp);
  workDir = wp;
  systemDir = wp;
  saveDir = sp;
  env->ReleaseStringUTFChars(corePath, cp);
  env->ReleaseStringUTFChars(romPath, rp);
  env->ReleaseStringUTFChars(workPath, wp);
  env->ReleaseStringUTFChars(savePath, sp);
  hw = {};
  hwRequested = false;
  targetFps = 60.0;
  pointerDown = false;
  vm = nullptr;
  env->GetJavaVM(&vm);
  activity = env->NewGlobalRef(owner);
  jclass cls = env->GetObjectClass(owner);
  frameMethod = env->GetMethodID(cls, "onFrame", "([III)V");
  audioMethod = env->GetMethodID(cls, "onAudio", "([S)V");
  audioFormatMethod = env->GetMethodID(cls, "onAudioFormat", "(I)V");
  savedMethod = env->GetMethodID(cls, "onSaveWritten", "()V");
  geometryMethod = env->GetMethodID(cls, "onGeometry", "(F)V");
  handle = dlopen(core.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!handle) {
    std::string err = dlerror();
    env->DeleteGlobalRef(activity);
    activity = nullptr;
    return env->NewStringUTF(err.c_str());
  }
  auto setEnv = symbol<void (*)(retro_environment_t)>("retro_set_environment");
  auto setVideo = symbol<void (*)(retro_video_refresh_t)>("retro_set_video_refresh");
  auto setAudio = symbol<void (*)(retro_audio_sample_t)>("retro_set_audio_sample");
  auto setBatch = symbol<void (*)(retro_audio_sample_batch_t)>("retro_set_audio_sample_batch");
  auto setPoll = symbol<void (*)(retro_input_poll_t)>("retro_set_input_poll");
  auto setInput = symbol<void (*)(retro_input_state_t)>("retro_set_input_state");
  auto init = symbol<void (*)()>("retro_init");
  auto deinit = symbol<void (*)()>("retro_deinit");
  auto load = symbol<bool (*)(const retro_game_info*)>("retro_load_game");
  auto unload = symbol<void (*)()>("retro_unload_game");
  auto run = symbol<void (*)()>("retro_run");
  auto reset = symbol<void (*)()>("retro_reset");
  auto av = symbol<void (*)(retro_system_av_info*)>("retro_get_system_av_info");
  auto systemInfo = symbol<void (*)(retro_system_info*)>("retro_get_system_info");
  auto serializeSize = symbol<size_t (*)()>("retro_serialize_size");
  auto serialize = symbol<bool (*)(void*, size_t)>("retro_serialize");
  auto unserialize = symbol<bool (*)(const void*, size_t)>("retro_unserialize");
  auto memoryData = symbol<void* (*)(unsigned)>("retro_get_memory_data");
  auto memorySize = symbol<size_t (*)(unsigned)>("retro_get_memory_size");
  std::string error;
  if (!setEnv || !setVideo || !setAudio || !setBatch || !setPoll || !setInput || !init || !deinit ||
      !load || !unload || !run || !av)
    error = "Incomplete Libretro core";
  else {
    setEnv(environment);
    setVideo(video);
    setAudio(audioSample);
    setBatch(audioBatch);
    setPoll(inputPoll);
    setInput(inputState);
    memoryDescriptors.clear();
    memoryMap = {};
    init();
    retro_system_info sys{};
    if (systemInfo) systemInfo(&sys);
    std::vector<char> content;
    if (!sys.need_fullpath) {
      std::ifstream file(rom, std::ios::binary);
      content.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
    }
    retro_game_info game{};
    game.path = rom.c_str();
    game.data = content.empty() ? nullptr : content.data();
    game.size = content.size();
    retro_system_av_info info{};
    if (!load(&game)) {
      error = "Core could not load this game";
    } else {
      av(&info);
      if (hwRequested) {
        unsigned width = std::max(info.geometry.max_width, info.geometry.base_width);
        unsigned height = std::max(info.geometry.max_height, info.geometry.base_height);
        if (!width || !height) {
          width = 640;
          height = 480;
        }
        if (!createContext(width, height)) {
          error = "OpenGL ES is not available";
          unload();
        } else if (hw.context_reset) {
          hw.context_reset();
        }
      }
    }
    if (error.empty()) {
      notifyGeometry(info.geometry);
      if (info.timing.fps > 1) targetFps = info.timing.fps;
      env->CallVoidMethod(activity, audioFormatMethod,
                          (jint)(info.timing.sample_rate > 8000 ? info.timing.sample_rate : 44100));
      std::string save = workDir + "/state.sav";
      BatteryFile sram{RETRO_MEMORY_SAVE_RAM, workDir + "/save.srm", {}};
      BatteryFile rtc{RETRO_MEMORY_RTC, workDir + "/save.rtc", {}};
      loadBattery(sram, memoryData, memorySize);
      loadBattery(rtc, memoryData, memorySize);
      if (autoStates && unserialize) {
        std::ifstream in(save, std::ios::binary);
        if (in) {
          std::vector<char> state((std::istreambuf_iterator<char>(in)),
                                  std::istreambuf_iterator<char>());
          unserialize(state.data(), state.size());
        }
      }
      ra::gameLoaded(rom.c_str(), content.empty() ? nullptr : (const uint8_t*)content.data(),
                     content.size(), memoryData,
                     memorySize, memoryDescriptors.empty() ? nullptr : &memoryMap);
      running = true;
      paused = false;
      auto next = std::chrono::steady_clock::now();
      auto lastCheck = next;
      flushRequested = false;
      while (running) {
        auto now = std::chrono::steady_clock::now();
        if (flushRequested.exchange(false) || now - lastCheck > std::chrono::seconds(5)) {
          lastCheck = now;
          // The clock ticks constantly; it is written alongside save RAM changes and on exit.
          if (flushBattery(sram, memoryData, memorySize)) {
            flushBattery(rtc, memoryData, memorySize);
            notifySaved(env);
          }
        }
        if (paused) {
          ra::idle();
          std::this_thread::sleep_for(std::chrono::milliseconds(16));
          next = std::chrono::steady_clock::now();
          continue;
        }
        run();
        ra::frame();
        if (ra::takeResetRequest() && reset) reset();
        next += std::chrono::nanoseconds((long long)(1000000000.0 / targetFps.load()));
        std::this_thread::sleep_until(next);
        if (next < std::chrono::steady_clock::now() - std::chrono::milliseconds(100))
          next = std::chrono::steady_clock::now();
      }
      if (saveStatesEnabled && serializeSize && serialize) {
        size_t size = serializeSize();
        if (size) {
          std::vector<char> state(size);
          if (serialize(state.data(), size)) {
            std::ofstream out(save, std::ios::binary);
            out.write(state.data(), size);
          }
        }
      }
      flushBattery(sram, memoryData, memorySize);
      flushBattery(rtc, memoryData, memorySize);
      ra::gameUnloaded();
      if (hwRequested && hw.context_destroy) hw.context_destroy();
      unload();
    }
    deinit();
    destroyContext();
    hwRequested = false;
  }
  dlclose(handle);
  handle = nullptr;
  env->DeleteGlobalRef(activity);
  activity = nullptr;
  return env->NewStringUTF(error.c_str());
}
