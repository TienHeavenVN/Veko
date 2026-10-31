#include <jni.h>

#include <sstream>
#include <string>

#include "veko_native_context.h"
#include "veko_request_pipeline.h"

namespace {

constexpr const char* kPinnedChromiumVersion = "156.0.8078.85";
constexpr const char* kPinnedChromiumCommit = "0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5";

const char* DetectCompiledAbi() {
#if defined(__aarch64__)
  return "arm64-v8a";
#elif defined(__arm__)
  return "armeabi-v7a";
#elif defined(__x86_64__)
  return "x86_64";
#elif defined(__i386__)
  return "x86";
#else
  return "unknown";
#endif
}

std::string JStringToStdString(JNIEnv* env, jstring jstr) {
  if (jstr == nullptr) return "";
  const char* chars = env->GetStringUTFChars(jstr, nullptr);
  if (chars == nullptr) return "";
  std::string out(chars);
  env->ReleaseStringUTFChars(jstr, chars);
  return out;
}

veko::chromium::NativeCookieMode ToNativeCookieMode(jint ordinal) {
  switch (ordinal) {
    case 1:
      return veko::chromium::NativeCookieMode::kBlockAll;
    case 2:
      return veko::chromium::NativeCookieMode::kAllowAll;
    default:
      return veko::chromium::NativeCookieMode::kBlockThirdParty;
  }
}

jstring NativeGetEngineMetadata(JNIEnv* env, jclass /*clazz*/) {
  const char* adblock_ver = veko::chromium::VekoUrlRequestPipeline::Instance().AdblockEngineVersion();
#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
  const bool upstream_linked = true;
#else
  const bool upstream_linked = false;
#endif
  std::ostringstream json;
  json << "{"
       << "\"chromiumVersion\":\"" << kPinnedChromiumVersion << "\","
       << "\"chromiumCommit\":\"" << kPinnedChromiumCommit << "\","
       << "\"adblockEngine\":\"" << (adblock_ver ? adblock_ver : "unknown") << "\","
       << "\"compiledAbi\":\"" << DetectCompiledAbi() << "\","
       << "\"upstreamContentLinked\":" << (upstream_linked ? "true" : "false")
       << "}";
  return env->NewStringUTF(json.str().c_str());
}

jboolean NativeIsUpstreamContentLinked(JNIEnv* /*env*/, jclass /*clazz*/) {
#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
  return JNI_TRUE;
#else
  return JNI_FALSE;
#endif
}

jlong NativeCreateBrowserContext(JNIEnv* env,
                                 jclass /*clazz*/,
                                 jboolean is_off_the_record,
                                 jstring disk_storage_path) {
  const std::string path = JStringToStdString(env, disk_storage_path);
  return static_cast<jlong>(
      veko::chromium::NativeBrowserContextRegistry::Instance().CreateContext(
          is_off_the_record == JNI_TRUE, path));
}

jboolean NativeIsOffTheRecordContext(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  return veko::chromium::NativeBrowserContextRegistry::Instance().IsOffTheRecord(handle)
             ? JNI_TRUE
             : JNI_FALSE;
}

jstring NativeGetContextStoragePath(JNIEnv* env, jclass /*clazz*/, jlong handle) {
  const std::string path =
      veko::chromium::NativeBrowserContextRegistry::Instance().GetStoragePath(handle);
  return env->NewStringUTF(path.c_str());
}

jboolean NativeSetCookie(JNIEnv* env,
                         jclass /*clazz*/,
                         jlong handle,
                         jstring request_url,
                         jstring top_frame_url,
                         jstring cookie_kv,
                         jint cookie_mode_ordinal) {
  const bool ok = veko::chromium::NativeBrowserContextRegistry::Instance().SetCookie(
      handle,
      JStringToStdString(env, request_url),
      JStringToStdString(env, top_frame_url),
      JStringToStdString(env, cookie_kv),
      ToNativeCookieMode(cookie_mode_ordinal));
  return ok ? JNI_TRUE : JNI_FALSE;
}

jstring NativeGetCookies(JNIEnv* env,
                         jclass /*clazz*/,
                         jlong handle,
                         jstring request_url,
                         jstring top_frame_url,
                         jint cookie_mode_ordinal) {
  const std::string cookies = veko::chromium::NativeBrowserContextRegistry::Instance().GetCookies(
      handle,
      JStringToStdString(env, request_url),
      JStringToStdString(env, top_frame_url),
      ToNativeCookieMode(cookie_mode_ordinal));
  return env->NewStringUTF(cookies.c_str());
}

jboolean NativeClearStoragePartition(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  return veko::chromium::NativeBrowserContextRegistry::Instance().ClearStoragePartition(handle)
             ? JNI_TRUE
             : JNI_FALSE;
}

jboolean NativeDestroyBrowserContext(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
  return veko::chromium::NativeBrowserContextRegistry::Instance().DestroyContext(handle)
             ? JNI_TRUE
             : JNI_FALSE;
}

jint NativeGetActiveOffTheRecordContextCount(JNIEnv* /*env*/, jclass /*clazz*/) {
  return static_cast<jint>(
      veko::chromium::NativeBrowserContextRegistry::Instance().ActiveOffTheRecordContextCount());
}

jboolean NativeReloadFilterRules(JNIEnv* env,
                                 jclass /*clazz*/,
                                 jstring custom_rules_utf8,
                                 jboolean include_builtin) {
  const std::string rules = JStringToStdString(env, custom_rules_utf8);
  return veko::chromium::VekoUrlRequestPipeline::Instance().ReloadFilterRules(
             rules, include_builtin == JNI_TRUE)
             ? JNI_TRUE
             : JNI_FALSE;
}

jobjectArray NativeEvaluateUrlRequest(JNIEnv* env,
                                      jclass /*clazz*/,
                                      jstring request_url,
                                      jstring top_frame_url,
                                      jstring resource_type,
                                      jstring http_method,
                                      jboolean block_ads,
                                      jboolean block_trackers,
                                      jboolean strip_query_params,
                                      jboolean https_upgrade,
                                      jboolean block_mixed_content,
                                      jboolean record_stats) {
  veko::chromium::RequestEvaluationOptions opts;
  opts.block_ads = (block_ads == JNI_TRUE);
  opts.block_trackers = (block_trackers == JNI_TRUE);
  opts.strip_query_params = (strip_query_params == JNI_TRUE);
  opts.https_upgrade = (https_upgrade == JNI_TRUE);
  opts.block_mixed_content = (block_mixed_content == JNI_TRUE);
  opts.record_stats = (record_stats == JNI_TRUE);

  const auto result = veko::chromium::VekoUrlRequestPipeline::Instance().EvaluateRequest(
      JStringToStdString(env, request_url),
      JStringToStdString(env, top_frame_url),
      JStringToStdString(env, resource_type),
      JStringToStdString(env, http_method),
      opts);

  jclass string_cls = env->FindClass("java/lang/String");
  if (string_cls == nullptr) return nullptr;
  jobjectArray arr = env->NewObjectArray(3, string_cls, env->NewStringUTF(""));
  if (arr == nullptr) return nullptr;

  const std::string action_str = std::to_string(static_cast<int32_t>(result.action));
  const std::string err_str = std::to_string(result.chromium_net_error);
  jstring j_action = env->NewStringUTF(action_str.c_str());
  jstring j_err = env->NewStringUTF(err_str.c_str());
  jstring j_url = env->NewStringUTF(result.effective_url.c_str());

  env->SetObjectArrayElement(arr, 0, j_action);
  env->SetObjectArrayElement(arr, 1, j_err);
  env->SetObjectArrayElement(arr, 2, j_url);

  env->DeleteLocalRef(j_action);
  env->DeleteLocalRef(j_err);
  env->DeleteLocalRef(j_url);
  env->DeleteLocalRef(string_cls);
  return arr;
}

jboolean NativeEvaluateTlsCertificateError(JNIEnv* env,
                                           jclass /*clazz*/,
                                           jint net_error,
                                           jstring host) {
  const bool allow = veko::chromium::VekoUrlRequestPipeline::AllowInvalidTlsCertificate(
      static_cast<int>(net_error), JStringToStdString(env, host));
  return allow ? JNI_TRUE : JNI_FALSE;
}

jlongArray NativeGetFilterStats(JNIEnv* env, jclass /*clazz*/) {
  const auto stats = veko::chromium::VekoUrlRequestPipeline::Instance().GetStats();
  jlong values[7] = {
      static_cast<jlong>(stats.total_requests_checked),
      static_cast<jlong>(stats.ads_blocked),
      static_cast<jlong>(stats.trackers_blocked),
      static_cast<jlong>(stats.params_stripped),
      static_cast<jlong>(stats.https_upgrades),
      static_cast<jlong>(stats.mixed_content_blocked),
      static_cast<jlong>(stats.active_filter_rules),
  };
  jlongArray arr = env->NewLongArray(7);
  if (arr != nullptr) {
    env->SetLongArrayRegion(arr, 0, 7, values);
  }
  return arr;
}

void NativeResetFilterStats(JNIEnv* /*env*/, jclass /*clazz*/) {
  veko::chromium::VekoUrlRequestPipeline::Instance().ResetStats();
}

const JNINativeMethod kJniMethods[] = {
    {"nativeGetEngineMetadata", "()Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetEngineMetadata)},
    {"nativeIsUpstreamContentLinked", "()Z",
     reinterpret_cast<void*>(NativeIsUpstreamContentLinked)},
    {"nativeCreateBrowserContext", "(ZLjava/lang/String;)J",
     reinterpret_cast<void*>(NativeCreateBrowserContext)},
    {"nativeIsOffTheRecordContext", "(J)Z",
     reinterpret_cast<void*>(NativeIsOffTheRecordContext)},
    {"nativeGetContextStoragePath", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetContextStoragePath)},
    {"nativeSetCookie", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Z",
     reinterpret_cast<void*>(NativeSetCookie)},
    {"nativeGetCookies", "(JLjava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetCookies)},
    {"nativeClearStoragePartition", "(J)Z",
     reinterpret_cast<void*>(NativeClearStoragePartition)},
    {"nativeDestroyBrowserContext", "(J)Z",
     reinterpret_cast<void*>(NativeDestroyBrowserContext)},
    {"nativeGetActiveOffTheRecordContextCount", "()I",
     reinterpret_cast<void*>(NativeGetActiveOffTheRecordContextCount)},
    {"nativeReloadFilterRules", "(Ljava/lang/String;Z)Z",
     reinterpret_cast<void*>(NativeReloadFilterRules)},
    {"nativeEvaluateUrlRequest",
     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZZZZ)[Ljava/lang/String;",
     reinterpret_cast<void*>(NativeEvaluateUrlRequest)},
    {"nativeEvaluateTlsCertificateError", "(ILjava/lang/String;)Z",
     reinterpret_cast<void*>(NativeEvaluateTlsCertificateError)},
    {"nativeGetFilterStats", "()[J", reinterpret_cast<void*>(NativeGetFilterStats)},
    {"nativeResetFilterStats", "()V", reinterpret_cast<void*>(NativeResetFilterStats)},
};

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
    return JNI_ERR;
  }
  jclass bridge_cls =
      env->FindClass("io/veko/browser/chromium/nativebridge/VekoChromiumNative");
  if (bridge_cls == nullptr) {
    return JNI_ERR;
  }
  const jint rc = env->RegisterNatives(
      bridge_cls, kJniMethods, sizeof(kJniMethods) / sizeof(kJniMethods[0]));
  env->DeleteLocalRef(bridge_cls);
  if (rc != JNI_OK) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
