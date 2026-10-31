// Điểm vào ContentMainDelegate và ContentRendererClient cho bản dựng Chromium Content đầy đủ.
#ifndef VEKO_CHROMIUM_SRC_MAIN_DELEGATE_H_
#define VEKO_CHROMIUM_SRC_MAIN_DELEGATE_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <memory>

#include "content/public/app/content_main_delegate.h"
#include "content/public/renderer/content_renderer_client.h"
#include "veko_content_browser_client.h"

namespace veko::embedder {

class VekoContentRendererClient : public content::ContentRendererClient {
 public:
  VekoContentRendererClient() = default;
  ~VekoContentRendererClient() override = default;
};

class VekoMainDelegate : public content::ContentMainDelegate {
 public:
  VekoMainDelegate() = default;
  ~VekoMainDelegate() override = default;

 protected:
  content::ContentBrowserClient* CreateContentBrowserClient() override {
    browser_client_ = std::make_unique<VekoContentBrowserClient>();
    return browser_client_.get();
  }

  content::ContentRendererClient* CreateContentRendererClient() override {
    renderer_client_ = std::make_unique<VekoContentRendererClient>();
    return renderer_client_.get();
  }

 private:
  std::unique_ptr<VekoContentBrowserClient> browser_client_;
  std::unique_ptr<VekoContentRendererClient> renderer_client_;
};

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_MAIN_DELEGATE_H_
