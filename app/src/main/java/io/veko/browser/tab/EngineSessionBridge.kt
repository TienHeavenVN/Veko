package io.veko.browser.tab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.MainThread
import io.veko.browser.download.ExternalDownloadPayload
import io.veko.browser.engine.BrowserRuntimeManager
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.gecko.GeckoSettings
import io.veko.browser.permission.SitePermissionDecision
import io.veko.browser.permission.SitePermissionType
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.BasicSelectionActionDelegate
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.SlowScriptResponse
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@MainThread
fun TabManager.attachGeckoView(geckoView: GeckoView) {
    attachPlatformView(geckoView)
}

@MainThread
fun TabManager.detachGeckoView(geckoView: GeckoView) {
    detachPlatformView(geckoView)
}

/**
 * Triển khai thật kết nối trực tiếp tới Mozilla GeckoView 150 (duy trì qua cổng an toàn Giai đoạn 6).
 */
@Singleton
class GeckoEngineSessionBridge @Inject constructor(
    private val runtimeManager: BrowserRuntimeManager,
) : EngineSessionBridge {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedSessions: MutableSet<GeckoEngineSessionHandle> =
        Collections.newSetFromMap(ConcurrentHashMap())

    fun activeSessionCount(): Int = trackedSessions.count { it.isOpen }

    fun activePrivateSessionCount(): Int = trackedSessions.count { it.isPrivateSession && it.isOpen }

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

    override fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        runtimeManager.setOnRuntimeShutdownListener {
            trackedSessions.clear()
            listener?.invoke()
        }
    }

    override fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        runtimeManager.applyPageZoomSettings(autoFontSize, zoomPercent)
    }

    override fun createPlatformView(context: Context): View = GeckoView(context)

    override fun wrapPlatformView(platformView: View): EngineViewHandle {
        val geckoView = platformView as? GeckoView
            ?: throw IllegalArgumentException("Expected GeckoView instance, got ${platformView::class.java.name}")
        return GeckoEngineViewHandle(geckoView, mainHandler)
    }

    override fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle {
        val settings = GeckoSettings.buildSessionSettings(
            isPrivate = isPrivate,
            isDesktopMode = isDesktopMode,
        )

        val geckoSession = GeckoSession(settings)
        val handle = GeckoEngineSessionHandle(
            geckoSession = geckoSession,
            isPrivateSession = isPrivate,
            runtimeManager = runtimeManager,
            mainHandler = mainHandler,
            onClosed = { trackedSessions.remove(it) },
        )
        trackedSessions.add(handle)
        handle.bindCallbacks(callbacks)
        if (openImmediately) {
            handle.open()
        }
        return handle
    }
}

