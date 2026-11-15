#include "ra.h"
#include "vfs.h"

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

extern "C" {
#include "rc_client.h"
#include "rc_consoles.h"
#include "rc_hash.h"
#include "rc_libretro.h"
}

namespace {
JavaVM* vm = nullptr;
jclass bridge = nullptr;
jmethodID httpMethod = nullptr, eventMethod = nullptr, loginMethod = nullptr;
rc_client_t* client = nullptr;
std::mutex regionsLock;
rc_libretro_memory_regions_t regions{};
bool regionsReady = false;
std::vector<retro_memory_descriptor> descriptors;
retro_memory_map memoryMap{};
ra::MemoryData coreMemoryData = nullptr;
ra::MemorySize coreMemorySize = nullptr;
std::atomic<uint32_t> consoleId{0};
std::atomic<bool> resetRequested{false};
std::atomic<int> idleCounter{0};

// The server injects pseudo achievements (IDs from 101000001) to warn unsupported emulators
// about hardcore. EmuGo! runs softcore only, so they are never shown.
constexpr uint32_t WARNING_ACHIEVEMENT_ID = 101000001;

enum Event { UNLOCK = 1, LOADED = 2, LEADERBOARD = 3, COMPLETED = 4, ERROR = 5, CONNECTION = 6 };

JNIEnv* env() {
  JNIEnv* result = nullptr;
  if (!vm) return nullptr;
  if (vm->GetEnv((void**)&result, JNI_VERSION_1_6) == JNI_EDETACHED)
#ifdef _JAVASOFT_JNI_MD_H_
    vm->AttachCurrentThread((void**)&result, nullptr);  // desktop JDK jni.h (offline build)
#else
    vm->AttachCurrentThread(&result, nullptr);  // NDK jni.h
#endif
  return result;
}

// Builds a java.lang.String from real UTF-8 (NewStringUTF expects modified UTF-8 and can choke
// on emoji in achievement titles).
jstring utf(JNIEnv* e, const char* value) {
  if (!value) value = "";
  jsize length = (jsize)std::strlen(value);
  jbyteArray bytes = e->NewByteArray(length);
  e->SetByteArrayRegion(bytes, 0, length, (const jbyte*)value);
  jclass stringClass = e->FindClass("java/lang/String");
  jmethodID make = e->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
  jstring charset = e->NewStringUTF("UTF-8");
  jstring result = (jstring)e->NewObject(stringClass, make, bytes, charset);
  e->DeleteLocalRef(bytes);
  e->DeleteLocalRef(charset);
  e->DeleteLocalRef(stringClass);
  return result;
}

void post(int kind, int value, const char* title, const char* text, const char* url) {
  JNIEnv* e = env();
  if (!e || !bridge) return;
  jstring a = utf(e, title), b = utf(e, text), c = utf(e, url);
  e->CallStaticVoidMethod(bridge, eventMethod, (jint)kind, (jint)value, a, b, c);
  if (e->ExceptionCheck()) e->ExceptionClear();
  e->DeleteLocalRef(a);
  e->DeleteLocalRef(b);
  e->DeleteLocalRef(c);
}

uint32_t readMemory(uint32_t address, uint8_t* buffer, uint32_t size, rc_client_t*) {
  std::lock_guard<std::mutex> lock(regionsLock);
  if (!regionsReady) return 0;
  return rc_libretro_memory_read(&regions, address, buffer, size);
}

void serverCall(const rc_api_request_t* request, rc_client_server_callback_t callback,
                void* data, rc_client_t*) {
  JNIEnv* e = env();
  if (!e || !bridge) {
    rc_api_server_response_t response{};
    response.http_status_code = RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR;
    callback(&response, data);
    return;
  }
  jstring url = utf(e, request->url);
  jstring body = request->post_data ? utf(e, request->post_data) : nullptr;
  jstring type = request->content_type ? utf(e, request->content_type) : nullptr;
  e->CallStaticVoidMethod(bridge, httpMethod, url, body, type, (jlong)(intptr_t)callback,
                          (jlong)(intptr_t)data);
  if (e->ExceptionCheck()) e->ExceptionClear();
  e->DeleteLocalRef(url);
  if (body) e->DeleteLocalRef(body);
  if (type) e->DeleteLocalRef(type);
}

void handleEvent(const rc_client_event_t* event, rc_client_t*) {
  switch (event->type) {
    case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED: {
      const rc_client_achievement_t* a = event->achievement;
      if (a->id >= WARNING_ACHIEVEMENT_ID) break;
      post(UNLOCK, (int)a->points, a->title, a->description, a->badge_url);
      break;
    }
    case RC_CLIENT_EVENT_LEADERBOARD_STARTED:
      post(LEADERBOARD, 0, event->leaderboard->title, event->leaderboard->description, nullptr);
      break;
    case RC_CLIENT_EVENT_LEADERBOARD_FAILED:
      post(LEADERBOARD, 1, event->leaderboard->title, nullptr, nullptr);
      break;
    case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED:
      post(LEADERBOARD, 2, event->leaderboard->title, event->leaderboard->tracker_value, nullptr);
      break;
    case RC_CLIENT_EVENT_GAME_COMPLETED: {
      const rc_client_game_t* game = rc_client_get_game_info(client);
      post(COMPLETED, rc_client_get_hardcore_enabled(client), game ? game->title : "", nullptr,
           game ? game->badge_url : nullptr);
      break;
    }
    case RC_CLIENT_EVENT_RESET:
      resetRequested = true;
      break;
    case RC_CLIENT_EVENT_SERVER_ERROR:
      post(ERROR, 0, event->server_error->api, event->server_error->error_message, nullptr);
      break;
    case RC_CLIENT_EVENT_DISCONNECTED:
      post(CONNECTION, 0, nullptr, nullptr, nullptr);
      break;
    case RC_CLIENT_EVENT_RECONNECTED:
      post(CONNECTION, 1, nullptr, nullptr, nullptr);
      break;
    default:
      break;
  }
}

void loginDone(int result, const char* error, rc_client_t*, void*) {
  JNIEnv* e = env();
  if (!e || !bridge) return;
  jstring message = utf(e, result == RC_OK ? "" : error);
  e->CallStaticVoidMethod(bridge, loginMethod, (jboolean)(result == RC_OK), message);
  if (e->ExceptionCheck()) e->ExceptionClear();
  e->DeleteLocalRef(message);
}

void gameDone(int result, const char* error, rc_client_t* c, void*) {
  if (result != RC_OK) {
    post(ERROR, result, nullptr, error, nullptr);
    return;
  }
  const rc_client_game_t* game = rc_client_get_game_info(c);
  rc_client_user_game_summary_t summary{};
  rc_client_get_user_game_summary(c, &summary);
  std::string progress = std::to_string(summary.num_unlocked_achievements) + "/" +
                         std::to_string(summary.num_core_achievements);
  post(LOADED, game ? (int)game->id : 0, game ? game->title : "", progress.c_str(),
       game ? game->badge_url : nullptr);
}

void* hashOpen(const char* path) { return vfs::open(path, RETRO_VFS_FILE_ACCESS_READ, 0); }
void hashSeek(void* file, int64_t offset, int origin) {
  vfs::seek((retro_vfs_file_handle*)file, offset,
            origin == SEEK_CUR   ? RETRO_VFS_SEEK_POSITION_CURRENT
            : origin == SEEK_END ? RETRO_VFS_SEEK_POSITION_END
                                 : RETRO_VFS_SEEK_POSITION_START);
}
int64_t hashTell(void* file) { return vfs::tell((retro_vfs_file_handle*)file); }
size_t hashRead(void* file, void* buffer, size_t size) {
  int64_t got = vfs::read((retro_vfs_file_handle*)file, buffer, size);
  return got < 0 ? 0 : (size_t)got;
}
void hashClose(void* file) { vfs::close((retro_vfs_file_handle*)file); }
rc_hash_filereader_t hashReader = {hashOpen, hashSeek, hashTell, hashRead, hashClose};

void coreMemory(uint32_t id, rc_libretro_core_memory_info_t* info) {
  info->data = coreMemoryData ? (uint8_t*)coreMemoryData(id) : nullptr;
  info->size = coreMemorySize ? coreMemorySize(id) : 0;
}
}  // namespace

