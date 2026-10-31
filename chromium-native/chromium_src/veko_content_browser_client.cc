#include "veko_content_browser_client.h"

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include "content/public/browser/web_contents.h"
#include "services/network/public/cpp/resource_request.h"
#include "veko_url_loader_throttle.h"

namespace veko::embedder {

VekoBrowserContext::VekoBrowserContext(const base::FilePath& disk_profile_path,
                                       bool is_off_the_record)
    : disk_profile_path_(is_off_the_record ? base::FilePath() : disk_profile_path),
      is_off_the_record_(is_off_the_record) {}

VekoBrowserContext::~VekoBrowserContext() {
  ShutdownStoragePartitions();
}

base::FilePath VekoBrowserContext::GetPath() const {
  return is_off_the_record_ ? base::FilePath() : disk_profile_path_;
}

bool VekoBrowserContext::IsOffTheRecord() const {
  return is_off_the_record_;
}

VekoBrowserContext* VekoBrowserContext::GetOrCreateOffTheRecordContext() {
  if (is_off_the_record_) return this;
  if (!otr_context_) {
    otr_context_ = std::make_unique<VekoBrowserContext>(base::FilePath(), /*is_off_the_record=*/true);
  }
  return otr_context_.get();
}

void VekoBrowserContext::DestroyOffTheRecordContext() {
  otr_context_.reset();
}

VekoContentBrowserClient::VekoContentBrowserClient() = default;
VekoContentBrowserClient::~VekoContentBrowserClient() = default;

std::vector<std::unique_ptr<blink::URLLoaderThrottle>>
VekoContentBrowserClient::CreateURLLoaderThrottles(
    const network::ResourceRequest& request,
    content::BrowserContext* browser_context,
    const base::RepeatingCallback<content::WebContents*()>& wc_getter,
    content::NavigationUIData* /*navigation_ui_data*/,
    content::FrameTreeNodeId /*frame_tree_node_id*/,
    std::optional<int64_t> /*navigation_id*/) {
  std::vector<std::unique_ptr<blink::URLLoaderThrottle>> throttles;
  std::string top_frame_url;
  if (content::WebContents* wc = wc_getter.Run()) {
    top_frame_url = wc->GetLastCommittedURL().spec();
  }
  chromium::RequestEvaluationOptions opts;
  // Không ghi thống kê lịch sử dài hạn đối với Off-The-Record BrowserContext nếu cần tách biệt
  opts.record_stats = (browser_context == nullptr || !browser_context->IsOffTheRecord());
  throttles.push_back(
      std::make_unique<VekoPrivacyUrlLoaderThrottle>(std::move(top_frame_url), opts));
  return throttles;
}

void VekoContentBrowserClient::AllowCertificateError(
    content::WebContents* /*web_contents*/,
    int /*cert_error*/,
    const net::SSLInfo& /*ssl_info*/,
    const GURL& /*request_url*/,
    bool /*is_primary_main_frame_request*/,
    bool /*strict_enforcement*/,
    base::OnceCallback<void(content::CertificateRequestResultType)> callback) {
  std::move(callback).Run(content::CERTIFICATE_REQUEST_RESULT_TYPE_DENY);
}

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
