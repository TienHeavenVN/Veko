#include "veko_url_loader_throttle.h"

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include "net/base/net_errors.h"
#include "net/url_request/redirect_info.h"
#include "services/network/public/cpp/resource_request.h"
#include "url/gurl.h"

namespace veko::embedder {

VekoPrivacyUrlLoaderThrottle::VekoPrivacyUrlLoaderThrottle(
    std::string top_frame_url,
    chromium::RequestEvaluationOptions options)
    : top_frame_url_(std::move(top_frame_url)), options_(options) {}

VekoPrivacyUrlLoaderThrottle::~VekoPrivacyUrlLoaderThrottle() = default;

void VekoPrivacyUrlLoaderThrottle::DetachFromCurrentSequence() {}

void VekoPrivacyUrlLoaderThrottle::WillStartRequest(network::ResourceRequest* request,
                                                    bool* defer) {
  *defer = false;
  const std::string initiator =
      top_frame_url_.empty() && request->request_initiator.has_value()
          ? request->request_initiator->GetURL().spec()
          : top_frame_url_;

  const auto decision = chromium::VekoUrlRequestPipeline::Instance().EvaluateRequest(
      request->url.spec(),
      initiator,
      request->is_outermost_main_frame ? "document" : "subresource",
      request->method,
      options_);

  switch (decision.action) {
    case chromium::PipelineAction::kAllow:
      return;
    case chromium::PipelineAction::kRewriteCleanUrl:
    case chromium::PipelineAction::kUpgradeToHttps:
      request->url = GURL(decision.effective_url);
      return;
    case chromium::PipelineAction::kBlockAd:
    case chromium::PipelineAction::kBlockTracker:
    case chromium::PipelineAction::kBlockMixedContent:
    case chromium::PipelineAction::kBlockUnsafeScheme:
      delegate_->CancelWithError(decision.chromium_net_error, "VekoShield");
      return;
  }
}

void VekoPrivacyUrlLoaderThrottle::WillRedirectRequest(
    net::RedirectInfo* redirect_info,
    const network::mojom::URLResponseHead& /*response_head*/,
    bool* defer,
    std::vector<std::string>* /*to_be_removed_request_headers*/,
    net::HttpRequestHeaders* /*modified_request_headers*/,
    net::HttpRequestHeaders* /*modified_cors_exempt_request_headers*/) {
  *defer = false;
  const auto decision = chromium::VekoUrlRequestPipeline::Instance().EvaluateRequest(
      redirect_info->new_url.spec(),
      top_frame_url_,
      "subresource",
      redirect_info->new_method,
      options_);

  if (decision.chromium_net_error != chromium::kNetOk) {
    delegate_->CancelWithError(decision.chromium_net_error, "VekoShieldRedirect");
  } else if (!decision.effective_url.empty() &&
             decision.effective_url != redirect_info->new_url.spec()) {
    redirect_info->new_url = GURL(decision.effective_url);
  }
}

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
