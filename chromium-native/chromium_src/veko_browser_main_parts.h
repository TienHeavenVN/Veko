// Quản lý vòng đời tiến trình trình duyệt (BrowserMainParts) chuẩn Chromium Content 156.0.8078.85.
#ifndef VEKO_CHROMIUM_SRC_BROWSER_MAIN_PARTS_H_
#define VEKO_CHROMIUM_SRC_BROWSER_MAIN_PARTS_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <memory>

#include "build/build_config.h"
#include "content/public/browser/browser_main_parts.h"

namespace crash_reporter {
class ChildExitObserver;
}

namespace veko::embedder {

class VekoBrowserContext;

class VekoBrowserMainParts : public content::BrowserMainParts {
 public:
  VekoBrowserMainParts();
  VekoBrowserMainParts(const VekoBrowserMainParts&) = delete;
  VekoBrowserMainParts& operator=(const VekoBrowserMainParts&) = delete;
  ~VekoBrowserMainParts() override;

  // content::BrowserMainParts overrides:
  int PreEarlyInitialization() override;
  int PreCreateThreads() override;
  int PreMainMessageLoopRun() override;
  void PostMainMessageLoopRun() override;

  VekoBrowserContext* regular_browser_context() const {
    return regular_browser_context_.get();
  }

 private:
  std::unique_ptr<VekoBrowserContext> regular_browser_context_;
#if BUILDFLAG(IS_ANDROID)
  std::unique_ptr<crash_reporter::ChildExitObserver> child_exit_observer_;
#endif
};

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_BROWSER_MAIN_PARTS_H_