namespace ra {
void gameLoaded(const char* path, const uint8_t* data, size_t size, MemoryData memoryData,
                MemorySize memorySize, const retro_memory_map* map) {
  if (!client || !consoleId || !rc_client_get_user_info(client)) {
    // Login may still be in flight; rc_client queues the load behind it.
    if (!client || !consoleId) return;
  }
  {
    std::lock_guard<std::mutex> lock(regionsLock);
    coreMemoryData = memoryData;
    coreMemorySize = memorySize;
    descriptors.clear();
    if (map && map->descriptors && map->num_descriptors) {
      descriptors.assign(map->descriptors, map->descriptors + map->num_descriptors);
      memoryMap.descriptors = descriptors.data();
      memoryMap.num_descriptors = (unsigned)descriptors.size();
    }
    std::memset(&regions, 0, sizeof(regions));
    regionsReady = rc_libretro_memory_init(&regions, descriptors.empty() ? nullptr : &memoryMap,
                                           coreMemory, consoleId) != 0;
  }
  resetRequested = false;
  rc_client_begin_identify_and_load_game(client, consoleId, path, data, size, gameDone, nullptr);
}
void frame() {
  if (client) rc_client_do_frame(client);
}
void idle() {
  // Called about every 16 ms while paused; rc_client only needs a periodic nudge.
  if (client && ++idleCounter % 30 == 0) rc_client_idle(client);
}
void gameUnloaded() {
  if (client) rc_client_unload_game(client);
  std::lock_guard<std::mutex> lock(regionsLock);
  regionsReady = false;
  rc_libretro_memory_destroy(&regions);
  coreMemoryData = nullptr;
  coreMemorySize = nullptr;
}
bool takeResetRequest() { return resetRequested.exchange(false); }
bool hardcore() { return client && rc_client_is_game_loaded(client) && rc_client_get_hardcore_enabled(client); }
}  // namespace ra

