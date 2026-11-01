#include "veko_content_browser_client.h"

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <utility>

#include "base/check.h"
#include "base/files/file_util.h"
#include "components/keyed_service/content/browser_context_dependency_manager.h"
#include "components/keyed_service/core/dependency_manager.h"
#include "components/keyed_service/core/simple_dependency_manager.h"
#include "components/keyed_service/core/simple_factory_key.h"
#include "components/keyed_service/core/simple_key_map.h"
#include "content/public/browser/web_contents.h"
#include "content/public/common/user_agent.h"
#include "services/network/public/cpp/resource_request.h"
#include "veko_browser_main_parts.h"
#include "veko_url_loader_throttle.h"

namespace veko::embedder {
namespace {
VekoContentBrowserClient* g_browser_client_instance = nullptr;
}  // namespace

VekoBrowserContext::VekoBrowserContext(const base::FilePath& disk_profile_path,
                                       bool is_off_the_record)
    : disk_profile_path_(is_off_the_record ? base::FilePath() : disk_profile_path),
      is_off_the_record_(is_off_the_record) {
  if (!is_off_the_record_ && !disk_profile_path_.empty()) {
    base::CreateDirectory(disk_profile_path_);
  }
  key_ = std::make_unique<SimpleFactoryKey>(disk_profile_path_, is_off_the_record_);
  SimpleKeyMap::GetInstance()->Associate(this, key_.get());
  BrowserContextDependencyManager::GetInstance()->CreateBrowserContextServices(this);
}

VekoBrowserContext::~VekoBrowserContext() {
  otr_context_.reset();
  NotifyWillBeDestroyed();
  DependencyManager::PerformInterlockedTwoPhaseShutdown(
      BrowserContextDependencyManager::GetInstance(),
      this,
      SimpleDependencyManager::GetInstance(),
      key_.get());
  SimpleKeyMap::GetInstance()->Dissociate(this);
  ShutdownStoragePartitions();
}

std::unique_ptr<content::ZoomLevelDelegate>
VekoBrowserContext::CreateZoomLevelDelegate(const base::FilePath& /*partition_path*/) {
  return nullptr;
}

base::FilePath VekoBrowserContext::GetPath() const {
  return is_off_the_record_ ? base::FilePath() : disk_profile_path_;
}

bool VekoBrowserContext::IsOffTheRecord() {
  return is_off_the_record_;
}

content::DownloadManagerDelegate* VekoBrowserContext::GetDownloadManagerDelegate() {
  return nullptr;
}

content::BrowserPluginGuestManager* VekoBrowserContext::GetGuestManager() {
  return nullptr;
}

storage::SpecialStoragePolicy* VekoBrowserContext::GetSpecialStoragePolicy() {
  return nullptr;
}

content::PlatformNotificationService*
VekoBrowserContext::GetPlatformNotificationService() {
  return nullptr;
}

content::PushMessagingService* VekoBrowserContext::GetPushMessagingService() {
  return nullptr;
}

content::StorageNotificationService*
VekoBrowserContext::GetStorageNotificationService() {
  return nullptr;
}

content::SSLHostStateDelegate* VekoBrowserContext::GetSSLHostStateDelegate() {
  return nullptr;
}

content::PermissionControllerDelegate*
VekoBrowserContext::GetPermissionControllerDelegate() {
  return nullptr;
}

content::ReduceAcceptLanguageControllerDelegate*
VekoBrowserContext::GetReduceAcceptLanguageControllerDelegate() {
  return nullptr;
}

content::ClientHintsControllerDelegate*
VekoBrowserContext::GetClientHintsControllerDelegate() {
  return nullptr;
}

content::BackgroundFetchDelegate* VekoBrowserContext::GetBackgroundFetchDelegate() {
  return nullptr;
}

content::BackgroundSyncController* VekoBrowserContext::GetBackgroundSyncController() {
  return nullptr;
}

content::BrowsingDataRemoverDelegate*
VekoBrowserContext::GetBrowsingDataRemoverDelegate() {
  return nullptr;
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

VekoContentBrowserClient::VekoContentBrowserClient() {
  DCHECK(!g_browser_client_instance);
  g_browser_client_instance = this;
}

VekoContentBrowserClient::~VekoContentBrowserClient() {
  if (g_browser_client_instance == this) {
    g_browser_client_instance = nullptr;
  }
}

VekoContentBrowserClient* VekoContentBrowserClient::Get() {
  return g_browser_client_instance;
}

VekoBrowserContext* VekoContentBrowserClient::regular_browser_context() const {
  return main_parts_ ? main_parts_->regular_browser_context() : nullptr;
}

VekoBrowserContext* VekoContentBrowserClient::AcquireOffTheRecordBrowserContext() {
  VekoBrowserContext* regular = regular_browser_context();
  return regular ? regular->GetOrCreateOffTheRecordContext() : nullptr;
}

void VekoContentBrowserClient::ReleaseOffTheRecordBrowserContext() {
  if (VekoBrowserContext* regular = regular_browser_context()) {
    regular->DestroyOffTheRecordContext();
  }
}

std::unique_ptr<content::BrowserMainParts>
VekoContentBrowserClient::CreateBrowserMainParts(bool /*is_integration_test*/) {
  auto parts = std::make_unique<VekoBrowserMainParts>();
  main_parts_ = parts.get();
  return parts;
}

std::string VekoContentBrowserClient::GetUserAgent() {
  return content::BuildUnifiedPlatformUserAgentFromProduct(
      "Chrome/156.0.8078.85 Mobile Veko/0.2.0");
}

std::vector<std::unique_ptr<blink::URLLoaderThrottle>>
VekoContentBrowserClient::CreateURLLoaderThrottles(
    const network::ResourceRequest& /*request*/,
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
  opts.record_stats = (browser_context == nullptr || !browser_context->IsOffTheRecord());
  throttles.push_back(
      std::make_unique<VekoPrivacyUrlLoaderThrottle>(std::move(top_frame_url), opts));
  return throttles;
}

bool VekoContentBrowserClient::ShouldDenyRequestOnCertificateError(
    const GURL /*main_frame_url*/) {
  return true;
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
