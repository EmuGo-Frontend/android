#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>

// Discord Social SDK glue. The SDK is supplied by the developer at build time and is not
// redistributed in source, so its C entry points are resolved at runtime and builds without the
// optional AAR still work. Callbacks arrive on the main thread via Discord_RunCallbacks.
namespace {
struct Opaque {
  void* opaque;
};
using DClient = Opaque;
using DActivity = Opaque;
using DAssets = Opaque;
using DTimestamps = Opaque;
using DResult = Opaque;
using DUser = Opaque;
struct DString {
  uint8_t* ptr;
  size_t size;
};
using FreeFn = void (*)(void*);
using TokenCallback = void (*)(DResult*, DString, DString, int, int32_t, DString, void*);
using ResultCallback = void (*)(DResult*, void*);
using StatusCallback = void (*)(int, int, int32_t, void*);
using LogCallback = void (*)(DString, int, void*);
#define DLOG(...) __android_log_print(ANDROID_LOG_INFO, "EmuGo-Discord", __VA_ARGS__)

void* sdk = nullptr;
DClient client{};
JavaVM* vm = nullptr;
jclass bridge = nullptr;
jmethodID tokenMethod = nullptr, statusMethod = nullptr;

template <class T>
T api(const char* symbol) {
  return sdk ? reinterpret_cast<T>(dlsym(sdk, symbol)) : nullptr;
}
DString str(std::string& value) { return {reinterpret_cast<uint8_t*>(&value[0]), value.size()}; }
std::string text(JNIEnv* env, jstring value) {
  if (!value) return "";
  const char* utf = env->GetStringUTFChars(value, nullptr);
  std::string out(utf);
  env->ReleaseStringUTFChars(value, utf);
  return out;
}
std::string copy(DString value) { return std::string((const char*)value.ptr, value.size); }

JNIEnv* env() {
  JNIEnv* result = nullptr;
  if (vm) vm->GetEnv((void**)&result, JNI_VERSION_1_6);
  return result;
}

std::string resultError(DResult* result) {
  auto error = api<void (*)(DResult*, DString*)>("Discord_ClientResult_Error");
  auto freeFn = api<void (*)(void*)>("Discord_Free");
  if (!error) return "Discord error";
  DString value{};
  error(result, &value);
  std::string out = copy(value);
  if (freeFn && value.ptr) freeFn(value.ptr);
  return out.empty() ? "Discord error" : out;
}

bool successful(DResult* result) {
  auto ok = api<bool (*)(DResult*)>("Discord_ClientResult_Successful");
  return ok && ok(result);
}

void onToken(DResult* result, DString access, DString refresh, int, int32_t expiresIn, DString,
             void*) {
  JNIEnv* e = env();
  if (!e || !bridge) return;
  bool ok = successful(result);
  if (!ok) DLOG("Token exchange failed: %s", resultError(result).c_str());
  jstring a = e->NewStringUTF(ok ? copy(access).c_str() : "");
  jstring r = e->NewStringUTF(ok ? copy(refresh).c_str() : "");
  jstring error = e->NewStringUTF(ok ? "" : resultError(result).c_str());
  e->CallStaticVoidMethod(bridge, tokenMethod, (jboolean)ok, a, r, (jint)expiresIn, error);
  if (e->ExceptionCheck()) e->ExceptionClear();
  e->DeleteLocalRef(a);
  e->DeleteLocalRef(r);
  e->DeleteLocalRef(error);
}

void onStatus(int status, int error, int32_t detail, void*) {
  if (error) DLOG("Status %d (error %d, detail %d)", status, error, detail);
  JNIEnv* e = env();
  if (!e || !bridge) return;
  e->CallStaticVoidMethod(bridge, statusMethod, (jint)status, (jint)error, (jint)detail);
  if (e->ExceptionCheck()) e->ExceptionClear();
}

void onTokenUpdated(DResult* result, void*) {
  if (!successful(result)) {
    DLOG("UpdateToken failed: %s", resultError(result).c_str());
    return;
  }
  auto connect = api<void (*)(DClient*)>("Discord_Client_Connect");
  if (connect) connect(&client);
}

void presenceDone(DResult* result, void*) {
  if (!successful(result))
    DLOG("Rich presence update failed: %s", resultError(result).c_str());
}

void sdkLog(DString message, int severity, void*) {
  std::string text = copy(message);
  __android_log_print(severity >= 4 ? ANDROID_LOG_ERROR : severity == 3 ? ANDROID_LOG_WARN
                                                                       : ANDROID_LOG_INFO,
                      "EmuGo-Discord", "SDK: %s", text.c_str());
}
}  // namespace

