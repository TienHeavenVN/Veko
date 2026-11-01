package io.veko.browser.tab

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.download.ExternalDownloadPayload
import io.veko.browser.engine.EngineAvailability
import io.veko.browser.engine.EngineCapabilities
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.JavaScriptEvalResult
import io.veko.browser.engine.JavaScriptEvaluationPolicy
import io.veko.browser.permission.SitePermissionDecision
import io.veko.browser.permission.SitePermissionType
import kotlinx.coroutines.flow.StateFlow

/**
 * Giao diện đánh dấu Application chủ của trình duyệt để không phụ thuộc ngược từ `:browser-core` lên `:app`.
 */
interface BrowserHostApplication {
    fun markTabManagerReady()
}

/**
 * Dữ liệu yêu cầu điều hướng truyền từ Engine (Chromium Content / GeckoView) lên tầng chính sách của Veko.
 */
data class EngineLoadRequest(
    val uri: String,
    val triggerUri: String?,
    val targetWindow: Int,
    val isRedirect: Boolean,
    val hasUserGesture: Boolean,
    val isDirectNavigation: Boolean,
)

/**
 * Các callback sự kiện vòng đời/điều hướng/tương tác của một phiên trình duyệt.
 */
interface EngineSessionCallbacks {
    fun onLocationChange(session: EngineSessionHandle, url: String?)
    fun onCanGoBack(session: EngineSessionHandle, canGoBack: Boolean)
    fun onCanGoForward(session: EngineSessionHandle, canGoForward: Boolean)
    fun onLoadRequest(session: EngineSessionHandle, request: EngineLoadRequest): Boolean
    fun onNewSession(session: EngineSessionHandle, uri: String): EngineSessionHandle?
    fun onPopupSessionOpened(session: EngineSessionHandle) {}
    fun onLoadError(session: EngineSessionHandle, uri: String?, errorCode: Int): String?
    fun onPageStart(session: EngineSessionHandle, url: String)
    fun onProgressChange(session: EngineSessionHandle, progress: Int)
    fun onPageStop(session: EngineSessionHandle, success: Boolean)
    fun onSecurityChange(session: EngineSessionHandle, isSecure: Boolean, origin: String?)
    fun onSessionStateChange(session: EngineSessionHandle, serializedState: String)
    fun onTitleChange(session: EngineSessionHandle, title: String?)
    fun onCloseRequest(session: EngineSessionHandle)
    fun onKill(session: EngineSessionHandle)
    fun onCrash(session: EngineSessionHandle)
    fun onFullScreen(session: EngineSessionHandle, fullScreen: Boolean) {}
    fun onScrollChanged(session: EngineSessionHandle, scrollX: Int, scrollY: Int) {}
    fun onContextMenu(
        session: EngineSessionHandle,
        linkUri: String?,
        linkText: String?,
        mediaSrcUri: String?,
        mediaType: Int,
        title: String?,
    ) {}
    fun onExternalResponse(session: EngineSessionHandle, payload: ExternalDownloadPayload) {
        runCatching { payload.bodyStream?.close() }
    }
    fun onWebPrompt(session: EngineSessionHandle, prompt: WebDialogPrompt) {
        prompt.cancel()
    }
    fun onContentPermission(
        session: EngineSessionHandle,
        uri: String,
        permissionType: SitePermissionType,
        onDecision: (SitePermissionDecision) -> Unit,
    ) {
        onDecision(SitePermissionDecision.DENY)
    }
    fun onMediaPermission(
        session: EngineSessionHandle,
        uri: String,
        needsCamera: Boolean,
        needsMicrophone: Boolean,
        onDecision: (Boolean) -> Unit,
    ) {
        onDecision(false)
    }
    fun onAndroidPermissionsRequest(
        session: EngineSessionHandle,
        permissions: List<String>,
        onDecision: (Boolean) -> Unit,
    ) {
        onDecision(false)
    }
}

/**
 * Hợp đồng điều khiển một phiên Engine (độc lập giữa Chromium Content `WebContents` và `GeckoSession`).
 */
interface EngineSessionHandle {
    val engineType: EngineType
        get() = EngineType.GECKO
    val isOpen: Boolean
    fun open()
    fun close()
    fun clearCallbacks()
    fun loadUri(url: String)
    fun restoreStateFromString(encodedState: String): Boolean
    fun setActive(active: Boolean)
    fun setPriorityHigh(high: Boolean)
    fun setDesktopMode(enabled: Boolean)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    fun exitFullScreen() {}
    fun findInPage(
        query: String,
        backwards: Boolean,
        matchCase: Boolean,
        onResult: (found: Boolean, current: Int, total: Int) -> Unit,
    ) {
        onResult(false, 0, 0)
    }
    fun clearFindInPage() {}
    fun evaluateJavaScript(
        currentUrl: String,
        script: String,
        onResult: (JavaScriptEvalResult) -> Unit,
    ) {
        JavaScriptEvaluationPolicy.validate(currentUrl = currentUrl, script = script)?.let { blocked ->
            onResult(blocked)
            return
        }
        onResult(
            JavaScriptEvalResult.UnsupportedByEngine(
                "Host-injected JavaScript evaluation is not enabled on ${engineType.displayName}.",
            ),
        )
    }
}

/**
 * Hợp đồng điều khiển bề mặt hiển thị (bọc WeakReference<View> để tuyệt đối không giữ leak Activity/View).
 */
interface EngineViewHandle {
    val engineType: EngineType
        get() = EngineType.GECKO
    val identity: Any?
    val isAlive: Boolean
    fun setSession(session: EngineSessionHandle)
    fun releaseSession()
    fun capturePixelsAsync(onResult: (Bitmap?) -> Unit)
}

/**
 * Cầu nối khởi tạo EngineSessionHandle và theo dõi sự kiện tắt Runtime.
 */
interface EngineSessionBridge {
    fun createPlatformView(context: Context): View = View(context)

    fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle

    fun wrapPlatformView(platformView: View): EngineViewHandle

    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?)

    fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {}
}

/**
 * Cầu nối mở rộng cho kiến trúc Dual-Engine (`GeckoView` + `Chromium Content`),
 * cho phép `TabManager` định tuyến chính xác từng tab theo `EngineType` độc lập,
 * chỉ khởi tạo engine được chọn và cô lập hoàn toàn cookie/profile/phiên riêng tư giữa hai engine.
 */
interface MultiEngineSessionBridge : EngineSessionBridge {
    val engineAvailabilities: StateFlow<Map<EngineType, EngineAvailability>>
    val initializedEngines: StateFlow<Set<EngineType>>

    fun refreshAvailabilities(): Map<EngineType, EngineAvailability>

    fun getAvailability(engineType: EngineType): EngineAvailability

    fun getCapabilities(engineType: EngineType): EngineCapabilities

    fun isEngineSelectable(engineType: EngineType): Boolean

    fun resolveExecutableEngine(requested: EngineType): EngineType

    fun createPlatformView(context: Context, engineType: EngineType): View

    fun wrapPlatformView(platformView: View, engineType: EngineType): EngineViewHandle

    fun createSessionForEngine(
        engineType: EngineType,
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle

    fun setOnEngineShutdownListener(listener: ((EngineType) -> Unit)?)

    fun releasePrivateContextIfUnused(engineType: EngineType, activePrivateTabsForEngine: Int)

    fun applyCookiePolicy(mode: CookieControlMode) {}

    fun clearEngineStorage(
        engineType: EngineType,
        includeCookies: Boolean = true,
        includeCache: Boolean = true,
        onlyPrivate: Boolean = false,
    ) {}

    fun onLowMemory(level: Int) {}
}
