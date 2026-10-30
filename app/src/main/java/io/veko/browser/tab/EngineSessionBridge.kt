package io.veko.browser.tab

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import io.veko.browser.engine.BrowserRuntimeManager
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dữ liệu yêu cầu điều hướng truyền từ Engine lên tầng chính sách của Veko.
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
 * Các callback sự kiện vòng đời/điều hướng của một phiên trình duyệt.
 */
interface EngineSessionCallbacks {
    fun onLocationChange(session: EngineSessionHandle, url: String?)
    fun onCanGoBack(session: EngineSessionHandle, canGoBack: Boolean)
    fun onCanGoForward(session: EngineSessionHandle, canGoForward: Boolean)
    fun onLoadRequest(session: EngineSessionHandle, request: EngineLoadRequest): Boolean
    fun onNewSession(session: EngineSessionHandle, uri: String): EngineSessionHandle?
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
}

/**
 * Hợp đồng điều khiển một phiên Engine (bọc thực thể GeckoSession ở môi trường chạy thật).
 */
interface EngineSessionHandle {
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
}

/**
 * Hợp đồng điều khiển bề mặt hiển thị (bọc WeakReference<GeckoView> để tuyệt đối không giữ leak Activity/View).
 */
interface EngineViewHandle {
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
    fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle

    fun wrapGeckoView(geckoView: GeckoView): EngineViewHandle

    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?)
}

/**
 * Triển khai thật kết nối trực tiếp tới Mozilla GeckoView 150.
 */
@Singleton
class GeckoEngineSessionBridge @Inject constructor(
    private val runtimeManager: BrowserRuntimeManager,
) : EngineSessionBridge {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        runtimeManager.setOnRuntimeShutdownListener(listener)
    }

    override fun wrapGeckoView(geckoView: GeckoView): EngineViewHandle {
        return GeckoEngineViewHandle(geckoView, mainHandler)
    }

    override fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle {
        val settings = GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .useTrackingProtection(true)
            .userAgentMode(
                if (isDesktopMode) {
                    GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                },
            )
            .viewportMode(
                if (isDesktopMode) {
                    GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                } else {
                    GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                },
            )
            .build()

        val geckoSession = GeckoSession(settings)
        val handle = GeckoEngineSessionHandle(geckoSession, runtimeManager)
        handle.bindCallbacks(callbacks)
        if (openImmediately) {
            handle.open()
        }
        return handle
    }
}

internal class GeckoEngineSessionHandle(
    val geckoSession: GeckoSession,
    private val runtimeManager: BrowserRuntimeManager,
) : EngineSessionHandle {

    override val isOpen: Boolean
        get() = geckoSession.isOpen

    override fun open() {
        if (!geckoSession.isOpen) {
            geckoSession.open(runtimeManager.getOrCreateRuntime())
        }
    }

    override fun close() {
        if (geckoSession.isOpen) {
            geckoSession.close()
        }
    }

    override fun clearCallbacks() {
        geckoSession.navigationDelegate = null
        geckoSession.progressDelegate = null
        geckoSession.contentDelegate = null
    }

    override fun loadUri(url: String) {
        geckoSession.loadUri(url)
    }

    override fun restoreStateFromString(encodedState: String): Boolean {
        val parsed = runCatching { GeckoSession.SessionState.fromString(encodedState) }.getOrNull()
            ?: return false
        return runCatching {
            geckoSession.restoreState(parsed)
            true
        }.getOrDefault(false)
    }

    override fun setActive(active: Boolean) {
        geckoSession.setActive(active)
    }

    override fun setPriorityHigh(high: Boolean) {
        geckoSession.setPriorityHint(
            if (high) GeckoSession.PRIORITY_HIGH else GeckoSession.PRIORITY_DEFAULT,
        )
    }

    override fun setDesktopMode(enabled: Boolean) {
        geckoSession.settings.userAgentMode = if (enabled) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        geckoSession.settings.viewportMode = if (enabled) {
            GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
    }

    override fun goBack() = geckoSession.goBack()
    override fun goForward() = geckoSession.goForward()
    override fun reload() = geckoSession.reload()
    override fun stop() = geckoSession.stop()

    fun bindCallbacks(callbacks: EngineSessionCallbacks) {
        val self = this
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                callbacks.onLocationChange(self, url)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                callbacks.onCanGoBack(self, canGoBack)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                callbacks.onCanGoForward(self, canGoForward)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                val allowed = callbacks.onLoadRequest(
                    self,
                    EngineLoadRequest(
                        uri = request.uri,
                        triggerUri = request.triggerUri,
                        targetWindow = request.target,
                        isRedirect = request.isRedirect,
                        hasUserGesture = request.hasUserGesture,
                        isDirectNavigation = request.isDirectNavigation,
                    ),
                )
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                val childHandle = callbacks.onNewSession(self, uri) as? GeckoEngineSessionHandle
                return GeckoResult.fromValue(childHandle?.geckoSession)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                val errorUrl = callbacks.onLoadError(self, uri, error.code) ?: return null
                return GeckoResult.fromValue(errorUrl)
            }
        }

        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                callbacks.onPageStart(self, url)
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                callbacks.onProgressChange(self, progress)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                callbacks.onPageStop(self, success)
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
            ) {
                callbacks.onSecurityChange(self, securityInfo.isSecure, securityInfo.origin)
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState,
            ) {
                callbacks.onSessionStateChange(self, sessionState.toString())
            }
        }

        geckoSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                callbacks.onTitleChange(self, title)
            }

            override fun onCloseRequest(session: GeckoSession) {
                callbacks.onCloseRequest(self)
            }

            override fun onKill(session: GeckoSession) {
                callbacks.onKill(self)
            }

            override fun onCrash(session: GeckoSession) {
                callbacks.onCrash(self)
            }
        }
    }
}

private class GeckoEngineViewHandle(
    geckoView: GeckoView,
    private val mainHandler: Handler,
) : EngineViewHandle {

    // Luôn bọc GeckoView trong WeakReference để không bao giờ giữ sống Activity cũ
    private val viewRef = WeakReference(geckoView)

    override val identity: Any?
        get() = viewRef.get()

    override val isAlive: Boolean
        get() = viewRef.get() != null

    override fun setSession(session: EngineSessionHandle) {
        val view = viewRef.get() ?: return
        val geckoHandle = session as? GeckoEngineSessionHandle ?: return
        view.setSession(geckoHandle.geckoSession)
    }

    override fun releaseSession() {
        viewRef.get()?.releaseSession()
    }

    override fun capturePixelsAsync(onResult: (Bitmap?) -> Unit) {
        val view = viewRef.get() ?: return
        if (view.width <= 0 || view.height <= 0) return
        val captureResult = try {
            view.capturePixels()
        } catch (_: IllegalStateException) {
            return
        }
        captureResult.withHandler(mainHandler).accept(
            { bitmap -> onResult(bitmap) },
            { onResult(null) },
        )
    }
}
