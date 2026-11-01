#include "veko_browser_session.h"

#include <algorithm>
#include <utility>

#include "veko_native_context.h"

#if defined(__ANDROID__)
#include <android/native_window.h>
#include <android/native_window_jni.h>
#endif

namespace veko::chromium {

#if defined(__ANDROID__)
void VekoNativeBrowserRuntime::ANativeWindowDeleter::operator()(
    ANativeWindow* window) const noexcept {
  if (window != nullptr) {
    ANativeWindow_release(window);
  }
}
#endif

VekoNativeBrowserRuntime& VekoNativeBrowserRuntime::Instance() {
  static VekoNativeBrowserRuntime instance;
  return instance;
}

NativeRuntimeError VekoNativeBrowserRuntime::VerifyReadyAndThreadLocked() const {
  if (!initialized_) {
    return NativeRuntimeError::kErrNotInitialized;
  }
  if (enforce_thread_affinity_ && std::this_thread::get_id() != owner_thread_id_) {
    return NativeRuntimeError::kErrWrongThread;
  }
  return NativeRuntimeError::kOk;
}

VekoNativeBrowserRuntime::BrowserInstance*
VekoNativeBrowserRuntime::FindBrowserLocked(int64_t browser_handle) {
  auto it = browsers_.find(browser_handle);
  return (it != browsers_.end()) ? it->second.get() : nullptr;
}

const VekoNativeBrowserRuntime::BrowserInstance*
VekoNativeBrowserRuntime::FindBrowserLocked(int64_t browser_handle) const {
  auto it = browsers_.find(browser_handle);
  return (it != browsers_.end()) ? it->second.get() : nullptr;
}

NativeRuntimeStatusSnapshot VekoNativeBrowserRuntime::Initialize(
    std::string app_data_dir,
    bool enforce_thread_affinity) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (app_data_dir.empty()) {
    NativeRuntimeStatusSnapshot bad;
    bad.error = NativeRuntimeError::kErrInvalidArgument;
    bad.initialized = initialized_;
    bad.upstream_content_linked = IsUpstreamContentLinked();
    bad.thread_affinity_enforced = enforce_thread_affinity_;
    bad.active_browsers = static_cast<int32_t>(browsers_.size());
    bad.data_dir = app_data_dir_;
    return bad;
  }

  if (initialized_) {
    if (enforce_thread_affinity_ && std::this_thread::get_id() != owner_thread_id_) {
      NativeRuntimeStatusSnapshot wrong_thread;
      wrong_thread.error = NativeRuntimeError::kErrWrongThread;
      wrong_thread.initialized = true;
      wrong_thread.upstream_content_linked = IsUpstreamContentLinked();
      wrong_thread.thread_affinity_enforced = enforce_thread_affinity_;
      wrong_thread.active_browsers = static_cast<int32_t>(browsers_.size());
      wrong_thread.data_dir = app_data_dir_;
      return wrong_thread;
    }
  } else {
    initialized_ = true;
    owner_thread_id_ = std::this_thread::get_id();
    enforce_thread_affinity_ = enforce_thread_affinity;
    app_data_dir_ = std::move(app_data_dir);
  }

  NativeRuntimeStatusSnapshot snap;
  snap.error = NativeRuntimeError::kOk;
  snap.initialized = initialized_;
  snap.upstream_content_linked = IsUpstreamContentLinked();
  snap.thread_affinity_enforced = enforce_thread_affinity_;
  snap.active_browsers = static_cast<int32_t>(browsers_.size());
  snap.data_dir = app_data_dir_;
  return snap;
}

bool VekoNativeBrowserRuntime::IsInitialized() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return initialized_;
}

NativeRuntimeStatusSnapshot VekoNativeBrowserRuntime::GetStatus() const {
  std::lock_guard<std::mutex> lock(mutex_);
  NativeRuntimeStatusSnapshot snap;
  snap.error = initialized_ ? NativeRuntimeError::kOk : NativeRuntimeError::kErrNotInitialized;
  snap.initialized = initialized_;
  snap.upstream_content_linked = IsUpstreamContentLinked();
  snap.thread_affinity_enforced = enforce_thread_affinity_;
  snap.active_browsers = static_cast<int32_t>(browsers_.size());
  snap.data_dir = app_data_dir_;
  return snap;
}