internal class GeckoEngineSessionHandle(
    val geckoSession: GeckoSession,
    val isPrivateSession: Boolean,
    private val runtimeManager: BrowserRuntimeManager,
    private val mainHandler: Handler,
    private val onClosed: (GeckoEngineSessionHandle) -> Unit = {},
) : EngineSessionHandle {

    override val engineType: EngineType = EngineType.GECKO

    @Volatile
    private var openSnapshot: Boolean = false

    override val isOpen: Boolean
        get() = if (Looper.myLooper() == Looper.getMainLooper()) {
            geckoSession.isOpen.also { openSnapshot = it }
        } else {
            openSnapshot
        }

    override fun open() {
        if (!geckoSession.isOpen) {
            geckoSession.open(runtimeManager.getOrCreateRuntime())
        }
        openSnapshot = geckoSession.isOpen
    }

    override fun close() {
        openSnapshot = false
        if (geckoSession.isOpen) {
            geckoSession.close()
        }
        onClosed(this)
    }

    override fun clearCallbacks() {
        geckoSession.navigationDelegate = null
        geckoSession.progressDelegate = null
        geckoSession.contentDelegate = null
        geckoSession.scrollDelegate = null
        geckoSession.promptDelegate = null
        geckoSession.permissionDelegate = null
        geckoSession.selectionActionDelegate = null
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
    override fun exitFullScreen() = geckoSession.exitFullScreen()

    override fun findInPage(
        query: String,
        backwards: Boolean,
        matchCase: Boolean,
        onResult: (found: Boolean, current: Int, total: Int) -> Unit,
    ) {
        if (query.isBlank() || !geckoSession.isOpen) {
            runCatching { geckoSession.finder.clear() }
            onResult(false, 0, 0)
            return
        }
        var flags = if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else GeckoSession.FINDER_FIND_FORWARD
        if (matchCase) {
            flags = flags or GeckoSession.FINDER_FIND_MATCH_CASE
        }
        geckoSession.finder.setDisplayFlags(
            GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL or GeckoSession.FINDER_DISPLAY_DIM_PAGE,
        )
        geckoSession.finder.find(query, flags).withHandler(mainHandler).accept(
            { res ->
                if (res != null) {
                    onResult(res.found, res.current, res.total.coerceAtLeast(0))
                } else {
                    onResult(false, 0, 0)
                }
            },
            { onResult(false, 0, 0) },
        )
    }

    override fun clearFindInPage() {
        runCatching { geckoSession.finder.clear() }
    }

    fun bindCallbacks(callbacks: EngineSessionCallbacks) {
        val self = this

        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                callbacks.onPopupSessionOpened(self)
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
                    ?: return GeckoResult.fromValue(null)
                // Nhịp kiểm tra sau khi GeckoSession.java chạy result.map { session.open(runtime, newSessionId) }
                mainHandler.postDelayed({ childHandle.notifyIfEngineOpened(callbacks) }, 24L)
                mainHandler.postDelayed({ childHandle.notifyIfEngineOpened(callbacks) }, 120L)
                return GeckoResult.fromValue(childHandle.geckoSession)
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
                openSnapshot = session.isOpen
                callbacks.onPopupSessionOpened(self)
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

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                callbacks.onFullScreen(self, fullScreen)
            }

            override fun onContextMenu(
                session: GeckoSession,
                screenX: Int,
                screenY: Int,
                element: GeckoSession.ContentDelegate.ContextElement,
            ) {
                callbacks.onContextMenu(
                    session = self,
                    linkUri = element.linkUri,
                    linkText = element.linkText,
                    mediaSrcUri = element.srcUri,
                    mediaType = element.type,
                    title = element.title ?: element.altText,
                )
            }

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                val contentType = response.headerValueIgnoreCase("content-type")
                val contentDisposition = response.headerValueIgnoreCase("content-disposition")
                val contentLength = response.headerValueIgnoreCase("content-length")
                    ?.trim()
                    ?.toLongOrNull()
                    ?: -1L
                callbacks.onExternalResponse(
                    self,
                    ExternalDownloadPayload(
                        uri = response.uri,
                        contentType = contentType,
                        contentDisposition = contentDisposition,
                        contentLength = contentLength,
                        isPrivate = isPrivateSession,
                        bodyStream = response.body,
                    ),
                )
            }

            override fun onSlowScript(
                geckoSession: GeckoSession,
                scriptFileName: String,
            ): GeckoResult<SlowScriptResponse> {
                val result = GeckoResult<SlowScriptResponse>()
                val completed = AtomicBoolean(false)
                callbacks.onWebPrompt(
                    self,
                    WebDialogPrompt.SlowScript(
                        id = UUID.randomUUID().toString(),
                        tabId = "",
                        scriptFileName = scriptFileName,
                        onAction = { stop ->
                            if (completed.compareAndSet(false, true)) {
                                result.complete(if (stop) SlowScriptResponse.STOP else SlowScriptResponse.CONTINUE)
                            }
                        },
                    ),
                )
                return result
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

        geckoSession.scrollDelegate = object : GeckoSession.ScrollDelegate {
            override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
                callbacks.onScrollChanged(self, scrollX, scrollY)
            }
        }

        geckoSession.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val resolved = AtomicBoolean(false)
                callbacks.onWebPrompt(
                    self,
                    WebDialogPrompt.Alert(
                        id = UUID.randomUUID().toString(),
                        tabId = "",
                        title = prompt.title,
                        message = prompt.message.orEmpty(),
                        onDismiss = {
                            if (resolved.compareAndSet(false, true)) {
                                result.complete(prompt.dismiss())
                            }
                        },
                    ),
                )
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val resolved = AtomicBoolean(false)
                callbacks.onWebPrompt(
                    self,
                    WebDialogPrompt.Confirm(
                        id = UUID.randomUUID().toString(),
                        tabId = "",
                        title = prompt.title,
                        message = prompt.message.orEmpty(),
                        onDecision = { accepted ->
                            if (resolved.compareAndSet(false, true)) {
                                val btn = if (accepted) {
                                    GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
                                } else {
                                    GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE
                                }
                                result.complete(prompt.confirm(btn))
                            }
                        },
                    ),
                )
                return result
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val resolved = AtomicBoolean(false)
                callbacks.onWebPrompt(
                    self,
                    WebDialogPrompt.Confirm(
                        id = UUID.randomUUID().toString(),
                        tabId = "",
                        title = "Rời khỏi trang này?",
                        message = "Các thay đổi bạn vừa nhập có thể chưa được lưu.",
                        positiveLabel = "Rời trang",
                        negativeLabel = "Ở lại",
                        onDecision = { leave ->
                            if (resolved.compareAndSet(false, true)) {
                                result.complete(prompt.confirm(if (leave) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
                            }
                        },
                    ),
                )
                return result
            }

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val resolved = AtomicBoolean(false)
                callbacks.onWebPrompt(
                    self,
                    WebDialogPrompt.Confirm(
                        id = UUID.randomUUID().toString(),
                        tabId = "",
                        title = "Gửi lại biểu mẫu?",
                        message = "Trang này cần gửi lại dữ liệu biểu mẫu trước đó để làm mới.",
                        positiveLabel = "Gửi lại",
                        negativeLabel = "Hủy",
                        onDecision = { allow ->
                            if (resolved.compareAndSet(false, true)) {
                                result.complete(prompt.confirm(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY))
                            }
                        },
                    ),
                )
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val resolved = AtomicBoolean(false)
                callbacks.onWebPrompt(
                    self,
                    WebDialogPrompt.TextInput(
                        id = UUID.randomUUID().toString(),
                        tabId = "",
                        title = prompt.title,
                        message = prompt.message.orEmpty(),
                        defaultValue = prompt.defaultValue.orEmpty(),
                        onConfirm = { input ->
                            if (resolved.compareAndSet(false, true)) {
                                result.complete(prompt.confirm(input))
                            }
                        },
                        onDismiss = {
                            if (resolved.compareAndSet(false, true)) {
                                result.complete(prompt.dismiss())
                            }
                        },
                    ),
                )
                return result
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val resolved = AtomicBoolean(false)
                val isMultiple = prompt.type == GeckoSession.PromptDelegate.ChoicePrompt.Type.MULTIPLE
                val flattened = flattenChoices(prompt.choices)
                callbacks.onWebPrompt(
                    self,
                    WebDialogPrompt.ChoiceSelect(
                        id = UUID.randomUUID().toString(),
                        tabId = "",
                        title = prompt.title ?: prompt.message,
                        isMultiple = isMultiple,
                        options = flattened,
                        onConfirmIds = { selectedIds ->
                            if (resolved.compareAndSet(false, true)) {
                                if (isMultiple) {
                                    result.complete(prompt.confirm(selectedIds.toTypedArray()))
                                } else {
                                    val first = selectedIds.firstOrNull()
                                    if (first != null) {
                                        result.complete(prompt.confirm(first))
                                    } else {
                                        result.complete(prompt.dismiss())
                                    }
                                }
                            }
                        },
                        onDismiss = {
                            if (resolved.compareAndSet(false, true)) {
                                result.complete(prompt.dismiss())
                            }
                        },
                    ),
                )
                return result
            }
        }

        geckoSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback,
            ) {
                val permList = permissions?.toList().orEmpty()
                if (permList.isEmpty()) {
                    callback.reject()
                    return
                }
                val resolved = AtomicBoolean(false)
                callbacks.onAndroidPermissionsRequest(self, permList) { allow ->
                    if (resolved.compareAndSet(false, true)) {
                        if (allow) callback.grant() else callback.reject()
                    }
                }
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission,
            ): GeckoResult<Int> {
                val mappedType = when (perm.permission) {
                    GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> SitePermissionType.GEOLOCATION
                    GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> SitePermissionType.NOTIFICATION
                    GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> SitePermissionType.PERSISTENT_STORAGE
                    GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> {
                        return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                    }
                    else -> {
                        return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
                    }
                }
                val result = GeckoResult<Int>()
                val resolved = AtomicBoolean(false)
                callbacks.onContentPermission(self, perm.uri, mappedType) { decision ->
                    if (resolved.compareAndSet(false, true)) {
                        val value = if (decision == SitePermissionDecision.ALLOW) {
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                        } else {
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                        }
                        result.complete(value)
                    }
                }
                return result
            }

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback,
            ) {
                val needsCam = !video.isNullOrEmpty()
                val needsMic = !audio.isNullOrEmpty()
                if (!needsCam && !needsMic) {
                    callback.reject()
                    return
                }
                val resolved = AtomicBoolean(false)
                callbacks.onMediaPermission(self, uri, needsCam, needsMic) { allow ->
                    if (resolved.compareAndSet(false, true)) {
                        if (allow) {
                            callback.grant(video?.firstOrNull(), audio?.firstOrNull())
                        } else {
                            callback.reject()
                        }
                    }
                }
            }
        }
    }

    private fun notifyIfEngineOpened(callbacks: EngineSessionCallbacks) {
        if (geckoSession.isOpen) {
            callbacks.onPopupSessionOpened(this)
        }
    }

    private fun flattenChoices(
        choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>,
    ): List<WebChoiceOption> {
        val out = ArrayList<WebChoiceOption>(choices.size)
        for (choice in choices) {
            if (choice.separator) continue
            val subItems = choice.items
            if (subItems != null) {
                out.add(
                    WebChoiceOption(
                        id = choice.id,
                        label = choice.label,
                        disabled = true,
                        selected = false,
                        isGroupHeader = true,
                    ),
                )
                for (sub in subItems) {
                    if (!sub.separator) {
                        out.add(
                            WebChoiceOption(
                                id = sub.id,
                                label = sub.label,
                                disabled = choice.disabled || sub.disabled,
                                selected = sub.selected,
                                isGroupHeader = false,
                            ),
                        )
                    }
                }
            } else {
                out.add(
                    WebChoiceOption(
                        id = choice.id,
                        label = choice.label,
                        disabled = choice.disabled,
                        selected = choice.selected,
                        isGroupHeader = false,
                    ),
                )
            }
        }
        return out
    }
}

