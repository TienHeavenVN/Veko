#include "veko_request_pipeline.h"

#include <algorithm>
#include <array>
#include <cctype>

namespace veko::chromium {
namespace {

std::string TrimAndLowerScheme(const std::string& url) {
  const auto colon = url.find(':');
  if (colon == std::string::npos) return "";
  std::string scheme = url.substr(0, colon);
  std::transform(scheme.begin(), scheme.end(), scheme.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return scheme;
}

bool IsLoopbackOrLocalTestUrl(const std::string& url) {
  const auto pos = url.find("://");
  if (pos == std::string::npos) return false;
  const std::string rest = url.substr(pos + 3);
  if (rest.rfind("127.0.0.1", 0) == 0 || rest.rfind("localhost", 0) == 0 ||
      rest.rfind("[::1]", 0) == 0) {
    const char next = (rest.size() > 9) ? rest[9] : '/';
    if (rest.rfind("127.0.0.1", 0) == 0 && (next == ':' || next == '/' || next == '?')) {
      return true;
    }
    if (rest.rfind("localhost", 0) == 0) {
      const char ln = (rest.size() > 9) ? rest[9] : '/';
      if (ln == ':' || ln == '/' || ln == '?') return true;
    }
    if (rest.rfind("[::1]", 0) == 0) return true;
  }
  return false;
}

bool IsActiveResourceType(const std::string& raw_type) {
  std::string t = raw_type;
  std::transform(t.begin(), t.end(), t.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return t == "script" || t == "stylesheet" || t == "sub_frame" || t == "subdocument" ||
         t == "xmlhttprequest" || t == "fetch" || t == "websocket" || t == "worker" ||
         t == "shared_worker" || t == "service_worker" || t == "object" || t == "csp_report";
}

bool IsTopLevelDocumentType(const std::string& raw_type) {
  std::string t = raw_type;
  std::transform(t.begin(), t.end(), t.begin(), [](unsigned char c) {
    return static_cast<char>(std::tolower(c));
  });
  return t == "document" || t == "main_frame";
}

}  // namespace

VekoUrlRequestPipeline& VekoUrlRequestPipeline::Instance() {
  static VekoUrlRequestPipeline instance;
  return instance;
}

VekoUrlRequestPipeline::VekoUrlRequestPipeline()
    : adblock_handle_(veko_adblock_create_default()) {}

VekoUrlRequestPipeline::~VekoUrlRequestPipeline() {
  if (adblock_handle_ != nullptr) {
    veko_adblock_destroy(adblock_handle_);
    adblock_handle_ = nullptr;
  }
}

const char* VekoUrlRequestPipeline::AdblockEngineVersion() const {
  return veko_adblock_engine_version();
}

bool VekoUrlRequestPipeline::ReloadFilterRules(const std::string& custom_rules_utf8,
                                               bool include_builtin) {
  if (adblock_handle_ == nullptr) return false;
  return veko_adblock_reload_rules(adblock_handle_,
                                   custom_rules_utf8.data(),
                                   custom_rules_utf8.size(),
                                   include_builtin);
}

RequestEvaluationResult VekoUrlRequestPipeline::EvaluateRequest(
    const std::string& request_url,
    const std::string& top_frame_url,
    const std::string& resource_type,
    const std::string& http_method,
    const RequestEvaluationOptions& options) {
  RequestEvaluationResult out;
  out.effective_url = request_url;

  if (request_url.empty()) {
    out.action = PipelineAction::kBlockUnsafeScheme;
    out.chromium_net_error = kErrDisallowedUrlScheme;
    out.effective_url.clear();
    return out;
  }

  const std::string scheme = TrimAndLowerScheme(request_url);
  if (scheme == "about") {
    if (request_url == "about:blank") {
      return out;
    }
    out.action = PipelineAction::kBlockUnsafeScheme;
    out.chromium_net_error = kErrDisallowedUrlScheme;
    out.effective_url.clear();
    return out;
  }

  if (scheme != "http" && scheme != "https") {
    out.action = PipelineAction::kBlockUnsafeScheme;
    out.chromium_net_error = kErrDisallowedUrlScheme;
    out.effective_url.clear();
    return out;
  }

  // 1. Chặn hoặc nâng cấp Mixed Content khi trang gốc là HTTPS nhưng tài nguyên con là HTTP (trừ loopback W3C)
  const std::string top_scheme = TrimAndLowerScheme(top_frame_url);
  if (!IsTopLevelDocumentType(resource_type) && top_scheme == "https" && scheme == "http" &&
      !IsLoopbackOrLocalTestUrl(request_url)) {
    if (options.block_mixed_content || IsActiveResourceType(resource_type)) {
      if (options.record_stats) {
        mixed_content_blocked_.fetch_add(1, std::memory_order_relaxed);
      }
      out.action = PipelineAction::kBlockMixedContent;
      out.chromium_net_error = kErrMixedContentBlocked;
      out.effective_url.clear();
      return out;
    }
    if (options.https_upgrade) {
      if (options.record_stats) {
        https_upgrades_.fetch_add(1, std::memory_order_relaxed);
      }
      out.action = PipelineAction::kUpgradeToHttps;
      out.effective_url = "https://" + request_url.substr(7);
    }
  }

  // 2. Đánh giá trực tiếp qua Rust adblock-rust FFI ngay trong C++ (không phát sinh JNI)
  std::array<char, 4096> rewritten_buf{};
  const int32_t filter_code = veko_adblock_evaluate_request(
      adblock_handle_,
      out.effective_url.c_str(),
      top_frame_url.c_str(),
      resource_type.c_str(),
      http_method.c_str(),
      options.block_ads,
      options.block_trackers,
      options.strip_query_params,
      options.record_stats,
      rewritten_buf.data(),
      rewritten_buf.size());

  if (filter_code == VEKO_FILTER_BLOCK_AD) {
    out.action = PipelineAction::kBlockAd;
    out.chromium_net_error = kErrBlockedByClient;
    out.effective_url.clear();
    return out;
  }
  if (filter_code == VEKO_FILTER_BLOCK_TRACKER) {
    out.action = PipelineAction::kBlockTracker;
    out.chromium_net_error = kErrBlockedByClient;
    out.effective_url.clear();
    return out;
  }
  if (filter_code == VEKO_FILTER_REWRITE_URL && rewritten_buf[0] != '\0') {
    out.action = PipelineAction::kRewriteCleanUrl;
    out.effective_url.assign(rewritten_buf.data());
  }

  // 3. Tự động nâng cấp điều hướng khung chính http:// -> https:// (không áp dụng cho loopback 127.0.0.1/localhost)
  if (IsTopLevelDocumentType(resource_type) && options.https_upgrade &&
      TrimAndLowerScheme(out.effective_url) == "http" &&
      !IsLoopbackOrLocalTestUrl(out.effective_url)) {
    if (options.record_stats) {
      https_upgrades_.fetch_add(1, std::memory_order_relaxed);
    }
    out.action = PipelineAction::kUpgradeToHttps;
    out.effective_url = "https://" + out.effective_url.substr(7);
  }

  return out;
}

bool VekoUrlRequestPipeline::AllowInvalidTlsCertificate(int /*chromium_net_error*/,
                                                        const std::string& /*host*/) {
  // Bất biến an ninh: Veko tuyệt đối không cho phép vượt qua chứng chỉ TLS lỗi
  return false;
}

NativePipelineStats VekoUrlRequestPipeline::GetStats() const {
  VekoFilterStats rust_stats{};
  veko_adblock_get_stats(adblock_handle_, &rust_stats);
  NativePipelineStats combined;
  combined.total_requests_checked = rust_stats.total_requests_checked;
  combined.ads_blocked = rust_stats.ads_blocked;
  combined.trackers_blocked = rust_stats.trackers_blocked;
  combined.params_stripped = rust_stats.params_stripped;
  combined.https_upgrades = https_upgrades_.load(std::memory_order_relaxed);
  combined.mixed_content_blocked = mixed_content_blocked_.load(std::memory_order_relaxed);
  combined.active_filter_rules = rust_stats.active_rule_count;
  return combined;
}

void VekoUrlRequestPipeline::ResetStats() {
  veko_adblock_reset_stats(adblock_handle_);
  https_upgrades_.store(0, std::memory_order_relaxed);
  mixed_content_blocked_.store(0, std::memory_order_relaxed);
}

}  // namespace veko::chromium