extern "C" {
JNIEXPORT jboolean JNICALL Java_org_emugo_frontend_DiscordPresence_nativeInit(JNIEnv* e, jclass cls,
                                                                             jlong id) {
  if (client.opaque) return true;
  if (!sdk) sdk = dlopen("libdiscord_partner_sdk.so", RTLD_NOW | RTLD_LOCAL);
  auto init = api<void (*)(DClient*)>("Discord_Client_Init");
  auto setId = api<void (*)(DClient*, uint64_t)>("Discord_Client_SetApplicationId");
  auto setStatus =
      api<void (*)(DClient*, StatusCallback, FreeFn, void*)>("Discord_Client_SetStatusChangedCallback");
  if (!init || !setId) return false;
  e->GetJavaVM(&vm);
  bridge = (jclass)e->NewGlobalRef(cls);
  tokenMethod = e->GetStaticMethodID(
      cls, "onToken", "(ZLjava/lang/String;Ljava/lang/String;ILjava/lang/String;)V");
  statusMethod = e->GetStaticMethodID(cls, "onStatus", "(III)V");
  init(&client);
  auto addLog = api<void (*)(DClient*, LogCallback, FreeFn, void*, int)>("Discord_Client_AddLogCallback");
  if (addLog) addLog(&client, sdkLog, nullptr, nullptr, 3 /* Warning */);
  setId(&client, (uint64_t)id);
  if (setStatus) setStatus(&client, onStatus, nullptr, nullptr);
  return true;
}

JNIEXPORT void JNICALL Java_org_emugo_frontend_DiscordPresence_nativeGetToken(
    JNIEnv* e, jclass, jlong id, jstring code, jstring verifier, jstring redirect) {
  auto getToken = api<void (*)(DClient*, uint64_t, DString, DString, DString, TokenCallback, FreeFn,
                               void*)>("Discord_Client_GetToken");
  if (!client.opaque || !getToken) return;
  std::string c = text(e, code), v = text(e, verifier), r = text(e, redirect);
  getToken(&client, (uint64_t)id, str(c), str(v), str(r), onToken, nullptr, nullptr);
}

JNIEXPORT void JNICALL Java_org_emugo_frontend_DiscordPresence_nativeRefresh(JNIEnv* e, jclass,
                                                                            jlong id,
                                                                            jstring refresh) {
  auto refreshToken = api<void (*)(DClient*, uint64_t, DString, TokenCallback, FreeFn, void*)>(
      "Discord_Client_RefreshToken");
  if (!client.opaque || !refreshToken) return;
  std::string r = text(e, refresh);
  refreshToken(&client, (uint64_t)id, str(r), onToken, nullptr, nullptr);
}

JNIEXPORT void JNICALL Java_org_emugo_frontend_DiscordPresence_nativeConnect(JNIEnv* e, jclass,
                                                                            jstring access) {
  auto update = api<void (*)(DClient*, int, DString, ResultCallback, FreeFn, void*)>(
      "Discord_Client_UpdateToken");
  if (!client.opaque || !update) return;
  std::string token = text(e, access);
  update(&client, 1 /* Bearer */, str(token), onTokenUpdated, nullptr, nullptr);
}

JNIEXPORT void JNICALL Java_org_emugo_frontend_DiscordPresence_nativeDisconnect(JNIEnv*, jclass) {
  auto clear = api<void (*)(DClient*)>("Discord_Client_ClearRichPresence");
  auto disconnect = api<void (*)(DClient*)>("Discord_Client_Disconnect");
  if (!client.opaque) return;
  if (clear) clear(&client);
  if (disconnect) disconnect(&client);
}

JNIEXPORT jstring JNICALL Java_org_emugo_frontend_DiscordPresence_nativeUserName(JNIEnv* e, jclass) {
  auto current = api<bool (*)(DClient*, DUser*)>("Discord_Client_GetCurrentUserV2");
  auto displayName = api<void (*)(DUser*, DString*)>("Discord_UserHandle_DisplayName");
  auto dropUser = api<void (*)(DUser*)>("Discord_UserHandle_Drop");
  auto freeFn = api<void (*)(void*)>("Discord_Free");
  if (!client.opaque || !current || !displayName) return e->NewStringUTF("");
  DUser user{};
  if (!current(&client, &user)) return e->NewStringUTF("");
  DString name{};
  displayName(&user, &name);
  std::string out = copy(name);
  if (freeFn && name.ptr) freeFn(name.ptr);
  if (dropUser) dropUser(&user);
  return e->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL Java_org_emugo_frontend_DiscordPresence_nativeSetPresence(
    JNIEnv* e, jclass, jstring details, jstring state, jstring largeImage, jstring largeText,
    jstring smallImage, jstring smallText, jlong startMillis) {
  auto activityInit = api<void (*)(DActivity*)>("Discord_Activity_Init");
  auto activityDrop = api<void (*)(DActivity*)>("Discord_Activity_Drop");
  auto setType = api<void (*)(DActivity*, int)>("Discord_Activity_SetType");
  auto setDetails = api<void (*)(DActivity*, DString*)>("Discord_Activity_SetDetails");
  auto setState = api<void (*)(DActivity*, DString*)>("Discord_Activity_SetState");
  auto setAssets = api<void (*)(DActivity*, DAssets*)>("Discord_Activity_SetAssets");
  auto setTimestamps = api<void (*)(DActivity*, DTimestamps*)>("Discord_Activity_SetTimestamps");
  auto assetsInit = api<void (*)(DAssets*)>("Discord_ActivityAssets_Init");
  auto assetsDrop = api<void (*)(DAssets*)>("Discord_ActivityAssets_Drop");
  auto setLarge = api<void (*)(DAssets*, DString*)>("Discord_ActivityAssets_SetLargeImage");
  auto setLargeText = api<void (*)(DAssets*, DString*)>("Discord_ActivityAssets_SetLargeText");
  auto setSmall = api<void (*)(DAssets*, DString*)>("Discord_ActivityAssets_SetSmallImage");
  auto setSmallText = api<void (*)(DAssets*, DString*)>("Discord_ActivityAssets_SetSmallText");
  auto timesInit = api<void (*)(DTimestamps*)>("Discord_ActivityTimestamps_Init");
  auto timesDrop = api<void (*)(DTimestamps*)>("Discord_ActivityTimestamps_Drop");
  auto setStart = api<void (*)(DTimestamps*, uint64_t)>("Discord_ActivityTimestamps_SetStart");
  auto update = api<void (*)(DClient*, DActivity*, ResultCallback, FreeFn, void*)>(
      "Discord_Client_UpdateRichPresence");
  if (!client.opaque || !activityInit || !activityDrop || !update) return;
  std::string d = text(e, details), s = text(e, state), li = text(e, largeImage),
              lt = text(e, largeText), si = text(e, smallImage), st = text(e, smallText);
  DActivity activity{};
  activityInit(&activity);
  if (setType) setType(&activity, 0 /* Playing */);
  DString ds = str(d), ss = str(s), lis = str(li), lts = str(lt), sis = str(si), sts = str(st);
  // Discord requires 2+ characters for these strings.
  if (setDetails && d.size() >= 2) setDetails(&activity, &ds);
  if (setState && s.size() >= 2) setState(&activity, &ss);
  DAssets assets{};
  if (assetsInit && setAssets) {
    assetsInit(&assets);
    if (setLarge && !li.empty()) setLarge(&assets, &lis);
    if (setLargeText && lt.size() >= 2) setLargeText(&assets, &lts);
    if (setSmall && !si.empty()) setSmall(&assets, &sis);
    if (setSmallText && st.size() >= 2) setSmallText(&assets, &sts);
    setAssets(&activity, &assets);
    if (assetsDrop) assetsDrop(&assets);
  }
  if (startMillis > 0 && timesInit && setStart && setTimestamps) {
    DTimestamps times{};
    timesInit(&times);
    setStart(&times, (uint64_t)startMillis);
    setTimestamps(&activity, &times);
    if (timesDrop) timesDrop(&times);
  }
  update(&client, &activity, presenceDone, nullptr, nullptr);
  activityDrop(&activity);
}

JNIEXPORT void JNICALL Java_org_emugo_frontend_DiscordPresence_nativeClearPresence(JNIEnv*, jclass) {
  auto clear = api<void (*)(DClient*)>("Discord_Client_ClearRichPresence");
  if (client.opaque && clear) clear(&client);
}

JNIEXPORT void JNICALL Java_org_emugo_frontend_DiscordPresence_nativeTick(JNIEnv*, jclass) {
  auto callbacks = api<void (*)()>("Discord_RunCallbacks");
  if (callbacks) callbacks();
}
}
