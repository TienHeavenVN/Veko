#include <jni.h>

#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

#include "veko_browser_session.h"
#include "veko_native_context.h"
#include "veko_request_pipeline.h"

namespace {

constexpr const char* kPinnedChromiumVersion = "156.0.8078.85";
constexpr const char* kPinnedChromiumCommit = "0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5";
constexpr const char* kBridgeClassName =
    "io/veko/browser/chromium/nativebridge/VekoChromiumNative";

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

// RAII wrapper đảm bảo luôn giải phóng GetStringUTFChars trên mọi nhánh thoát hàm JNI.
class ScopedJniUtfChars {
 public:
  ScopedJniUtfChars(JNIEnv* env, jstring jstr) : env_(env), jstr_(jstr) {
    if (env_ != nullptr && jstr_ != nullptr) {
      chars_ = env_->GetStringUTFChars(jstr_, nullptr);
    }
  }
  ScopedJniUtfChars(const ScopedJniUtfChars&) = delete;
  ScopedJniUtfChars& operator=(const ScopedJniUtfChars&) = delete;
  ~ScopedJniUtfChars() {
    if (env_ != nullptr && jstr_ != nullptr && chars_ != nullptr) {
      env_->ReleaseStringUTFChars(jstr_, chars_);
    }
  }

  std::string str() const { return chars_ != nullptr ? std::string(chars_) : std::string(); }

