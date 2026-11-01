// Quản lý vòng đời WebContents, NavigationController, WebContentsDelegate và WebContentsObserver (156.0.8078.85).
#ifndef VEKO_CHROMIUM_SRC_WEB_CONTENTS_BRIDGE_H_
#define VEKO_CHROMIUM_SRC_WEB_CONTENTS_BRIDGE_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

#include "base/sequence_checker.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_contents_delegate.h"
#include "content/public/browser/web_contents_observer.h"

namespace content {
class BrowserContext;
class NavigationEntry;
class NavigationHandle;
class RenderFrameHost;
}  // namespace content

namespace veko::embedder {

struct WebContentsEventCallbacks {
  std::function<void(const std::string& url)> on_url_changed;
  std::function<void(const std::string& title)> on_title_changed;
  std::function<void(int32_t progress_percent)> on_progress_changed;
  std::function<void(bool is_loading)> on_loading_changed;
  std::function<void(int32_t net_error, const std::string& failing_url)> on_load_error;
  std::function<void(bool renderer_crashed)> on_renderer_gone;
};

class VekoWebContentsBridge : public content::WebContentsDelegate,
                              public content::WebContentsObserver {
 public:
  VekoWebContentsBridge(content::BrowserContext* browser_context,
                        WebContentsEventCallbacks callbacks);
  VekoWebContentsBridge(const VekoWebContentsBridge&) = delete;
  VekoWebContentsBridge& operator=(const VekoWebContentsBridge&) = delete;
  ~VekoWebContentsBridge() override;

  content::WebContents* web_contents() const { return web_contents_.get(); }

  bool LoadUrl(const std::string& utf8_url);
  void Reload();
  void StopLoading();
  bool CanGoBack() const;
  bool CanGoForward() const;
  bool GoBack();
  bool GoForward();
  std::string GetCurrentUrl() const;
  std::string GetTitleUtf8() const;
  int32_t last_progress_percent() const { return last_progress_percent_; }
  bool renderer_crashed() const { return renderer_crashed_; }

  // content::WebContentsDelegate:
  void LoadingStateChanged(content::WebContents* source,
                           bool should_show_loading_ui) override;
  void CloseContents(content::WebContents* source) override;
  bool CanEnterFullscreenModeForTab(content::RenderFrameHost* requesting_frame) override;

  // content::WebContentsObserver:
  void DidStartLoading() override;
  void DidStopLoading() override;
  void LoadProgressChanged(double progress) override;
  void DidFinishNavigation(content::NavigationHandle* navigation_handle) override;
  void DidFailLoad(content::RenderFrameHost* render_frame_host,
                   const GURL& validated_url,
                   int error_code) override;
  void TitleWasSet(content::NavigationEntry* entry) override;
  void PrimaryMainFrameRenderProcessGone(base::TerminationStatus status) override;

 private:
  SEQUENCE_CHECKER(sequence_checker_);
  WebContentsEventCallbacks callbacks_;
  std::unique_ptr<content::WebContents> web_contents_;
  int32_t last_progress_percent_ = 0;
  bool renderer_crashed_ = false;
};

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_WEB_CONTENTS_BRIDGE_H_
