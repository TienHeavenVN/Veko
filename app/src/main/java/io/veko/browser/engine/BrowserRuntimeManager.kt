package io.veko.browser.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.data.repository.AutoplayPolicy
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.engine.gecko.GeckoSettings
import io.veko.browser.privacy.PrivacyPolicyConfig
import io.veko.browser.privacy.PrivacyShieldEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quản lý duy nhất một thực thể [GeckoRuntime] trong suốt vòng đời tiến trình ứng dụng.
 *
 * Bất biến hiệu năng & cô lập (Dual-Engine + Privacy Shield):
 * - Chỉ khởi tạo lười (`getOrCreateRuntime`) khi có tab `EngineType.GECKO` thực sự mở.
 * - Nạp tiện ích mở rộng hệ thống tích hợp `veko-shield@veko.browser` để chặn tài nguyên quảng cáo/theo dõi con,
 *   tiêm CSS ẩn phần tử rỗng từ `brave/adblock-rust` và chống thu thập dấu vân tay trình duyệt (Anti-Fingerprinting).
 * - Cô lập hoàn toàn hồ sơ đĩa và bộ nhớ cookie/cache của Mozilla Gecko khỏi `ChromiumBrowserContext`.
 */
@Singleton
class BrowserRuntimeManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val privacyShieldEngine: PrivacyShieldEngine?,
    private val settingsRepository: SettingsRepository?,
) {
    constructor(appContext: Context) : this(appContext, null, null)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var geckoRuntime: GeckoRuntime? = null
    private var shieldExtension: WebExtension? = null
    private var onShutdownListener: (() -> Unit)? = null
    private var pendingAutoFontSize: Boolean = true
    private var pendingZoomFactor: Float = 1.0f
    private var pendingCookieMode: CookieControlMode = CookieControlMode.BLOCK_THIRD_PARTY

    @Volatile
    private var latestSettings: VekoSettings = VekoSettings()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    val isRuntimeCreated: Boolean
        get() = geckoRuntime != null

    init {
        settingsRepository?.let { repo ->
            runtimeScope.launch {
                repo.settingsFlow.collect { settings ->
                    latestSettings = settings
                    val runtime = geckoRuntime ?: return@collect
                    runtime.settings.contentBlocking
                        .setEnhancedTrackingProtectionLevel(
                            GeckoSettings.mapEtpLevel(settings.trackerBlockEnabled, settings.strictAntiTracking),
                        )
                        .setAntiTracking(
                            GeckoSettings.mapAntiTrackingCategories(
                                settings.trackerBlockEnabled,
                                settings.strictAntiTracking,
                            ),
                        )
                        .setCookieBannerMode(
                            GeckoSettings.mapCookieBannerMode(
                                settings.trackerBlockEnabled,
                                settings.strictAntiTracking,
                            ),
                        )
                        .setCookieBannerGlobalRulesEnabled(
                            settings.trackerBlockEnabled && settings.strictAntiTracking,
                        )
                }
            }
        }
    }

    fun currentAutoplayPolicy(): AutoplayPolicy = latestSettings.autoplayPolicy

    fun isShieldActiveForUrl(rawUrl: String, isPrivate: Boolean): Boolean {
        val shield = privacyShieldEngine ?: return true
        val s = latestSettings
        if (!s.adBlockEnabled && !s.trackerBlockEnabled) return false
        return !shield.isSiteShieldPaused(rawUrl, s.trustedShieldHosts, isOffTheRecord = isPrivate)
    }

    fun recordGeckoContentBlocked(
        blockedUri: String,
        antiTrackingCategory: Int,
        isPrivate: Boolean,
    ) {
        val shield = privacyShieldEngine ?: return
        val isFingerprinterOrMiner =
            (antiTrackingCategory and ContentBlocking.AntiTracking.FINGERPRINTING) != 0 ||
                (antiTrackingCategory and ContentBlocking.AntiTracking.CRYPTOMINING) != 0
        val isAd = (antiTrackingCategory and ContentBlocking.AntiTracking.AD) != 0
        val isTracker = (antiTrackingCategory and (
            ContentBlocking.AntiTracking.ANALYTIC or
                ContentBlocking.AntiTracking.SOCIAL or
                ContentBlocking.AntiTracking.CONTENT or
                ContentBlocking.AntiTracking.STP
            )) != 0
        shield.recordEngineSubresourceBlock(
            blockedUri = blockedUri,
            isAd = isAd,
            isTracker = isTracker,
            isFingerprinterOrMiner = isFingerprinterOrMiner,
            isOffTheRecord = isPrivate,
        )
    }

    @MainThread
    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        onShutdownListener = listener
    }

    @MainThread
    fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        checkMainThread("applyPageZoomSettings")
        val factor = (zoomPercent.coerceIn(75, 150) / 100f)
        pendingAutoFontSize = autoFontSize
        pendingZoomFactor = factor
        val runtime = geckoRuntime ?: return
        val settings = runtime.settings
        if (autoFontSize && zoomPercent == 100) {
            settings.setAutomaticFontSizeAdjustment(true)
        } else {
            settings.setAutomaticFontSizeAdjustment(false)
            settings.setFontSizeFactor(factor)
        }
    }

    @MainThread
    fun applyCookiePolicy(mode: CookieControlMode) {
        checkMainThread("applyCookiePolicy")
        pendingCookieMode = mode
        val runtime = geckoRuntime ?: return
        runtime.settings.contentBlocking
            .setCookieBehavior(GeckoSettings.mapRegularCookieBehavior(mode))
            .setCookieBehaviorPrivateMode(GeckoSettings.mapPrivateCookieBehavior(mode))
    }

    @MainThread
    fun clearStorageData(
        includeCookies: Boolean,
        includeCache: Boolean,
    ) {
        checkMainThread("clearStorageData")
        val runtime = geckoRuntime ?: return
        var flags = 0L
        if (includeCookies) {
            flags = flags or
                StorageController.ClearFlags.COOKIES or
                StorageController.ClearFlags.DOM_STORAGES or
                StorageController.ClearFlags.AUTH_SESSIONS
        }
        if (includeCache) {
            flags = flags or StorageController.ClearFlags.ALL_CACHES
        }
        if (flags != 0L) {
            runCatching { runtime.storageController.clearData(flags) }
        }
    }

    @MainThread
    fun onLowMemory(level: Int) {
        checkMainThread("onLowMemory")
        val runtime = geckoRuntime ?: return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            runCatching {
                runtime.storageController.clearData(StorageController.ClearFlags.IMAGE_CACHE)
            }
        }
    }

    /**
     * Khởi tạo lười (lazy) trên UI thread ngay khi tab `GECKO` đầu tiên cần mở session.
     */
    @MainThread
    fun getOrCreateRuntime(): GeckoRuntime {
        checkMainThread("getOrCreateRuntime")
        geckoRuntime?.let { return it }

        val s = latestSettings
        val runtimeSettings = GeckoSettings.buildRuntimeSettings(
            cookieMode = pendingCookieMode,
            autoFontSize = pendingAutoFontSize,
            zoomFactor = pendingZoomFactor,
            trackerBlockEnabled = s.trackerBlockEnabled,
            strictAntiTracking = s.strictAntiTracking,
        )

        return GeckoRuntime.create(appContext, runtimeSettings).also { created ->
            created.delegate = GeckoRuntime.Delegate {
                Log.w(TAG, "GeckoRuntime stopped (onShutdown).")
                geckoRuntime = null
                shieldExtension = null
                _isInitialized.value = false
                onShutdownListener?.invoke()
            }
            geckoRuntime = created
            _isInitialized.value = true
            installBuiltInShieldExtension(created)
        }
    }

    private fun installBuiltInShieldExtension(runtime: GeckoRuntime) {
        val shield = privacyShieldEngine ?: return
        runCatching {
            runtime.webExtensionController
                .ensureBuiltIn(SHIELD_EXTENSION_URI, SHIELD_EXTENSION_ID)
                .withHandler(mainHandler)
                .accept(
                    { ext ->
                        if (ext != null) {
                            shieldExtension = ext
                            ext.setMessageDelegate(buildShieldMessageDelegate(shield), SHIELD_NATIVE_APP)
                        }
                    },
                    { err ->
                        Log.w(TAG, "Built-in Veko Shield extension install skipped: ${err?.message}")
                    },
                )
        }
    }

    private fun buildShieldMessageDelegate(shield: PrivacyShieldEngine): WebExtension.MessageDelegate {
        return object : WebExtension.MessageDelegate {
            override fun onMessage(
                nativeApp: String,
                message: Any,
                sender: WebExtension.MessageSender,
            ): GeckoResult<Any>? {
                if (nativeApp != SHIELD_NATIVE_APP) return null
                val json = message as? JSONObject ?: return null
                val msgType = json.optString("type", "")
                val s = latestSettings
                val policy = PrivacyPolicyConfig(
                    adBlockEnabled = s.adBlockEnabled,
                    trackerBlockEnabled = s.trackerBlockEnabled,
                    strictAntiTracking = s.strictAntiTracking,
                    stripTrackingParamsEnabled = s.stripTrackingParamsEnabled,
                    httpsUpgradeEnabled = s.httpsUpgradeEnabled,
                    blockMixedContent = s.blockMixedContent,
                    cookieControlMode = s.cookieControlMode,
                    trustedHosts = s.trustedShieldHosts,
                )

                return when (msgType) {
                    "evaluateSubresource" -> {
                        val reqUrl = json.optString("url", "")
                        val topUrl = json.optString("topFrameUrl", "")
                        val rType = mapWebExtResourceType(json.optString("resourceType", "other"))
                        val method = json.optString("method", "GET")
                        val incognito = json.optBoolean("incognito", false)

                        val decision = shield.evaluateRequest(
                            requestUrl = reqUrl,
                            topFrameUrl = topUrl,
                            resourceType = rType,
                            httpMethod = method,
                            config = policy,
                            isOffTheRecord = incognito,
                        )
                        val response = JSONObject().apply {
                            put("cancel", decision.isBlocked)
                            if (decision.action == NativePipelineAction.REWRITE_CLEAN_URL ||
                                decision.action == NativePipelineAction.UPGRADE_TO_HTTPS
                            ) {
                                put("redirectUrl", decision.effectiveUrl)
                            }
                        }
                        GeckoResult.fromValue(response)
                    }

                    "pageShield" -> {
                        val pageUrl = json.optString("url", "")
                        val incognito = json.optBoolean("incognito", false)
                        val paused = shield.isSiteShieldPaused(
                            pageUrlOrHost = pageUrl,
                            persistedTrustedHosts = s.trustedShieldHosts,
                            isOffTheRecord = incognito,
                        )
                        val cosmeticCss = if (!paused && s.adBlockEnabled) {
                            shield.getCosmeticCssForPage(pageUrl, policy, isOffTheRecord = incognito)
                        } else {
                            ""
                        }
                        val response = JSONObject().apply {
                            put("paused", paused)
                            put("cosmeticCss", cosmeticCss)
                            put("antiTracking", !paused && s.trackerBlockEnabled)
                            put("strictAntiTracking", !paused && s.trackerBlockEnabled && s.strictAntiTracking)
                            put("stripParams", !paused && s.stripTrackingParamsEnabled)
                        }
                        GeckoResult.fromValue(response)
                    }

                    else -> null
                }
            }
        }
    }

    private fun mapWebExtResourceType(raw: String): String = when (raw.lowercase()) {
        "sub_frame" -> "subdocument"
        "imageset" -> "image"
        "beacon" -> "ping"
        else -> raw.lowercase()
    }

    @MainThread
    fun shutdown() {
        checkMainThread("shutdown")
        val runtime = geckoRuntime ?: return
        geckoRuntime = null
        shieldExtension = null
        _isInitialized.value = false
        runCatching { runtime.shutdown() }
    }

    @MainThread
    fun onConfigurationChanged(newConfig: Configuration) {
        checkMainThread("onConfigurationChanged")
        geckoRuntime?.configurationChanged(newConfig)
    }

    private fun checkMainThread(methodName: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BrowserRuntimeManager.$methodName() bắt buộc gọi trên Android Main/UI Thread."
        }
    }

    private companion object {
        const val TAG = "BrowserRuntimeManager"
        const val SHIELD_EXTENSION_URI = "resource://android/assets/extensions/veko_shield/"
        const val SHIELD_EXTENSION_ID = "veko-shield@veko.browser"
        const val SHIELD_NATIVE_APP = "vekoShield"
    }
}
