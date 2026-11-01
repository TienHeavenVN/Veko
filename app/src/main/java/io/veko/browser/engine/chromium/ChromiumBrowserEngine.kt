package io.veko.browser.engine.chromium

import android.content.ComponentCallbacks2
import android.content.Context
import android.view.View
import dagger.hilt.android.qualifiers.ApplicationContext
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.engine.BrowserEngine
import io.veko.browser.engine.EngineAvailability
import io.veko.browser.engine.EngineCapabilities
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.JavaScriptEvalResult
import io.veko.browser.engine.JavaScriptEvaluationPolicy
import io.veko.browser.tab.EngineSessionCallbacks
import io.veko.browser.tab.EngineSessionHandle
import io.veko.browser.tab.EngineViewHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nhân trình duyệt thứ cấp của Veko dựa trên Chromium Content API `156.0.8078.85`
 * (`0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5`, Rendering: Blink, JavaScript: V8).
 *
 * Bất biến trung thực (Anti-Fake Policy):
 * - Luôn kiểm tra qua [ChromiumAvailabilityChecker] trước khi cho phép `initialize()`, `createPlatformView()`
 *   hoặc `createSession()`.
 * - Khi gói SDK native chưa được nhập/xác thực, giữ trạng thái `BLOCKED` / `UNAVAILABLE` rõ ràng,
 *   tuyệt đối không giả lập bằng Android System WebView hoặc giao diện giả.
 */
@Singleton
class ChromiumBrowserEngine internal constructor(
    private val appFilesDir: File?,
    private val availabilityChecker: ChromiumAvailabilityChecker,
    private val sessionManager: ChromiumSessionManager,
) : BrowserEngine {

    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        availabilityChecker: ChromiumAvailabilityChecker,
        sessionManager: ChromiumSessionManager,
    ) : this(
        appFilesDir = appContext.filesDir,
        availabilityChecker = availabilityChecker,
        sessionManager = sessionManager,
    )

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    @Volatile
    private var currentCookieMode: CookieControlMode = CookieControlMode.BLOCK_THIRD_PARTY

    override val engineType: EngineType = EngineType.CHROMIUM

    override val capabilities: EngineCapabilities
        get() = EngineCapabilities.defaultForChromium(
            fullUpstreamLinked = checkAvailability().isSelectable,
        )

    override fun checkAvailability(): EngineAvailability =
        availabilityChecker.checkAvailability()

    override fun initialize(): Boolean {
        val availability = checkAvailability()
        if (!availability.isSelectable) {
            return false
        }
        if (_isInitialized.value) {
            return true
        }
        val initializedOk = sessionManager.initializeRuntime()
        _isInitialized.value = initializedOk
        return initializedOk
    }

    override fun destroy() {
        if (!_isInitialized.value && sessionManager.activeSessionCount() == 0) {
            return
        }
        sessionManager.shutdown()
        _isInitialized.value = false
    }

    override fun onLowMemory(level: Int) {
        if (!_isInitialized.value) return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            if (sessionManager.activePrivateSessionCount() == 0) {
                sessionManager.releasePrivateContextIfUnused(activePrivateTabsForEngine = 0)
            }
        }
    }

    override fun createPlatformView(context: Context): View {
        requireVerifiedSdk("createPlatformView")
        initialize()
        return sessionManager.createPlatformView(context)
    }

    override fun wrapPlatformView(platformView: View): EngineViewHandle {
        requireVerifiedSdk("wrapPlatformView")
        return sessionManager.wrapPlatformView(platformView)
    }

    override fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle {
        requireVerifiedSdk("createSession")
        initialize()
        return sessionManager.createSession(
            isPrivate = isPrivate,
            isDesktopMode = isDesktopMode,
            openImmediately = openImmediately,
            callbacks = callbacks,
        )
    }

    override fun activeSessionCount(): Int = sessionManager.activeSessionCount()

    override fun activePrivateSessionCount(): Int = sessionManager.activePrivateSessionCount()

    override fun releasePrivateContextIfUnused(activePrivateTabsForEngine: Int) {
        if (!_isInitialized.value && sessionManager.activePrivateSessionCount() == 0) return
        sessionManager.releasePrivateContextIfUnused(activePrivateTabsForEngine)
    }

    override fun clearBrowsingStorage(
        includeCookies: Boolean,
        includeCache: Boolean,
        onlyPrivate: Boolean,
    ) {
        sessionManager.clearIsolatedStorage(
            appFilesDir = appFilesDir,
            includeCookies = includeCookies,
            includeCache = includeCache,
            onlyPrivate = onlyPrivate,
        )
    }

    override fun applyCookiePolicy(mode: CookieControlMode) {
        currentCookieMode = mode
    }

    override fun applyPageZoom(autoFontSize: Boolean, zoomPercent: Int) {
        if (!_isInitialized.value) return
        sessionManager.applyPageZoomSettings(autoFontSize, zoomPercent)
    }

    override fun evaluateJavaScript(
        session: EngineSessionHandle,
        currentUrl: String,
        script: String,
        onResult: (JavaScriptEvalResult) -> Unit,
    ) {
        JavaScriptEvaluationPolicy.validate(currentUrl = currentUrl, script = script)?.let { blocked ->
            onResult(blocked)
            return
        }
        session.evaluateJavaScript(
            currentUrl = currentUrl,
            script = script,
            onResult = onResult,
        )
    }

    override fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        sessionManager.setOnRuntimeShutdownListener {
            _isInitialized.value = false
            listener?.invoke()
        }
    }

    private fun requireVerifiedSdk(operation: String) {
        val availability = checkAvailability()
        check(availability.isSelectable) {
            "ChromiumBrowserEngine.$operation blocked: status=${availability.status}, " +
                "validation=${availability.sdkValidationStatus}, reason=${availability.unavailabilityReason}"
        }
    }
}