private fun WebResponse.headerValueIgnoreCase(targetKey: String): String? {
    for ((k, v) in headers) {
        if (k.equals(targetKey, ignoreCase = true)) return v
    }
    return null
}

private class GeckoEngineViewHandle(
    geckoView: GeckoView,
    private val mainHandler: Handler,
) : EngineViewHandle {

    override val engineType: EngineType = EngineType.GECKO

    // Luôn bọc GeckoView trong WeakReference để không bao giờ giữ sống Activity cũ
    private val viewRef = WeakReference(geckoView)

    override val identity: Any?
        get() = viewRef.get()

    override val isAlive: Boolean
        get() = viewRef.get() != null

    override fun setSession(session: EngineSessionHandle) {
        val view = viewRef.get() ?: return
        val geckoHandle = session as? GeckoEngineSessionHandle ?: return
        // Gắn thanh chọn văn bản gốc của Android (Sao chép/Chia sẻ/Chọn tất cả) chỉ khi phiên đang gắn với View hiện hành
        findActivity(view.context)?.let { activity ->
            geckoHandle.geckoSession.selectionActionDelegate = BasicSelectionActionDelegate(activity)
        }
        if (view.session === geckoHandle.geckoSession) return
        if (view.session != null) {
            view.session?.selectionActionDelegate = null
            view.releaseSession()
        }
        view.setSession(geckoHandle.geckoSession)
    }

    override fun releaseSession() {
        val view = viewRef.get() ?: return
        view.session?.selectionActionDelegate = null
        view.releaseSession()
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

    private fun findActivity(context: Context?): Activity? {
        var cur = context
        while (cur is ContextWrapper) {
            if (cur is Activity) return cur
            cur = cur.baseContext
        }
        return null
    }
}
