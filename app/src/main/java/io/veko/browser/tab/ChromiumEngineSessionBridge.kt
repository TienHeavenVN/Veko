package io.veko.browser.tab

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.chromium.embedder.ChromiumSurfaceHostView
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.JavaScriptEvalResult
import io.veko.browser.engine.JavaScriptEvaluationPolicy
import io.veko.browser.security.TlsCertificatePolicy
import io.veko.browser.security.TlsValidationVerdict
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triển khai `EngineSessionBridge` kết nối trực tiếp `:browser-core` (`TabManager` / `BrowserMviStore`)
 * tới tầng C++20/JNI của Chromium Content Embedder (`VekoChromiumNative` + `ChromiumSurfaceHostView`).
 *
 * Đảm bảo các bất biến kiến trúc (Phases 4, 5, 7, 8):
 * - Tách biệt cứng giữa `Regular BrowserContext` và `Off-The-Record BrowserContext` (hủy vùng nhớ OTR ngay khi đóng tab riêng tư cuối cùng).
 * - Không bao giờ bỏ qua lỗi chứng chỉ TLS (`TlsCertificatePolicy` + `VekoChromiumNative.evaluateTlsCertificateError` luôn trả về từ chối).
 * - Bọc `ChromiumSurfaceHostView` qua `WeakReference` để không rò rỉ `Activity`/`View` khi xoay màn hình hoặc hủy Activity.
 */
@Singleton
class ChromiumEngineSessionBridge @Inject constructor(
    private val embedderRuntime: ChromiumEmbedderRuntime,
) : EngineSessionBridge {

    private val mainHandler: Handler? by lazy {
        runCatching { Looper.getMainLooper()?.let(::Handler) }.getOrNull()
    }
    private val activePrivateSessionCount = AtomicInteger(0)
    private val trackedSessions: MutableSet<ChromiumEngineSessionHandle> =
        Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile
    private var shutdownListener: (() -> Unit)? = null

    fun activeSessionCount(): Int = trackedSessions.count { it.isOpen }

    fun activePrivateCount(): Int = activePrivateSessionCount.get().coerceAtLeast(0)

    fun closeAllTrackedSessions(onlyPrivate: Boolean = false) {
        val snapshot = trackedSessions.toList()
        for (session in snapshot) {
            if (!onlyPrivate || session.isPrivateSession) {
                session.clearCallbacks()
                session.close()
                trackedSessions.remove(session)
            }
        }
    }

    override fun createPlatformView(context: Context): View = ChromiumSurfaceHostView(context)

    override fun wrapPlatformView(platformView: View): EngineViewHandle {
        val surfaceHost = platformView as? ChromiumSurfaceHostView
            ?: throw IllegalArgumentException(
                "Expected ChromiumSurfaceHostView instance, got ${platformView::class.java.name}",
            )
        return ChromiumEngineViewHandle(surfaceHost, mainHandler)
    }

    override fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle {
        val handle = ChromiumEngineSessionHandle(
            isPrivateSession = isPrivate,
            initialDesktopMode = isDesktopMode,
            embedderRuntime = embedderRuntime,
            privateCounter = activePrivateSessionCount,
            callbacks = callbacks,
            onClosed = { trackedSessions.remove(it) },
        )
        trackedSessions.add(handle)
        if (openImmediately) {
            handle.open()
        }
        return handle
    }

    override fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        shutdownListener = listener
    }

    override fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        // Lưu cấu hình tỷ lệ thu phóng cho WebContents HostZoomMap khi gắn kết xuất thượng nguồn
    }

    fun notifyRuntimeShutdown() {
        closeAllTrackedSessions(onlyPrivate = false)
        embedderRuntime.shutdown()
        shutdownListener?.invoke()
    }
}

