package io.veko.browser.engine.gecko

import io.veko.browser.BuildConfig
import io.veko.browser.data.repository.CookieControlMode
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSessionSettings

/**
 * Cấu hình bảo mật và quyền riêng tư tập trung cho nhân Mozilla GeckoView
 * (Gecko + SpiderMonkey từ kho chính thức `maven.mozilla.org`).
 *
 * Bất biến bảo mật:
 * - Xác thực TLS nghiêm ngặt (`enterpriseRootsEnabled(false)`, không bao giờ bỏ qua lỗi chứng chỉ).
 * - Tắt `about:config`, tắt `remoteDebugging`, tắt xuất console/debug log để tránh rò rỉ URL/token/cookie.
 * - Bật Global Privacy Control (`Sec-GPC: 1`), Enhanced Tracking Protection (Strict ETP + Anti-Fingerprinting),
 *   Cookie Banner Auto-Reject, SafeBrowsing và cô lập cookie dFPI.
 */
object GeckoSettings {

    val GECKOVIEW_VERSION: String = BuildConfig.GECKOVIEW_VERSION

    fun mapRegularCookieBehavior(mode: CookieControlMode): Int = when (mode) {
        CookieControlMode.ALLOW_ALL -> ContentBlocking.CookieBehavior.ACCEPT_ALL
        CookieControlMode.BLOCK_THIRD_PARTY -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
        CookieControlMode.BLOCK_ALL -> ContentBlocking.CookieBehavior.ACCEPT_NONE
    }

    fun mapPrivateCookieBehavior(mode: CookieControlMode): Int = when (mode) {
        CookieControlMode.BLOCK_ALL -> ContentBlocking.CookieBehavior.ACCEPT_NONE
        CookieControlMode.ALLOW_ALL,
        CookieControlMode.BLOCK_THIRD_PARTY,
        -> ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS
    }

    fun mapEtpLevel(trackerBlockEnabled: Boolean, strictAntiTracking: Boolean): Int = when {
        !trackerBlockEnabled -> ContentBlocking.EtpLevel.NONE
        strictAntiTracking -> ContentBlocking.EtpLevel.STRICT
        else -> ContentBlocking.EtpLevel.DEFAULT
    }

    fun mapAntiTrackingCategories(trackerBlockEnabled: Boolean, strictAntiTracking: Boolean): Int = when {
        !trackerBlockEnabled -> ContentBlocking.AntiTracking.NONE
        strictAntiTracking -> ContentBlocking.AntiTracking.STRICT or ContentBlocking.AntiTracking.STP
        else -> ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.STP
    }

    fun mapCookieBannerMode(trackerBlockEnabled: Boolean, strictAntiTracking: Boolean): Int =
        if (trackerBlockEnabled && strictAntiTracking) {
            ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_REJECT
        } else {
            ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_DISABLED
        }

    fun buildRuntimeSettings(
        cookieMode: CookieControlMode,
        autoFontSize: Boolean,
        zoomFactor: Float,
        trackerBlockEnabled: Boolean = true,
        strictAntiTracking: Boolean = true,
    ): GeckoRuntimeSettings {
        val contentBlocking = ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(mapEtpLevel(trackerBlockEnabled, strictAntiTracking))
            .antiTracking(mapAntiTrackingCategories(trackerBlockEnabled, strictAntiTracking))
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .cookieBehavior(mapRegularCookieBehavior(cookieMode))
            .cookieBehaviorPrivateMode(mapPrivateCookieBehavior(cookieMode))
            .cookieBannerHandlingMode(mapCookieBannerMode(trackerBlockEnabled, strictAntiTracking))
            .cookieBannerHandlingModePrivateBrowsing(ContentBlocking.CookieBannerMode.COOKIE_BANNER_MODE_REJECT)
            .cookieBannerGlobalRulesEnabled(trackerBlockEnabled && strictAntiTracking)
            .build()

        val useAutoFont = autoFontSize && zoomFactor == 1.0f
        return GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking)
            .javaScriptEnabled(true)
            .globalPrivacyControlEnabled(true)
            .enterpriseRootsEnabled(false)
            .remoteDebuggingEnabled(false)
            .consoleOutput(false)
            .debugLogging(false)
            .loginAutofillEnabled(true)
            .automaticFontSizeAdjustment(useAutoFont)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .aboutConfigEnabled(false)
            .build()
            .apply {
                setAllowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE)
                if (!useAutoFont) {
                    setFontSizeFactor(zoomFactor)
                }
            }
    }

    fun buildSessionSettings(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        useTrackingProtection: Boolean = true,
    ): GeckoSessionSettings = GeckoSessionSettings.Builder()
        .usePrivateMode(isPrivate)
        .useTrackingProtection(useTrackingProtection)
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
}
