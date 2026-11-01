#ifndef VEKO_CHROMIUM_NATIVE_BROWSER_SESSION_H_
#define VEKO_CHROMIUM_NATIVE_BROWSER_SESSION_H_

#include <jni.h>

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "veko_request_pipeline.h"

#if defined(__ANDROID__)
struct ANativeWindow;
#endif

namespace veko::chromium {

enum class NativeRuntimeError : int32_t {
  kOk = 0,
  kErrNotInitialized = -1,
  kErrAlreadyShutdown = -2,
  kErrWrongThread = -3,
  kErrInvalidContext = -4,
  kErrInvalidBrowserHandle = -5,
  kErrInvalidArgument = -6,
  kErrBlockedByPolicy = -7,
  kErrSurfaceAcquireFailed = -8,
  kErrNativeException = -9,
};

struct NativeRuntimeStatusSnapshot {
  NativeRuntimeError error = NativeRuntimeError::kOk;
  bool initialized = false;
  bool upstream_content_linked = false;
  bool thread_affinity_enforced = true;
  int32_t active_browsers = 0;
  std::string data_dir;
};

struct NativeLoadUrlOutcome {
  NativeRuntimeError error = NativeRuntimeError::kOk;
  PipelineAction pipeline_action = PipelineAction::kAllow;
  std::string final_url;
  std::string matched_rule_reason;
  bool upstream_content_rendered = false;
};

struct NativeSurfaceState {
  bool attached = false;
  int32_t width_px = 0;
  int32_t height_px = 0;
  int32_t density_dpi = 0;
  uint64_t attach_generation = 0;
};

class VekoNativeBrowserRuntime {
 public:
  static VekoNativeBrowserRuntime& Instance();

  static constexpr bool IsUpstreamContentLinked() noexcept {
#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
    return true;
#else
    return false;
#endif
  }

  NativeRuntimeStatusSnapshot Initialize(std::string app_data_dir,
                                         bool enforce_thread_affinity = true);
  bool IsInitialized() const;
  NativeRuntimeStatusSnapshot GetStatus() const;

  // Tạo phiên trình duyệt gắn với BrowserContext hợp lệ; trả về > 0 nếu thành công, hoặc mã lỗi âm NativeRuntimeError.
  int64_t CreateBrowser(int64_t context_handle, bool desktop_mode = false);

  NativeLoadUrlOutcome LoadUrl(int64_t browser_handle, std::string raw_url);
  NativeRuntimeError Reload(int64_t browser_handle);
  NativeRuntimeError StopLoading(int64_t browser_handle);
  bool CanGoBack(int64_t browser_handle) const;
  bool CanGoForward(int64_t browser_handle) const;
  bool GoBack(int64_t browser_handle);
  bool GoForward(int64_t browser_handle);

  std::string GetCurrentUrl(int64_t browser_handle) const;
  std::string GetPageTitle(int64_t browser_handle) const;
  int32_t GetLoadProgress(int64_t browser_handle) const;
  bool IsDesktopMode(int64_t browser_handle) const;
  NativeRuntimeError SetDesktopMode(int64_t browser_handle, bool desktop_mode);
  NativeRuntimeError UpdatePageMetadata(int64_t browser_handle,
                                        std::string committed_url,
                                        std::string page_title,
                                        int32_t load_progress);

  // Quản lý kết nối Android Surface / ANativeWindow (RAII, không rò rỉ tham chiếu native window).
  NativeRuntimeError AttachSurface(JNIEnv* env,
                                   int64_t browser_handle,
                                   jobject jsurface,
                                   int32_t width_px,
                                   int32_t height_px,
                                   int32_t density_dpi);
  NativeRuntimeError ResizeSurface(int64_t browser_handle,
                                   int32_t width_px,
                                   int32_t height_px);
  NativeRuntimeError DetachSurface(int64_t browser_handle);
  NativeSurfaceState GetSurfaceState(int64_t browser_handle) const;

  NativeRuntimeError DestroyBrowser(int64_t browser_handle);
  size_t DestroyBrowsersForContext(int64_t context_handle);
  int32_t ActiveBrowserCount() const;

  // Giải phóng toàn bộ phiên, ANativeWindow và đặt lại trạng thái runtime an toàn.
  NativeRuntimeError Shutdown();

  // Dùng cho kiểm thử C++ host nhằm kiểm chứng luồng gọi sai thread mà không cần JVM.
  void ResetForTesting();

 private:
  VekoNativeBrowserRuntime() = default;

#if defined(__ANDROID__)
  struct ANativeWindowDeleter {
    void operator()(ANativeWindow* window) const noexcept;
  };
  using ScopedNativeWindow = std::unique_ptr<ANativeWindow, ANativeWindowDeleter>;
#endif

  struct NavigationEntryRecord {
    std::string url;
    std::string title;
  };

  struct BrowserInstance {
    int64_t browser_handle = 0;
    int64_t context_handle = 0;
    bool off_the_record = false;
    bool desktop_mode = false;
    bool is_loading = false;
    int32_t load_progress = 0;
    std::vector<NavigationEntryRecord> history;
    int32_t history_index = -1;
    NativeSurfaceState surface;
#if defined(__ANDROID__)
    ScopedNativeWindow native_window;
#endif
  };

  NativeRuntimeError VerifyReadyAndThreadLocked() const;
  BrowserInstance* FindBrowserLocked(int64_t browser_handle);
  const BrowserInstance* FindBrowserLocked(int64_t browser_handle) const;

  mutable std::mutex mutex_;
  bool initialized_ = false;
  bool enforce_thread_affinity_ = true;
  std::thread::id owner_thread_id_{};
  std::string app_data_dir_;
  int64_t next_browser_handle_ = 2001;
  std::map<int64_t, std::unique_ptr<BrowserInstance>> browsers_;
};

}  // namespace veko::chromium

#endif  // VEKO_CHROMIUM_NATIVE_BROWSER_SESSION_H_