class ChromiumEngineSessionHandle internal constructor(
    val isPrivateSession: Boolean,
    initialDesktopMode: Boolean,
    private val embedderRuntime: ChromiumEmbedderRuntime,
    private val privateCounter: AtomicInteger,
    callbacks: EngineSessionCallbacks,
    private val onClosed: (ChromiumEngineSessionHandle) -> Unit = {},
) : EngineSessionHandle {

    override val engineType: EngineType = EngineType.CHROMIUM

    private val opened = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile
    private var callbacksRef: EngineSessionCallbacks? = callbacks
    @Volatile
    private var desktopModeEnabled: Boolean = initialDesktopMode
    @Volatile
    var nativeBrowserHandle: Long = 0L
        private set

    override val isOpen: Boolean
        get() = opened.get() && !closed.get() && nativeBrowserHandle > 0L

    override fun open() {
        if (closed.get() || opened.get()) return
        embedderRuntime.ensureNativeRuntimeInitialized()
        val browserContext = if (isPrivateSession) {
            privateCounter.incrementAndGet()
            embedderRuntime.acquireOffTheRecordContext()
        } else {
            embedderRuntime.regularBrowserContext
        }
        val createdHandle = VekoChromiumNative.createBrowser(
            contextHandle = browserContext.nativeHandle,
            desktopMode = desktopModeEnabled,
        )
        check(createdHandle > 0L) {
            "Failed to create native Chromium browser session (error=$createdHandle)"
        }
        nativeBrowserHandle = createdHandle
        opened.set(true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val handle = nativeBrowserHandle
        nativeBrowserHandle = 0L
        if (handle > 0L) {
            VekoChromiumNative.detachSurface(handle)
            VekoChromiumNative.destroyBrowser(handle)
        }
        if (isPrivateSession) {
            val remaining = privateCounter.decrementAndGet().coerceAtLeast(0)
            embedderRuntime.releaseOffTheRecordContextIfUnused(remaining)
        }
        callbacksRef = null
        onClosed(this)
    }

    override fun clearCallbacks() {
        callbacksRef = null
    }

    override fun loadUri(url: String) {
        if (closed.get()) return
        if (!opened.get()) open()
        val handle = nativeBrowserHandle
        if (handle <= 0L) return

        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        val cb = callbacksRef
        if (cb != null) {
            val trigger = VekoChromiumNative.getCurrentUrl(handle).takeIf { it.isNotEmpty() }
            // onLoadRequest trả về true nếu cho phép nạp, hoặc false nếu đã bị chặn/nâng cấp HTTPS/tách tham số bẩn
            val allowed = cb.onLoadRequest(
                this,
                EngineLoadRequest(
                    uri = trimmed,
                    triggerUri = trigger,
                    targetWindow = 0,
                    isRedirect = false,
                    hasUserGesture = true,
                    isDirectNavigation = true,
                ),
            )
            if (!allowed) return
        }

        cb?.onPageStart(this, trimmed)
        cb?.onProgressChange(this, 15)

        val loadResult = VekoChromiumNative.loadUrl(handle, trimmed)
        if (!loadResult.accepted) {
            val netErr = if (loadResult.chromiumNetError != 0) {
                loadResult.chromiumNetError
            } else {
                loadResult.errorCode
            }
            cb?.onSecurityChange(this, false, UrlNavigationPolicy.extractOrigin(trimmed))
            cb?.onLoadError(this, trimmed, netErr)
            cb?.onPageStop(this, false)
            return
        }

        val committedUrl = loadResult.finalUrl.ifEmpty { trimmed }
        val isHttps = committedUrl.startsWith("https://", ignoreCase = true)
        val origin = UrlNavigationPolicy.extractOrigin(committedUrl)

        cb?.onLocationChange(this, committedUrl)
        cb?.onSecurityChange(this, isHttps, origin)
        cb?.onCanGoBack(this, VekoChromiumNative.canGoBack(handle))
        cb?.onCanGoForward(this, VekoChromiumNative.canGoForward(handle))

        val progress = VekoChromiumNative.getLoadProgress(handle)
        cb?.onProgressChange(this, progress)
        if (!isPrivateSession) {
            cb?.onSessionStateChange(this, encodeCurrentStateJson(committedUrl))
        }

        // Nếu chỉ chạy tầng điều hướng native mà chưa liên kết Blink/V8 thượng nguồn,
        // hoàn tất chu trình trạng thái một cách trung thực (không treo isLoading vĩnh viễn)
        if (!loadResult.upstreamContentRendered) {
            val title = VekoChromiumNative.getPageTitle(handle).takeIf { it.isNotBlank() }
            cb?.onTitleChange(this, title)
            cb?.onProgressChange(this, 100)
            cb?.onPageStop(this, true)
        }
    }

    /**
     * Áp đặt chính sách chứng chỉ TLS nghiêm ngặt: tuyệt đối không bao giờ cho phép vượt rào lỗi chứng chỉ.
     */
    fun reportTlsCertificateFailure(chromiumNetError: Int, failingUrl: String): TlsValidationVerdict {
        val verdict = TlsCertificatePolicy.evaluateCertificateError(
            chromiumNetError = chromiumNetError,
            rawUrlOrHost = failingUrl,
            strictHstsActive = true,
        )
        val nativeAllowed = VekoChromiumNative.evaluateTlsCertificateError(
            netError = verdict.technicalCode,
            host = verdict.sanitizedHost,
        )
        check(!verdict.allowProceed && !verdict.allowUserBypass && !nativeAllowed) {
            "TLS certificate error bypass is strictly prohibited in Veko Browser."
        }
        val cb = callbacksRef
        cb?.onSecurityChange(this, false, UrlNavigationPolicy.extractOrigin(failingUrl))
        cb?.onLoadError(this, failingUrl, verdict.technicalCode)
        cb?.onPageStop(this, false)
        return verdict
    }

    override fun restoreStateFromString(encodedState: String): Boolean {
        if (closed.get() || encodedState.isBlank()) return false
        val restoredUrl = runCatching {
            JSONObject(encodedState).optString("url", "")
        }.getOrDefault("")
        if (restoredUrl.isBlank()) return false
        loadUri(restoredUrl)
        return true
    }

    override fun setActive(active: Boolean) {
        // Quản lý độ ưu tiên hiển thị của WebContents khi chuyển đổi foreground/background
    }

    override fun setPriorityHigh(high: Boolean) {
        // Điều chỉnh mức ưu tiên tiến trình Renderer (ChildProcessImportance)
    }

    override fun setDesktopMode(enabled: Boolean) {
        desktopModeEnabled = enabled
        val handle = nativeBrowserHandle
        if (handle > 0L) {
            VekoChromiumNative.setDesktopMode(handle, enabled)
        }
    }

    override fun goBack() {
        val handle = nativeBrowserHandle
        if (handle <= 0L) return
        if (VekoChromiumNative.goBack(handle)) {
            syncHistoryNavigationCallbacks(handle)
        }
    }

    override fun goForward() {
        val handle = nativeBrowserHandle
        if (handle <= 0L) return
        if (VekoChromiumNative.goForward(handle)) {
            syncHistoryNavigationCallbacks(handle)
        }
    }

    override fun reload() {
        val handle = nativeBrowserHandle
        if (handle <= 0L) return
        val currentUrl = VekoChromiumNative.getCurrentUrl(handle)
        if (VekoChromiumNative.reload(handle)) {
            val cb = callbacksRef
            if (currentUrl.isNotEmpty()) {
                cb?.onPageStart(this, currentUrl)
            }
            val progress = VekoChromiumNative.getLoadProgress(handle)
            cb?.onProgressChange(this, progress)
            if (!VekoChromiumNative.isUpstreamContentLinked()) {
                cb?.onProgressChange(this, 100)
                cb?.onPageStop(this, true)
            }
        }
    }

    override fun stop() {
        val handle = nativeBrowserHandle
        if (handle <= 0L) return
        if (VekoChromiumNative.stopLoading(handle)) {
            callbacksRef?.onPageStop(this, false)
        }
    }

    val isNavigationActionBlockedSample: Boolean
        get() = NativePipelineAction.BLOCK_AD.code == 1

    private fun syncHistoryNavigationCallbacks(handle: Long) {
        val cb = callbacksRef ?: return
        val url = VekoChromiumNative.getCurrentUrl(handle)
        val title = VekoChromiumNative.getPageTitle(handle).takeIf { it.isNotBlank() }
        val isHttps = url.startsWith("https://", ignoreCase = true)
        cb.onLocationChange(this, url)
        cb.onTitleChange(this, title)
        cb.onSecurityChange(this, isHttps, UrlNavigationPolicy.extractOrigin(url))
        cb.onCanGoBack(this, VekoChromiumNative.canGoBack(handle))
        cb.onCanGoForward(this, VekoChromiumNative.canGoForward(handle))
        cb.onProgressChange(this, 100)
        cb.onPageStop(this, true)
    }

    private fun encodeCurrentStateJson(url: String): String =
        JSONObject()
            .put("engine", "chromium-156.0.8078.85")
            .put("url", url)
            .put("desktop", desktopModeEnabled)
            .toString()

    override fun evaluateJavaScript(
        currentUrl: String,
        script: String,
        onResult: (JavaScriptEvalResult) -> Unit,
    ) {
        JavaScriptEvaluationPolicy.validate(currentUrl = currentUrl, script = script)?.let { blocked ->
            onResult(blocked)
            return
        }
        if (!isOpen) {
            onResult(JavaScriptEvalResult.ExecutionError("CHROMIUM_SESSION_NOT_OPEN"))
            return
        }
        if (!VekoChromiumNative.isUpstreamContentLinked()) {
            onResult(
                JavaScriptEvalResult.UnsupportedByEngine(
                    "Upstream V8 RenderFrameHost evaluation requires verified libveko_content_embedder.so linkage.",
                ),
            )
            return
        }
        onResult(JavaScriptEvalResult.UnsupportedByEngine("V8 isolated-world bridge not attached."))
    }
}

internal class ChromiumEngineViewHandle(
    surfaceHostView: ChromiumSurfaceHostView,
    private val mainHandler: Handler?,
) : EngineViewHandle {

    override val engineType: EngineType = EngineType.CHROMIUM

    private val viewRef = WeakReference(surfaceHostView)

    override val identity: Any?
        get() = viewRef.get()

    override val isAlive: Boolean
        get() {
            val v = viewRef.get() ?: return false
            return v.isAttachedToWindow || v.windowToken != null || v.width >= 0
        }

    override fun setSession(session: EngineSessionHandle) {
        val view = viewRef.get() ?: return
        val chromiumSession = session as? ChromiumEngineSessionHandle
            ?: throw IllegalArgumentException(
                "ChromiumEngineViewHandle requires ChromiumEngineSessionHandle, got ${session::class.java.name}",
            )
        if (!chromiumSession.isOpen) {
            chromiumSession.open()
        }
        view.bindBrowserHandle(chromiumSession.nativeBrowserHandle)
    }

    override fun releaseSession() {
        viewRef.get()?.unbindBrowserHandle()
    }

    override fun capturePixelsAsync(onResult: (Bitmap?) -> Unit) {
        val host = viewRef.get()
        val surface = host?.surfaceView?.holder?.surface
        val w = host?.surfaceView?.width ?: 0
        val h = host?.surfaceView?.height ?: 0
        val handler = mainHandler
        if (host == null || handler == null || surface == null || !surface.isValid || w <= 0 || h <= 0) {
            onResult(null)
            return
        }
        val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull()
        if (bitmap == null) {
            onResult(null)
            return
        }
        runCatching {
            PixelCopy.request(
                surface,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        onResult(bitmap)
                    } else {
                        bitmap.recycle()
                        onResult(null)
                    }
                },
                handler,
            )
        }.onFailure {
            bitmap.recycle()
            onResult(null)
        }
    }
}
