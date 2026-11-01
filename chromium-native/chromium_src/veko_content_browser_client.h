// Triển khai VekoBrowserContext (đầy đủ 14 pure virtual overrides của content::BrowserContext 156.0.8078.85)
// và VekoContentBrowserClient (gắn URLLoaderThrottle + khóa cứng chứng chỉ TLS lỗi).
#ifndef VEKO_CHROMIUM_SRC_CONTENT_BROWSER_CLIENT_H_
#define VEKO_CHROMIUM_SRC_CONTENT_BROWSER_CLIENT_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <cstdint>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "base/files/file_path.h"
#include "base/functional/callback.h"
#include "base/memory/raw_ptr.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/certificate_request_result_type.h"
#include "content/public/browser/content_browser_client.h"

class SimpleFactoryKey;

namespace veko::embedder {

class VekoBrowserMainParts;

class VekoBrowserContext : public content::BrowserContext {
 public:
  VekoBrowserContext(const base::FilePath& disk_profile_path, bool is_off_the_record);
  VekoBrowserContext(const VekoBrowserContext&) = delete;
  VekoBrowserContext& operator=(const VekoBrowserContext&) = delete;
  ~VekoBrowserContext() override;

  // Đầy đủ các phương thức thuần ảo (pure virtual) của content::BrowserContext tại 156.0.8078.85:
  std::unique_ptr<content::ZoomLevelDelegate> CreateZoomLevelDelegate(
      const base::FilePath& partition_path) override;
  base::FilePath GetPath() const override;
  bool IsOffTheRecord() override;
  content::DownloadManagerDelegate* GetDownloadManagerDelegate() override;
  content::BrowserPluginGuestManager* GetGuestManager() override;
  storage::SpecialStoragePolicy* GetSpecialStoragePolicy() override;
  content::PlatformNotificationService* GetPlatformNotificationService() override;
  content::PushMessagingService* GetPushMessagingService() override;
  content::StorageNotificationService* GetStorageNotificationService() override;
  content::SSLHostStateDelegate* GetSSLHostStateDelegate() override;
  content::PermissionControllerDelegate* GetPermissionControllerDelegate() override;
  content::ReduceAcceptLanguageControllerDelegate*
  GetReduceAcceptLanguageControllerDelegate() override;
  content::ClientHintsControllerDelegate* GetClientHintsControllerDelegate() override;
  content::BackgroundFetchDelegate* GetBackgroundFetchDelegate() override;
  content::BackgroundSyncController* GetBackgroundSyncController() override;
  content::BrowsingDataRemoverDelegate* GetBrowsingDataRemoverDelegate() override;

  VekoBrowserContext* GetOrCreateOffTheRecordContext();
  void DestroyOffTheRecordContext();

 private:
  const base::FilePath disk_profile_path_;
  const bool is_off_the_record_;
  std::unique_ptr<SimpleFactoryKey> key_;
  std::unique_ptr<VekoBrowserContext> otr_context_;
};

class VekoContentBrowserClient : public content::ContentBrowserClient {
 public:
  VekoContentBrowserClient();
  VekoContentBrowserClient(const VekoContentBrowserClient&) = delete;
  VekoContentBrowserClient& operator=(const VekoContentBrowserClient&) = delete;
  ~VekoContentBrowserClient() override;

  static VekoContentBrowserClient* Get();

  VekoBrowserContext* regular_browser_context() const;
  VekoBrowserContext* AcquireOffTheRecordBrowserContext();
  void ReleaseOffTheRecordBrowserContext();

  // content::ContentBrowserClient overrides:
  std::unique_ptr<content::BrowserMainParts> CreateBrowserMainParts(
      bool is_integration_test) override;

  std::string GetUserAgent() override;

  std::vector<std::unique_ptr<blink::URLLoaderThrottle>> CreateURLLoaderThrottles(
      const network::ResourceRequest& request,
      content::BrowserContext* browser_context,
      const base::RepeatingCallback<content::WebContents*()>& wc_getter,
      content::NavigationUIData* navigation_ui_data,
      content::FrameTreeNodeId frame_tree_node_id,
      std::optional<int64_t> navigation_id) override;

  bool ShouldDenyRequestOnCertificateError(const GURL main_frame_url) override;

  void AllowCertificateError(
      content::WebContents* web_contents,
      int cert_error,
      const net::SSLInfo& ssl_info,
      const GURL& request_url,
      bool is_primary_main_frame_request,
      bool strict_enforcement,
      base::OnceCallback<void(content::CertificateRequestResultType)> callback) override;

 private:
  raw_ptr<VekoBrowserMainParts> main_parts_ = nullptr;
};

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_CONTENT_BROWSER_CLIENT_H_
