#include "veko_shield_pipeline.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <mutex>
#include <vector>

namespace veko::privacy {
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
  ReleaseNativeResources();
}

const char* VekoUrlRequestPipeline::AdblockEngineVersion() const {
  return veko_adblock_engine_version();
}

bool VekoUrlRequestPipeline::IsHandleAllocated() const {
  std::shared_lock<std::shared_mutex> lock(handle_mutex_);
  return adblock_handle_ != nullptr;
}

VekoAdblockHandle* VekoUrlRequestPipeline::EnsureHandleLocked() {
  if (adblock_handle_ == nullptr) {
    adblock_handle_ = veko_adblock_create_with_rules(
        cached_custom_rules_.data(), cached_custom_rules_.size(), cached_include_builtin_);
  }
  return adblock_handle_;
}

bool VekoUrlRequestPipeline::ReloadFilterRules(const std::string& custom_rules_utf8,
                                               bool include_builtin) {
  std::unique_lock<std::shared_mutex> lock(handle_mutex_);
  cached_custom_rules_ = custom_rules_utf8;
  cached_include_builtin_ = include_builtin;
  if (adblock_handle_ == nullptr) {
    adblock_handle_ = veko_adblock_create_with_rules(
        custom_rules_utf8.data(), custom_rules_utf8.size(), include_builtin);
    return adblock_handle_ != nullptr;
  }
  return veko_adblock_reload_rules(
      adblock_handle_, custom_rules_utf8.data(), custom_rules_utf8.size(), include_builtin);
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
    out.reason_code = kReasonDisallowedUrlScheme;
    out.effective_url.clear();
    return out;
  }

  const std::string scheme = TrimAndLowerScheme(request_url);
  if (scheme == "about") {
    if (request_url == "about:blank") {
      return out;
    }
    out.action = PipelineAction::kBlockUnsafeScheme;
    out.reason_code = kReasonDisallowedUrlScheme;
    out.effective_url.clear();
    return out;
  }

  if (scheme != "http" && scheme != "https") {
    out.action = PipelineAction::kBlockUnsafeScheme;
    out.reason_code = kReasonDisallowedUrlScheme;
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
      out.reason_code = kReasonMixedContentBlocked;
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

  // 2. Đánh giá trực tiếp qua Rust adblock-rust FFI với khóa đọc chia sẻ (shared_lock)
  std::array<char, 4096> rewritten_buf{};
  int32_t filter_code = VEKO_FILTER_ALLOW;
  {
    std::shared_lock<std::shared_mutex> read_lock(handle_mutex_);
    if (adblock_handle_ != nullptr) {
      filter_code = veko_adblock_evaluate_request(
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
    } else {
      read_lock.unlock();
      std::unique_lock<std::shared_mutex> write_lock(handle_mutex_);
      VekoAdblockHandle* recreated = EnsureHandleLocked();
      if (recreated != nullptr) {
        filter_code = veko_adblock_evaluate_request(
            recreated,
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
      }
    }
  }

  if (filter_code == VEKO_FILTER_BLOCK_AD) {
    out.action = PipelineAction::kBlockAd;
    out.reason_code = kReasonBlockedByShield;
    out.effective_url.clear();
    return out;
  }
  if (filter_code == VEKO_FILTER_BLOCK_TRACKER) {
    out.action = PipelineAction::kBlockTracker;
    out.reason_code = kReasonBlockedByShield;
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

std::string VekoUrlRequestPipeline::GetCosmeticCssForUrl(const std::string& page_url) {
  if (page_url.empty()) return "";
  std::vector<char> css_buf(16384, '\0');
  size_t written = 0;
  {
    std::shared_lock<std::shared_mutex> read_lock(handle_mutex_);
    if (adblock_handle_ != nullptr) {
      written = veko_adblock_cosmetic_css_for_url(
          adblock_handle_, page_url.c_str(), css_buf.data(), css_buf.size());
    } else {
      read_lock.unlock();
      std::unique_lock<std::shared_mutex> write_lock(handle_mutex_);
      VekoAdblockHandle* recreated = EnsureHandleLocked();
      if (recreated != nullptr) {
        written = veko_adblock_cosmetic_css_for_url(
            recreated, page_url.c_str(), css_buf.data(), css_buf.size());
      }
    }
  }
  if (written == 0 || written >= css_buf.size()) return "";
  return std::string(css_buf.data(), written);
}

bool VekoUrlRequestPipeline::AllowInvalidTlsCertificate(int /*error_code*/,
                                                        const std::string& /*host*/) {
  // Bất biến an ninh: Veko tuyệt đối không cho phép vượt qua chứng chỉ TLS lỗi
  return false;
}

ShieldPipelineStats VekoUrlRequestPipeline::GetStats() {
  VekoFilterStats rust_stats{};
  {
    std::shared_lock<std::shared_mutex> read_lock(handle_mutex_);
    if (adblock_handle_ != nullptr) {
      veko_adblock_get_stats(adblock_handle_, &rust_stats);
    } else {
      read_lock.unlock();
      std::unique_lock<std::shared_mutex> write_lock(handle_mutex_);
      VekoAdblockHandle* recreated = EnsureHandleLocked();
      if (recreated != nullptr) {
        veko_adblock_get_stats(recreated, &rust_stats);
      }
    }
  }
  ShieldPipelineStats combined;
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
  std::shared_lock<std::shared_mutex> lock(handle_mutex_);
  if (adblock_handle_ != nullptr) {
    veko_adblock_reset_stats(adblock_handle_);
  }
  https_upgrades_.store(0, std::memory_order_relaxed);
  mixed_content_blocked_.store(0, std::memory_order_relaxed);
}

bool VekoUrlRequestPipeline::ReleaseNativeResources() {
  std::unique_lock<std::shared_mutex> lock(handle_mutex_);
  if (adblock_handle_ == nullptr) {
    return false;
  }
  veko_adblock_destroy(adblock_handle_);
  adblock_handle_ = nullptr;
  return true;
}

}  // namespace veko::privacy
