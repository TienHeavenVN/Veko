package io.veko.browser.privacy

import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SettingsRepository
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
                regularSession.callbacks.onLoadRequest(
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
                regularSession.callbacks.onLoadRequest(
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
}
