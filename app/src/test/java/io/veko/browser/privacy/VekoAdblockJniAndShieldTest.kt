package io.veko.browser.privacy

import io.veko.browser.privacy.nativebridge.ShieldPipelineAction
import io.veko.browser.privacy.nativebridge.VekoAdblockNative
import io.veko.browser.security.MixedContentDecision
import io.veko.browser.security.TlsCertificateError
import io.veko.browser.security.TlsCertificatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Kiểm thử cầu nối gốc `libveko_adblock_jni.so` (C++20 JNI + Rust `brave/adblock-rust` 0.13.4)
 * độc lập hoàn toàn với browser engine, phục vụ kiến trúc GeckoView-only của Veko Browser.
 */
class VekoAdblockJniAndShieldTest {

    @Test
    fun `Native Rust adblock-rust JNI blocks ads, trackers, regional VN ad networks and strips tracking params`() {
        assertTrue("Host libveko_adblock_jni.so must be loaded", VekoAdblockNative.isNativeLibraryLoaded)
        val metadata = VekoAdblockNative.getShieldMetadataJson()
        assertTrue("Expected brave/adblock-rust-0.13.4 in metadata: $metadata", metadata.contains("brave/adblock-rust-0.13.4"))
        assertFalse("Shield metadata must contain zero removed engine references", metadata.lowercase().contains("chromium"))

        val tempDir = Files.createTempDirectory("veko_shield_rules").toFile()
        try {
            val filterManager = FilterListManager(customRulesStorageFile = tempDir.resolve("custom.txt"))
            val shield = PrivacyShieldEngine(filterManager)
            shield.resetTelemetry()

            val adVerdict = shield.evaluateRequest(
                requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                topFrameUrl = "https://news.example.com/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            assertTrue(adVerdict.isBlocked)
            assertEquals(ShieldPipelineAction.BLOCK_AD, adVerdict.action)

            val vnAdVerdict = shield.evaluateRequest(
                requestUrl = "https://adi.admicro.vn/adt/banners/nam2026/popup.js",
                topFrameUrl = "https://vnexpress.net/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            assertTrue(vnAdVerdict.isBlocked)
            assertEquals(ShieldPipelineAction.BLOCK_AD, vnAdVerdict.action)

            val fpVerdict = shield.evaluateRequest(
                requestUrl = "https://cdn.clarity.ms/tag/session-replay.js",
                topFrameUrl = "https://shop.example.com/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            assertTrue(fpVerdict.isBlocked)
            assertTrue(
                fpVerdict.action == ShieldPipelineAction.BLOCK_TRACKER ||
                    fpVerdict.action == ShieldPipelineAction.BLOCK_AD,
            )

            val paramVerdict = shield.evaluateRequest(
                requestUrl = "https://example.org/post?id=99&utm_source=newsletter&fbclid=abc123&gclid=xyz987",
                topFrameUrl = "https://example.org/",
                resourceType = "document",
                isOffTheRecord = false,
            )
            assertFalse(paramVerdict.isBlocked)
            assertEquals(ShieldPipelineAction.REWRITE_CLEAN_URL, paramVerdict.action)
            assertEquals("https://example.org/post?id=99", paramVerdict.effectiveUrl)

            val cosmeticCss = shield.getCosmeticCssForPage(
                pageUrl = "https://news.example.com/article",
                isOffTheRecord = false,
            )
            assertTrue("Expected cosmetic CSS from adblock-rust", cosmeticCss.contains("display: none !important"))

            // Phiên riêng tư (Off-The-Record) chặn quảng cáo bình thường nhưng KHÔNG ghi nhật ký tên miền hay tăng bộ đếm
            val telemetryBeforeOtr = shield.refreshTelemetry()
            val otrDecision = shield.evaluateRequest(
                requestUrl = "https://www.google-analytics.com/analytics.js",
                topFrameUrl = "https://secret.example.org/",
                resourceType = "script",
                isOffTheRecord = true,
            )
            assertTrue(otrDecision.isBlocked)
            val telemetryAfterOtr = shield.refreshTelemetry()
            assertEquals(telemetryBeforeOtr.totalRequestsChecked, telemetryAfterOtr.totalRequestsChecked)
            assertFalse(telemetryAfterOtr.recentBlockedItems.any { it.contains("secret.example.org") })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `TlsCertificatePolicy and native TLS validator fail closed with zero bypass`() {
        for (certErr in TlsCertificateError.entries) {
            val verdict = TlsCertificatePolicy.evaluateCertificateError(
                errorCode = certErr.errorCode,
                rawUrlOrHost = "https://expired.badssl.com/login",
            )
            assertFalse("TLS error $certErr must never allow proceed", verdict.allowProceed)
            assertFalse("TLS error $certErr must never allow user bypass", verdict.allowUserBypass)
        }
        assertFalse(VekoAdblockNative.evaluateTlsCertificateError(-202, "expired.badssl.com"))
        assertEquals(
            MixedContentDecision.BLOCK_MIXED_CONTENT,
            TlsCertificatePolicy.evaluateMixedContent(
                topFrameUrl = "https://bank.example.com",
                resourceUrl = "http://cdn.example.com/app.js",
                resourceType = "script",
                blockAllMixedContent = false,
            ),
        )
    }
}
