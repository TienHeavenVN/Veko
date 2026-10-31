// Triển khai URLLoaderThrottle chuẩn Chromium Content 156.0.8078.85 (biên dịch trong cây nguồn //veko_embedder).
// Đánh chặn mọi điều hướng khung chính, tài nguyên con và chuyển hướng 30x trước khi phát sinh gói tin mạng.
#ifndef VEKO_CHROMIUM_SRC_URL_LOADER_THROTTLE_H_
#define VEKO_CHROMIUM_SRC_URL_LOADER_THROTTLE_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <string>

#include "third_party/blink/public/common/loader/url_loader_throttle.h"
#include "veko_request_pipeline.h"

namespace veko::embedder {

class VekoPrivacyUrlLoaderThrottle : public blink::URLLoaderThrottle {
 public:
  explicit VekoPrivacyUrlLoaderThrottle(std::string top_frame_url,
                                        chromium::RequestEvaluationOptions options);
  ~VekoPrivacyUrlLoaderThrottle() override;

  // blink::URLLoaderThrottle:
  void DetachFromCurrentSequence() override;
  void WillStartRequest(network::ResourceRequest* request, bool* defer) override;
  void WillRedirectRequest(net::RedirectInfo* redirect_info,
                           const network::mojom::URLResponseHead& response_head,
                           bool* defer,
                           std::vector<std::string>* to_be_removed_request_headers,
                           net::HttpRequestHeaders* modified_request_headers,
                           net::HttpRequestHeaders* modified_cors_exempt_request_headers) override;

 private:
  std::string top_frame_url_;
  chromium::RequestEvaluationOptions options_;
};

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_URL_LOADER_THROTTLE_H_
