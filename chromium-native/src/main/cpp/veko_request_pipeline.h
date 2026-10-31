#ifndef VEKO_CHROMIUM_REQUEST_PIPELINE_H_
#define VEKO_CHROMIUM_REQUEST_PIPELINE_H_

#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>

#include "veko_adblock_ffi.h"

namespace veko::chromium {

// Hằng số mã lỗi mạng đối chiếu trực tiếp với net/base/net_error_list.h của Chromium
constexpr int kNetOk = 0;
constexpr int kErrBlockedByClient = -20;
constexpr int kErrCertCommonNameInvalid = -200;
constexpr int kErrCertAuthorityInvalid = -202;
constexpr int kErrCertInvalid = -207;
constexpr int kErrMixedContentBlocked = -301;
constexpr int kErrDisallowedUrlScheme = -302;

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
  int chromium_net_error = kNetOk;
  std::string effective_url;
};

struct NativePipelineStats {
  uint64_t total_requests_checked = 0;
  uint64_t ads_blocked = 0;
  uint64_t trackers_blocked = 0;
  uint64_t params_stripped = 0;
  uint64_t https_upgrades = 0;
  uint64_t mixed_content_blocked = 0;
  uint64_t active_filter_rules = 0;
};

/**
 * Đường ống đánh chặn request C++ kết nối trực tiếp tới Rust adblock-rust FFI:
 * Thực thi tại điểm chặn WillStartRequest / WillRedirectRequest của URLLoaderThrottle.
 */
class VekoUrlRequestPipeline {
 public:
  static VekoUrlRequestPipeline& Instance();

  ~VekoUrlRequestPipeline();

  const char* AdblockEngineVersion() const;

  bool ReloadFilterRules(const std::string& custom_rules_utf8, bool include_builtin);

  RequestEvaluationResult EvaluateRequest(const std::string& request_url,
                                          const std::string& top_frame_url,
                                          const std::string& resource_type,
                                          const std::string& http_method,
                                          const RequestEvaluationOptions& options);

  // Chính sách cứng: không bao giờ bỏ qua lỗi chứng chỉ TLS không hợp lệ
  static bool AllowInvalidTlsCertificate(int chromium_net_error, const std::string& host);

  NativePipelineStats GetStats() const;
  void ResetStats();

 private:
  VekoUrlRequestPipeline();

  VekoAdblockHandle* adblock_handle_ = nullptr;
  std::atomic<uint64_t> https_upgrades_{0};
  std::atomic<uint64_t> mixed_content_blocked_{0};
};

}  // namespace veko::chromium

#endif  // VEKO_CHROMIUM_REQUEST_PIPELINE_H_
