package io.veko.browser.testing

import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.mvi.BrowserMviAction
import io.veko.browser.mvi.BrowserMviState
import io.veko.browser.mvi.BrowserReducer
import io.veko.browser.privacy.FilterListManager
import io.veko.browser.privacy.PrivacyPolicyConfig
import io.veko.browser.privacy.PrivacyShieldEngine
import io.veko.browser.privacy.nativebridge.ShieldPipelineAction
import io.veko.browser.privacy.nativebridge.VekoAdblockNative
import io.veko.browser.security.MixedContentDecision
import io.veko.browser.security.TlsCertificateError
import io.veko.browser.security.TlsCertificatePolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Bộ kiểm thử tích hợp xuyên module (:privacy + :security + :storage + :browser-core + :testing):
 * Xác minh toàn bộ đường ống gốc C++20 JNI (`libveko_adblock_jni.so`) + Rust `brave/adblock-rust` 0.13.4,
 * chính sách TLS không cho phép vượt rào, tùy chỉnh quy tắc lọc nóng và hàm chuyển trạng thái thuần khiết MVI.
 */
class VekoNativePrivacyAndMviIntegrationTest {

    @Test
    fun `Native C++20 and Rust adblock-rust pipeline blocks ads, trackers, mixed content and strips tracking params`() {
        assertTrue("Host libveko_adblock_jni.so must be loaded", VekoAdblockNative.isNativeLibraryLoaded)
        val shield = PrivacyShieldEngine()
        shield.resetTelemetry()

        val adDecision = shield.evaluateRequest(
            requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            topFrameUrl = "https://vnexpress.net/",
            resourceType = "script",
        )
        assertTrue(adDecision.isBlocked)
        assertEquals(ShieldPipelineAction.BLOCK_AD, adDecision.action)

        val trackerDecision = shield.evaluateRequest(
            requestUrl = "https://www.google-analytics.com/analytics.js",
            topFrameUrl = "https://mozilla.org/",
            resourceType = "script",
        )
        assertTrue(trackerDecision.isBlocked)
        assertEquals(ShieldPipelineAction.BLOCK_TRACKER, trackerDecision.action)

        val cleanedDecision = shield.evaluateRequest(
            requestUrl = "https://example.com/news?id=101&utm_source=facebook&fbclid=IwAR12345&gclid=999",
            topFrameUrl = "https://example.com/",
            resourceType = "document",
        )
        assertFalse(cleanedDecision.isBlocked)
        assertEquals(ShieldPipelineAction.REWRITE_CLEAN_URL, cleanedDecision.action)
        assertEquals("https://example.com/news?id=101", cleanedDecision.effectiveUrl)

        val normalSite = shield.evaluateRequest(
            requestUrl = "https://developer.android.com/jetpack/compose",
            topFrameUrl = "https://developer.android.com/",
            resourceType = "document",
        )
        assertFalse(normalSite.isBlocked)
        assertEquals(ShieldPipelineAction.ALLOW, normalSite.action)

        val mixedScript = shield.evaluateRequest(
            requestUrl = "http://insecure-cdn.example.net/bundle.js",
            topFrameUrl = "https://secure-bank.example.com/",
            resourceType = "script",
        )
        assertTrue(mixedScript.isBlocked)
        assertEquals(ShieldPipelineAction.BLOCK_MIXED_CONTENT, mixedScript.action)

        val snap = shield.refreshTelemetry()
        assertTrue(snap.adsBlocked >= 1L)
        assertTrue(snap.trackersBlocked >= 1L)
        assertTrue(snap.paramsStripped >= 1L)
        assertTrue(snap.mixedContentBlocked >= 1L)
    }

    @Test
    fun `TLS policy strictly rejects invalid certificates without user bypass and blocks active mixed content`() {
        for (errType in TlsCertificateError.entries) {
            val verdict = TlsCertificatePolicy.evaluateCertificateError(
                errorCode = errType.errorCode,
                rawUrlOrHost = "https://untrusted-root.badssl.com/account",
            )
            assertFalse("Invalid TLS ($errType) must never proceed", verdict.allowProceed)
            assertFalse("Invalid TLS ($errType) must never expose user bypass", verdict.allowUserBypass)
        }
        assertFalse(VekoAdblockNative.evaluateTlsCertificateError(-202, "expired.badssl.com"))
        assertEquals(
            MixedContentDecision.BLOCK_MIXED_CONTENT,
            TlsCertificatePolicy.evaluateMixedContent(
                topFrameUrl = "https://secure.example.org",
                resourceUrl = "http://legacy.example.org/frame.html",
                resourceType = "sub_frame",
                blockAllMixedContent = false,
            ),
        )
    }

    @Test
    fun `Hot-reloading custom Adblock Plus rules into Rust engine updates decisions and MVI reducer stays pure`() = runTest {
        val tempDir = Files.createTempDirectory("veko_mvi_filter_test").toFile()
        try {
            val rulesFile = File(tempDir, "custom_rules.txt")
            val filterManager = FilterListManager(customRulesStorageFile = rulesFile)
            val shield = PrivacyShieldEngine(filterManager)

            val beforeCustom = shield.evaluateRequest(
                requestUrl = "https://custom-ad-tracker.example.org/pixel.js",
                topFrameUrl = "https://reader.example.com/",
                resourceType = "script",
                config = PrivacyPolicyConfig(),
            )
            assertFalse(beforeCustom.isBlocked)

            assertTrue(filterManager.updateCustomRules("||custom-ad-tracker.example.org^"))
            val afterCustom = shield.evaluateRequest(
                requestUrl = "https://custom-ad-tracker.example.org/pixel.js",
                topFrameUrl = "https://reader.example.com/",
                resourceType = "script",
                config = PrivacyPolicyConfig(),
            )
            assertTrue(afterCustom.isBlocked)

            val settingsRepo = SettingsRepository(InMemoryVekoSettingsStore())
            settingsRepo.setCookieControlMode(CookieControlMode.BLOCK_ALL)
            assertEquals(CookieControlMode.BLOCK_ALL, settingsRepo.settingsFlow.first().cookieControlMode)

            val initialState = BrowserMviState()
            val openedSheet = BrowserReducer.reduce(
                initialState,
                BrowserMviAction.UserIntent.SetPrivacySheetVisible(true),
            )
            assertTrue(openedSheet.isPrivacySheetOpen)
            val withNativeDiag = BrowserReducer.reduce(
                openedSheet,
                BrowserMviAction.SystemPatch.NativeShieldDiagnosticsUpdated(isNativeLoaded = true),
            )
            assertTrue(withNativeDiag.isNativeShieldLoaded)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
