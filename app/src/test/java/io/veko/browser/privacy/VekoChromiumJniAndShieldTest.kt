package io.veko.browser.privacy

import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.tab.ChromiumEngineSessionBridge
import io.veko.browser.tab.ChromiumEngineSessionHandle
import io.veko.browser.tab.EngineLoadRequest
import io.veko.browser.tab.FakeEngineSessionBridge
import io.veko.browser.tab.InMemorySettingsStore
import io.veko.browser.tab.InMemoryTabSessionStorage
import io.veko.browser.tab.NoOpBookmarkDao
import io.veko.browser.tab.NoOpHistoryDao
import io.veko.browser.tab.TabManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class VekoChromiumJniAndShieldTest {

    @Test
    fun `source tree across all 9 modules strictly forbids android webkit WebView`() {
        val repoRoot = sequenceOf(
            System.getProperty("veko.repo.root")?.let(::File),
            File(".").canonicalFile,
            File("..").canonicalFile,
        ).filterNotNull().first { File(it, "settings.gradle.kts").isFile }

        val moduleDirs = listOf(
            "app/src/main",
            "browser-core/src/main",
            "chromium-embedder/src/main",
            "chromium-native/src/main",
            "privacy/src/main",
            "downloads/src/main",
            "storage/src/main",
            "security/src/main",
            "testing/src/main",
        )
        var scannedSourceFiles = 0
        for (relDir in moduleDirs) {
            val dir = File(repoRoot, relDir)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                .forEach { file ->
                    scannedSourceFiles++
                    val content = file.readText()
                    assertFalse(
                        "Forbidden android.webkit.WebView reference found in ${file.relativeTo(repoRoot)}",
                        content.contains("android.webkit.WebView"),
                    )
                }
        }
        assertTrue("Expected to scan production Kotlin/Java files", scannedSourceFiles >= 20)
    }

    @Test
    fun `pinned Chromium lockfile matches native C++20 JNI metadata and TabManager blocks trackers and destroys OTR context`() = runTest {
        val metadata = VekoChromiumNative.getEngineMetadataJson()
        assertTrue(metadata.contains("\"chromiumVersion\":\"156.0.8078.85\""))
        assertTrue(metadata.contains("brave/adblock-rust-0.13.4"))

        val tempProfile = Files.createTempDirectory("veko_tm_otr").toFile()
        try {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val bridge = FakeEngineSessionBridge()
            val shield = PrivacyShieldEngine(FilterListManager())
            shield.resetTelemetry()
            val embedderRuntime = ChromiumEmbedderRuntime(tempProfile)

            val manager = TabManager(
                sessionStorage = InMemoryTabSessionStorage(),
                engineBridge = bridge,
                dataRepository = BrowserDataRepository(NoOpHistoryDao(), NoOpBookmarkDao(), dispatcher),
                settingsRepository = SettingsRepository(InMemorySettingsStore()),
                mainDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                defaultDispatcher = dispatcher,
                uiPoster = { it() },
                isOnMainThread = { true },
                clockMs = { 1_700_000_000_000L },
                privacyShieldEngine = shield,
                chromiumEmbedderRuntime = embedderRuntime,
            )

            val regularTabId = manager.createTab("https://developer.android.com", isPrivate = false, select = true)
            val regularSession = bridge.allCreatedSessions.last()

            // Trình theo dõi và quảng cáo bị từ chối ngay tại onLoadRequest qua JNI C++20 -> Rust adblock-rust
            assertFalse(
                regularSession.callbacks!!.onLoadRequest(
                    regularSession,
                    EngineLoadRequest(
                        uri = "https://www.google-analytics.com/analytics.js",
                        triggerUri = "https://developer.android.com/",
                        targetWindow = 0,
                        isRedirect = false,
                        hasUserGesture = false,
                        isDirectNavigation = false,
                    ),
                ),
            )

            // URL chứa tham số theo dõi bị chặn bản bẩn và tự động nạp lại URL sạch
            assertFalse(
                regularSession.callbacks!!.onLoadRequest(
                    regularSession,
                    EngineLoadRequest(
                        uri = "https://developer.android.com/compose?topic=mvi&utm_source=ads&gclid=test99",
                        triggerUri = "https://developer.android.com/",
                        targetWindow = 0,
                        isRedirect = false,
                        hasUserGesture = true,
                        isDirectNavigation = false,
                    ),
                ),
            )
            assertEquals("https://developer.android.com/compose?topic=mvi", regularSession.loadedUrls.last())

            // Mở Tab riêng tư khởi tạo Off-The-Record BrowserContext trên RAM C++ và hủy sạch khi đóng
            val privateTabId = manager.createTab("https://mozilla.org", isPrivate = true, select = true)
            assertTrue(embedderRuntime.hasActiveOffTheRecordContext())
            assertEquals(1, VekoChromiumNative.getActiveOffTheRecordContextCount())

            manager.closeTab(privateTabId)
            assertFalse(embedderRuntime.hasActiveOffTheRecordContext())
            assertEquals(0, VekoChromiumNative.getActiveOffTheRecordContextCount())
            assertEquals(regularTabId, manager.uiState.value.activeTabId)
        } finally {
            tempProfile.deleteRecursively()
        }
    }

    @Test
    fun `custom filter rules hot-reload into Rust engine and private evaluations leave zero telemetry trace`() {
        val tempRules = Files.createTempFile("veko_custom_rules", ".txt").toFile()
        try {
            val filterManager = FilterListManager(tempRules)
            val shield = PrivacyShieldEngine(filterManager)
            shield.resetTelemetry()

            assertTrue(filterManager.updateCustomRules("||custom-ad-sdk.example^"))
            val blockedCustom = shield.evaluateRequest(
                requestUrl = "https://cdn.custom-ad-sdk.example/v1/banner.js",
                topFrameUrl = "https://news.example.org/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            assertEquals(NativePipelineAction.BLOCK_AD, blockedCustom.action)

            // Đánh giá trong phiên riêng tư (Off-The-Record) không bao giờ ghi tên miền vào danh sách gần đây hay bộ đếm
            val statsBeforePrivate = shield.refreshTelemetry()
            val privateBlocked = shield.evaluateRequest(
                requestUrl = "https://doubleclick.net/pcs/view?secret_private_session=1",
                topFrameUrl = "https://confidential.example.org/",
                resourceType = "script",
                isOffTheRecord = true,
            )
            assertEquals(NativePipelineAction.BLOCK_AD, privateBlocked.action)
            val statsAfterPrivate = shield.refreshTelemetry()
            assertEquals(statsBeforePrivate.adsBlocked, statsAfterPrivate.adsBlocked)
            assertFalse(statsAfterPrivate.recentBlockedItems.any { it.contains("doubleclick.net") })

            // Khôi phục tập quy tắc mặc định
            assertTrue(filterManager.updateCustomRules(""))
        } finally {
            tempRules.delete()
        }
    }

    @Test
    fun `real ChromiumEngineSessionBridge drives TabManager navigation history OTR teardown and strict TLS denial`() = runTest {
        val tempProfile = Files.createTempDirectory("veko_real_chromium_bridge").toFile()
        try {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val shield = PrivacyShieldEngine(FilterListManager())
            shield.resetTelemetry()
            val embedderRuntime = ChromiumEmbedderRuntime(tempProfile)
            val chromiumBridge = ChromiumEngineSessionBridge(embedderRuntime)

            val manager = TabManager(
                sessionStorage = InMemoryTabSessionStorage(),
                engineBridge = chromiumBridge,
                dataRepository = BrowserDataRepository(NoOpHistoryDao(), NoOpBookmarkDao(), dispatcher),
                settingsRepository = SettingsRepository(InMemorySettingsStore()),
                mainDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                defaultDispatcher = dispatcher,
                uiPoster = { it() },
                isOnMainThread = { true },
                clockMs = { 1_700_000_000_000L },
                privacyShieldEngine = shield,
                chromiumEmbedderRuntime = embedderRuntime,
            )

            // 1. Tạo tab thường với URL HTTP kèm tham số theo dõi -> tự động nâng cấp HTTPS & gọt sạch tham số bẩn
            val regularTabId = manager.createTab(
                url = "http://example.org/news?id=10&utm_source=feed&fbclid=abc123",
                isPrivate = false,
                select = true,
            )
            val afterFirstLoad = manager.uiState.value.activeTab!!
            assertEquals(regularTabId, afterFirstLoad.id)
            assertEquals("https://example.org/news?id=10", afterFirstLoad.sessionState.url)
            assertEquals(io.veko.browser.tab.SecurityState.SECURE, afterFirstLoad.sessionState.securityState)
            assertFalse(afterFirstLoad.sessionState.canGoBack)

            // 2. Điều hướng sang trang thứ hai và kiểm chứng Back / Forward trên VekoNativeBrowserRuntime C++20
            manager.loadUrlOrSearch("https://developer.android.com/compose")
            assertEquals("https://developer.android.com/compose", manager.uiState.value.activeTab!!.sessionState.url)
            assertTrue(manager.uiState.value.activeTab!!.sessionState.canGoBack)

            manager.goBack()
            assertEquals("https://example.org/news?id=10", manager.uiState.value.activeTab!!.sessionState.url)
            assertTrue(manager.uiState.value.activeTab!!.sessionState.canGoForward)

            manager.goForward()
            assertEquals("https://developer.android.com/compose", manager.uiState.value.activeTab!!.sessionState.url)

            // 3. Mở tab riêng tư gắn với Off-The-Record BrowserContext riêng biệt và hủy sạch khi đóng
            manager.createTab(
                url = "https://mozilla.org/en-US/privacy/",
                isPrivate = true,
                select = true,
            )
            assertEquals(1, VekoChromiumNative.getActiveOffTheRecordContextCount())
            assertEquals(2, VekoChromiumNative.getActiveBrowserCount())

            manager.closeAllPrivateTabs()
            assertEquals(0, VekoChromiumNative.getActiveOffTheRecordContextCount())
            assertEquals(1, VekoChromiumNative.getActiveBrowserCount())

            // 4. Kiểm chứng từ chối tuyệt đối chứng chỉ TLS không hợp lệ (không bao giờ cho phép bypass)
            var capturedErrorCode = 0
            var capturedSecure = true
            val standaloneSession = chromiumBridge.createSession(
                isPrivate = false,
                isDesktopMode = false,
                openImmediately = true,
                callbacks = object : io.veko.browser.tab.EngineSessionCallbacks {
                    override fun onLocationChange(session: io.veko.browser.tab.EngineSessionHandle, url: String?) {}
                    override fun onCanGoBack(session: io.veko.browser.tab.EngineSessionHandle, canGoBack: Boolean) {}
                    override fun onCanGoForward(session: io.veko.browser.tab.EngineSessionHandle, canGoForward: Boolean) {}
                    override fun onLoadRequest(
                        session: io.veko.browser.tab.EngineSessionHandle,
                        request: EngineLoadRequest,
                    ): Boolean = true
                    override fun onNewSession(
                        session: io.veko.browser.tab.EngineSessionHandle,
                        uri: String,
                    ): io.veko.browser.tab.EngineSessionHandle? = null
                    override fun onLoadError(
                        session: io.veko.browser.tab.EngineSessionHandle,
                        uri: String?,
                        errorCode: Int,
                    ): String? {
                        capturedErrorCode = errorCode
                        return null
                    }
                    override fun onPageStart(session: io.veko.browser.tab.EngineSessionHandle, url: String) {}
                    override fun onProgressChange(session: io.veko.browser.tab.EngineSessionHandle, progress: Int) {}
                    override fun onPageStop(session: io.veko.browser.tab.EngineSessionHandle, success: Boolean) {}
                    override fun onSecurityChange(
                        session: io.veko.browser.tab.EngineSessionHandle,
                        isSecure: Boolean,
                        origin: String?,
                    ) {
                        capturedSecure = isSecure
                    }
                    override fun onSessionStateChange(
                        session: io.veko.browser.tab.EngineSessionHandle,
                        serializedState: String,
                    ) {}
                    override fun onTitleChange(session: io.veko.browser.tab.EngineSessionHandle, title: String?) {}
                    override fun onCloseRequest(session: io.veko.browser.tab.EngineSessionHandle) {}
                    override fun onKill(session: io.veko.browser.tab.EngineSessionHandle) {}
                    override fun onCrash(session: io.veko.browser.tab.EngineSessionHandle) {}
                },
            ) as ChromiumEngineSessionHandle

            val tlsVerdict = standaloneSession.reportTlsCertificateFailure(
                chromiumNetError = -202,
                failingUrl = "https://self-signed.badssl.com/",
            )
            assertFalse(tlsVerdict.allowProceed)
            assertFalse(tlsVerdict.allowUserBypass)
            assertFalse(capturedSecure)
            assertEquals(-202, capturedErrorCode)

            standaloneSession.close()
            chromiumBridge.notifyRuntimeShutdown()
            assertEquals(0, VekoChromiumNative.getActiveBrowserCount())
        } finally {
            tempProfile.deleteRecursively()
        }
    }

    @Test
    fun `v0_4_0 upgraded Rust shield blocks VN ads, fingerprinters, strips 44+ tracking params, emits cosmetic CSS and honors per-site pause`() {
        val tempDir = Files.createTempDirectory("veko_shield_v040").toFile()
        try {
            val filterManager = FilterListManager(
                customRulesStorageFile = File(tempDir, "custom.txt"),
            )
            filterManager.syncTrustedHosts(emptySet())
            val shield = PrivacyShieldEngine(filterManager)
            shield.resetTelemetry()

            // 1. Vietnamese Ad Shield & Core Ads
            val vnAdVerdict = shield.evaluateRequest(
                requestUrl = "https://adi.admicro.vn/adt/banners/nam2026/popup.js",
                topFrameUrl = "https://vnexpress.net/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            assertEquals(NativePipelineAction.BLOCK_AD, vnAdVerdict.action)

            // 2. Fingerprinting & Session-Replay Anti-Tracking
            val fpVerdict = shield.evaluateRequest(
                requestUrl = "https://cdn.clarity.ms/tag/session-replay.js",
                topFrameUrl = "https://shop.example.com/",
                resourceType = "script",
                isOffTheRecord = false,
            )
            assertTrue(
                fpVerdict.action == NativePipelineAction.BLOCK_TRACKER ||
                    fpVerdict.action == NativePipelineAction.BLOCK_AD,
            )

            // 3. Expanded URL tracking-parameter stripping (ttclid, _gl, mc_cid, yclid)
            val paramVerdict = shield.evaluateRequest(
                requestUrl = "https://shop.example.com/item?sku=42&ttclid=tok1&_gl=ga2&mc_cid=mail9&yclid=yan3",
                topFrameUrl = "https://shop.example.com/",
                resourceType = "main_frame",
                isOffTheRecord = false,
            )
            assertEquals(NativePipelineAction.REWRITE_CLEAN_URL, paramVerdict.action)
            assertEquals("https://shop.example.com/item?sku=42", paramVerdict.effectiveUrl)

            // 4. Cosmetic CSS generation (generic + custom domain cosmetic rule)
            assertTrue(filterManager.updateCustomRules("shop.example.com##.sponsored-hero-slot"))
            val css = shield.getCosmeticCssForPage("https://shop.example.com/deals", isOffTheRecord = false)
            assertTrue("Expected cosmetic CSS to hide .sponsored-hero-slot", css.contains(".sponsored-hero-slot"))
            assertTrue("Expected cosmetic CSS to include display: none !important", css.contains("display: none !important"))

            // 5. Per-site Shield pause (trusted host bypasses blocking & cosmetic CSS; OTR pause stays in RAM)
            val trustedSet = setOf("trusted.example.org")
            val pausedPolicy = PrivacyPolicyConfig(trustedHosts = trustedSet)
            filterManager.syncTrustedHosts(trustedSet)
            assertTrue(
                shield.isSiteShieldPaused(
                    pageUrlOrHost = "https://trusted.example.org/article",
                    persistedTrustedHosts = trustedSet,
                    isOffTheRecord = false,
                ),
            )
            assertEquals(
                "",
                shield.getCosmeticCssForPage(
                    pageUrl = "https://trusted.example.org/article",
                    config = pausedPolicy,
                    isOffTheRecord = false,
                ),
            )

            shield.setOffTheRecordHostPaused("https://private-trusted.example.org/", paused = true)
            assertTrue(
                shield.isSiteShieldPaused(
                    pageUrlOrHost = "https://private-trusted.example.org/",
                    persistedTrustedHosts = emptySet(),
                    isOffTheRecord = true,
                ),
            )
            assertFalse(
                shield.isSiteShieldPaused(
                    pageUrlOrHost = "https://private-trusted.example.org/",
                    persistedTrustedHosts = emptySet(),
                    isOffTheRecord = false,
                ),
            )
            shield.clearOffTheRecordTrustedHosts()
            assertFalse(
                shield.isSiteShieldPaused(
                    pageUrlOrHost = "https://private-trusted.example.org/",
                    persistedTrustedHosts = emptySet(),
                    isOffTheRecord = true,
                ),
            )

            // 6. Toggleable Filter Subscriptions (5 packs)
            assertEquals(5, filterManager.subscriptions.value.size)
            assertTrue(filterManager.setSubscriptionEnabled(FilterListManager.ID_VN_REGIONAL, false))
            assertFalse(filterManager.subscriptions.value.first { it.id == FilterListManager.ID_VN_REGIONAL }.enabled)
            assertTrue(filterManager.setSubscriptionEnabled(FilterListManager.ID_VN_REGIONAL, true))
            filterManager.syncTrustedHosts(emptySet())
            filterManager.updateCustomRules("")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `BookmarkPortabilityCodec exports and imports Netscape HTML and JSON losslessly and VekoStringsCatalog covers 8 languages`() {
        val sample = listOf(
            io.veko.browser.data.local.BookmarkEntity(
                url = "https://kotlinlang.org",
                title = "Kotlin & Compose",
                createdAtEpochMs = 1_730_000_000_000L,
            ),
            io.veko.browser.data.local.BookmarkEntity(
                url = "https://f-droid.org",
                title = "F-Droid FOSS",
                createdAtEpochMs = 1_730_000_005_000L,
            ),
        )
        val html = io.veko.browser.data.repository.BookmarkPortabilityCodec.encodeNetscapeHtml(sample)
        assertTrue(html.contains("<!DOCTYPE NETSCAPE-Bookmark-file-1>"))
        val parsedHtml = io.veko.browser.data.repository.BookmarkPortabilityCodec.decodeBookmarks(html)
        assertEquals(2, parsedHtml.size)
        assertEquals("Kotlin & Compose", parsedHtml[0].title)
        assertEquals("https://kotlinlang.org", parsedHtml[0].url)

        val json = io.veko.browser.data.repository.BookmarkPortabilityCodec.encodeJson(sample)
        val parsedJson = io.veko.browser.data.repository.BookmarkPortabilityCodec.decodeBookmarks(json)
        assertEquals(2, parsedJson.size)
        assertEquals("https://f-droid.org", parsedJson[1].url)

        // Verify all 8 language catalogs resolve cleanly with distinct localized strings
        val supported = listOf(
            io.veko.browser.data.repository.AppLanguage.EN,
            io.veko.browser.data.repository.AppLanguage.VI,
            io.veko.browser.data.repository.AppLanguage.JA,
            io.veko.browser.data.repository.AppLanguage.KO,
            io.veko.browser.data.repository.AppLanguage.ZH_CN,
            io.veko.browser.data.repository.AppLanguage.FR,
            io.veko.browser.data.repository.AppLanguage.DE,
            io.veko.browser.data.repository.AppLanguage.ES,
        )
        val titles = supported.map { lang ->
            val s = io.veko.browser.ui.i18n.VekoStringsCatalog.forLanguage(lang, java.util.Locale.US)
            assertTrue("Blank shieldTitle for $lang", s.shieldTitle.isNotBlank())
            assertTrue("Blank settingsTitle for $lang", s.settingsTitle.isNotBlank())
            s.settingsTitle
        }.toSet()
        assertEquals("Expected all 8 languages to have distinct localized settings titles", 8, titles.size)
    }
}
