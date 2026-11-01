#include <jni.h>

#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

#include "veko_shield_pipeline.h"

namespace {

constexpr const char* kBridgeClassName =
    "io/veko/browser/privacy/nativebridge/VekoAdblockNative";

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

// RAII wrapper đảm bảo giải phóng GetStringUTFChars an toàn trên mọi nhánh thoát hàm JNI.
class ScopedJniUtfChars {
 public:
  ScopedJniUtfChars(JNIEnv* env, jstring jstr) : env_(env), jstr_(jstr) {
    if (env_ != nullptr && jstr_ != nullptr) {
      chars_ = env_->GetStringUTFChars(jstr_, nullptr);
      if (env_->ExceptionCheck() == JNI_TRUE) {
        env_->ExceptionClear();
        chars_ = nullptr;
      }
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
  jstring out = env->NewStringUTF(utf8.c_str());
  if (env->ExceptionCheck() == JNI_TRUE) {
    env->ExceptionClear();
    return nullptr;
  }
  return out;
}

jobjectArray ToJStringArray(JNIEnv* env, const std::vector<std::string>& items) {
  if (env == nullptr) return nullptr;
  jclass string_cls = env->FindClass("java/lang/String");
  if (string_cls == nullptr || env->ExceptionCheck() == JNI_TRUE) {
    env->ExceptionClear();
    return nullptr;
  }
  jstring empty = env->NewStringUTF("");
  jobjectArray arr =
      env->NewObjectArray(static_cast<jsize>(items.size()), string_cls, empty);
  if (empty != nullptr) {
    env->DeleteLocalRef(empty);
  }
  if (arr == nullptr || env->ExceptionCheck() == JNI_TRUE) {
    env->ExceptionClear();
    env->DeleteLocalRef(string_cls);
    return nullptr;
  }
  for (size_t i = 0; i < items.size(); ++i) {
    jstring elem = env->NewStringUTF(items[i].c_str());
    if (elem != nullptr && env->ExceptionCheck() != JNI_TRUE) {
      env->SetObjectArrayElement(arr, static_cast<jsize>(i), elem);
    } else {
      env->ExceptionClear();
    }
    if (elem != nullptr) {
      env->DeleteLocalRef(elem);
    }
  }
  env->DeleteLocalRef(string_cls);
  return arr;
}

jstring JNICALL NativeGetShieldMetadata(JNIEnv* env, jclass /*clazz*/) {
  try {
    const auto& pipeline = veko::privacy::VekoUrlRequestPipeline::Instance();
    const char* adblock_ver = pipeline.AdblockEngineVersion();
    const bool handle_live = pipeline.IsHandleAllocated();
    std::ostringstream json;
    json << "{"
         << "\"adblockEngine\":\"" << (adblock_ver ? adblock_ver : "unknown") << "\","
         << "\"compiledAbi\":\"" << DetectCompiledAbi() << "\","
         << "\"nativeHandleAllocated\":" << (handle_live ? "true" : "false")
         << "}";
    return ToJString(env, json.str());
  } catch (...) {
    return ToJString(env, "{}");
  }
}

jboolean JNICALL NativeReloadFilterRules(JNIEnv* env,
                                         jclass /*clazz*/,
                                         jstring custom_rules_utf8,
                                         jboolean include_builtin) {
  try {
    ScopedJniUtfChars rules(env, custom_rules_utf8);
    return veko::privacy::VekoUrlRequestPipeline::Instance().ReloadFilterRules(
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

    veko::privacy::RequestEvaluationOptions opts;
    opts.block_ads = (block_ads == JNI_TRUE);
    opts.block_trackers = (block_trackers == JNI_TRUE);
    opts.strip_query_params = (strip_query_params == JNI_TRUE);
    opts.https_upgrade = (https_upgrade == JNI_TRUE);
    opts.block_mixed_content = (block_mixed_content == JNI_TRUE);
    opts.record_stats = (record_stats == JNI_TRUE);

    const auto result = veko::privacy::VekoUrlRequestPipeline::Instance().EvaluateRequest(
        req.str(), top.str(), rtype.str(), method.str(), opts);

    return ToJStringArray(
        env,
        {
            std::to_string(static_cast<int32_t>(result.action)),
            std::to_string(result.reason_code),
            result.effective_url,
        });
  } catch (...) {
    return ToJStringArray(
        env,
        {
            std::to_string(
                static_cast<int32_t>(veko::privacy::PipelineAction::kBlockUnsafeScheme)),
            std::to_string(veko::privacy::kReasonDisallowedUrlScheme),
            "",
        });
  }
}

jboolean JNICALL NativeEvaluateTlsCertificateError(JNIEnv* env,
                                                   jclass /*clazz*/,
                                                   jint error_code,
                                                   jstring host) {
  try {
    ScopedJniUtfChars host_str(env, host);
    return veko::privacy::VekoUrlRequestPipeline::AllowInvalidTlsCertificate(
               static_cast<int>(error_code), host_str.str())
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jlongArray JNICALL NativeGetFilterStats(JNIEnv* env, jclass /*clazz*/) {
  if (env == nullptr) return nullptr;
  jlongArray arr = env->NewLongArray(7);
  if (arr == nullptr || env->ExceptionCheck() == JNI_TRUE) {
    env->ExceptionClear();
    return nullptr;
  }
  try {
    const auto stats = veko::privacy::VekoUrlRequestPipeline::Instance().GetStats();
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
    veko::privacy::VekoUrlRequestPipeline::Instance().ResetStats();
  } catch (...) {
  }
}

jstring JNICALL NativeGetCosmeticCssForUrl(JNIEnv* env, jclass /*clazz*/, jstring page_url) {
  try {
    ScopedJniUtfChars url(env, page_url);
    return ToJString(
        env, veko::privacy::VekoUrlRequestPipeline::Instance().GetCosmeticCssForUrl(url.str()));
  } catch (...) {
    return ToJString(env, "");
  }
}

jboolean JNICALL NativeReleaseResources(JNIEnv* /*env*/, jclass /*clazz*/) {
  try {
    return veko::privacy::VekoUrlRequestPipeline::Instance().ReleaseNativeResources()
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

const JNINativeMethod kJniMethods[] = {
    {"nativeGetShieldMetadata", "()Ljava/lang/String;",
     reinterpret_cast<void*>(NativeGetShieldMetadata)},
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
    {"nativeReleaseResources", "()Z", reinterpret_cast<void*>(NativeReleaseResources)},
};

}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
  JNIEnv* env = nullptr;
  if (vm == nullptr ||
      vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
      env == nullptr) {
    return JNI_ERR;
  }
  jclass bridge_cls = env->FindClass(kBridgeClassName);
  if (bridge_cls == nullptr) {
    env->ExceptionClear();
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

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
  veko::privacy::VekoUrlRequestPipeline::Instance().ReleaseNativeResources();
}