int64_t VekoNativeBrowserRuntime::CreateBrowser(int64_t context_handle,
                                                bool desktop_mode) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) {
    return static_cast<int64_t>(check);
  }

  auto& ctx_registry = NativeBrowserContextRegistry::Instance();
  if (!ctx_registry.IsContextAlive(context_handle)) {
    return static_cast<int64_t>(NativeRuntimeError::kErrInvalidContext);
  }

  const int64_t handle = next_browser_handle_++;
  auto instance = std::make_unique<BrowserInstance>();
  instance->browser_handle = handle;
  instance->context_handle = context_handle;
  instance->off_the_record = ctx_registry.IsOffTheRecord(context_handle);
  instance->desktop_mode = desktop_mode;
  browsers_.emplace(handle, std::move(instance));
  return handle;
}

NativeLoadUrlOutcome VekoNativeBrowserRuntime::LoadUrl(int64_t browser_handle,
                                                       std::string raw_url) {
  std::lock_guard<std::mutex> lock(mutex_);
  NativeLoadUrlOutcome outcome;
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) {
    outcome.error = check;
    return outcome;
  }

  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) {
    outcome.error = NativeRuntimeError::kErrInvalidBrowserHandle;
    return outcome;
  }

  if (raw_url.empty()) {
    outcome.error = NativeRuntimeError::kErrInvalidArgument;
    return outcome;
  }

  const std::string initiator =
      (browser->history_index >= 0 &&
       static_cast<size_t>(browser->history_index) < browser->history.size())
          ? browser->history[static_cast<size_t>(browser->history_index)].url
          : std::string();

  RequestEvaluationOptions eval_opts;
  eval_opts.https_upgrade_enabled = true;
  eval_opts.strip_tracking_params = true;
  eval_opts.adblock_enabled = true;
  eval_opts.record_stats = !browser->off_the_record;

  const RequestDecision decision = VekoUrlRequestPipeline::Instance().EvaluateRequest(
      raw_url, initiator, "document", eval_opts);
  outcome.pipeline_action = decision.action;
  outcome.matched_rule_reason = decision.matched_rule_reason;

  if (decision.action == PipelineAction::kBlock ||
      decision.action == PipelineAction::kRejectUnsafe) {
    outcome.error = NativeRuntimeError::kErrBlockedByPolicy;
    outcome.final_url.clear();
    browser->is_loading = false;
    return outcome;
  }

  const std::string resolved_url =
      decision.mutated_url.empty() ? raw_url : decision.mutated_url;

  // Cắt bỏ nhánh forward cũ nếu người dùng điều hướng sang URL mới từ giữa lịch sử
  if (browser->history_index >= 0 &&
      static_cast<size_t>(browser->history_index + 1) < browser->history.size()) {
    browser->history.erase(
        browser->history.begin() + browser->history_index + 1,
        browser->history.end());
  }

  NavigationEntryRecord entry;
  entry.url = resolved_url;
  entry.title = resolved_url;
  browser->history.push_back(std::move(entry));
  browser->history_index = static_cast<int32_t>(browser->history.size()) - 1;
  browser->is_loading = IsUpstreamContentLinked();
  browser->load_progress = IsUpstreamContentLinked() ? 15 : 100;

  outcome.error = NativeRuntimeError::kOk;
  outcome.final_url = resolved_url;
  outcome.upstream_content_rendered = IsUpstreamContentLinked();
  return outcome;
}

NativeRuntimeError VekoNativeBrowserRuntime::Reload(int64_t browser_handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) return NativeRuntimeError::kErrInvalidBrowserHandle;
  if (browser->history_index < 0) return NativeRuntimeError::kErrInvalidArgument;
  browser->is_loading = IsUpstreamContentLinked();
  browser->load_progress = IsUpstreamContentLinked() ? 10 : 100;
  return NativeRuntimeError::kOk;
}

NativeRuntimeError VekoNativeBrowserRuntime::StopLoading(int64_t browser_handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) return NativeRuntimeError::kErrInvalidBrowserHandle;
  browser->is_loading = false;
  return NativeRuntimeError::kOk;
}

