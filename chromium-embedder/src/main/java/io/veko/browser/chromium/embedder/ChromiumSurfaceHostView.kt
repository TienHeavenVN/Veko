package io.veko.browser.chromium.embedder

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import io.veko.browser.chromium.nativebridge.VekoChromiumNative

/**
 * Host kết xuất Android `SurfaceView` -> JNI `ANativeWindow` cho Chromium Content Compositor.
 *
 * Thiết kế tuân thủ nghiêm ngặt hợp đồng SurfaceFlinger của Android:
 * - Gắn `Surface` thực qua `VekoChromiumNative.attachSurface` khi `surfaceChanged` có kích thước hợp lệ.
 * - Khi chuyển đổi tab (`bindBrowserHandle`), tháo `ANativeWindow` khỏi phiên cũ trước khi gắn sang phiên mới.
 * - Giải phóng `ANativeWindow` đồng bộ ngay bên trong `surfaceDestroyed` và `onDetachedFromWindow` để chống rò rỉ hoặc use-after-free.
 * - Không bao giờ vẽ chữ placeholder giả mạo kết xuất trang web.
 */
class ChromiumSurfaceHostView(context: Context) : FrameLayout(context), SurfaceHolder.Callback2 {

    val surfaceView: SurfaceView = SurfaceView(context)

    private var boundBrowserHandle: Long = 0L
    private var activeSurface: Surface? = null
    private var surfaceWidthPx: Int = 0
    private var surfaceHeightPx: Int = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        surfaceView.isFocusable = true
        surfaceView.isFocusableInTouchMode = true
        surfaceView.holder.setFormat(PixelFormat.OPAQUE)
        surfaceView.holder.addCallback(this)
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    val currentBrowserHandle: Long
        get() = boundBrowserHandle

    /**
     * Liên kết view hiện tại với một `browserHandle` native của tab đang mở.
     */
    fun bindBrowserHandle(browserHandle: Long) {
        if (boundBrowserHandle == browserHandle) {
            syncActiveSurfaceToBoundBrowser()
            return
        }
        detachNativeSurfaceIfBound()
        boundBrowserHandle = browserHandle
        syncActiveSurfaceToBoundBrowser()
    }

    /**
     * Ngắt liên kết phiên trình duyệt hiện tại và giải phóng `ANativeWindow` gắn kèm.
     */
    fun unbindBrowserHandle() {
        detachNativeSurfaceIfBound()
        boundBrowserHandle = 0L
    }

    fun currentSurfaceSnapshot(): VekoChromiumNative.NativeSurfaceSnapshot {
        val handle = boundBrowserHandle
        if (handle <= 0L || !VekoChromiumNative.isLibraryLoaded()) {
            return VekoChromiumNative.NativeSurfaceSnapshot(false, 0, 0, 0, 0L)
        }
        return VekoChromiumNative.getSurfaceSnapshot(handle)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        activeSurface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        activeSurface = holder.surface
        surfaceWidthPx = width
        surfaceHeightPx = height
        syncActiveSurfaceToBoundBrowser()
    }

    override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
        syncActiveSurfaceToBoundBrowser()
    }

    override fun surfaceRedrawNeededAsync(holder: SurfaceHolder, drawingFinished: Runnable) {
        syncActiveSurfaceToBoundBrowser()
        drawingFinished.run()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Bắt buộc giải phóng ANativeWindow trước khi trả điều khiển về SurfaceFlinger
        detachNativeSurfaceIfBound()
        activeSurface = null
        surfaceWidthPx = 0
        surfaceHeightPx = 0
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        if (boundBrowserHandle > 0L && surfaceWidthPx > 0 && surfaceHeightPx > 0) {
            syncActiveSurfaceToBoundBrowser()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.actionMasked == MotionEvent.ACTION_DOWN && !hasFocus()) {
            requestFocus()
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        detachNativeSurfaceIfBound()
        super.onDetachedFromWindow()
    }

    private fun syncActiveSurfaceToBoundBrowser() {
        val handle = boundBrowserHandle
        val surface = activeSurface
        if (handle <= 0L || surface == null || !surface.isValid) return
        if (surfaceWidthPx <= 0 || surfaceHeightPx <= 0) return
        if (!VekoChromiumNative.ensureLoaded()) return

        val densityDpi = resources.displayMetrics.densityDpi.coerceAtLeast(120)
        val snap = VekoChromiumNative.getSurfaceSnapshot(handle)
        if (!snap.attached) {
            VekoChromiumNative.attachSurface(
                browserHandle = handle,
                surface = surface,
                widthPx = surfaceWidthPx,
                heightPx = surfaceHeightPx,
                densityDpi = densityDpi,
            )
        } else if (snap.widthPx != surfaceWidthPx || snap.heightPx != surfaceHeightPx) {
            VekoChromiumNative.resizeSurface(
                browserHandle = handle,
                widthPx = surfaceWidthPx,
                heightPx = surfaceHeightPx,
            )
        }
    }

    private fun detachNativeSurfaceIfBound() {
        val handle = boundBrowserHandle
        if (handle > 0L && VekoChromiumNative.isLibraryLoaded()) {
            val snap = VekoChromiumNative.getSurfaceSnapshot(handle)
            if (snap.attached) {
                VekoChromiumNative.detachSurface(handle)
            }
        }
    }
}
