package io.veko.browser.testing

import io.veko.browser.chromium.embedder.ChromiumBrowserContext
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.download.DownloadMimeValidator
import io.veko.browser.mvi.BrowserMviAction
import io.veko.browser.mvi.BrowserMviState
import io.veko.browser.mvi.BrowserReducer
import io.veko.browser.privacy.FilterListManager
import io.veko.browser.privacy.PrivacyShieldEngine
import io.veko.browser.security.MixedContentDecision
import io.veko.browser.security.TlsCertificateError
import io.veko.browser.security.TlsCertificatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VekoNativePrivacyAndMviIntegrationTest {

    @Test
    fun `JNI C++20 and Rust adblock-rust pipeline blocks ads, trackers and strips tracking params`() {
        val shield = PrivacyShieldEngine(FilterListManager())
        shield.resetTelemetry()

        val adDecision = shield.evaluateRequest(
            requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            topFrameUrl = "https://news.example.com/world",
            resourceType = "script",
        )
        assertEquals(NativePipelineAction.BLOCK_AD, adDecision.action)
        assertEquals(-20, adDecision.chromiumNetError)

        val trackerDecision = shield.evaluateRequest(
            requestUrl = "https://www.google-analytics.com/g/collect?v=2",
            topFrameUrl = "https://news.example.com/world",
            resourceType = "xmlhttprequest",
        )
        assertEquals(NativePipelineAction.BLOCK_TRACKER, trackerDecision.action)
        assertEquals(-20, trackerDecision.chromiumNetError)

        val cleanedDecision = shield.evaluateRequest(
            requestUrl = "https://example.org/read?article=99&utm_source=social&fbclid=IwAR777&lang=vi",
            topFrameUrl = "https://feed.example.net/",
            resourceType = "document",
        )
        assertEquals(NativePipelineAction.REWRITE_CLEAN_URL, cleanedDecision.action)
        assertEquals("https://example.org/read?article=99&lang=vi", cleanedDecision.effectiveUrl)

        val normalSite = shield.evaluateRequest(
            requestUrl = "https://developer.android.com/jetpack/compose/mental-model",
            topFrameUrl = "https://developer.android.com/",
            resourceType = "document",
        )
        assertEquals(NativePipelineAction.ALLOW, normalSite.action)

        val stats = shield.refreshTelemetry()
        assertEquals(1L, stats.adsBlocked)
        assertEquals(1L, stats.trackersBlocked)
        assertEquals(1L, stats.paramsStripped)
        assertTrue(stats.activeFilterRules >= 40L)
    }

    @Test
    fun `Off-The-Record ChromiumBrowserContext isolates RAM cookies and has zero disk storage path`() {
        val tempDir = Files.createTempDirectory("veko_ctx_test").toFile()
        try {
            ChromiumBrowserContext.createRegular(tempDir).use { regular ->
                ChromiumBrowserContext.createOffTheRecord().use { otr ->
                    assertFalse(regular.isOffTheRecord)
                    assertTrue(otr.isOffTheRecord)
                    assertTrue(regular.storagePartitionPath.isNotEmpty())
                    assertEquals("", otr.storagePartitionPath)

                    assertTrue(
                        regular.setCookie(
                            requestUrl = "https://portal.example.com/home",
                            topFrameUrl = "https://portal.example.com/",
                            cookieHeader = "auth=regular_only",
                            mode = CookieControlMode.BLOCK_THIRD_PARTY,
                        ),
                    )
                    assertEquals(
                        "",
                        otr.getCookies(
                            requestUrl = "https://portal.example.com/home",
                            topFrameUrl = "https://portal.example.com/",
                            mode = CookieControlMode.BLOCK_THIRD_PARTY,
                        ),
                    )
                    // Cookie bên thứ ba luôn bị chặn trong Off-The-Record kể cả khi cấu hình ALLOW_ALL
                    assertFalse(
                        otr.setCookie(
                            requestUrl = "https://adtech.tracker.example/sync",
                            topFrameUrl = "https://portal.example.com/",
                            cookieHeader = "id=cross_site",
                            mode = CookieControlMode.ALLOW_ALL,
                        ),
                    )
                }
            }
            assertEquals(0, VekoChromiumNative.getActiveOffTheRecordContextCount())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `TLS certificate policy and DownloadMimeValidator enforce strict security invariants`() {
        for (errType in TlsCertificateError.entries) {
            val kotlinVerdict = TlsCertificatePolicy.evaluateCertificateError(
                chromiumNetError = errType.chromiumNetError,
                rawUrlOrHost = "untrusted.example.com",
            )
            assertFalse(kotlinVerdict.allowProceed)
            assertFalse(kotlinVerdict.allowUserBypass)
        }
        assertFalse(VekoChromiumNative.evaluateTlsCertificateError(-202, "expired.badssl.com"))
        assertEquals(
            MixedContentDecision.BLOCK_MIXED_CONTENT,
            TlsCertificatePolicy.evaluateMixedContent(
                topFrameUrl = "https://bank.example.com",
                resourceUrl = "http://cdn.example.com/bundle.js",
                resourceType = "script",
                blockAllMixedContent = false,
            ),
        )

        val spoofed = DownloadMimeValidator.validate("application/pdf", "invoice_2026.pdf.apk")
        assertTrue(spoofed.isPotentiallyDangerousExecutable)
        assertTrue(spoofed.wasMimeSpoofed)
        assertEquals("application/vnd.android.package-archive", spoofed.effectiveMimeType)

        val validPdf = DownloadMimeValidator.validate("Application/PDF; charset=binary", "report.pdf")
        assertFalse(validPdf.isPotentiallyDangerousExecutable)
        assertFalse(validPdf.wasMimeSpoofed)
        assertEquals("application/pdf", validPdf.effectiveMimeType)
    }

    @Test
    fun `BrowserReducer transitions MVI state deterministically without mutating previous snapshots`() {
        val initial = BrowserMviState()
        val opened = BrowserReducer.reduce(
            initial,
            BrowserMviAction.UserIntent.SetPrivacySheetVisible(true),
        )
        assertFalse(initial.isPrivacySheetOpen)
        assertTrue(opened.isPrivacySheetOpen)

        val updatedSettings = VekoSettings(
            cookieControlMode = CookieControlMode.BLOCK_ALL,
            saveSearchHistory = false,
        )
        val withSettings = BrowserReducer.reduce(
            opened,
            BrowserMviAction.SystemPatch.SettingsUpdated(updatedSettings),
        )
        assertEquals(CookieControlMode.BLOCK_ALL, withSettings.userSettings.cookieControlMode)

        val tempDir = Files.createTempDirectory("veko_runtime_probe").toFile()
        try {
            val runtime = ChromiumEmbedderRuntime(tempDir)
            val readiness = runtime.probeReadiness()
            assertEquals("156.0.8078.85", readiness.pinnedChromiumVersion)
            assertEquals("0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5", readiness.pinnedChromiumCommit)
            assertTrue(readiness.nativeJniBridgeLoaded)
            assertTrue(readiness.rustAdblockLoaded)
            assertFalse(readiness.upstreamBlinkV8ContentLinked)
            assertFalse(readiness.isFullUpstreamRenderingReady)
            runtime.shutdown()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `Phase 4 JNI browser session lifecycle navigation surface geometry and thread affinity work deterministically`() {
        val tempDir = Files.createTempDirectory("veko_phase4_jni").toFile()
        try {
            VekoChromiumNative.shutdown()
            val status = VekoChromiumNative.initialize(tempDir.absolutePath, enforceUiThread = true)
            assertTrue(status.isOk)
            assertFalse(status.upstreamContentLinked)

            ChromiumBrowserContext.createRegular(File(tempDir, "regular")).use { regCtx ->
                val browser = VekoChromiumNative.createBrowser(regCtx.nativeHandle, desktopMode = false)
                assertTrue("Expected positive browser handle, got $browser", browser > 0L)

                val firstNav = VekoChromiumNative.loadUrl(
                    browser,
                    "http://example.com/article?id=42&utm_medium=social&fbclid=track1",
                )
                assertTrue(firstNav.accepted)
                assertEquals("https://example.com/article?id=42", firstNav.finalUrl)
                assertFalse(firstNav.upstreamContentRendered)
                assertFalse(VekoChromiumNative.canGoBack(browser))

                val secondNav = VekoChromiumNative.loadUrl(browser, "https://www.wikipedia.org/")
                assertTrue(secondNav.accepted)
                assertTrue(VekoChromiumNative.canGoBack(browser))
                assertTrue(VekoChromiumNative.goBack(browser))
                assertEquals("https://example.com/article?id=42", VekoChromiumNative.getCurrentUrl(browser))
                assertTrue(VekoChromiumNative.canGoForward(browser))
                assertTrue(VekoChromiumNative.goForward(browser))
                assertEquals("https://www.wikipedia.org/", VekoChromiumNative.getCurrentUrl(browser))

                assertEquals(
                    VekoChromiumNative.NATIVE_OK,
                    VekoChromiumNative.attachSurface(browser, null, 1080, 2400, 420),
                )
                val attachedSnap = VekoChromiumNative.getSurfaceSnapshot(browser)
                assertTrue(attachedSnap.attached)
                assertEquals(1080, attachedSnap.widthPx)
                assertEquals(2400, attachedSnap.heightPx)

                assertEquals(VekoChromiumNative.NATIVE_OK, VekoChromiumNative.resizeSurface(browser, 2400, 1080))
                assertEquals(2400, VekoChromiumNative.getSurfaceSnapshot(browser).widthPx)
                assertEquals(VekoChromiumNative.NATIVE_OK, VekoChromiumNative.detachSurface(browser))
                assertFalse(VekoChromiumNative.getSurfaceSnapshot(browser).attached)

                // Kiểm chứng khóa luồng: luồng nền bị từ chối bằng ERR_WRONG_THREAD (-3)
                var backgroundErr = 0
                val worker = Thread {
                    backgroundErr = VekoChromiumNative.createBrowser(regCtx.nativeHandle, false).toInt()
                }
                worker.start()
                worker.join()
                assertEquals(VekoChromiumNative.ERR_WRONG_THREAD, backgroundErr)

                assertTrue(VekoChromiumNative.destroyBrowser(browser))
                assertFalse(VekoChromiumNative.destroyBrowser(browser))
            }
            assertTrue(VekoChromiumNative.shutdown())
            assertFalse(VekoChromiumNative.isInitialized())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
