#include <jni.h>

#include <cstdint>
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

// RAII wrapper đảm bảo luôn gọi ReleaseStringUTFChars trên mọi nhánh thoát hàm JNI.
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
  jobjectArray out =
      env->NewObjectArray(static_cast<jsize>(items.size()), string_cls, ToJString(env, ""));
  if (out == nullptr) {
    env->DeleteLocalRef(string_cls);
    return nullptr;
  }
  for (size_t i = 0; i < items.size(); ++i) {
    jstring elem = ToJString(env, items[i]);
    env->SetObjectArrayElement(out, static_cast<jsize>(i), elem);
    if (elem != nullptr) {
      env->DeleteLocalRef(elem);
    }
  }
  env->DeleteLocalRef(string_cls);
  return out;
}

const char* ActionToWireName(veko::chromium::PipelineAction action) {
  using veko::chromium::PipelineAction;
  switch (action) {
    case PipelineAction::kAllow:
      return "ALLOW";
    case PipelineAction::kBlock:
      return "BLOCK";
    case PipelineAction::kUpgradeScheme:
      return "UPGRADE_HTTPS";
    case PipelineAction::kRewriteUrl:
      return "REWRITE_URL";
    case PipelineAction::kRejectUnsafe:
      return "REJECT_UNSAFE";
  }
  return "ALLOW";
}

// ============================================================================
// Nhóm 1: Metadata & BrowserContext / Cookie / Privacy Pipeline (tương thích ngược)
// ============================================================================

jstring JNICALL NativeGetPinnedChromiumVersion(JNIEnv* env, jobject /*thiz*/) {
  try {
    return ToJString(env, kPinnedChromiumVersion);
  } catch (...) {
    return ToJString(env, "");
  }
}

jstring JNICALL NativeGetPinnedChromiumCommit(JNIEnv* env, jobject /*thiz*/) {
  try {
    return ToJString(env, kPinnedChromiumCommit);
  } catch (...) {
    return ToJString(env, "");
  }
}

jlong JNICALL NativeCreateBrowserContext(JNIEnv* env,
                                         jobject /*thiz*/,
                                         jboolean off_the_record,
                                         jstring storage_path) {
  try {
    ScopedJniUtfChars path(env, storage_path);
    return static_cast<jlong>(
        veko::chromium::NativeBrowserContextRegistry::Instance().CreateContext(
            off_the_record == JNI_TRUE, path.str()));
  } catch (...) {
    return 0;
  }
}

