// Điểm vào ContentMainDelegate, ContentClient và ContentRendererClient chuẩn Chromium Content 156.0.8078.85 (0c5c70590a80).
#ifndef VEKO_CHROMIUM_SRC_MAIN_DELEGATE_H_
#define VEKO_CHROMIUM_SRC_MAIN_DELEGATE_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <jni.h>

#include <memory>
#include <optional>
#include <string>

#include "build/build_config.h"
#include "content/public/app/content_main_delegate.h"
#include "content/public/common/content_client.h"
#include "content/public/renderer/content_renderer_client.h"
#include "veko_content_browser_client.h"

namespace veko::embedder {

inline constexpr int kVekoContentPakDescriptor = 1000;

class VekoContentClient : public content::ContentClient {
 public:
  VekoContentClient() = default;
  ~VekoContentClient() override = default;

  std::u16string GetLocalizedString(int message_id) override;
  base::StringPiece GetDataResource(
      int resource_id,
      ui::ResourceScaleFactor scale_factor) override;
  base::RefCountedMemory* GetDataResourceBytes(int resource_id) override;
};

class VekoContentRendererClient : public content::ContentRendererClient {
 public:
  VekoContentRendererClient() = default;
  ~VekoContentRendererClient() override = default;
};

class VekoMainDelegate : public content::ContentMainDelegate {
 public:
  VekoMainDelegate();
  VekoMainDelegate(const VekoMainDelegate&) = delete;
  VekoMainDelegate& operator=(const VekoMainDelegate&) = delete;
  ~VekoMainDelegate() override;

  // content::ContentMainDelegate:
  std::optional<int> BasicStartupComplete() override;
  void PreSandboxStartup() override;
  std::optional<int> PreBrowserMain() override;
  content::ContentClient* CreateContentClient() override;
  content::ContentBrowserClient* CreateContentBrowserClient() override;
  content::ContentRendererClient* CreateContentRendererClient() override;

  static bool InitializeResourceBundle();

 private:
  std::unique_ptr<VekoContentClient> content_client_;
  std::unique_ptr<VekoContentBrowserClient> browser_client_;
  std::unique_ptr<VekoContentRendererClient> renderer_client_;
};

// Đăng ký JNI_OnLoad cho libveko_content_embedder.so trên Android
bool RegisterVekoContentMainOnLoad(JavaVM* vm);

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_MAIN_DELEGATE_H_
