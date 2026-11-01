package io.veko.browser.engine.gecko

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import io.veko.browser.BuildConfig
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
import io.veko.browser.tab.EngineSessionBridge
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
 * Triển khai browser engine duy nhất của Veko Browser dựa trên Mozilla GeckoView chính thức:
 * - Rendering Engine: Gecko (WebRender GPU Compositor)
 * - JavaScript Engine: SpiderMonkey
 * - Hỗ trợ đầy đủ cô lập Private Browsing (`usePrivateMode`), khôi phục trạng thái phiên,
 *   ETP Strict + GPC, chọn tệp SAF gốc (`onFilePrompt`) và tự động phục hồi khi content process dừng.
 */
@Singleton
class GeckoBrowserEngine internal constructor(
    private val runtimeManager: BrowserRuntimeManager,
    private val sessionManager: GeckoSessionManager,
    private val deviceAbiProvider: () -> List<String>,
    private val geckoSdkClassPresent: () -> Boolean,
    private val nativeLibraryDirProvider: () -> File?,
) : BrowserEngine, EngineSessionBridge, ComponentCallbacks2 {

    @Inject
    constructor(
        @dagger.hilt.android.qualifiers.ApplicationContext appContext: Context,
        runtimeManager: BrowserRuntimeManager,
        sessionManager: GeckoSessionManager,
    ) : this(
        runtimeManager = runtimeManager,
        sessionManager = sessionManager,
        deviceAbiProvider = {
            Build.SUPPORTED_ABIS?.filter { it.isNotBlank() }.orEmpty().ifEmpty { listOf("arm64-v8a") }
        },
        geckoSdkClassPresent = {
            try {
                Class.forName("org.mozilla.geckoview.GeckoRuntime")
                Class.forName("org.mozilla.geckoview.GeckoSession")
                true
            } catch (_: Throwable) {
                false
            }
        },
        nativeLibraryDirProvider = {
            appContext.applicationInfo?.nativeLibraryDir?.let(::File)
        },
    )

    override val engineType: EngineType = EngineType.GECKO

    override val capabilities: EngineCapabilities = EngineCapabilities.defaultForGecko()

    override val isInitialized: StateFlow<Boolean>
        get() = runtimeManager.isInitialized

    override val isEngineInitialized: StateFlow<Boolean>
        get() = runtimeManager.isInitialized

    private val _engineAvailability = MutableStateFlow<EngineAvailability?>(checkAvailability())
    override val engineAvailability: StateFlow<EngineAvailability?> = _engineAvailability.asStateFlow()

    override fun refreshAvailability(): EngineAvailability {
        val fresh = checkAvailability()
        _engineAvailability.value = fresh
        return fresh
    }

    override fun getAvailability(): EngineAvailability =
        _engineAvailability.value ?: refreshAvailability()

    override fun getCapabilities(): EngineCapabilities = capabilities

    override fun checkAvailability(): EngineAvailability {
        val deviceAbis = deviceAbiProvider()
        val abiOk = deviceAbis.any { it in capabilities.supportedAbis }
        val sdkPresent = geckoSdkClassPresent()
        val nativeDir = nativeLibraryDirProvider()
        val nativeSoVerified = if (nativeDir != null && nativeDir.isDirectory && !nativeDir.list().isNullOrEmpty()) {
            File(nativeDir, "libxul.so").isFile && File(nativeDir, "libmozglue.so").isFile
        } else {
            // Trên JVM Unit Test hoặc cài đặt App Bundle split-APK nén trực tiếp từ base APK
            true
        }

        if (!abiOk) {
            return EngineAvailability(
                engineType = EngineType.GECKO,
                engineName = EngineType.GECKO.displayName,
                engineVersion = BuildConfig.GECKOVIEW_VERSION,
                status = EngineAvailabilityStatus.UNAVAILABLE,
                renderingEngine = EngineType.GECKO.renderingEngineName,
                javascriptEngine = EngineType.GECKO.javascriptEngineName,
                deviceAbis = deviceAbis,
                abiCompatible = false,
                sdkValidationStatus = SdkValidationStatus.ABI_INCOMPATIBLE,
                isSelectable = false,
                unavailabilityReason = "Kiến trúc CPU thiết bị (${deviceAbis.joinToString()}) không nằm trong tập ABI được hỗ trợ (${capabilities.supportedAbis.joinToString()}).",
            )
        }

        if (!sdkPresent || !nativeSoVerified) {
            val missing = buildList {
                if (!sdkPresent) add("org.mozilla.geckoview.GeckoRuntime")
                if (!nativeSoVerified) {
                    add("libxul.so")
                    add("libmozglue.so")
                }
            }
            return EngineAvailability(
                engineType = EngineType.GECKO,
                engineName = EngineType.GECKO.displayName,
                engineVersion = BuildConfig.GECKOVIEW_VERSION,
                status = EngineAvailabilityStatus.UNAVAILABLE,
                renderingEngine = EngineType.GECKO.renderingEngineName,
                javascriptEngine = EngineType.GECKO.javascriptEngineName,
                deviceAbis = deviceAbis,
                abiCompatible = true,
                sdkValidationStatus = SdkValidationStatus.INVALID_ARTIFACT_PROVENANCE,
                isSelectable = false,
                unavailabilityReason = "Không tìm thấy gói Mozilla GeckoView hợp lệ trên thiết bị.",
                missingArtifacts = missing,
            )
        }

        return EngineAvailability(
            engineType = EngineType.GECKO,
            engineName = EngineType.GECKO.displayName,
            engineVersion = BuildConfig.GECKOVIEW_VERSION,
            status = EngineAvailabilityStatus.AVAILABLE,
            renderingEngine = EngineType.GECKO.renderingEngineName,
            javascriptEngine = EngineType.GECKO.javascriptEngineName,
            deviceAbis = deviceAbis,
            abiCompatible = true,
            sdkValidationStatus = SdkValidationStatus.VERIFIED_MAVEN_SDK,
            isSelectable = true,
        )
    }

    override fun initialize(): Boolean {
        if (!checkAvailability().isSelectable) return false
        return runCatching {
            runtimeManager.getOrCreateRuntime()
            true
        }.getOrDefault(false)
    }

    override fun destroy() {
        sessionManager.closeAllSessions()
        runtimeManager.shutdown()
    }

    override fun onLowMemory(level: Int) {
        runtimeManager.onLowMemory(level)
    }

    override fun onTrimMemory(level: Int) {
        onLowMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        runtimeManager.onConfigurationChanged(newConfig)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        onLowMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
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

    override fun releasePrivateContextIfUnused(activePrivateTabs: Int) {
        sessionManager.releasePrivateSessionsIfUnused(activePrivateTabs)
    }

    override fun clearBrowsingStorage(
        includeCookies: Boolean,
        includeCache: Boolean,
        onlyPrivate: Boolean,
    ) {
        if (onlyPrivate) {
            sessionManager.releasePrivateSessionsIfUnused(0)
            return
        }
        if (!runtimeManager.isInitialized.value) return
        runtimeManager.clearStorageData(
            includeCookies = includeCookies,
            includeCache = includeCache,
        )
    }

    override fun clearEngineStorage(
        includeCookies: Boolean,
        includeCache: Boolean,
        onlyPrivate: Boolean,
    ) {
        clearBrowsingStorage(
            includeCookies = includeCookies,
            includeCache = includeCache,
            onlyPrivate = onlyPrivate,
        )
    }

    override fun applyCookiePolicy(mode: CookieControlMode) {
        runtimeManager.applyCookiePolicy(mode)
    }

    override fun applyPageZoom(autoFontSize: Boolean, zoomPercent: Int) {
        runtimeManager.applyPageZoomSettings(autoFontSize, zoomPercent)
    }

    override fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        applyPageZoom(autoFontSize, zoomPercent)
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
}
