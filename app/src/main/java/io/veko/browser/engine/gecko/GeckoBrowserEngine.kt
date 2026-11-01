package io.veko.browser.engine.gecko

import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import dagger.hilt.android.qualifiers.ApplicationContext
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.engine.BrowserEngine
import io.veko.browser.engine.BrowserRuntimeManager
import io.veko.browser.engine.EngineAvailability
import io.veko.browser.engine.EngineAvailabilityStatus
import io.veko.browser.engine.EngineCapabilities
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.JavaScriptEvalResult
import io.veko.browser.engine.JavaScriptEvaluationPolicy
import io.veko.browser.engine.SdkValidationStatus
import io.veko.browser.tab.EngineSessionCallbacks
import io.veko.browser.tab.EngineSessionHandle
import io.veko.browser.tab.EngineViewHandle
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nhân trình duyệt mặc định chính thức của Veko dựa trên Mozilla GeckoView Android SDK
 * (`org.mozilla.geckoview:geckoview` từ `maven.mozilla.org`).
 *
 * - Rendering Engine: Gecko (WebRender)
 * - JavaScript Engine: SpiderMonkey
 * - Khởi tạo lười (Lazy): chỉ khởi tạo `GeckoRuntime` khi `initialize()` hoặc `createSession()` được gọi.
 * - Hồ sơ đĩa và bộ nhớ cookie/cache của Gecko hoàn toàn tách biệt khỏi `ChromiumBrowserEngine`.
 */