bool VekoNativeBrowserRuntime::CanGoBack(int64_t browser_handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) return false;
  const BrowserInstance* browser = FindBrowserLocked(browser_handle);
  return browser != nullptr && browser->history_index > 0;
}

bool VekoNativeBrowserRuntime::CanGoForward(int64_t browser_handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) return false;
  const BrowserInstance* browser = FindBrowserLocked(browser_handle);
  return browser != nullptr &&
         browser->history_index >= 0 &&
         static_cast<size_t>(browser->history_index + 1) < browser->history.size();
}

bool VekoNativeBrowserRuntime::GoBack(int64_t browser_handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (VerifyReadyAndThreadLocked() != NativeRuntimeError::kOk) return false;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr || browser->history_index <= 0) return false;
  --browser->history_index;
  browser->load_progress = 100;
  return true;
}

bool VekoNativeBrowserRuntime::GoForward(int64_t browser_handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (VerifyReadyAndThreadLocked() != NativeRuntimeError::kOk) return false;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr ||
      browser->history_index < 0 ||
      static_cast<size_t>(browser->history_index + 1) >= browser->history.size()) {
    return false;
  }
  ++browser->history_index;
  browser->load_progress = 100;
  return true;
}

std::string VekoNativeBrowserRuntime::GetCurrentUrl(int64_t browser_handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) return "";
  const BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr || browser->history_index < 0 ||
      static_cast<size_t>(browser->history_index) >= browser->history.size()) {
    return "";
  }
  return browser->history[static_cast<size_t>(browser->history_index)].url;
}

std::string VekoNativeBrowserRuntime::GetPageTitle(int64_t browser_handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) return "";
  const BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr || browser->history_index < 0 ||
      static_cast<size_t>(browser->history_index) >= browser->history.size()) {
    return "";
  }
  return browser->history[static_cast<size_t>(browser->history_index)].title;
}

int32_t VekoNativeBrowserRuntime::GetLoadProgress(int64_t browser_handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) return 0;
  const BrowserInstance* browser = FindBrowserLocked(browser_handle);
  return browser != nullptr ? browser->load_progress : 0;
}

bool VekoNativeBrowserRuntime::IsDesktopMode(int64_t browser_handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) return false;
  const BrowserInstance* browser = FindBrowserLocked(browser_handle);
  return browser != nullptr && browser->desktop_mode;
}

NativeRuntimeError VekoNativeBrowserRuntime::SetDesktopMode(int64_t browser_handle,
                                                            bool desktop_mode) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) return NativeRuntimeError::kErrInvalidBrowserHandle;
  browser->desktop_mode = desktop_mode;
  return NativeRuntimeError::kOk;
}

NativeRuntimeError VekoNativeBrowserRuntime::UpdatePageMetadata(
    int64_t browser_handle,
    std::string committed_url,
    std::string page_title,
    int32_t load_progress) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) return NativeRuntimeError::kErrInvalidBrowserHandle;

  browser->load_progress = std::clamp(load_progress, 0, 100);
  browser->is_loading = (browser->load_progress < 100);
  if (browser->history_index >= 0 &&
      static_cast<size_t>(browser->history_index) < browser->history.size()) {
    auto& active = browser->history[static_cast<size_t>(browser->history_index)];
    if (!committed_url.empty()) active.url = std::move(committed_url);
    if (!page_title.empty()) active.title = std::move(page_title);
  }
  return NativeRuntimeError::kOk;
}

NativeRuntimeError VekoNativeBrowserRuntime::AttachSurface(JNIEnv* env,
                                                           int64_t browser_handle,
                                                           jobject jsurface,
                                                           int32_t width_px,
                                                           int32_t height_px,
                                                           int32_t density_dpi) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) return NativeRuntimeError::kErrInvalidBrowserHandle;
  if (density_dpi <= 0) return NativeRuntimeError::kErrInvalidArgument;