extern "C" {
JNIEXPORT void JNICALL Java_org_emugo_frontend_RaBridge_nativeInit(JNIEnv* e, jclass cls) {
  if (client) return;
  e->GetJavaVM(&vm);
  bridge = (jclass)e->NewGlobalRef(cls);
  httpMethod = e->GetStaticMethodID(
      cls, "http", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;JJ)V");
  eventMethod = e->GetStaticMethodID(
      cls, "onEvent", "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
  loginMethod = e->GetStaticMethodID(cls, "onLogin", "(ZLjava/lang/String;)V");
  client = rc_client_create(readMemory, serverCall);
  rc_hash_init_custom_filereader(&hashReader);
  rc_client_set_event_handler(client, handleEvent);
}
JNIEXPORT jstring JNICALL Java_org_emugo_frontend_RaBridge_nativeUserAgentClause(JNIEnv* e, jclass) {
  char buffer[128] = {0};
  if (client) rc_client_get_user_agent_clause(client, buffer, sizeof(buffer));
  return e->NewStringUTF(buffer);
}
JNIEXPORT void JNICALL Java_org_emugo_frontend_RaBridge_nativeLogin(JNIEnv* e, jclass, jstring user,
                                                                    jstring token) {
  if (!client) return;
  const char* u = e->GetStringUTFChars(user, nullptr);
  const char* t = e->GetStringUTFChars(token, nullptr);
  rc_client_begin_login_with_token(client, u, t, loginDone, nullptr);
  e->ReleaseStringUTFChars(user, u);
  e->ReleaseStringUTFChars(token, t);
}
JNIEXPORT void JNICALL Java_org_emugo_frontend_RaBridge_nativeLogout(JNIEnv*, jclass) {
  if (client) rc_client_logout(client);
}
JNIEXPORT jboolean JNICALL Java_org_emugo_frontend_RaBridge_nativeLoggedIn(JNIEnv*, jclass) {
  return client && rc_client_get_user_info(client) != nullptr;
}
JNIEXPORT void JNICALL Java_org_emugo_frontend_RaBridge_nativeConfigure(JNIEnv*, jclass,
                                                                        jint console,
                                                                        jboolean hardcore) {
  consoleId = (uint32_t)console;
  if (client) rc_client_set_hardcore_enabled(client, hardcore ? 1 : 0);
}
JNIEXPORT void JNICALL Java_org_emugo_frontend_RaBridge_nativeHttpDone(JNIEnv* e, jclass,
                                                                       jlong callback, jlong data,
                                                                       jint status,
                                                                       jbyteArray body) {
  std::vector<char> bytes;
  if (body) {
    jsize length = e->GetArrayLength(body);
    bytes.resize((size_t)length + 1, 0);
    e->GetByteArrayRegion(body, 0, length, (jbyte*)bytes.data());
    bytes.resize((size_t)length);
  }
  bytes.push_back(0);
  rc_api_server_response_t response{};
  response.body = bytes.data();
  response.body_length = bytes.size() - 1;
  response.http_status_code = status;
  reinterpret_cast<rc_client_server_callback_t>((intptr_t)callback)(&response,
                                                                     (void*)(intptr_t)data);
}
JNIEXPORT jstring JNICALL Java_org_emugo_frontend_RaBridge_nativeRichPresence(JNIEnv* e, jclass) {
  char buffer[256] = {0};
  if (client && rc_client_is_game_loaded(client))
    rc_client_get_rich_presence_message(client, buffer, sizeof(buffer));
  return utf(e, buffer);
}
// Rows: "H\t<bucket type>" starts a section; "A\t<unlocked>\t<title>\t<description>\t<points>\t
// <progress>\t<badge url>" is an achievement.
JNIEXPORT jobjectArray JNICALL Java_org_emugo_frontend_RaBridge_nativeAchievements(JNIEnv* e,
                                                                                  jclass) {
  jclass stringClass = e->FindClass("java/lang/String");
  if (!client || !rc_client_is_game_loaded(client)) return e->NewObjectArray(0, stringClass, nullptr);
  rc_client_achievement_list_t* list = rc_client_create_achievement_list(
      client, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
  std::vector<std::string> rows;
  for (uint32_t b = 0; list && b < list->num_buckets; b++) {
    const rc_client_achievement_bucket_t& bucket = list->buckets[b];
    rows.push_back("H\t" + std::to_string(bucket.bucket_type));
    for (uint32_t i = 0; i < bucket.num_achievements; i++) {
      const rc_client_achievement_t* a = bucket.achievements[i];
      if (a->id >= WARNING_ACHIEVEMENT_ID) continue;
      const char* badge = a->unlocked ? a->badge_url : a->badge_locked_url;
      rows.push_back(std::string("A\t") + (a->unlocked ? "1" : "0") + "\t" +
                     (a->title ? a->title : "") + "\t" + (a->description ? a->description : "") +
                     "\t" + std::to_string(a->points) + "\t" + a->measured_progress + "\t" +
                     (badge ? badge : ""));
    }
  }
  if (list) rc_client_destroy_achievement_list(list);
  jobjectArray out = e->NewObjectArray((jsize)rows.size(), stringClass, nullptr);
  for (size_t i = 0; i < rows.size(); i++) {
    jstring s = utf(e, rows[i].c_str());
    e->SetObjectArrayElement(out, (jsize)i, s);
    e->DeleteLocalRef(s);
  }
  return out;
}
// "<title>\t<badge url>\t<unlocked>\t<total>\t<points unlocked>\t<points total>\t<hardcore>"
JNIEXPORT jstring JNICALL Java_org_emugo_frontend_RaBridge_nativeSummary(JNIEnv* e, jclass) {
  if (!client || !rc_client_is_game_loaded(client)) return utf(e, "");
  const rc_client_game_t* game = rc_client_get_game_info(client);
  rc_client_user_game_summary_t summary{};
  rc_client_get_user_game_summary(client, &summary);
  std::string row = std::string(game && game->title ? game->title : "") + "\t" +
                    (game && game->badge_url ? game->badge_url : "") + "\t" +
                    std::to_string(summary.num_unlocked_achievements) + "\t" +
                    std::to_string(summary.num_core_achievements) + "\t" +
                    std::to_string(summary.points_unlocked) + "\t" +
                    std::to_string(summary.points_core) + "\t" +
                    std::to_string(rc_client_get_hardcore_enabled(client));
  return utf(e, row.c_str());
}
}