jboolean JNICALL NativeDestroyBrowserContext(JNIEnv* /*env*/,
                                             jobject /*thiz*/,
                                             jlong context_handle) {
  try {
    const int64_t handle = static_cast<int64_t>(context_handle);
    // Đảm bảo hủy toàn bộ phiên trình duyệt con trước khi hủy BrowserContext sở hữu chúng
    veko::chromium::VekoNativeBrowserRuntime::Instance().DestroyBrowsersForContext(handle);
    return veko::chromium::NativeBrowserContextRegistry::Instance().DestroyContext(handle)
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeIsOffTheRecord(JNIEnv* /*env*/,
                                      jobject /*thiz*/,
                                      jlong context_handle) {
  try {
    return veko::chromium::NativeBrowserContextRegistry::Instance().IsOffTheRecord(
               static_cast<int64_t>(context_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeSetCookie(JNIEnv* env,
                                 jobject /*thiz*/,
                                 jlong context_handle,
                                 jstring origin,
                                 jstring name,
                                 jstring value) {
  try {
    ScopedJniUtfChars origin_str(env, origin);
    ScopedJniUtfChars name_str(env, name);
    ScopedJniUtfChars value_str(env, value);
    return veko::chromium::NativeBrowserContextRegistry::Instance().SetCookie(
               static_cast<int64_t>(context_handle),
               origin_str.str(),
               name_str.str(),
               value_str.str())
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jstring JNICALL NativeGetCookie(JNIEnv* env,
                                jobject /*thiz*/,
                                jlong context_handle,
                                jstring origin,
                                jstring name) {
  try {
    ScopedJniUtfChars origin_str(env, origin);
    ScopedJniUtfChars name_str(env, name);
    const std::string val =
        veko::chromium::NativeBrowserContextRegistry::Instance().GetCookie(
            static_cast<int64_t>(context_handle), origin_str.str(), name_str.str());
    return ToJString(env, val);
  } catch (...) {
    return ToJString(env, "");
  }
}

jint JNICALL NativeActiveContextCount(JNIEnv* /*env*/, jobject /*thiz*/) {
  try {
    return static_cast<jint>(
        veko::chromium::NativeBrowserContextRegistry::Instance().ActiveContextCount());
  } catch (...) {
    return 0;
  }
}

jboolean JNICALL NativeLoadFilterRules(JNIEnv* env,
                                       jobject /*thiz*/,
                                       jstring raw_filter_list) {
  try {
    ScopedJniUtfChars rules(env, raw_filter_list);
    return veko::chromium::VekoUrlRequestPipeline::Instance().ConfigureFilterRules(
               rules.str())
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jlong JNICALL NativeGetFilterRuleCount(JNIEnv* /*env*/, jobject /*thiz*/) {
  try {
    return static_cast<jlong>(
        veko::chromium::VekoUrlRequestPipeline::Instance().LoadedFilterRuleCount());
  } catch (...) {
    return 0;
  }
}

jobjectArray JNICALL NativeEvaluateUrlRequest(JNIEnv* env,
                                              jobject /*thiz*/,
                                              jstring request_url,
                                              jstring initiator_url,
                                              jstring resource_type,
                                              jboolean https_upgrade,
                                              jboolean strip_tracking_params,
                                              jboolean adblock_enabled,
                                              jboolean record_stats) {
  try {
    ScopedJniUtfChars req(env, request_url);
    ScopedJniUtfChars init(env, initiator_url);
    ScopedJniUtfChars rtype(env, resource_type);
    veko::chromium::RequestEvaluationOptions opts;
    opts.https_upgrade_enabled = (https_upgrade == JNI_TRUE);
    opts.strip_tracking_params = (strip_tracking_params == JNI_TRUE);
    opts.adblock_enabled = (adblock_enabled == JNI_TRUE);
    opts.record_stats = (record_stats == JNI_TRUE);

    const auto decision =
        veko::chromium::VekoUrlRequestPipeline::Instance().EvaluateRequest(
            req.str(), init.str(), rtype.str(), opts);

    return ToJStringArray(env,
                          {
                              ActionToWireName(decision.action),
                              decision.mutated_url,
                              decision.matched_rule_reason,
                          });
  } catch (...) {
    return ToJStringArray(env, {"REJECT_UNSAFE", "", "native_exception"});
  }
}

jlongArray JNICALL NativeGetPipelineStats(JNIEnv* env, jobject /*thiz*/) {
  jlongArray arr = env->NewLongArray(4);
  if (arr == nullptr) return nullptr;
  try {
    const auto stats = veko::chromium::VekoUrlRequestPipeline::Instance().GetStats();
    jlong values[4] = {
        static_cast<jlong>(stats.total_evaluated),
        static_cast<jlong>(stats.total_blocked),
        static_cast<jlong>(stats.total_upgraded_https),
        static_cast<jlong>(stats.total_params_stripped),
    };
    env->SetLongArrayRegion(arr, 0, 4, values);
  } catch (...) {
    jlong zeros[4] = {0, 0, 0, 0};
    env->SetLongArrayRegion(arr, 0, 4, zeros);
  }
  return arr;
}

void JNICALL NativeResetPipelineStats(JNIEnv* /*env*/, jobject /*thiz*/) {
  try {
    veko::chromium::VekoUrlRequestPipeline::Instance().ResetStats();
  } catch (...) {
  }
}

// ============================================================================
// Nhóm 2 (Phase 4): Runtime Lifecycle, Browser Session, Navigation & Surface JNI
// ============================================================================

jobjectArray JNICALL NativeInitializeRuntime(JNIEnv* env,
                                             jobject /*thiz*/,
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

jboolean JNICALL NativeIsRuntimeInitialized(JNIEnv* /*env*/, jobject /*thiz*/) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().IsInitialized()
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeIsUpstreamContentLinked(JNIEnv* /*env*/, jobject /*thiz*/) {
  return veko::chromium::VekoNativeBrowserRuntime::IsUpstreamContentLinked()
             ? JNI_TRUE
             : JNI_FALSE;
}

jlong JNICALL NativeCreateBrowser(JNIEnv* /*env*/,
                                  jobject /*thiz*/,
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
                                   jobject /*thiz*/,
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
            ActionToWireName(outcome.pipeline_action),
            outcome.final_url,
            outcome.matched_rule_reason,
            outcome.upstream_content_rendered ? "1" : "0",
        });
  } catch (...) {
    return ToJStringArray(
        env,
        {
            std::to_string(
                static_cast<int32_t>(veko::chromium::NativeRuntimeError::kErrNativeException)),
            "REJECT_UNSAFE",
            "",
            "native_exception",
            "0",
        });
  }
}

jint JNICALL NativeReload(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().Reload(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jint JNICALL NativeStopLoading(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().StopLoading(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jboolean JNICALL NativeGoBack(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().GoBack(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeGoForward(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().GoForward(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeCanGoBack(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().CanGoBack(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jboolean JNICALL NativeCanGoForward(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return veko::chromium::VekoNativeBrowserRuntime::Instance().CanGoForward(
               static_cast<int64_t>(browser_handle))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jstring JNICALL NativeGetCurrentUrl(JNIEnv* env, jobject /*thiz*/, jlong browser_handle) {
  try {
    return ToJString(
        env,
        veko::chromium::VekoNativeBrowserRuntime::Instance().GetCurrentUrl(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return ToJString(env, "");
  }
}

jstring JNICALL NativeGetPageTitle(JNIEnv* env, jobject /*thiz*/, jlong browser_handle) {
  try {
    return ToJString(
        env,
        veko::chromium::VekoNativeBrowserRuntime::Instance().GetPageTitle(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return ToJString(env, "");
  }
}

jint JNICALL NativeGetLoadProgress(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().GetLoadProgress(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return 0;
  }
}

jint JNICALL NativeAttachSurface(JNIEnv* env,
                                 jobject /*thiz*/,
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
                                 jobject /*thiz*/,
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

jint JNICALL NativeDetachSurface(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().DetachSurface(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jlongArray JNICALL NativeGetSurfaceState(JNIEnv* env, jobject /*thiz*/, jlong browser_handle) {
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

jint JNICALL NativeDestroyBrowser(JNIEnv* /*env*/, jobject /*thiz*/, jlong browser_handle) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().DestroyBrowser(
            static_cast<int64_t>(browser_handle)));
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

jint JNICALL NativeGetActiveBrowserCount(JNIEnv* /*env*/, jobject /*thiz*/) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().ActiveBrowserCount());
  } catch (...) {
    return 0;
  }
}

jint JNICALL NativeShutdownRuntime(JNIEnv* /*env*/, jobject /*thiz*/) {
  try {
    return static_cast<jint>(
        veko::chromium::VekoNativeBrowserRuntime::Instance().Shutdown());
  } catch (...) {
    return static_cast<jint>(veko::chromium::NativeRuntimeError::kErrNativeException);
  }
}

const JNINativeMethod kMethods[] = {
    {"nativeGetPinnedChromiumVersion", "()Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetPinnedChromiumVersion)},
    {"nativeGetPinnedChromiumCommit", "()Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetPinnedChromiumCommit)},
    {"nativeCreateBrowserContext", "(ZLjava/lang/String;)J",
     reinterpret_cast<void*>(NativeCreateBrowserContext)},
    {"nativeDestroyBrowserContext", "(J)Z",
     reinterpret_cast<void*>(NativeDestroyBrowserContext)},
    {"nativeIsOffTheRecord", "(J)Z",
     reinterpret_cast<void*>(NativeIsOffTheRecord)},
    {"nativeSetCookie", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)Z",
     reinterpret_cast<void*>(NativeSetCookie)},
    {"nativeGetCookie", "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetCookie)},
    {"nativeActiveContextCount", "()I",
     reinterpret_cast<void*>(NativeActiveContextCount)},
    {"nativeLoadFilterRules", "(Ljava/lang/String;)Z",
     reinterpret_cast<void*>(NativeLoadFilterRules)},
    {"nativeGetFilterRuleCount", "()J",
     reinterpret_cast<void*>(NativeGetFilterRuleCount)},
    {"nativeEvaluateUrlRequest",
     "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZZ)[Ljava/lang/String;",
     reinterpret_cast<void*>(NativeEvaluateUrlRequest)},
    {"nativeGetPipelineStats", "()[J",
     reinterpret_cast<void*>(NativeGetPipelineStats)},
    {"nativeResetPipelineStats", "()V",
     reinterpret_cast<void*>(NativeResetPipelineStats)},
    // Phase 4 Runtime / Session / Navigation / Surface bindings
    {"nativeInitializeRuntime", "(Ljava/lang/String;Z)[Ljava/lang/String;",
     reinterpret_cast<void*>(NativeInitializeRuntime)},
    {"nativeIsRuntimeInitialized", "()Z",
     reinterpret_cast<void*>(NativeIsRuntimeInitialized)},
    {"nativeIsUpstreamContentLinked", "()Z",
     reinterpret_cast<void*>(NativeIsUpstreamContentLinked)},
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
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
      env == nullptr) {
    return JNI_ERR;
  }

  jclass bridge_cls = env->FindClass(kBridgeClassName);
  if (bridge_cls == nullptr) {
    return JNI_ERR;
  }

  const jint rc = env->RegisterNatives(
      bridge_cls, kMethods, static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
  env->DeleteLocalRef(bridge_cls);
  if (rc != JNI_OK) {
    return JNI_ERR;
  }
  return JNI_VERSION_1_6;
}