 private:
  JNIEnv* env_ = nullptr;
  jstring jstr_ = nullptr;
  const char* chars_ = nullptr;
};

jstring ToJString(JNIEnv* env, const std::string& utf8) {
  if (env == nullptr) return nullptr;
  return env->NewStringUTF(utf8.c_str());
}

jobjectArray ToJStringArray(JNIEnv* env, const std::vector<std::string>& items) {
  if (env == nullptr) return nullptr;
  jclass string_cls = env->FindClass("java/lang/String");
  if (string_cls == nullptr) return nullptr;
  jstring empty = env->NewStringUTF("");
  jobjectArray arr =
      env->NewObjectArray(static_cast<jsize>(items.size()), string_cls, empty);
  if (empty != nullptr) {
    env->DeleteLocalRef(empty);
  }
  if (arr == nullptr) {
    env->DeleteLocalRef(string_cls);
    return nullptr;
  }
  for (size_t i = 0; i < items.size(); ++i) {
    jstring elem = env->NewStringUTF(items[i].c_str());
    env->SetObjectArrayElement(arr, static_cast<jsize>(i), elem);
    if (elem != nullptr) {
      env->DeleteLocalRef(elem);
    }
  }
  env->DeleteLocalRef(string_cls);
  return arr;
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

// ============================================================================
// Nhóm 1: 15 phương thức JNI gốc của Veko (tương thích 100% chữ ký tĩnh jclass)
// ============================================================================

jstring JNICALL NativeGetEngineMetadata(JNIEnv* env, jclass /*clazz*/) {
  try {
    const char* adblock_ver =
        veko::chromium::VekoUrlRequestPipeline::Instance().AdblockEngineVersion();
    const bool upstream_linked =
        veko::chromium::VekoNativeBrowserRuntime::IsUpstreamContentLinked();
    std::ostringstream json;
    json << "{"
         << "\"chromiumVersion\":\"" << kPinnedChromiumVersion << "\","
         << "\"chromiumCommit\":\"" << kPinnedChromiumCommit << "\","
         << "\"adblockEngine\":\"" << (adblock_ver ? adblock_ver : "unknown") << "\","
         << "\"compiledAbi\":\"" << DetectCompiledAbi() << "\","
         << "\"upstreamContentLinked\":" << (upstream_linked ? "true" : "false")
         << "}";
    return ToJString(env, json.str());
  } catch (...) {
    return ToJString(env, "{}");
  }
}

jboolean JNICALL NativeIsUpstreamContentLinked(JNIEnv* /*env*/, jclass /*clazz*/) {
  return veko::chromium::VekoNativeBrowserRuntime::IsUpstreamContentLinked()
             ? JNI_TRUE
             : JNI_FALSE;
}

jlong JNICALL NativeCreateBrowserContext(JNIEnv* env,
                                         jclass /*clazz*/,
                                         jboolean is_off_the_record,
                                         jstring disk_storage_path) {
  try {
    ScopedJniUtfChars path(env, disk_storage_path);
    return static_cast<jlong>(
        veko::chromium::NativeBrowserContextRegistry::Instance().CreateContext(
            is_off_the_record == JNI_TRUE, path.str()));
  } catch (...) {
    return 0;
  }
}

jboolean JNICALL NativeIsOffTheRecordContext(JNIEnv* /*env*/,
                                             jclass /*clazz*/,
                                             jlong handle) {
  try {
    return veko::chromium::NativeBrowserContextRegistry::Instance().IsOffTheRecord(handle)
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jstring JNICALL NativeGetContextStoragePath(JNIEnv* env, jclass /*clazz*/, jlong handle) {
  try {
    return ToJString(
        env, veko::chromium::NativeBrowserContextRegistry::Instance().GetStoragePath(handle));
  } catch (...) {
    return ToJString(env, "");
  }
}

jboolean JNICALL NativeSetCookie(JNIEnv* env,
                                 jclass /*clazz*/,
                                 jlong handle,
                                 jstring request_url,
                                 jstring top_frame_url,
                                 jstring cookie_kv,
                                 jint cookie_mode_ordinal) {
  try {
    ScopedJniUtfChars req(env, request_url);
    ScopedJniUtfChars top(env, top_frame_url);
    ScopedJniUtfChars kv(env, cookie_kv);
    const bool ok = veko::chromium::NativeBrowserContextRegistry::Instance().SetCookie(
        handle, req.str(), top.str(), kv.str(), ToNativeCookieMode(cookie_mode_ordinal));
    return ok ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jstring JNICALL NativeGetCookies(JNIEnv* env,
                                 jclass /*clazz*/,
                                 jlong handle,
                                 jstring request_url,
                                 jstring top_frame_url,
                                 jint cookie_mode_ordinal) {
  try {
    ScopedJniUtfChars req(env, request_url);
    ScopedJniUtfChars top(env, top_frame_url);
    const std::string cookies =
        veko::chromium::NativeBrowserContextRegistry::Instance().GetCookies(
            handle, req.str(), top.str(), ToNativeCookieMode(cookie_mode_ordinal));
    return ToJString(env, cookies);
  } catch (...) {
    return ToJString(env, "");
  }
}

jboolean JNICALL NativeClearStoragePartition(JNIEnv* /*env*/,
                                             jclass /*clazz*/,
                                             jlong handle) {
  try {
    return veko::chromium::NativeBrowserContextRegistry::Instance().ClearStoragePartition(
               handle)
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeDestroyBrowserContext(JNIEnv* /*env*/,
                                             jclass /*clazz*/,
                                             jlong handle) {
  try {
    // Hủy sạch mọi phiên trình duyệt con thuộc BrowserContext trước khi giải phóng Context
    veko::chromium::VekoNativeBrowserRuntime::Instance().DestroyBrowsersForContext(handle);
    return veko::chromium::NativeBrowserContextRegistry::Instance().DestroyContext(handle)
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jint JNICALL NativeGetActiveOffTheRecordContextCount(JNIEnv* /*env*/, jclass /*clazz*/) {
  try {
    return static_cast<jint>(
        veko::chromium::NativeBrowserContextRegistry::Instance()
            .ActiveOffTheRecordContextCount());
  } catch (...) {
    return 0;
  }
}

jboolean JNICALL NativeReloadFilterRules(JNIEnv* env,
                                         jclass /*clazz*/,
                                         jstring custom_rules_utf8,
                                         jboolean include_builtin) {
  try {
    ScopedJniUtfChars rules(env, custom_rules_utf8);
    return veko::chromium::VekoUrlRequestPipeline::Instance().ReloadFilterRules(
               rules.str(), include_builtin == JNI_TRUE)
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jobjectArray JNICALL NativeEvaluateUrlRequest(JNIEnv* env,
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
  try {
    ScopedJniUtfChars req(env, request_url);
    ScopedJniUtfChars top(env, top_frame_url);
    ScopedJniUtfChars rtype(env, resource_type);
    ScopedJniUtfChars method(env, http_method);

    veko::chromium::RequestEvaluationOptions opts;
    opts.block_ads = (block_ads == JNI_TRUE);
    opts.block_trackers = (block_trackers == JNI_TRUE);
    opts.strip_query_params = (strip_query_params == JNI_TRUE);
    opts.https_upgrade = (https_upgrade == JNI_TRUE);
    opts.block_mixed_content = (block_mixed_content == JNI_TRUE);
    opts.record_stats = (record_stats == JNI_TRUE);

    const auto result = veko::chromium::VekoUrlRequestPipeline::Instance().EvaluateRequest(
        req.str(), top.str(), rtype.str(), method.str(), opts);

    return ToJStringArray(
        env,
        {
            std::to_string(static_cast<int32_t>(result.action)),
            std::to_string(result.chromium_net_error),
            result.effective_url,
        });
  } catch (...) {
    return ToJStringArray(
        env,
        {
            std::to_string(
                static_cast<int32_t>(veko::chromium::PipelineAction::kBlockUnsafeScheme)),
            std::to_string(veko::chromium::kErrDisallowedUrlScheme),
            "",
        });
  }
}

jboolean JNICALL NativeEvaluateTlsCertificateError(JNIEnv* env,
                                                   jclass /*clazz*/,
                                                   jint net_error,
                                                   jstring host) {
  try {
    ScopedJniUtfChars host_str(env, host);
    return veko::chromium::VekoUrlRequestPipeline::AllowInvalidTlsCertificate(
               static_cast<int>(net_error), host_str.str())
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jlongArray JNICALL NativeGetFilterStats(JNIEnv* env, jclass /*clazz*/) {
  jlongArray arr = env->NewLongArray(7);
  if (arr == nullptr) return nullptr;
  try {
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
    env->SetLongArrayRegion(arr, 0, 7, values);
  } catch (...) {
    jlong zeros[7] = {0, 0, 0, 0, 0, 0, 0};
    env->SetLongArrayRegion(arr, 0, 7, zeros);
  }
  return arr;
}

void JNICALL NativeResetFilterStats(JNIEnv* /*env*/, jclass /*clazz*/) {
  try {
    veko::chromium::VekoUrlRequestPipeline::Instance().ResetStats();
  } catch (...) {
  }
}

jstring JNICALL NativeGetCosmeticCssForUrl(JNIEnv* env, jclass /*clazz*/, jstring page_url) {
  try {
    ScopedJniUtfChars url(env, page_url);
    return ToJString(
        env, veko::chromium::VekoUrlRequestPipeline::Instance().GetCosmeticCssForUrl(url.str()));
  } catch (...) {
    return ToJString(env, "");
  }
}

// ============================================================================
// Nhóm 2 (Phase 4): Runtime Lifecycle, Browser Session, Navigation & Surface JNI
// ============================================================================

jobjectArray JNICALL NativeInitializeRuntime(JNIEnv* env,
                                             jclass /*clazz*/,
                                             jstring app_data_dir,
                                             jboolean enforce_thread_affinity) {
  try {
    ScopedJniUtfChars dir(env, app_data_dir);
    const auto snap = veko::chromium::VekoNativeBrowserRuntime::Instance().Initialize(
        dir.str(), enforce_thread_affinity == JNI_TRUE);
    return ToJStringArray(
        env,
        {
            std::to_string(static_cast<int32_t>(snap.error)),
            snap.initialized ? "1" : "0",
            snap.upstream_content_linked ? "1" : "0",
            snap.thread_affinity_enforced ? "1" : "0",
            std::to_string(snap.active_browsers),
            snap.data_dir,
        });
  } catch (...) {
    return ToJStringArray(
        env,
        {
            std::to_string(
                static_cast<int32_t>(veko::chromium::NativeRuntimeError::kErrNativeException)),
            "0",
            "0",
            "1",
            "0",
            "",
        });
  }
}

jboolean JNICALL NativeIsRuntimeInitialized(JNIEnv* /*env*/, jclass /*clazz*/) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().IsInitialized()
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jlong JNICALL NativeCreateBrowser(JNIEnv* /*env*/,
                                  jclass /*clazz*/,
                                  jlong context_handle,
                                  jboolean desktop_mode) {
  try {
    return static_cast<jlong>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().CreateBrowser(
            static_cast<int64_t>(context_handle), desktop_mode == JNI_TRUE));
  } catch (...) {
    return static_cast<jlong>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jobjectArray JNICALL NativeLoadUrl(JNIEnv* env,
                                   jclass /*clazz*/,
                                   jlong browser_handle,
                                   jstring raw_url) {
  try {
    ScopedJniUtfChars url(env, raw_url);
    const auto outcome = veko::chromium::VekoNativeBrowserRuntime::Instance().LoadUrl(
        static_cast<int64_t>(browser_handle), url.str());
    return ToJStringArray(
        env,
        {
            std::to_string(static_cast<int32_t>(outcome.error)),
            std::to_string(static_cast<int32_t>(outcome.pipeline_action)),
            std::to_string(outcome.chromium_net_error),
            outcome.final_url,
            outcome.upstream_content_rendered ? "1" : "0",
        });
  } catch (...) {
    return ToJStringArray(
        env,
        {
            std::to_string(
                static_cast<int32_t>(veko::chromium::NativeRuntimeError::kErrNativeException)),
            std::to_string(
                static_cast<int32_t>(veko::chromium::PipelineAction::kBlockUnsafeScheme)),
            std::to_string(veko::chromium::kErrDisallowedUrlScheme),
            "",
            "0",
        });
  }
}

jint JNICALL NativeReload(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().Reload(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jint JNICALL NativeStopLoading(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().StopLoading(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jboolean JNICALL NativeGoBack(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().GoBack(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeGoForward(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().GoForward(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeCanGoBack(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().CanGoBack(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeCanGoForward(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().CanGoForward(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jstring JNICALL NativeGetCurrentUrl(JNIEnv* env, jclass /*clazz*/, jlong browser_handle) {
  try {
    return ToJString(
        env,
        veko::chromium::VekoNativeBrowserRuntime::Instance().GetCurrentUrl(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return ToJString(env, "");
  }
}

jstring JNICALL NativeGetPageTitle(JNIEnv* env, jclass /*clazz*/, jlong browser_handle) {
  try {
    return ToJString(
        env,
        veko::chromium::VekoNativeBrowserRuntime::Instance().GetPageTitle(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return ToJString(env, "");
  }
}

jint JNICALL NativeGetLoadProgress(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().GetLoadProgress(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return 0;
  }
}

jint JNICALL NativeSetDesktopMode(JNIEnv* /*env*/,
                                  jclass /*clazz*/,
                                  jlong browser_handle,
                                  jboolean desktop_mode) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().SetDesktopMode(
            static_cast<int64_t>(browser_handle), desktop_mode == JNI_TRUE));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jboolean JNICALL NativeIsDesktopMode(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().IsDesktopMode(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jint JNICALL NativeAttachSurface(JNIEnv* env,
                                 jclass /*clazz*/,
                                 jlong browser_handle,
                                 jobject surface,
                                 jint width_px,
                                 jint height_px,
                                 jint density_dpi) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().AttachSurface(
            env,
            static_cast<int64_t>(browser_handle),
            surface,
            static_cast<int32_t>(width_px),
            static_cast<int32_t>(height_px),
            static_cast<int32_t>(density_dpi)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jint JNICALL NativeResizeSurface(JNIEnv* /*env*/,
                                 jclass /*clazz*/,
                                 jlong browser_handle,
                                 jint width_px,
                                 jint height_px) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().ResizeSurface(
            static_cast<int64_t>(browser_handle),
            static_cast<int32_t>(width_px),
            static_cast<int32_t>(height_px)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jint JNICALL NativeDetachSurface(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().DetachSurface(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jlongArray JNICALL NativeGetSurfaceState(JNIEnv* env, jclass /*clazz*/, jlong browser_handle) {
  jlongArray arr = env->NewLongArray(5);
  if (arr == nullptr) return nullptr;
  try {
    const auto state =
        veko::chromium::VekoNativeBrowserRuntime::Instance().GetSurfaceState(
            static_cast<int64_t>(browser_handle));
    jlong values[5] = {
        state.attached ? 1L : 0L,
        static_cast<jlong>(state.width_px),
        static_cast<jlong>(state.height_px),
        static_cast<jlong>(state.density_dpi),
        static_cast<jlong>(state.attach_generation),
    };
    env->SetLongArrayRegion(arr, 0, 5, values);
  } catch (...) {
    jlong zeros[5] = {0, 0, 0, 0, 0};
    env->SetLongArrayRegion(arr, 0, 5, zeros);
  }
  return arr;
}

jint JNICALL NativeDestroyBrowser(JNIEnv* /*env*/, jclass /*clazz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().DestroyBrowser(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jint JNICALL NativeGetActiveBrowserCount(JNIEnv* /*env*/, jclass /*clazz*/) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().ActiveBrowserCount());
  } catch (...) {
    return 0;
  }
}

jint JNICALL NativeShutdownRuntime(JNIEnv* /*env*/, jclass /*clazz*/) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().Shutdown());
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
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
    {"nativeGetCosmeticCssForUrl", "(Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetCosmeticCssForUrl)},
    // Phase 4 Runtime / Session / Navigation / Surface JNI methods
    {"nativeInitializeRuntime", "(Ljava/lang/String;Z)[Ljava/lang/String;",
     reinterpret_cast<void*>(NativeInitializeRuntime)},
    {"nativeIsRuntimeInitialized", "()Z",
     reinterpret_cast<void*>(NativeIsRuntimeInitialized)},
    {"nativeCreateBrowser", "(JZ)J",
     reinterpret_cast<void*>(NativeCreateBrowser)},
    {"nativeLoadUrl", "(JLjava/lang/String;)[Ljava/lang/String;",
     reinterpret_cast<void*>(NativeLoadUrl)},
    {"nativeReload", "(J)I",
     reinterpret_cast<void*>(NativeReload)},
    {"nativeStopLoading", "(J)I",
     reinterpret_cast<void*>(NativeStopLoading)},
    {"nativeGoBack", "(J)Z",
     reinterpret_cast<void*>(NativeGoBack)},
    {"nativeGoForward", "(J)Z",
     reinterpret_cast<void*>(NativeGoForward)},
    {"nativeCanGoBack", "(J)Z",
     reinterpret_cast<void*>(NativeCanGoBack)},
    {"nativeCanGoForward", "(J)Z",
     reinterpret_cast<void*>(NativeCanGoForward)},
    {"nativeGetCurrentUrl", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetCurrentUrl)},
    {"nativeGetPageTitle", "(J)Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetPageTitle)},
    {"nativeGetLoadProgress", "(J)I",
     reinterpret_cast<void*>(NativeGetLoadProgress)},
    {"nativeSetDesktopMode", "(JZ)I",
     reinterpret_cast<void*>(NativeSetDesktopMode)},
    {"nativeIsDesktopMode", "(J)Z",
     reinterpret_cast<void*>(NativeIsDesktopMode)},
    {"nativeAttachSurface", "(JLjava/lang/Object;III)I",
     reinterpret_cast<void*>(NativeAttachSurface)},
    {"nativeResizeSurface", "(JII)I",
     reinterpret_cast<void*>(NativeResizeSurface)},
    {"nativeDetachSurface", "(J)I",
     reinterpret_cast<void*>(NativeDetachSurface)},
    {"nativeGetSurfaceState", "(J)[J",
     reinterpret_cast<void*>(NativeGetSurfaceState)},
    {"nativeDestroyBrowser", "(J)I",
     reinterpret_cast<void*>(NativeDestroyBrowser)},
    {"nativeGetActiveBrowserCount", "()I",
     reinterpret_cast<void*>(NativeGetActiveBrowserCount)},
    {"nativeShutdownRuntime", "()I",
     reinterpret_cast<void*>(NativeShutdownRuntime)},
};

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
    return JNI_ERR;
  }
  jclass bridge_cls = env->FindClass(kBridgeClassName);
  if (bridge_cls == nullptr) {
    return JNI_ERR;
  }
  const jint rc = env->RegisterNatives(
      bridge_cls, kJniMethods, static_cast<jint>(sizeof(kJniMethods) / sizeof(kJniMethods[0])));
  env->DeleteLocalRef(bridge_cls);
  if (rc != JNI_OK) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
