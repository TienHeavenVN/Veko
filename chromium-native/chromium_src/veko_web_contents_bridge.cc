#include "veko_web_contents_bridge.h"

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <algorithm>
#include <utility>

#include "base/strings/utf_string_conversions.h"
#include "content/public/browser/navigation_controller.h"
#include "content/public/browser/navigation_entry.h"
#include "content/public/browser/navigation_handle.h"
#include "content/public/browser/reload_type.h"
#include "ui/base/page_transition_types.h"
#include "url/gurl.h"

namespace veko::embedder {

VekoWebContentsBridge::VekoWebContentsBridge(content::BrowserContext* browser_context,
                                             WebContentsEventCallbacks callbacks)
    : callbacks_(std::move(callbacks)) {
  content::WebContents::CreateParams params(browser_context);
  web_contents_ = content::WebContents::Create(params);
  web_contents_->SetDelegate(this);
  Observe(web_contents_.get());
}

VekoWebContentsBridge::~VekoWebContentsBridge() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  Observe(nullptr);
  if (web_contents_) {
    web_contents_->SetDelegate(nullptr);
    web_contents_.reset();
  }
}

bool VekoWebContentsBridge::LoadUrl(const std::string& utf8_url) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!web_contents_) return false;
  const GURL gurl(utf8_url);
  if (!gurl.is_valid()) return false;
  renderer_crashed_ = false;
  content::NavigationController::LoadURLParams load_params(gurl);
  load_params.transition_type = ui::PAGE_TRANSITION_TYPED;
  web_contents_->GetController().LoadURLWithParams(load_params);
  return true;
}

void VekoWebContentsBridge::Reload() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (web_contents_) {
    web_contents_->GetController().Reload(content::ReloadType::NORMAL,
                                          /*check_for_repost=*/true);
  }
}

void VekoWebContentsBridge::StopLoading() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (web_contents_) {
    web_contents_->Stop();
  }
}

bool VekoWebContentsBridge::CanGoBack() const {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return web_contents_ && web_contents_->GetController().CanGoBack();
}

bool VekoWebContentsBridge::CanGoForward() const {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  return web_contents_ && web_contents_->GetController().CanGoForward();
}

bool VekoWebContentsBridge::GoBack() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!CanGoBack()) return false;
  web_contents_->GetController().GoBack();
  return true;
}

bool VekoWebContentsBridge::GoForward() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!CanGoForward()) return false;
  web_contents_->GetController().GoForward();
  return true;
}

std::string VekoWebContentsBridge::GetCurrentUrl() const {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!web_contents_) return "";
  const GURL& visible = web_contents_->GetVisibleURL();
  if (!visible.is_empty()) return visible.spec();
  return web_contents_->GetLastCommittedURL().spec();
}

std::string VekoWebContentsBridge::GetTitleUtf8() const {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!web_contents_) return "";
  return base::UTF16ToUTF8(web_contents_->GetTitle());
}

void VekoWebContentsBridge::LoadingStateChanged(content::WebContents* /*source*/,
                                                bool should_show_loading_ui) {
  if (callbacks_.on_loading_changed) {
    callbacks_.on_loading_changed(should_show_loading_ui);
  }
}

void VekoWebContentsBridge::CloseContents(content::WebContents* /*source*/) {}

bool VekoWebContentsBridge::CanEnterFullscreenModeForTab(
    content::RenderFrameHost* /*requesting_frame*/) {
  return true;
}

void VekoWebContentsBridge::DidStartLoading() {
  if (callbacks_.on_loading_changed) {
    callbacks_.on_loading_changed(true);
  }
}

void VekoWebContentsBridge::DidStopLoading() {
  last_progress_percent_ = 100;
  if (callbacks_.on_loading_changed) {
    callbacks_.on_loading_changed(false);
  }
}

void VekoWebContentsBridge::LoadProgressChanged(double progress) {
  last_progress_percent_ =
      std::clamp(static_cast<int32_t>(progress * 100.0), 0, 100);
  if (callbacks_.on_progress_changed) {
    callbacks_.on_progress_changed(last_progress_percent_);
  }
}

void VekoWebContentsBridge::DidFinishNavigation(
    content::NavigationHandle* navigation_handle) {
  if (!navigation_handle || !navigation_handle->IsInPrimaryMainFrame()) {
    return;
  }
  if (navigation_handle->HasCommitted() && callbacks_.on_url_changed) {
    callbacks_.on_url_changed(navigation_handle->GetURL().spec());
  }
  if (navigation_handle->IsErrorPage() && callbacks_.on_load_error) {
    callbacks_.on_load_error(static_cast<int32_t>(navigation_handle->GetNetErrorCode()),
                             navigation_handle->GetURL().spec());
  }
}

void VekoWebContentsBridge::DidFailLoad(
    content::RenderFrameHost* /*render_frame_host*/,
    const GURL& validated_url,
    int error_code) {
  if (callbacks_.on_load_error) {
    callbacks_.on_load_error(error_code, validated_url.spec());
  }
}

void VekoWebContentsBridge::TitleWasSet(content::NavigationEntry* entry) {
  if (entry && callbacks_.on_title_changed) {
    callbacks_.on_title_changed(base::UTF16ToUTF8(entry->GetTitle()));
  }
}

void VekoWebContentsBridge::PrimaryMainFrameRenderProcessGone(
    base::TerminationStatus status) {
  renderer_crashed_ = (status != base::TERMINATION_STATUS_NORMAL_TERMINATION &&
                       status != base::TERMINATION_STATUS_STILL_RUNNING);
  if (callbacks_.on_renderer_gone) {
    callbacks_.on_renderer_gone(renderer_crashed_);
  }
}

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
