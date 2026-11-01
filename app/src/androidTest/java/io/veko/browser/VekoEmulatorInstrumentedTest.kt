package io.veko.browser

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.veko.browser.download.DownloadStatus
import io.veko.browser.engine.EngineAvailabilityStatus
import io.veko.browser.engine.EngineSelectionResult
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.SdkValidationStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.geckoview.GeckoView
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bộ kiểm thử tích hợp chạy trực tiếp trên Android Emulator (x86_64) với native GeckoRuntime + libxul.so:
 * - Xác minh khởi tạo GeckoRuntime, tiến trình con :tab, gắn kết GeckoView trong Jetpack Compose và lưu lịch sử Room.
 * - Xác minh tái tạo Activity (xoay màn hình / cấu hình) không nhân đôi tab ACTION_VIEW và gắn lại GeckoView sạch sẽ.
 * - Xác minh trần cứng 6 GeckoSession sống khi mở tab dồn dập và cô lập 100% dữ liệu Private Mode khỏi hệ thống tệp Android.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VekoEmulatorInstrumentedTest {

    private lateinit var server: LoopbackHttpTestServer

    @Before
    fun setUp() {
        server = LoopbackHttpTestServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun geckoRuntimeBootsRendersPageAndPersistsRegularHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val targetUrl = "${server.baseUrl}/smoke"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            .setClass(targetContext, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val tabManager = app.tabManager.get()

            waitUntil(
                timeoutMs = 45_000L,
                description = "GeckoRuntime loads loopback /smoke page with expected title",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/smoke" &&
                    !active.sessionState.isLoading
            }

            // Kiểm tra cây View thực tế của MainActivity có đúng thực thể GeckoView đã đo kích thước và gắn session mở
            waitUntil(
                timeoutMs = 10_000L,
                description = "MainActivity hosts measured GeckoView with open GeckoSession",
            ) {
                val ready = AtomicBoolean(false)
                scenario.onActivity { activity ->
                    val gv = findFirstGeckoView(activity.window.decorView)
                    ready.set(gv != null && gv.width > 0 && gv.height > 0 && gv.session?.isOpen == true)
                }
                ready.get()
            }

            // Xác minh Room SQLite thực tế đã ghi nhận lịch sử cho tab thường
            val repo = app.dataRepository.get()
            waitUntil(
                timeoutMs = 10_000L,
                description = "Room browsing_history persists visited regular URL",
            ) {
                runBlocking {
                    repo.recentHistory.first().any { it.url == targetUrl }
                }
            }
        }
    }

    @Test
    fun activityRecreateDoesNotDuplicateExternalIntentTabAndRebindsGeckoView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val initialUrl = "${server.baseUrl}/recreate-initial"
        val afterRecreateUrl = "${server.baseUrl}/recreate-after"

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(initialUrl))
            .setClass(targetContext, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val tabManager = app.tabManager.get()

            waitUntil(
                timeoutMs = 45_000L,
                description = "Initial external ACTION_VIEW page finishes loading",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/recreate-initial" &&
                    !active.sessionState.isLoading
            }

            val tabCountBeforeRecreate = tabManager.uiState.value.tabs.size
            val activeTabIdBeforeRecreate = tabManager.uiState.value.activeTabId

            // Tái tạo Activity thực tế trên Emulator: hủy GeckoView cũ và gắn GeckoView mới mà không crash hay mở lặp tab
            scenario.recreate()

            assertEquals(
                "EXTRA_EXTERNAL_URL_CONSUMED must prevent duplicate tab creation on Activity.recreate()",
                tabCountBeforeRecreate,
                tabManager.uiState.value.tabs.size,
            )
            assertEquals(activeTabIdBeforeRecreate, tabManager.uiState.value.activeTabId)

            waitUntil(
                timeoutMs = 10_000L,
                description = "Recreated MainActivity rebinds a live measured GeckoView",
            ) {
                val rebound = AtomicBoolean(false)
                scenario.onActivity { activity ->
                    val gv = findFirstGeckoView(activity.window.decorView)
                    rebound.set(gv != null && gv.width > 0 && gv.height > 0 && gv.session?.isOpen == true)
                }
                rebound.get()
            }

            scenario.onActivity {
                tabManager.loadUrlOrSearch(afterRecreateUrl)
            }

            waitUntil(
                timeoutMs = 30_000L,
                description = "Recreated MainActivity navigates cleanly to /recreate-after",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/recreate-after" &&
                    !active.sessionState.isLoading
            }
        }
    }

    @Test
    fun rapidTabCreationEnforcesSixLiveGeckoSessionsAndKeepsPrivateCanaryOffDisk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val privateCanaryToken = "veko-private-canary-98412"
        val privateCanaryUrl = "https://$privateCanaryToken.invalid/secret-path"

        val launchIntent = Intent(targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val tabManager = app.tabManager.get()

            waitUntil(
                timeoutMs = 30_000L,
                description = "TabManager completes initial bootstrap",
            ) {
                !tabManager.uiState.value.isRestoring && tabManager.uiState.value.tabs.isNotEmpty()
            }

            val createdIds = mutableListOf<String>()

            // Mở dồn dập 8 tab thường + 1 tab riêng tư ngay trên UI thread thực tế
            scenario.onActivity {
                for (index in 1..8) {
                    createdIds += tabManager.createTab(
                        url = "${server.baseUrl}/rapid-$index",
                        isPrivate = false,
                        select = true,
                    )
                }
                assertTrue(
                    "Live native GeckoSessions (${tabManager.activeLiveSessionCount}) must never exceed 6",
                    tabManager.activeLiveSessionCount <= 6,
                )

                createdIds += tabManager.createTab(
                    url = privateCanaryUrl,
                    isPrivate = true,
                    select = true,
                )
                assertTrue(
                    "Live native GeckoSessions (${tabManager.activeLiveSessionCount}) must remain <= 6 after private tab",
                    tabManager.activeLiveSessionCount <= 6,
                )
            }

            assertTrue("All regular + private tabs must remain preserved in UI state", tabManager.uiState.value.tabs.size >= 9)

            // Chờ vượt ngưỡng debounce 600ms để DataStore flush phiên tab thường xuống đĩa thật của Android
            SystemClock.sleep(1_500L)

            val datastoreDir = File(targetContext.filesDir, "datastore")
            val sessionStoreFile = File(datastoreDir, "veko_tabs_session_store.preferences_pb")
            waitUntil(
                timeoutMs = 10_000L,
                description = "DataStore writes regular tabs snapshot to disk",
            ) {
                sessionStoreFile.isFile && sessionStoreFile.length() > 0L
            }

            val sessionBytesText = String(sessionStoreFile.readBytes(), StandardCharsets.ISO_8859_1)
            assertTrue("Regular loopback tab URL should be persisted in session store", sessionBytesText.contains("127.0.0.1"))
            assertFalse(
                "Private tab canary must NEVER appear in DataStore session file",
                sessionBytesText.contains(privateCanaryToken),
            )

            // Quét toàn bộ tệp SQLite Room (db, wal, shm) để chắc chắn không rò rỉ dữ liệu riêng tư
            val dbParentDir = targetContext.getDatabasePath("veko_browser.db").parentFile
            val dbFiles = dbParentDir?.listFiles().orEmpty()
            for (file in dbFiles) {
                if (file.isFile) {
                    val rawDbContent = String(file.readBytes(), StandardCharsets.ISO_8859_1)
                    assertFalse(
                        "Private canary leaked into database file ${file.name}",
                        rawDbContent.contains(privateCanaryToken),
                    )
                }
            }

            val historyItems = runBlocking { app.dataRepository.get().recentHistory.first() }
            assertFalse(
                "Private canary must never appear in Room History Flow",
                historyItems.any { it.url.contains(privateCanaryToken) },
            )

            // Dọn các tab vừa tạo để giữ bộ nhớ nhẹ cho các test kế tiếp
            scenario.onActivity {
                createdIds.forEach { id -> tabManager.closeTab(id) }
            }
        }
    }

    @Test
    fun navigationBackForwardRedirectFindInPageOfflineRecoveryAndDownloadWorkEndToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("${server.baseUrl}/nav-first"))
            .setClass(targetContext, MainActivity::class.java)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            val tabManager = app.tabManager.get()
            val downloadManager = app.downloadManager.get()

            waitUntil(
                timeoutMs = 45_000L,
                description = "First navigation page /nav-first loads",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null && active.sessionState.title == "Title:/nav-first" && !active.sessionState.isLoading
            }

            // Điều hướng qua HTTP 302 redirect tới /nav-second
            scenario.onActivity {
                tabManager.loadUrlOrSearch("${server.baseUrl}/redirect-to-second")
            }
            waitUntil(
                timeoutMs = 30_000L,
                description = "HTTP 302 redirect lands on /nav-second with canGoBack=true",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/nav-second" &&
                    active.sessionState.canGoBack &&
                    !active.sessionState.isLoading
            }

            // Kiểm tra Back & Forward thực trên GeckoSession
            scenario.onActivity { tabManager.goBack() }
            waitUntil(
                timeoutMs = 30_000L,
                description = "goBack returns to /nav-first",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null && active.sessionState.title == "Title:/nav-first" && active.sessionState.canGoForward
            }

            scenario.onActivity { tabManager.goForward() }
            waitUntil(
                timeoutMs = 30_000L,
                description = "goForward returns to /nav-second",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null && active.sessionState.title == "Title:/nav-second" && !active.sessionState.isLoading
            }

            // Kiểm tra Tìm trong trang (SessionFinder) trên DOM thật của GeckoView
            scenario.onActivity {
                tabManager.openFindInPage()
                tabManager.updateFindInPageQuery("Veko Local Test Server")
            }
            waitUntil(
                timeoutMs = 15_000L,
                description = "SessionFinder locates text inside rendered GeckoView page",
            ) {
                val findState = tabManager.uiState.value.findInPage
                findState.isVisible && findState.totalMatches >= 1 && !findState.notFound
            }
            scenario.onActivity { tabManager.closeFindInPage() }

            // Kiểm tra tải tệp thực qua ContentDelegate.onExternalResponse -> MediaStore.Downloads
            scenario.onActivity {
                tabManager.loadUrlOrSearch("${server.baseUrl}/download-bin")
            }
            waitUntil(
                timeoutMs = 30_000L,
                description = "GeckoSession onExternalResponse completes scoped MediaStore download",
            ) {
                downloadManager.downloads.value.any {
                    it.fileName.startsWith("veko-emulator-artifact") &&
                        it.status == DownloadStatus.COMPLETED &&
                        it.downloadedBytes == LoopbackHttpTestServer.SAMPLE_DOWNLOAD_BYTES.size.toLong()
                }
            }

            // Kiểm tra trang lỗi kết nối (cổng đóng) và khôi phục mượt mà sang trang sống
            val closedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
            scenario.onActivity {
                tabManager.loadUrlOrSearch("http://127.0.0.1:$closedPort/unreachable")
            }
            waitUntil(
                timeoutMs = 30_000L,
                description = "Unreachable port triggers safe load-error state without crashing UI",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null && active.sessionState.hasLoadError && !active.sessionState.isLoading
            }

            scenario.onActivity {
                tabManager.loadUrlOrSearch("${server.baseUrl}/recovered")
            }
            waitUntil(
                timeoutMs = 30_000L,
                description = "Tab recovers cleanly from load error to /recovered",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    !active.sessionState.hasLoadError &&
                    active.sessionState.title == "Title:/recovered" &&
                    !active.sessionState.isLoading
            }
        }
    }

    @Test
    fun vekoHomeDeckShortcutManagementNavigateHomeAndLandscapeLayoutWorkEndToEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val repo = app.dataRepository.get()
        val shortcutUrl = "${server.baseUrl}/pinned-home-shortcut"

        val launchIntent = Intent(targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val tabManager = app.tabManager.get()

            waitUntil(
                timeoutMs = 30_000L,
                description = "TabManager completes bootstrap before Home Deck test",
            ) {
                !tabManager.uiState.value.isRestoring && tabManager.uiState.value.tabs.isNotEmpty()
            }

            // Mở một tab Trang chủ mới (about:blank) và thêm lối tắt thông qua Room saveShortcutBookmark
            scenario.onActivity {
                tabManager.createTab(url = io.veko.browser.tab.TabManager.ABOUT_BLANK, isPrivate = false, select = true)
            }
            assertEquals(
                io.veko.browser.tab.TabManager.ABOUT_BLANK,
                tabManager.uiState.value.activeTab?.sessionState?.url,
            )

            runBlocking {
                assertTrue(repo.saveShortcutBookmark(shortcutUrl, "Veko Pinned Docs"))
            }
            waitUntil(
                timeoutMs = 10_000L,
                description = "Room bookmarks emits saved Home shortcut",
            ) {
                runBlocking {
                    repo.bookmarks.first().any { it.url == shortcutUrl && it.title == "Veko Pinned Docs" }
                }
            }

            // Xoay ngang màn hình (kích hoạt lưới Bento 2 cột >= 640dp của VekoHomePage) rồi quay lại dọc mượt mà
            scenario.onActivity { activity ->
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            instrumentation.waitForIdleSync()

            // Mở trang từ lối tắt rồi nhấn Về Trang chủ (navigateHome) -> quay về about:blank tức thì, giữ nguyên GeckoView sống
            scenario.onActivity {
                tabManager.loadUrlOrSearch(shortcutUrl)
            }
            waitUntil(
                timeoutMs = 30_000L,
                description = "Pinned shortcut loads /pinned-home-shortcut",
            ) {
                val active = tabManager.uiState.value.activeTab
                active != null &&
                    active.sessionState.title == "Title:/pinned-home-shortcut" &&
                    !active.sessionState.isLoading
            }

            scenario.onActivity { activity ->
                tabManager.navigateHome()
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            waitUntil(
                timeoutMs = 10_000L,
                description = "navigateHome returns active tab to about:blank and keeps GeckoView measured",
            ) {
                val ok = AtomicBoolean(false)
                scenario.onActivity { activity ->
                    val gv = findFirstGeckoView(activity.window.decorView)
                    val active = tabManager.uiState.value.activeTab
                    ok.set(
                        active != null &&
                            active.sessionState.url == io.veko.browser.tab.TabManager.ABOUT_BLANK &&
                            !active.sessionState.isLoading &&
                            gv != null &&
                            gv.width > 0 &&
                            gv.height > 0,
                    )
                }
                ok.get()
            }
        }
    }

    @Test
    fun nativeJniRustShieldAndOffTheRecordContextWorkEndToEndOnAndroidEmulator() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val app = targetContext.applicationContext as VekoApplication

        // 1. Nạp và xác minh libveko_chromium_native.so (C++20 JNI + Rust adblock-rust 0.13.4) trực tiếp trên Android ART
        val metadataJson = io.veko.browser.chromium.nativebridge.VekoChromiumNative.getEngineMetadataJson()
        assertTrue("Expected pinned Chromium 156.0.8078.85 in native metadata: $metadataJson", metadataJson.contains("156.0.8078.85"))
        assertTrue("Expected brave/adblock-rust-0.13.4 in native metadata: $metadataJson", metadataJson.contains("brave/adblock-rust-0.13.4"))

        val adDecision = io.veko.browser.chromium.nativebridge.VekoChromiumNative.evaluateUrlRequest(
            requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            topFrameUrl = "https://news.example.com/",
            resourceType = "script",
        )
        assertEquals(io.veko.browser.chromium.nativebridge.NativePipelineAction.BLOCK_AD, adDecision.action)

        val cleanedDecision = io.veko.browser.chromium.nativebridge.VekoChromiumNative.evaluateUrlRequest(
            requestUrl = "https://example.org/story?id=7&utm_source=emu&fbclid=xyz",
            topFrameUrl = "https://example.org/",
            resourceType = "document",
        )
        assertEquals("https://example.org/story?id=7", cleanedDecision.effectiveUrl)

        // 2. Xác minh cô lập phiên riêng tư theo từng Engine (Dual-Engine Isolation) và vòng đời Off-The-Record C++
        val launchIntent = Intent(targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val tabManager = app.tabManager.get()
            val engineManager = app.engineManager.get()
            waitUntil(
                timeoutMs = 30_000L,
                description = "TabManager completes bootstrap before OTR native context test",
            ) {
                !tabManager.uiState.value.isRestoring
            }

            var privateTabId = ""
            scenario.onActivity {
                privateTabId = tabManager.createTab(
                    url = "${server.baseUrl}/otr-native-verify",
                    isPrivate = true,
                    select = true,
                )
            }
            // Tab riêng tư của GeckoView chỉ khởi tạo phiên riêng tư Gecko, KHÔNG chạm vào Chromium OTR BrowserContext
            assertTrue(
                "Expected active GeckoView private session while Gecko private tab is open",
                engineManager.engineFor(EngineType.GECKO).activePrivateSessionCount() >= 1,
            )
            assertEquals(
                "GeckoView private tab must remain strictly isolated from Chromium OTR BrowserContext",
                0,
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.getActiveOffTheRecordContextCount(),
            )

            val memInfo = android.os.Debug.MemoryInfo()
            android.os.Debug.getMemoryInfo(memInfo)
            val nativeHeapKb = android.os.Debug.getNativeHeapAllocatedSize() / 1024L
            android.util.Log.i(
                "VekoMetrics",
                "RUNTIME_TOTAL_PSS_KB=${memInfo.totalPss} NATIVE_HEAP_KB=$nativeHeapKb",
            )

            scenario.onActivity {
                tabManager.closeAllPrivateTabs()
            }
            assertFalse(tabManager.uiState.value.tabs.any { it.id == privateTabId })
            assertEquals(0, engineManager.engineFor(EngineType.GECKO).activePrivateSessionCount())
        }

        // Kiểm chứng độc lập vòng đời Off-The-Record BrowserContext C++ của ChromiumEmbedderRuntime trên Emulator
        val otrTestDir = File(targetContext.cacheDir, "chromium_otr_isolation_verify").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val chromiumRuntime = io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime(otrTestDir)
            chromiumRuntime.acquireOffTheRecordContext()
            assertTrue(
                "Expected active native Chromium Off-The-Record BrowserContext when acquired",
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.getActiveOffTheRecordContextCount() >= 1,
            )
            chromiumRuntime.releaseOffTheRecordContextIfUnused(activePrivateTabCount = 0)
            assertEquals(
                "Releasing Chromium Off-The-Record BrowserContext must drop native OTR count to 0",
                0,
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.getActiveOffTheRecordContextCount(),
            )
            chromiumRuntime.shutdown()
        } finally {
            otrTestDir.deleteRecursively()
        }
    }

    @Test
    fun androidHardwareSurfaceANativeWindowAndChromiumSurfaceHostViewWorkOnEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val runtimeRoot = File(targetContext.cacheDir, "chromium_emu_surface_test").apply {
            deleteRecursively()
            mkdirs()
        }

        val embedderRuntime = io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime(runtimeRoot)
        val chromiumBridge = io.veko.browser.tab.ChromiumEngineSessionBridge(embedderRuntime)
        val surfaceTexture = android.graphics.SurfaceTexture(0).apply {
            setDefaultBufferSize(1080, 1920)
        }
        val hardwareSurface = android.view.Surface(surfaceTexture)

        try {
            assertTrue("Hardware Surface from SurfaceTexture must be valid", hardwareSurface.isValid)

            val readiness = embedderRuntime.probeReadiness(targetContext)
            assertEquals("156.0.8078.85", readiness.pinnedChromiumVersion)
            assertTrue(readiness.nativeJniBridgeLoaded)
            assertFalse(readiness.upstreamBlinkV8ContentLinked)

            val noopCallbacks = object : io.veko.browser.tab.EngineSessionCallbacks {
                override fun onLocationChange(session: io.veko.browser.tab.EngineSessionHandle, url: String?) {}
                override fun onCanGoBack(session: io.veko.browser.tab.EngineSessionHandle, canGoBack: Boolean) {}
                override fun onCanGoForward(session: io.veko.browser.tab.EngineSessionHandle, canGoForward: Boolean) {}
                override fun onLoadRequest(
                    session: io.veko.browser.tab.EngineSessionHandle,
                    request: io.veko.browser.tab.EngineLoadRequest,
                ): Boolean = true
                override fun onNewSession(
                    session: io.veko.browser.tab.EngineSessionHandle,
                    uri: String,
                ): io.veko.browser.tab.EngineSessionHandle? = null
                override fun onLoadError(
                    session: io.veko.browser.tab.EngineSessionHandle,
                    uri: String?,
                    errorCode: Int,
                ): String? = null
                override fun onPageStart(session: io.veko.browser.tab.EngineSessionHandle, url: String) {}
                override fun onProgressChange(session: io.veko.browser.tab.EngineSessionHandle, progress: Int) {}
                override fun onPageStop(session: io.veko.browser.tab.EngineSessionHandle, success: Boolean) {}
                override fun onSecurityChange(
                    session: io.veko.browser.tab.EngineSessionHandle,
                    isSecure: Boolean,
                    origin: String?,
                ) {}
                override fun onSessionStateChange(
                    session: io.veko.browser.tab.EngineSessionHandle,
                    serializedState: String,
                ) {}
                override fun onTitleChange(session: io.veko.browser.tab.EngineSessionHandle, title: String?) {}
                override fun onCloseRequest(session: io.veko.browser.tab.EngineSessionHandle) {}
                override fun onKill(session: io.veko.browser.tab.EngineSessionHandle) {}
                override fun onCrash(session: io.veko.browser.tab.EngineSessionHandle) {}
            }

            val session = chromiumBridge.createSession(
                isPrivate = false,
                isDesktopMode = false,
                openImmediately = true,
                callbacks = noopCallbacks,
            ) as io.veko.browser.tab.ChromiumEngineSessionHandle

            val handle = session.nativeBrowserHandle
            assertTrue("Expected valid native browser handle > 0", handle > 0L)

            // 1. Gắn android.view.Surface thực tế qua JNI -> ANativeWindow_fromSurface(env, jsurface) trong C++20
            val attachRc = io.veko.browser.chromium.nativebridge.VekoChromiumNative.attachSurface(
                browserHandle = handle,
                surface = hardwareSurface,
                widthPx = 1080,
                heightPx = 1920,
                densityDpi = targetContext.resources.displayMetrics.densityDpi,
            )
            assertEquals(io.veko.browser.chromium.nativebridge.VekoChromiumNative.NATIVE_OK, attachRc)

            val snapAttached = io.veko.browser.chromium.nativebridge.VekoChromiumNative.getSurfaceSnapshot(handle)
            assertTrue(snapAttached.attached)
            assertEquals(1080, snapAttached.widthPx)
            assertEquals(1920, snapAttached.heightPx)
            assertEquals(1L, snapAttached.attachGeneration)

            // 2. Xoay ngang / đổi kích thước viewport (1920x1080) -> cập nhật geometry C++20
            assertEquals(
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.NATIVE_OK,
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.resizeSurface(handle, 1920, 1080),
            )
            assertEquals(1920, io.veko.browser.chromium.nativebridge.VekoChromiumNative.getSurfaceSnapshot(handle).widthPx)

            // 3. Tháo và gắn lại Surface -> giải phóng ANativeWindow_release và tăng attachGeneration
            assertEquals(
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.NATIVE_OK,
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.detachSurface(handle),
            )
            assertFalse(io.veko.browser.chromium.nativebridge.VekoChromiumNative.getSurfaceSnapshot(handle).attached)

            assertEquals(
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.NATIVE_OK,
                io.veko.browser.chromium.nativebridge.VekoChromiumNative.attachSurface(
                    browserHandle = handle,
                    surface = hardwareSurface,
                    widthPx = 1920,
                    heightPx = 1080,
                    densityDpi = targetContext.resources.displayMetrics.densityDpi,
                ),
            )
            assertEquals(2L, io.veko.browser.chromium.nativebridge.VekoChromiumNative.getSurfaceSnapshot(handle).attachGeneration)

            // 4. Kiểm chứng ChromiumSurfaceHostView + EngineViewHandle trên luồng giao diện Android
            instrumentation.runOnMainSync {
                val hostView = chromiumBridge.createPlatformView(targetContext)
                    as io.veko.browser.chromium.embedder.ChromiumSurfaceHostView
                val viewHandle = chromiumBridge.wrapPlatformView(hostView)
                viewHandle.setSession(session)
                assertEquals(handle, hostView.currentBrowserHandle)
                viewHandle.releaseSession()
                assertEquals(0L, hostView.currentBrowserHandle)
            }

            session.close()
        } finally {
            hardwareSurface.release()
            surfaceTexture.release()
            chromiumBridge.notifyRuntimeShutdown()
            runtimeRoot.deleteRecursively()
        }
    }

    @Test
    fun dualEngineCoordinatorValidatesSdkExecutesGeckoSpiderMonkeyJsAndNavigatesCleanlyOnEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val app = targetContext.applicationContext as VekoApplication
        val jsUrl = "${server.baseUrl}/js-spidermonkey-verify"
        val stepTwoUrl = "${server.baseUrl}/dual-step-two"

        val launchIntent = Intent(targetContext, MainActivity::class.java)
        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val tabManager = app.tabManager.get()
            val engineManager = app.engineManager.get()

            waitUntil(
                timeoutMs = 30_000L,
                description = "TabManager completes initial Dual-Engine bootstrap",
            ) {
                !tabManager.uiState.value.isRestoring && tabManager.uiState.value.tabs.isNotEmpty()
            }

            // 1. Kiểm chứng trạng thái kiểm định SDK trung thực trên thiết bị Emulator thực tế
            val geckoAvailability = engineManager.getAvailability(EngineType.GECKO)
            assertEquals(EngineAvailabilityStatus.AVAILABLE, geckoAvailability.status)
            assertEquals(SdkValidationStatus.VERIFIED_MAVEN_SDK, geckoAvailability.sdkValidationStatus)
            assertTrue(geckoAvailability.abiCompatible)
            assertTrue(geckoAvailability.isSelectable)

            val chromiumAvailability = engineManager.getAvailability(EngineType.CHROMIUM)
            assertEquals(EngineAvailabilityStatus.BLOCKED, chromiumAvailability.status)
            assertEquals(SdkValidationStatus.MISSING_UPSTREAM_SDK, chromiumAvailability.sdkValidationStatus)
            assertFalse(chromiumAvailability.isSelectable)
            assertTrue(!chromiumAvailability.unavailabilityReason.isNullOrBlank())

            // Chỉ duy nhất nhân GeckoView được nạp; nhân Chromium không bị khởi tạo thừa
            assertEquals(setOf(EngineType.GECKO), engineManager.initializedEngines.value)

            val rejectedSwitch = runBlocking { engineManager.selectDefaultEngine(EngineType.CHROMIUM) }
            assertTrue(rejectedSwitch is EngineSelectionResult.Rejected)

            // 2. Tạo tab mới với GeckoView, xác minh thực thi JavaScript thực tế trong SpiderMonkey
            var testTabId = ""
            scenario.onActivity {
                assertEquals(
                    null,
                    tabManager.createTabWithExplicitEngine(
                        url = jsUrl,
                        isPrivate = false,
                        engineType = EngineType.CHROMIUM,
                        select = true,
                    ),
                )
                testTabId = tabManager.createTab(
                    url = jsUrl,
                    isPrivate = false,
                    select = true,
                    engineType = EngineType.GECKO,
                )
            }

            waitUntil(
                timeoutMs = 45_000L,
                description = "GeckoView SpiderMonkey executes page JS and updates document.title",
            ) {
                val tab = tabManager.uiState.value.tabs.firstOrNull { it.id == testTabId }
                tab != null &&
                    tab.engineType == EngineType.GECKO &&
                    tab.sessionState.title == "SpiderMonkeyJS:42:Gecko=true" &&
                    !tab.sessionState.isLoading
            }

            // 3. Kiểm chứng chuỗi điều hướng Load -> Back -> Forward -> Reload và giải phóng Session sạch sẽ
            scenario.onActivity {
                tabManager.loadUrlOrSearch(stepTwoUrl)
            }
            waitUntil(
                timeoutMs = 30_000L,
                description = "Navigates forward to /dual-step-two with canGoBack=true",
            ) {
                val tab = tabManager.uiState.value.tabs.firstOrNull { it.id == testTabId }
                tab != null &&
                    tab.sessionState.title == "Title:/dual-step-two" &&
                    tab.sessionState.canGoBack &&
                    !tab.sessionState.isLoading
            }

            scenario.onActivity { tabManager.goBack() }
            waitUntil(
                timeoutMs = 30_000L,
                description = "goBack() restores SpiderMonkey JS page with canGoForward=true",
            ) {
                val tab = tabManager.uiState.value.tabs.firstOrNull { it.id == testTabId }
                tab != null &&
                    tab.sessionState.title == "SpiderMonkeyJS:42:Gecko=true" &&
                    tab.sessionState.canGoForward &&
                    !tab.sessionState.isLoading
            }

            scenario.onActivity { tabManager.goForward() }
            waitUntil(
                timeoutMs = 30_000L,
                description = "goForward() returns to /dual-step-two",
            ) {
                val tab = tabManager.uiState.value.tabs.firstOrNull { it.id == testTabId }
                tab != null &&
                    tab.sessionState.title == "Title:/dual-step-two" &&
                    !tab.sessionState.isLoading
            }

            scenario.onActivity { tabManager.reloadOrStop() }
            waitUntil(
                timeoutMs = 30_000L,
                description = "reloadOrStop() cleanly reloads /dual-step-two",
            ) {
                val tab = tabManager.uiState.value.tabs.firstOrNull { it.id == testTabId }
                tab != null &&
                    tab.sessionState.title == "Title:/dual-step-two" &&
                    !tab.sessionState.isLoading
            }

            var sessionsBeforeClose = 0
            scenario.onActivity {
                sessionsBeforeClose = engineManager.engineFor(EngineType.GECKO).activeSessionCount()
                tabManager.closeTab(testTabId)
            }
            waitUntil(
                timeoutMs = 10_000L,
                description = "Closing tab cleans up GeckoSession without leaking",
            ) {
                var cleaned = false
                scenario.onActivity {
                    cleaned = tabManager.uiState.value.tabs.none { it.id == testTabId } &&
                        engineManager.engineFor(EngineType.GECKO).activeSessionCount() < sessionsBeforeClose
                }
                cleaned
            }
        }
    }

    private fun findFirstGeckoView(root: View?): GeckoView? {
        if (root == null) return null
        if (root is GeckoView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findFirstGeckoView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun waitUntil(
        timeoutMs: Long,
        pollIntervalMs: Long = 150L,
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(pollIntervalMs)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description")
    }

    /**
     * Máy chủ HTTP loopback tối giản chạy trên 127.0.0.1 để kiểm thử GeckoRuntime hoàn toàn độc lập với mạng ngoài.
     */
    private class LoopbackHttpTestServer : Closeable {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()

        @Volatile
        private var running = true

        val baseUrl: String
            get() = "http://127.0.0.1:${serverSocket.localPort}"

        fun start() {
            executor.execute {
                while (running) {
                    val client = try {
                        serverSocket.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    executor.execute {
                        runCatching {
                            client.use { socket ->
                                socket.soTimeout = 5_000
                                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                                val requestLine = reader.readLine().orEmpty()
                                while (true) {
                                    val header = reader.readLine() ?: break
                                    if (header.isEmpty()) break
                                }
                                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                                val out = socket.getOutputStream()
                                when (path) {
                                    "/redirect-to-second" -> {
                                        val headers = buildString {
                                            append("HTTP/1.1 302 Found\r\n")
                                            append("Location: $baseUrl/nav-second\r\n")
                                            append("Content-Length: 0\r\n")
                                            append("Connection: close\r\n\r\n")
                                        }.toByteArray(StandardCharsets.UTF_8)
                                        out.write(headers)
                                        out.flush()
                                    }
                                    "/download-bin" -> {
                                        val headers = buildString {
                                            append("HTTP/1.1 200 OK\r\n")
                                            append("Content-Type: application/octet-stream\r\n")
                                            append("Content-Disposition: attachment; filename=\"veko-emulator-artifact.bin\"\r\n")
                                            append("Content-Length: ${SAMPLE_DOWNLOAD_BYTES.size}\r\n")
                                            append("Cache-Control: no-store\r\n")
                                            append("Connection: close\r\n\r\n")
                                        }.toByteArray(StandardCharsets.UTF_8)
                                        out.write(headers)
                                        out.write(SAMPLE_DOWNLOAD_BYTES)
                                        out.flush()
                                    }
                                    "/js-spidermonkey-verify" -> {
                                        val jsHtml = """
                                            <!DOCTYPE html>
                                            <html lang="en">
                                            <head>
                                              <meta charset="utf-8">
                                              <title>Initial-Before-JS</title>
                                            </head>
                                            <body>
                                              <div id="out">pending</div>
                                              <script>
                                                Promise.resolve(Array.from([20, 22]).reduce((a, b) => a + b, 0)).then(sum => {
                                                  const isGecko = navigator.userAgent.includes("Gecko");
                                                  document.getElementById("out").textContent = "Computed:" + sum;
                                                  document.title = "SpiderMonkeyJS:" + sum + ":Gecko=" + isGecko;
                                                });
                                              </script>
                                            </body>
                                            </html>
                                        """.trimIndent()
                                        val bodyBytes = jsHtml.toByteArray(StandardCharsets.UTF_8)
                                        val headers = buildString {
                                            append("HTTP/1.1 200 OK\r\n")
                                            append("Content-Type: text/html; charset=utf-8\r\n")
                                            append("Content-Length: ${bodyBytes.size}\r\n")
                                            append("Cache-Control: no-store\r\n")
                                            append("Connection: close\r\n\r\n")
                                        }.toByteArray(StandardCharsets.UTF_8)
                                        out.write(headers)
                                        out.write(bodyBytes)
                                        out.flush()
                                    }
                                    else -> {
                                        val html = buildHtml(path)
                                        val bodyBytes = html.toByteArray(StandardCharsets.UTF_8)
                                        val headers = buildString {
                                            append("HTTP/1.1 200 OK\r\n")
                                            append("Content-Type: text/html; charset=utf-8\r\n")
                                            append("Content-Length: ${bodyBytes.size}\r\n")
                                            append("Cache-Control: no-store\r\n")
                                            append("Connection: close\r\n\r\n")
                                        }.toByteArray(StandardCharsets.UTF_8)
                                        out.write(headers)
                                        out.write(bodyBytes)
                                        out.flush()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private fun buildHtml(path: String): String = """
            <!DOCTYPE html>
            <html lang="vi">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>Title:$path</title>
            </head>
            <body>
              <h1>Veko Local Test Server</h1>
              <p>Path: $path</p>
            </body>
            </html>
        """.trimIndent()

        override fun close() {
            running = false
            runCatching { serverSocket.close() }
            executor.shutdownNow()
        }

        companion object {
            val SAMPLE_DOWNLOAD_BYTES = "Veko-GeckoView-Binary-Download-Payload-2026\n".repeat(64)
                .toByteArray(StandardCharsets.UTF_8)
        }
    }
}
