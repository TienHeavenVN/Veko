// Triển khai ContentBrowserClient và Off-The-Record BrowserContext chuẩn Chromium Content 156.0.8078.85.
#ifndef VEKO_CHROMIUM_SRC_CONTENT_BROWSER_CLIENT_H_
#define VEKO_CHROMIUM_SRC_CONTENT_BROWSER_CLIENT_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <memory>
#include <vector>

#include "base/files/file_path.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/certificate_request_result_type.h"
#include "content/public/browser/content_browser_client.h"

namespace veko::embedder {

class VekoBrowserContext : public content::BrowserContext {
 public:
  VekoBrowserContext(const base::FilePath& disk_profile_path, bool is_off_the_record);
  ~VekoBrowserContext() override;

  // content::BrowserContext:
  base::FilePath GetPath() const override;
  bool IsOffTheRecord() const override;

  VekoBrowserContext* GetOrCreateOffTheRecordContext();
  void DestroyOffTheRecordContext();

 private:
  const base::FilePath disk_profile_path_;
  const bool is_off_the_record_;
  std::unique_ptr<VekoBrowserContext> otr_context_;
};

class VekoContentBrowserClient : public content::ContentBrowserClient {
 public:
  VekoContentBrowserClient();
  ~VekoContentBrowserClient() override;

  // Gắn VekoPrivacyUrlLoaderThrottle vào mọi điều hướng trình duyệt và keepalive fetch
  std::vector<std::unique_ptr<blink::URLLoaderThrottle>> CreateURLLoaderThrottles(
      const network::ResourceRequest& request,
      content::BrowserContext* browser_context,
      const base::RepeatingCallback<content::WebContents*()>& wc_getter,
      content::NavigationUIData* navigation_ui_data,
      content::FrameTreeNodeId frame_tree_node_id,
      std::optional<int64_t> navigation_id) override;

  // Chặn tuyệt đối mọi chứng chỉ TLS không hợp lệ (không hiển thị nút bỏ qua cho người dùng)
  void AllowCertificateError(
      content::WebContents* web_contents,
      int cert_error,
      const net::SSLInfo& ssl_info,
      const GURL& request_url,
      bool is_primary_main_frame_request,
      bool strict_enforcement,
      base::OnceCallback<void(content::CertificateRequestResultType)> callback) override;
};

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_CONTENT_BROWSER_CLIENT_H_
