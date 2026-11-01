// Cầu nối kết xuất content::Compositor <-> Android Surface / SurfaceControl cho Chromium 156.0.8078.85.
#ifndef VEKO_CHROMIUM_SRC_COMPOSITOR_BRIDGE_H_
#define VEKO_CHROMIUM_SRC_COMPOSITOR_BRIDGE_H_

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include <cstdint>
#include <memory>
#include <optional>

#include "base/android/scoped_java_ref.h"
#include "base/sequence_checker.h"
#include "content/public/browser/android/compositor.h"
#include "content/public/browser/android/compositor_client.h"
#include "ui/gfx/native_ui_types.h"

namespace content {
class WebContents;
}

namespace veko::embedder {

class VekoCompositorBridge : public content::CompositorClient {
 public:
  explicit VekoCompositorBridge(gfx::NativeWindow root_window);
  VekoCompositorBridge(const VekoCompositorBridge&) = delete;
  VekoCompositorBridge& operator=(const VekoCompositorBridge&) = delete;
  ~VekoCompositorBridge() override;

  void BindWebContents(content::WebContents* web_contents);
  void SurfaceCreated();
  std::optional<int> SurfaceChanged(
      int32_t format,
      int32_t width,
      int32_t height,
      const base::android::JavaRef<jobject>& jsurface,
      const base::android::JavaRef<jobject>& browser_input_token);
  void SurfaceDestroyed();
  void SetDrawPaused(bool paused);

  // content::CompositorClient:
  void UpdateLayerTreeHost() override;
  void DidSwapFrame(int pending_frames) override;

 private:
  void EnsureCompositor();

  SEQUENCE_CHECKER(sequence_checker_);
  gfx::NativeWindow root_window_;
  std::unique_ptr<content::Compositor> compositor_;
  int32_t current_surface_format_ = 0;
  int32_t width_px_ = 0;
  int32_t height_px_ = 0;
};

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
#endif  // VEKO_CHROMIUM_SRC_COMPOSITOR_BRIDGE_H_