#if defined(__ANDROID__)
  if (env == nullptr || jsurface == nullptr) {
    return NativeRuntimeError::kErrInvalidArgument;
  }
  ANativeWindow* acquired = ANativeWindow_fromSurface(env, jsurface);
  if (acquired == nullptr) {
    return NativeRuntimeError::kErrSurfaceAcquireFailed;
  }
  const int32_t resolved_w =
      width_px > 0 ? width_px : ANativeWindow_getWidth(acquired);
  const int32_t resolved_h =
      height_px > 0 ? height_px : ANativeWindow_getHeight(acquired);
  if (resolved_w <= 0 || resolved_h <= 0) {
    ANativeWindow_release(acquired);
    return NativeRuntimeError::kErrInvalidArgument;
  }
  browser->native_window.reset(acquired);
  browser->surface.width_px = resolved_w;
  browser->surface.height_px = resolved_h;
#else
  (void)env;
  (void)jsurface;
  if (width_px <= 0 || height_px <= 0) {
    return NativeRuntimeError::kErrInvalidArgument;
  }
  browser->surface.width_px = width_px;
  browser->surface.height_px = height_px;
#endif

  browser->surface.attached = true;
  browser->surface.density_dpi = density_dpi;
  ++browser->surface.attach_generation;
  return NativeRuntimeError::kOk;
}

NativeRuntimeError VekoNativeBrowserRuntime::ResizeSurface(int64_t browser_handle,
                                                           int32_t width_px,
                                                           int32_t height_px) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) return NativeRuntimeError::kErrInvalidBrowserHandle;
  if (!browser->surface.attached || width_px <= 0 || height_px <= 0) {
    return NativeRuntimeError::kErrInvalidArgument;
  }
  browser->surface.width_px = width_px;
  browser->surface.height_px = height_px;
  return NativeRuntimeError::kOk;
}

NativeRuntimeError VekoNativeBrowserRuntime::DetachSurface(int64_t browser_handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  BrowserInstance* browser = FindBrowserLocked(browser_handle);
  if (browser == nullptr) return NativeRuntimeError::kErrInvalidBrowserHandle;
#if defined(__ANDROID__)
  browser->native_window.reset();
#endif
  browser->surface.attached = false;
  browser->surface.width_px = 0;
  browser->surface.height_px = 0;
  return NativeRuntimeError::kOk;
}

NativeSurfaceState VekoNativeBrowserRuntime::GetSurfaceState(
    int64_t browser_handle) const {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) return NativeSurfaceState{};
  const BrowserInstance* browser = FindBrowserLocked(browser_handle);
  return browser != nullptr ? browser->surface : NativeSurfaceState{};
}

NativeRuntimeError VekoNativeBrowserRuntime::DestroyBrowser(int64_t browser_handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  const NativeRuntimeError check = VerifyReadyAndThreadLocked();
  if (check != NativeRuntimeError::kOk) return check;
  auto it = browsers_.find(browser_handle);
  if (it == browsers_.end()) {
    return NativeRuntimeError::kErrInvalidBrowserHandle;
  }
  browsers_.erase(it);
  return NativeRuntimeError::kOk;
}

size_t VekoNativeBrowserRuntime::DestroyBrowsersForContext(int64_t context_handle) {
  std::lock_guard<std::mutex> lock(mutex_);
  size_t removed = 0;
  for (auto it = browsers_.begin(); it != browsers_.end();) {
    if (it->second->context_handle == context_handle) {
      it = browsers_.erase(it);
      ++removed;
    } else {
      ++it;
    }
  }
  return removed;
}

int32_t VekoNativeBrowserRuntime::ActiveBrowserCount() const {
  std::lock_guard<std::mutex> lock(mutex_);
  return static_cast<int32_t>(browsers_.size());
}

NativeRuntimeError VekoNativeBrowserRuntime::Shutdown() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!initialized_) {
    return NativeRuntimeError::kErrAlreadyShutdown;
  }
  if (enforce_thread_affinity_ && std::this_thread::get_id() != owner_thread_id_) {
    return NativeRuntimeError::kErrWrongThread;
  }
  browsers_.clear();
  app_data_dir_.clear();
  initialized_ = false;
  owner_thread_id_ = std::thread::id{};
  return NativeRuntimeError::kOk;
}

void VekoNativeBrowserRuntime::ResetForTesting() {
  std::lock_guard<std::mutex> lock(mutex_);
  browsers_.clear();
  app_data_dir_.clear();
  initialized_ = false;
  enforce_thread_affinity_ = true;
  owner_thread_id_ = std::thread::id{};
}

}  // namespace veko::chromium