@Singleton
class GeckoBrowserEngine internal constructor(
    private val appContext: Context?,
    private val runtimeManager: BrowserRuntimeManager,
    private val sessionManager: GeckoSessionManager,
    private val deviceAbisProvider: () -> List<String>,
    private val sdkClassPresenceChecker: (String) -> Boolean,
    private val nativeLibPresenceChecker: (List<String>) -> Boolean,
) : BrowserEngine {

    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        runtimeManager: BrowserRuntimeManager,
        sessionManager: GeckoSessionManager,
    ) : this(
        appContext = appContext,
        runtimeManager = runtimeManager,
        sessionManager = sessionManager,
        deviceAbisProvider = { Build.SUPPORTED_ABIS?.toList().orEmpty() },
        sdkClassPresenceChecker = { fqcn ->
            runCatching {
                Class.forName(fqcn, false, GeckoBrowserEngine::class.java.classLoader)
                true
            }.getOrDefault(false)
        },
        nativeLibPresenceChecker = { abis ->
            probeGeckoNativeLibrary(appContext, abis)
        },
    )

    override val engineType: EngineType = EngineType.GECKO

    override val capabilities: EngineCapabilities = EngineCapabilities.defaultForGecko()

    override val isInitialized: StateFlow<Boolean> = runtimeManager.isInitialized

    override fun checkAvailability(): EngineAvailability {
        val deviceAbis = deviceAbisProvider().ifEmpty { listOf(EngineCapabilities.PRIMARY_TARGET_ABI) }
        val abiCompatible = deviceAbis.any { it in capabilities.supportedAbis }
        val sdkClassesPresent = REQUIRED_GECKO_CLASSES.all(sdkClassPresenceChecker)
        val nativeLibPresent = nativeLibPresenceChecker(deviceAbis)

        if (!abiCompatible) {
            return EngineAvailability(
                engineType = EngineType.GECKO,
                engineName = EngineType.GECKO.displayName,
                engineVersion = GeckoSettings.GECKOVIEW_VERSION,
                status = EngineAvailabilityStatus.UNAVAILABLE,
                renderingEngine = EngineType.GECKO.renderingEngineName,
                javascriptEngine = EngineType.GECKO.javascriptEngineName,
                deviceAbis = deviceAbis,
                abiCompatible = false,
                sdkValidationStatus = SdkValidationStatus.ABI_INCOMPATIBLE,
                isSelectable = false,
                unavailabilityReason = "Thiết bị có kiến trúc CPU (${deviceAbis.joinToString()}) nằm ngoài tập hỗ trợ của GeckoView.",
            )
        }

        if (!sdkClassesPresent || !nativeLibPresent) {
            val missing = buildList {
                if (!sdkClassesPresent) add("org.mozilla.geckoview.GeckoRuntime")
                if (!nativeLibPresent) add("libxul.so / libmozglue.so")
            }
            return EngineAvailability(
                engineType = EngineType.GECKO,
                engineName = EngineType.GECKO.displayName,
                engineVersion = GeckoSettings.GECKOVIEW_VERSION,
                status = EngineAvailabilityStatus.UNAVAILABLE,
                renderingEngine = EngineType.GECKO.renderingEngineName,
                javascriptEngine = EngineType.GECKO.javascriptEngineName,
                deviceAbis = deviceAbis,
                abiCompatible = true,
                sdkValidationStatus = SdkValidationStatus.INVALID_ARTIFACT_PROVENANCE,
                isSelectable = false,
                unavailabilityReason = "Gói Mozilla GeckoView Maven SDK thiếu thành phần bắt buộc: ${missing.joinToString()}.",
                missingArtifacts = missing,
            )
        }

        return EngineAvailability(
            engineType = EngineType.GECKO,
            engineName = EngineType.GECKO.displayName,
            engineVersion = GeckoSettings.GECKOVIEW_VERSION,
            status = EngineAvailabilityStatus.AVAILABLE,
            renderingEngine = EngineType.GECKO.renderingEngineName,
            javascriptEngine = EngineType.GECKO.javascriptEngineName,
            deviceAbis = deviceAbis,
            abiCompatible = true,
            sdkValidationStatus = SdkValidationStatus.VERIFIED_MAVEN_SDK,
            isSelectable = true,
            unavailabilityReason = null,
        )
    }

    override fun initialize(): Boolean {
        val availability = checkAvailability()
        if (!availability.isSelectable) return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runtimeManager.getOrCreateRuntime()
        }
        return true
    }

    override fun destroy() {
        sessionManager.closeAllSessions()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runtimeManager.shutdown()
        }
    }

    override fun onLowMemory(level: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runtimeManager.onLowMemory(level)
        }
    }

    override fun createPlatformView(context: Context): View =
        sessionManager.createPlatformView(context)

    override fun wrapPlatformView(platformView: View): EngineViewHandle =
        sessionManager.wrapPlatformView(platformView)

    override fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle = sessionManager.createSession(
        isPrivate = isPrivate,
        isDesktopMode = isDesktopMode,
        openImmediately = openImmediately,
        callbacks = callbacks,
    )

    override fun activeSessionCount(): Int = sessionManager.activeSessionCount()

    override fun activePrivateSessionCount(): Int = sessionManager.activePrivateSessionCount()

    override fun releasePrivateContextIfUnused(activePrivateTabsForEngine: Int) {
        sessionManager.releasePrivateSessionsIfUnused(activePrivateTabsForEngine)
    }

    override fun clearBrowsingStorage(
        includeCookies: Boolean,
        includeCache: Boolean,
        onlyPrivate: Boolean,
    ) {
        if (onlyPrivate) {
            sessionManager.releasePrivateSessionsIfUnused(activePrivateTabsForEngine = 0)
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runtimeManager.clearStorageData(
                includeCookies = includeCookies,
                includeCache = includeCache,
            )
        }
    }

    override fun applyCookiePolicy(mode: CookieControlMode) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runtimeManager.applyCookiePolicy(mode)
        }
    }

    override fun applyPageZoom(autoFontSize: Boolean, zoomPercent: Int) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            sessionManager.applyPageZoomSettings(autoFontSize, zoomPercent)
        }
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
        sessionManager.setOnRuntimeShutdownListener(listener)
    }

    companion object {
        private val REQUIRED_GECKO_CLASSES = listOf(
            "org.mozilla.geckoview.GeckoRuntime",
            "org.mozilla.geckoview.GeckoSession",
            "org.mozilla.geckoview.GeckoView",
        )

        private fun probeGeckoNativeLibrary(context: Context?, abis: List<String>): Boolean {
            if (context == null) return true
            val appInfo = runCatching { context.applicationInfo }.getOrNull() ?: return true
            val nativeDir = appInfo.nativeLibraryDir?.let(::File)
            if (nativeDir != null && (File(nativeDir, "libmozglue.so").isFile || File(nativeDir, "libxul.so").isFile)) {
                return true
            }
            val apkPath = appInfo.sourceDir?.takeIf { it.isNotBlank() } ?: return true
            return runCatching {
                ZipFile(apkPath).use { zip ->
                    abis.any { abi ->
                        zip.getEntry("lib/$abi/libmozglue.so") != null ||
                            zip.getEntry("lib/$abi/libxul.so") != null
                    }
                }
            }.getOrDefault(true)
        }
    }
}
