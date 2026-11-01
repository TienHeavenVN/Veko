#ifndef VEKO_PRIVACY_SHIELD_PIPELINE_H_
#define VEKO_PRIVACY_SHIELD_PIPELINE_H_

#include <atomic>
#include <cstdint>
#include <shared_mutex>
#include <string>

#include "veko_adblock_ffi.h"

namespace veko::privacy {

// Mã lý do kỹ thuật độc lập với browser engine của Veko Privacy Shield
constexpr int kReasonOk = 0;
constexpr int kReasonBlockedByShield = -20;
constexpr int kReasonMixedContentBlocked = -301;
constexpr int kReasonDisallowedUrlScheme = -302;

enum class PipelineAction : int32_t {
  kAllow = 0,
  kBlockAd = 1,
  kBlockTracker = 2,
  kRewriteCleanUrl = 3,
  kUpgradeToHttps = 4,
  kBlockMixedContent = 5,
  kBlockUnsafeScheme = 6,
};

struct RequestEvaluationOptions {
  bool block_ads = true;
  bool block_trackers = true;
  bool strip_query_params = true;
  bool https_upgrade = true;
  bool block_mixed_content = true;
  bool record_stats = true;
};

struct RequestEvaluationResult {
  PipelineAction action = PipelineAction::kAllow;
  int reason_code = kReasonOk;
  std::string effective_url;
};

struct ShieldPipelineStats {
  uint64_t total_requests_checked = 0;
  uint64_t ads_blocked = 0;
  uint64_t trackers_blocked = 0;
  uint64_t params_stripped = 0;
  uint64_t https_upgrades = 0;
  uint64_t mixed_content_blocked = 0;
  uint64_t active_filter_rules = 0;
};

/**
 * Đường ống đánh giá yêu cầu mạng và CSS ẩn quảng cáo C++20 kết nối trực tiếp tới Rust `adblock-rust` FFI:
 * - Độc lập hoàn toàn với browser engine (phục vụ Mozilla GeckoView NavigationDelegate & Built-in WebExtension).
 * - Quản lý vòng đời bộ nhớ `VekoAdblockHandle*` an toàn đa luồng qua `std::shared_mutex` và giải phóng sạch khi hủy.
 */
class VekoUrlRequestPipeline {
 public:
  static VekoUrlRequestPipeline& Instance();

  VekoUrlRequestPipeline();
  ~VekoUrlRequestPipeline();

  VekoUrlRequestPipeline(const VekoUrlRequestPipeline&) = delete;
  VekoUrlRequestPipeline& operator=(const VekoUrlRequestPipeline&) = delete;

  const char* AdblockEngineVersion() const;

  bool IsHandleAllocated() const;

  bool ReloadFilterRules(const std::string& custom_rules_utf8, bool include_builtin);

  RequestEvaluationResult EvaluateRequest(const std::string& request_url,
                                          const std::string& top_frame_url,
                                          const std::string& resource_type,
                                          const std::string& http_method,
                                          const RequestEvaluationOptions& options);

  std::string GetCosmeticCssForUrl(const std::string& page_url);

  // Chính sách bất biến fail-closed: không bao giờ bỏ qua lỗi chứng chỉ TLS không hợp lệ
  static bool AllowInvalidTlsCertificate(int error_code, const std::string& host);

  ShieldPipelineStats GetStats();
  void ResetStats();

  // Giải phóng tường minh bộ nhớ Rust VekoAdblockHandle (tự động cấp phát lại nếu có yêu cầu mới)
  bool ReleaseNativeResources();

 private:
  VekoAdblockHandle* EnsureHandleLocked();

  mutable std::shared_mutex handle_mutex_;
  VekoAdblockHandle* adblock_handle_ = nullptr;
  std::string cached_custom_rules_;
  bool cached_include_builtin_ = true;

  std::atomic<uint64_t> https_upgrades_{0};
  std::atomic<uint64_t> mixed_content_blocked_{0};
};

}  // namespace veko::privacy

#endif  // VEKO_PRIVACY_SHIELD_PIPELINE_H_
