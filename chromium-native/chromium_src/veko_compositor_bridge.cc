#include "veko_compositor_bridge.h"

#if defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)

#include "cc/slim/layer.h"
#include "content/public/browser/web_contents.h"
#include "ui/android/view_android.h"
#include "ui/gfx/geometry/size.h"

namespace veko::embedder {

VekoCompositorBridge::VekoCompositorBridge(gfx::NativeWindow root_window)
    : root_window_(root_window) {}

VekoCompositorBridge::~VekoCompositorBridge() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (compositor_) {
    compositor_->SetSurface(nullptr, false, nullptr);
    compositor_.reset();
  }
}

void VekoCompositorBridge::EnsureCompositor() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!compositor_) {
    compositor_.reset(content::Compositor::Create(this, root_window_));
  }
}

void VekoCompositorBridge::BindWebContents(content::WebContents* web_contents) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  EnsureCompositor();
  if (web_contents && web_contents->GetNativeView()) {
    if (width_px_ > 0 && height_px_ > 0) {
      web_contents->GetNativeView()->OnPhysicalBackingSizeChanged(
          gfx::Size(width_px_, height_px_));
    }
    compositor_->SetRootLayer(web_contents->GetNativeView()->GetLayer());
  } else {
    compositor_->SetRootLayer(scoped_refptr<cc::slim::Layer>());
  }
}

void VekoCompositorBridge::SurfaceCreated() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  current_surface_format_ = 0;
  EnsureCompositor();
}

std::optional<int> VekoCompositorBridge::SurfaceChanged(
    int32_t format,
    int32_t width,
    int32_t height,
    const base::android::JavaRef<jobject>& jsurface,
    const base::android::JavaRef<jobject>& browser_input_token) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  EnsureCompositor();
  width_px_ = width;
  height_px_ = height;
  std::optional<int> handle = std::nullopt;
  if (current_surface_format_ != format) {
    current_surface_format_ = format;
    handle = compositor_->SetSurface(
        jsurface,
        /*can_be_used_with_surface_control=*/true,
        browser_input_token);
  }
  compositor_->SetWindowBounds(gfx::Size(width, height));
  return handle;
}

void VekoCompositorBridge::SurfaceDestroyed() {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (!compositor_) return;
  compositor_->PreserveChildSurfaceControls();
  compositor_->SetSurface(nullptr, false, nullptr);
  current_surface_format_ = 0;
}

void VekoCompositorBridge::SetDrawPaused(bool paused) {
  DCHECK_CALLED_ON_VALID_SEQUENCE(sequence_checker_);
  if (compositor_) {
    compositor_->SetDrawPaused(paused);
  }
}

void VekoCompositorBridge::UpdateLayerTreeHost() {}

void VekoCompositorBridge::DidSwapFrame(int /*pending_frames*/) {}

}  // namespace veko::embedder

#endif  // defined(VEKO_HAS_UPSTREAM_CHROMIUM_CONTENT)
