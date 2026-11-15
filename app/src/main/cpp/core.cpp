#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <atomic>
#include <chrono>
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
#include <cstdarg>

static JavaVM* vm = nullptr;
static jobject activity = nullptr;
static jmethodID frameMethod = nullptr, audioMethod = nullptr, audioFormatMethod = nullptr;
static std::atomic<bool> running{false};
static std::atomic<bool> paused{false};
static std::atomic<bool> saveStatesEnabled{false};
static std::atomic<unsigned> buttons{0};
static std::atomic<int16_t> axes[4];
static std::string systemDir, saveDir;
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
  std::vector<jint> argb(width * height);
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
  if (device == RETRO_DEVICE_ANALOG) {
    if (index > RETRO_DEVICE_INDEX_ANALOG_RIGHT || id > RETRO_DEVICE_ID_ANALOG_Y) return 0;
    return axes[index * 2 + id].load();
  }
  if (device != RETRO_DEVICE_JOYPAD) return 0;
  if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(buttons.load() & 0xffff);
  if (id > 15) return 0;
  return (buttons.load() & (1u << id)) ? 1 : 0;
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
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeMapFile(
    JNIEnv* env, jclass, jstring path, jint fd) {
  const char* p = env->GetStringUTFChars(path, nullptr);
  vfs::map(p, fd);
  env->ReleaseStringUTFChars(path, p);
}
extern "C" JNIEXPORT void JNICALL Java_org_emugo_frontend_CoreActivity_nativeClearFiles(JNIEnv*,
                                                                                        jclass) {
  vfs::clear();
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
    jboolean autoStates) {
  saveStatesEnabled = autoStates;
  const char* cp = env->GetStringUTFChars(corePath, nullptr);
  const char* rp = env->GetStringUTFChars(romPath, nullptr);
  const char* wp = env->GetStringUTFChars(workPath, nullptr);
  std::string core(cp), rom(rp);
  systemDir = wp;
  saveDir = wp;
  env->ReleaseStringUTFChars(corePath, cp);
  env->ReleaseStringUTFChars(romPath, rp);
  env->ReleaseStringUTFChars(workPath, wp);
  vm = nullptr;
  env->GetJavaVM(&vm);
  activity = env->NewGlobalRef(owner);
  jclass cls = env->GetObjectClass(owner);
  frameMethod = env->GetMethodID(cls, "onFrame", "([III)V");
  audioMethod = env->GetMethodID(cls, "onAudio", "([S)V");
  audioFormatMethod = env->GetMethodID(cls, "onAudioFormat", "(I)V");
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
    if (!load(&game))
      error = "Core could not load this game";
    else {
      retro_system_av_info info{};
      av(&info);
      env->CallVoidMethod(activity, audioFormatMethod,
                          (jint)(info.timing.sample_rate > 8000 ? info.timing.sample_rate : 44100));
      std::string save = saveDir + "/state.sav";
      std::string sramPath = saveDir + "/save.srm";
      if (memoryData && memorySize) {
        void* memory = memoryData(RETRO_MEMORY_SAVE_RAM);
        size_t size = memorySize(RETRO_MEMORY_SAVE_RAM);
        if (memory && size) {
          std::ifstream in(sramPath, std::ios::binary);
          if (in) in.read((char*)memory, size);
        }
      }
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
      double fps = info.timing.fps > 1 ? info.timing.fps : 60.0;
      auto next = std::chrono::steady_clock::now();
      while (running) {
        if (paused) {
          ra::idle();
          std::this_thread::sleep_for(std::chrono::milliseconds(16));
          next = std::chrono::steady_clock::now();
          continue;
        }
        run();
        ra::frame();
        if (ra::takeResetRequest() && reset) reset();
        next += std::chrono::nanoseconds((long long)(1000000000.0 / fps));
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
      if (memoryData && memorySize) {
        void* memory = memoryData(RETRO_MEMORY_SAVE_RAM);
        size_t size = memorySize(RETRO_MEMORY_SAVE_RAM);
        if (memory && size) {
          std::ofstream out(sramPath, std::ios::binary);
          out.write((const char*)memory, size);
        }
      }
      ra::gameUnloaded();
      unload();
    }
    deinit();
  }
  dlclose(handle);
  handle = nullptr;
  env->DeleteGlobalRef(activity);
  activity = nullptr;
  return env->NewStringUTF(error.c_str());
}
