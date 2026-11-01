package io.veko.browser.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.view.View
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.engine.chromium.ChromiumAvailabilityChecker
import io.veko.browser.engine.chromium.ChromiumBrowserEngine
import io.veko.browser.engine.chromium.ChromiumSdkArtifactProbe
import io.veko.browser.engine.chromium.ChromiumSessionManager
import io.veko.browser.mvi.BrowserMviAction
import io.veko.browser.mvi.BrowserMviStore
import io.veko.browser.privacy.FilterListManager
import io.veko.browser.privacy.PrivacyShieldEngine
import io.veko.browser.tab.ChromiumEngineSessionBridge
import io.veko.browser.tab.EngineSessionCallbacks
import io.veko.browser.tab.EngineSessionHandle
import io.veko.browser.tab.EngineViewHandle
import io.veko.browser.tab.FakeEngineSession
import io.veko.browser.tab.FakeEngineView
import io.veko.browser.tab.InMemorySettingsStore
import io.veko.browser.tab.InMemoryTabSessionStorage
import io.veko.browser.tab.NoOpBookmarkDao
import io.veko.browser.tab.NoOpHistoryDao
import io.veko.browser.tab.SessionPersistenceCodec
import io.veko.browser.tab.TabManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class DualEngineArchitectureTest {

    @Test
    fun `ChromiumAvailabilityChecker validates ABI, missing SDK artifacts, fake placeholder provenance and verified native SDK`() {
        // 1) Kiến trúc CPU không tương thích (ví dụ mips64/riscv64) -> UNAVAILABLE + ABI_INCOMPATIBLE
        val unsupportedAbiChecker = ChromiumAvailabilityChecker(
            FakeChromiumProbe(
                abis = listOf("mips64"),
                hasNativeSo = true,
                assets = setOf("veko_content.pak", "icudtl.dat", "v8_context_snapshot_64.bin"),
                hasUpstreamJava = true,
                upstreamSymbolLinked = true,
            ),
        )
        val unsupportedAbi = unsupportedAbiChecker.checkAvailability()
        assertEquals(EngineAvailabilityStatus.UNAVAILABLE, unsupportedAbi.status)
        assertEquals(SdkValidationStatus.ABI_INCOMPATIBLE, unsupportedAbi.sdkValidationStatus)
        assertFalse(unsupportedAbi.abiCompatible)
        assertFalse(unsupportedAbi.isSelectable)

        // 2) Thiếu tạo tác SDK thực tế (.so, .pak, icudtl.dat, v8 snapshot, content_java) -> BLOCKED + MISSING_UPSTREAM_SDK
        val missingSdkChecker = ChromiumAvailabilityChecker(
            FakeChromiumProbe(
                abis = listOf("arm64-v8a"),
                hasNativeSo = false,
                assets = emptySet(),
                hasUpstreamJava = false,
                upstreamSymbolLinked = false,
            ),
        )
        val missingSdk = missingSdkChecker.checkAvailability()
        assertEquals(EngineAvailabilityStatus.BLOCKED, missingSdk.status)
        assertEquals(SdkValidationStatus.MISSING_UPSTREAM_SDK, missingSdk.sdkValidationStatus)
        assertTrue(missingSdk.abiCompatible)
        assertFalse(missingSdk.isSelectable)
        assertEquals("156.0.8078.85", missingSdk.engineVersion)
        assertEquals("0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5", missingSdk.pinnedCommit)
        assertTrue(missingSdk.missingArtifacts.any { it.contains("libveko_content_embedder.so") })
        assertTrue(missingSdk.missingArtifacts.any { it.contains("veko_content.pak") })
        assertTrue(missingSdk.missingArtifacts.any { it.contains("icudtl.dat") })
        assertNotNull(missingSdk.unavailabilityReason)

        // 3) Chống giả mạo: tệp có mặt trên đĩa nhưng C++ JNI báo chưa liên kết Blink/V8 thật -> INVALID_ARTIFACT_PROVENANCE
        val fakePlaceholderChecker = ChromiumAvailabilityChecker(
            FakeChromiumProbe(
                abis = listOf("arm64-v8a"),
                hasNativeSo = true,
                assets = setOf("veko_content.pak", "icudtl.dat", "v8_context_snapshot_64.bin"),
                hasUpstreamJava = true,
                upstreamSymbolLinked = false,
            ),
        )
        val fakeResult = fakePlaceholderChecker.checkAvailability()
        assertEquals(EngineAvailabilityStatus.BLOCKED, fakeResult.status)
        assertEquals(SdkValidationStatus.INVALID_ARTIFACT_PROVENANCE, fakeResult.sdkValidationStatus)
        assertFalse(fakeResult.isSelectable)

        // 4) Đủ toàn bộ gói SDK gốc + ABI hợp lệ + liên kết ký hiệu thực -> AVAILABLE + VERIFIED_NATIVE_SDK
        val verifiedChecker = ChromiumAvailabilityChecker(
            FakeChromiumProbe(
                abis = listOf("arm64-v8a", "armeabi-v7a"),
                hasNativeSo = true,
                assets = setOf("veko_content.pak", "icudtl.dat", "v8_context_snapshot_64.bin"),
                hasUpstreamJava = true,
                upstreamSymbolLinked = true,
            ),
        )
        val verified = verifiedChecker.checkAvailability()
        assertEquals(EngineAvailabilityStatus.AVAILABLE, verified.status)
        assertEquals(SdkValidationStatus.VERIFIED_NATIVE_SDK, verified.sdkValidationStatus)
        assertTrue(verified.abiCompatible)
        assertTrue(verified.isSelectable)
        assertNull(verified.unavailabilityReason)
    }

    @Test
    fun `ChromiumBrowserEngine refuses initialization and session creation when SDK is blocked`() {
        val tempDir = Files.createTempDirectory("veko_blocked_chromium").toFile()
        try {
            val runtime = ChromiumEmbedderRuntime(tempDir)
            val bridge = ChromiumEngineSessionBridge(runtime)
            val sessionManager = ChromiumSessionManager(runtime, bridge)
            val blockedChecker = ChromiumAvailabilityChecker(
                FakeChromiumProbe(
                    abis = listOf("arm64-v8a"),
                    hasNativeSo = false,
                    assets = emptySet(),
                    hasUpstreamJava = false,
                    upstreamSymbolLinked = false,
                ),
            )
            val chromiumEngine = ChromiumBrowserEngine(
                appFilesDir = tempDir,
                availabilityChecker = blockedChecker,
                sessionManager = sessionManager,
            )

            assertFalse(chromiumEngine.initialize())
            assertFalse(chromiumEngine.isInitialized.value)
            assertEquals(0, chromiumEngine.activeSessionCount())

            var threwOnCreateSession = false
            try {
                chromiumEngine.createSession(
                    isPrivate = false,
                    isDesktopMode = false,
                    openImmediately = true,
                    callbacks = NoOpCallbacks,
                )
            } catch (_: IllegalStateException) {
                threwOnCreateSession = true
            }
            assertTrue("Blocked ChromiumBrowserEngine must refuse createSession()", threwOnCreateSession)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `BrowserEngineManager initializes only selected engine lazily and rejects unavailable Chromium selection`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val settingsRepo = SettingsRepository(InMemorySettingsStore())
        val geckoFake = FakeBrowserEngine(EngineType.GECKO, selectable = true)
        val chromiumFake = FakeBrowserEngine(EngineType.CHROMIUM, selectable = false)
        val manager = BrowserEngineManager(
            engines = linkedMapOf(
                EngineType.GECKO to geckoFake,
                EngineType.CHROMIUM to chromiumFake,
            ),
            settingsRepository = settingsRepo,
            mainDispatcher = dispatcher,
        )

        // Khi khởi tạo BrowserEngineManager, KHÔNG engine nào bị gọi initialize() sớm
        assertTrue(manager.initializedEngines.value.isEmpty())
        assertEquals(0, geckoFake.initializeCount)
        assertEquals(0, chromiumFake.initializeCount)

        // Cố chọn Chromium khi chưa có SDK bị từ chối rõ ràng và không lưu vào Settings
        val rejected = manager.selectDefaultEngine(EngineType.CHROMIUM)
        assertTrue(rejected is EngineSelectionResult.Rejected)
        assertEquals(EngineType.GECKO, manager.resolveExecutableEngine(EngineType.CHROMIUM))

        // Tạo phiên mặc định chỉ khởi tạo duy nhất GeckoView; Chromium vẫn giữ 0 lần initialize()
        val session = manager.createSession(
            isPrivate = false,
            isDesktopMode = false,
            openImmediately = true,
            callbacks = NoOpCallbacks,
        )
        assertEquals(EngineType.GECKO, session.engineType)
        assertEquals(setOf(EngineType.GECKO), manager.initializedEngines.value)
        assertEquals(1, geckoFake.initializeCount)
        assertEquals(0, chromiumFake.initializeCount)

        // Low-memory chỉ chuyển tiếp tới engine đã khởi tạo
        manager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(1, geckoFake.lowMemoryCount)
        assertEquals(0, chromiumFake.lowMemoryCount)

        // Hủy engine giải phóng sạch trạng thái initialized
        manager.destroyEngine(EngineType.GECKO)
        assertTrue(manager.initializedEngines.value.isEmpty())
        assertEquals(1, geckoFake.destroyCount)
    }

    @Test
    fun `switching default engine applies only to new tabs, never replaces existing tab engine and preserves URL on explicit open`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val harness = createDualEngineHarness(dispatcher = dispatcher, chromiumSelectable = true)

        // 1) Tab đầu tiên mở bằng mặc định GeckoView -> chỉ GeckoView được khởi tạo
        val geckoTabId = harness.tabManager.createTab(
            url = "https://mozilla.org/en-US/firefox/",
            isPrivate = false,
            select = true,
        )
        val geckoTabBeforeSwitch = harness.tabManager.uiState.value.tabs.first { it.id == geckoTabId }
        assertEquals(EngineType.GECKO, geckoTabBeforeSwitch.engineType)
        assertEquals(setOf(EngineType.GECKO), harness.engineManager.initializedEngines.value)
        assertEquals(0, harness.chromiumEngine.initializeCount)

        // 2) Người dùng chuyển nhân mặc định sang Chromium (khi gói SDK đã được xác thực)
        val selectRes = harness.engineManager.selectDefaultEngine(EngineType.CHROMIUM)
        assertTrue(selectRes is EngineSelectionResult.Selected)

        // Tab đang mở sẵn TUYỆT ĐỐI giữ nguyên EngineType.GECKO và không khởi chạy Chromium ngầm
        val geckoTabAfterSwitch = harness.tabManager.uiState.value.tabs.first { it.id == geckoTabId }
        assertEquals(EngineType.GECKO, geckoTabAfterSwitch.engineType)
        assertEquals(0, harness.chromiumEngine.initializeCount)

        // 3) Tạo tab mới áp dụng nhân mặc định mới (Chromium)
        val chromiumTabId = harness.tabManager.createTab(
            url = "https://www.chromium.org/Home/",
            isPrivate = false,
            select = true,
        )
        val chromiumTab = harness.tabManager.uiState.value.tabs.first { it.id == chromiumTabId }
        assertEquals(EngineType.CHROMIUM, chromiumTab.engineType)
        assertEquals(EngineType.GECKO, harness.tabManager.uiState.value.tabs.first { it.id == geckoTabId }.engineType)
        assertEquals(setOf(EngineType.GECKO, EngineType.CHROMIUM), harness.engineManager.initializedEngines.value)

        // 4) Quay lại tab Gecko rồi chọn "Mở URL hiện tại trong tab mới bằng Chromium": bảo toàn URL mà không ghi đè tab cũ
        harness.tabManager.selectTab(geckoTabId)
        val migratedTabId = harness.tabManager.openCurrentUrlInNewTabWithEngine(EngineType.CHROMIUM)
        assertNotNull(migratedTabId)
        val migratedTab = harness.tabManager.uiState.value.tabs.first { it.id == migratedTabId }
        assertEquals(EngineType.CHROMIUM, migratedTab.engineType)
        assertEquals("https://mozilla.org/en-US/firefox/", migratedTab.sessionState.url)
        assertEquals(EngineType.GECKO, harness.tabManager.uiState.value.tabs.first { it.id == geckoTabId }.engineType)
    }

    @Test
    fun `explicit tab creation with unavailable Chromium is rejected without silent GeckoView substitution`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val harness = createDualEngineHarness(dispatcher = dispatcher, chromiumSelectable = false)

        val geckoId = harness.tabManager.createTab("https://example.com", isPrivate = false, select = true)
        assertEquals(1, harness.tabManager.uiState.value.tabs.size)

        val rejectedTabId = harness.tabManager.createTabWithExplicitEngine(
            url = "https://example.org",
            isPrivate = false,
            engineType = EngineType.CHROMIUM,
            select = true,
        )
        assertNull("Must never silently open a GeckoView tab pretending to be Chromium", rejectedTabId)
        assertNull(harness.tabManager.openCurrentUrlInNewTabWithEngine(EngineType.CHROMIUM))
        assertEquals(1, harness.tabManager.uiState.value.tabs.size)
        assertEquals(geckoId, harness.tabManager.uiState.value.activeTabId)
        assertEquals(0, harness.chromiumEngine.initializeCount)
    }

    @Test
    fun `private browsing and runtime crash isolation remain strictly separated per engine`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val harness = createDualEngineHarness(dispatcher = dispatcher, chromiumSelectable = true)

        val geckoPrivateId = harness.tabManager.createTab(
            url = "https://private-gecko.example",
            isPrivate = true,
            select = true,
            engineType = EngineType.GECKO,
        )
        val chromiumPrivateId = harness.tabManager.createTab(
            url = "https://private-chromium.example",
            isPrivate = true,
            select = true,
            engineType = EngineType.CHROMIUM,
        )

        // Đóng tab riêng tư Chromium chỉ giải phóng ngữ cảnh riêng tư của Chromium, không động tới phiên riêng tư Gecko
        harness.tabManager.closeTab(chromiumPrivateId)
        assertEquals(1, harness.chromiumEngine.privateReleaseZeroCount)
        assertEquals(0, harness.geckoEngine.privateReleaseZeroCount)
        assertEquals(geckoPrivateId, harness.tabManager.uiState.value.activeTabId)

        // Mở thêm tab thường Chromium rồi giả lập sự cố sập runtime của riêng Gecko: tab Chromium vẫn hoạt động bình thường
        val chromiumRegularId = harness.tabManager.createTab(
            url = "https://healthy-chromium.example",
            isPrivate = false,
            select = true,
            engineType = EngineType.CHROMIUM,
        )
        harness.geckoEngine.simulateUnexpectedShutdown()

        val geckoAfterCrash = harness.tabManager.uiState.value.tabs.first { it.id == geckoPrivateId }
        val chromiumAfterCrash = harness.tabManager.uiState.value.tabs.first { it.id == chromiumRegularId }
        assertTrue("Gecko tab should be marked crashed on Gecko runtime shutdown", geckoAfterCrash.sessionState.isCrashed)
        assertFalse("Chromium tab must stay healthy when Gecko runtime crashes", chromiumAfterCrash.sessionState.isCrashed)
    }

    @Test
    fun `SessionPersistenceCodec v3 persists per-tab EngineType and sanitizes unavailable engine on restore without state corruption`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val dualHarness = createDualEngineHarness(dispatcher = dispatcher, chromiumSelectable = true)
        dualHarness.tabManager.createTab(
            url = "https://gecko.example.org/docs",
            isPrivate = false,
            select = true,
            engineType = EngineType.GECKO,
        )
        val chromiumId = dualHarness.tabManager.createTab(
            url = "https://chromium.example.org/blink",
            isPrivate = false,
            select = true,
            engineType = EngineType.CHROMIUM,
        )
        val chromSession = dualHarness.chromiumEngine.createdSessions.last()
        chromSession.callbacks?.onSessionStateChange(chromSession, "CHROMIUM_SERIALIZED_BLOB_V1")

        // Lưu xuống bộ mã hóa v3
        val encoded = SessionPersistenceCodec.encodeRegularTabs(dualHarness.tabManager.uiState.value)
        val decoded = SessionPersistenceCodec.decodePersistedTabs(encoded.tabsJson, encoded.activeRegularTabId)
        assertEquals(2, decoded.tabs.size)
        assertEquals(EngineType.GECKO, decoded.tabs[0].engineType)
        assertEquals(EngineType.CHROMIUM, decoded.tabs[1].engineType)
        assertEquals(chromiumId, decoded.activeTabId)

        // Khôi phục trên thiết bị/bản dựng mà Chromium SDK không còn khả dụng:
        // Tab phải chuyển trung thực về EngineType.GECKO và XÓA bỏ serializedEngineState của Chromium để tránh nhiễm bẩn phiên!
        val fallbackStorage = InMemoryTabSessionStorage(
            savedJson = encoded.tabsJson,
            savedActiveId = encoded.activeRegularTabId,
        )
        val geckoOnlyHarness = createDualEngineHarness(
            dispatcher = dispatcher,
            chromiumSelectable = false,
            storage = fallbackStorage,
        )
        geckoOnlyHarness.tabManager.bootstrapIfNeeded()

        val restoredTabs = geckoOnlyHarness.tabManager.uiState.value.tabs
        assertEquals(2, restoredTabs.size)
        assertTrue(restoredTabs.all { it.engineType == EngineType.GECKO })
        val convertedTab = restoredTabs.first { it.id == chromiumId }
        assertEquals("https://chromium.example.org/blink", convertedTab.sessionState.url)
        assertNull(
            "Cross-engine serializedEngineState must be cleared when falling back to Gecko",
            convertedTab.sessionState.serializedEngineState,
        )
    }

    @Test
    fun `JavaScriptEvaluationPolicy enforces strict origin and scheme boundaries`() {
        assertNull(
            JavaScriptEvaluationPolicy.validate(
                currentUrl = "https://developer.mozilla.org/en-US/",
                script = "document.title",
            ),
        )
        assertNotNull(JavaScriptEvaluationPolicy.validate("about:blank", "1 + 1"))
        assertNotNull(JavaScriptEvaluationPolicy.validate("file:///sdcard/Download/secret.html", "document.body.innerHTML"))
        assertNotNull(JavaScriptEvaluationPolicy.validate("data:text/html,<h1>Error</h1>", "window.origin"))
        assertNotNull(JavaScriptEvaluationPolicy.validate("https://example.com", "javascript:alert(1)"))
        assertNotNull(JavaScriptEvaluationPolicy.validate("https://example.com", "   "))
        assertNotNull(JavaScriptEvaluationPolicy.validate("https://example.com", "a".repeat(20_000)))
        assertNotNull(
            JavaScriptEvaluationPolicy.validate(
                currentUrl = "https://example.com",
                script = "1 + 1",
                hostEvaluationAllowed = false,
            ),
        )
    }

    @Test
    fun `BrowserMviStore synchronizes engine availabilities and dispatches engine selection intents cleanly`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val harness = createDualEngineHarness(dispatcher = dispatcher, chromiumSelectable = false)
        val shield = PrivacyShieldEngine(FilterListManager())
        val store = BrowserMviStore(
            tabManager = harness.tabManager,
            settingsRepository = harness.settingsRepository,
            privacyShieldEngine = shield,
            multiEngineBridge = harness.engineManager,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )

        assertEquals(2, store.state.value.engineAvailabilities.size)
        assertTrue(store.state.value.engineAvailabilities.getValue(EngineType.GECKO).isSelectable)
        assertFalse(store.state.value.engineAvailabilities.getValue(EngineType.CHROMIUM).isSelectable)

        store.dispatch(BrowserMviAction.UserIntent.SelectPreferredEngine(EngineType.CHROMIUM))
        assertEquals(EngineType.GECKO, store.state.value.userSettings.preferredEngine)
    }

    @Test
    fun `Compose UI layer and MainActivity do not import GeckoView or Chromium internal classes`() {
        val repoRoot = sequenceOf(
            System.getProperty("veko.repo.root")?.let(::File),
            File(".").canonicalFile,
            File("..").canonicalFile,
        ).filterNotNull().first { File(it, "settings.gradle.kts").isFile }

        val uiFiles = listOf(
            File(repoRoot, "app/src/main/java/io/veko/browser/MainActivity.kt"),
            File(repoRoot, "app/src/main/java/io/veko/browser/ui/BrowserScreen.kt"),
            File(repoRoot, "app/src/main/java/io/veko/browser/ui/BrowserOverlays.kt"),
            File(repoRoot, "app/src/main/java/io/veko/browser/ui/PrivacyShieldSheet.kt"),
        )
        for (file in uiFiles) {
            assertTrue("Expected UI file ${file.path} to exist", file.isFile)
            val content = file.readText()
            assertFalse(
                "UI file ${file.name} must not import org.mozilla.geckoview.* directly",
                content.contains("import org.mozilla.geckoview."),
            )
            assertFalse(
                "UI file ${file.name} must not import org.chromium.* directly",
                content.contains("import org.chromium."),
            )
        }
    }

    private data class DualEngineHarness(
        val geckoEngine: FakeBrowserEngine,
        val chromiumEngine: FakeBrowserEngine,
        val engineManager: BrowserEngineManager,
        val settingsRepository: SettingsRepository,
        val tabManager: TabManager,
    )

    private fun createDualEngineHarness(
        dispatcher: CoroutineDispatcher,
        chromiumSelectable: Boolean,
        storage: InMemoryTabSessionStorage = InMemoryTabSessionStorage(),
    ): DualEngineHarness {
        val settingsRepo = SettingsRepository(InMemorySettingsStore())
        val geckoEngine = FakeBrowserEngine(EngineType.GECKO, selectable = true)
        val chromiumEngine = FakeBrowserEngine(EngineType.CHROMIUM, selectable = chromiumSelectable)
        val engineManager = BrowserEngineManager(
            engines = linkedMapOf(
                EngineType.GECKO to geckoEngine,
                EngineType.CHROMIUM to chromiumEngine,
            ),
            settingsRepository = settingsRepo,
            mainDispatcher = dispatcher,
        )
        val tabManager = TabManager(
            sessionStorage = storage,
            engineBridge = engineManager,
            dataRepository = BrowserDataRepository(NoOpHistoryDao(), NoOpBookmarkDao(), dispatcher),
            settingsRepository = settingsRepo,
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher,
            uiPoster = { it() },
            isOnMainThread = { true },
            clockMs = { 1_762_000_000_000L },
        )
        tabManager.onAppForeground("test-owner")
        return DualEngineHarness(
            geckoEngine = geckoEngine,
            chromiumEngine = chromiumEngine,
            engineManager = engineManager,
            settingsRepository = settingsRepo,
            tabManager = tabManager,
        )
    }

    private class FakeChromiumProbe(
        private val abis: List<String>,
        private val hasNativeSo: Boolean,
        private val assets: Set<String>,
        private val hasUpstreamJava: Boolean,
        private val upstreamSymbolLinked: Boolean,
    ) : ChromiumSdkArtifactProbe {
        override fun deviceSupportedAbis(): List<String> = abis
        override fun hasNativeContentSharedLibrary(abi: String): Boolean = hasNativeSo
        override fun presentAssetNames(): Set<String> = assets
        override fun hasUpstreamJavaRuntimeClasses(): Boolean = hasUpstreamJava
        override fun isUpstreamBlinkV8SymbolLinked(): Boolean = upstreamSymbolLinked
    }

    private class FakeBrowserEngine(
        override val engineType: EngineType,
        private val selectable: Boolean,
    ) : BrowserEngine {
        private val _initialized = MutableStateFlow(false)
        override val isInitialized: StateFlow<Boolean> = _initialized.asStateFlow()
        override val capabilities: EngineCapabilities = when (engineType) {
            EngineType.GECKO -> EngineCapabilities.defaultForGecko()
            EngineType.CHROMIUM -> EngineCapabilities.defaultForChromium(selectable)
        }

        var initializeCount: Int = 0
        var destroyCount: Int = 0
        var lowMemoryCount: Int = 0
        var privateReleaseZeroCount: Int = 0
        val createdSessions = mutableListOf<FakeEngineSession>()
        private var shutdownListener: (() -> Unit)? = null

        override fun checkAvailability(): EngineAvailability = if (selectable) {
            EngineAvailability(
                engineType = engineType,
                engineName = engineType.displayName,
                engineVersion = if (engineType == EngineType.GECKO) "150.0.20260511200624" else "156.0.8078.85",
                pinnedCommit = if (engineType == EngineType.CHROMIUM) "0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5" else null,
                status = EngineAvailabilityStatus.AVAILABLE,
                renderingEngine = engineType.renderingEngineName,
                javascriptEngine = engineType.javascriptEngineName,
                deviceAbis = listOf("arm64-v8a"),
                abiCompatible = true,
                sdkValidationStatus = if (engineType == EngineType.GECKO) {
                    SdkValidationStatus.VERIFIED_MAVEN_SDK
                } else {
                    SdkValidationStatus.VERIFIED_NATIVE_SDK
                },
                isSelectable = true,
            )
        } else {
            EngineAvailability(
                engineType = engineType,
                engineName = engineType.displayName,
                engineVersion = "156.0.8078.85",
                pinnedCommit = "0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5",
                status = EngineAvailabilityStatus.BLOCKED,
                renderingEngine = engineType.renderingEngineName,
                javascriptEngine = engineType.javascriptEngineName,
                deviceAbis = listOf("arm64-v8a"),
                abiCompatible = true,
                sdkValidationStatus = SdkValidationStatus.MISSING_UPSTREAM_SDK,
                isSelectable = false,
                unavailabilityReason = "Missing upstream Chromium Content SDK artifacts.",
                missingArtifacts = listOf("libveko_content_embedder.so"),
            )
        }

        override fun initialize(): Boolean {
            if (!selectable) return false
            initializeCount++
            _initialized.value = true
            return true
        }

        override fun destroy() {
            destroyCount++
            _initialized.value = false
        }

        override fun onLowMemory(level: Int) {
            lowMemoryCount++
        }

        override fun createPlatformView(context: Context): View = View(context)

        override fun wrapPlatformView(platformView: View): EngineViewHandle =
            FakeEngineView(engineType = engineType)

        override fun createSession(
            isPrivate: Boolean,
            isDesktopMode: Boolean,
            openImmediately: Boolean,
            callbacks: EngineSessionCallbacks,
        ): EngineSessionHandle {
            check(selectable) { "Engine $engineType is not selectable" }
            return FakeEngineSession(
                isPrivate = isPrivate,
                desktopModeEnabled = isDesktopMode,
                openImmediately = openImmediately,
                callbacks = callbacks,
                engineType = engineType,
            ).also { createdSessions += it }
        }

        override fun activeSessionCount(): Int = createdSessions.count { it.isOpen }

        override fun activePrivateSessionCount(): Int = createdSessions.count { it.isPrivate && it.isOpen }

        override fun releasePrivateContextIfUnused(activePrivateTabsForEngine: Int) {
            if (activePrivateTabsForEngine <= 0) {
                privateReleaseZeroCount++
            }
        }

        override fun clearBrowsingStorage(
            includeCookies: Boolean,
            includeCache: Boolean,
            onlyPrivate: Boolean,
        ) = Unit

        override fun applyCookiePolicy(mode: CookieControlMode) = Unit

        override fun applyPageZoom(autoFontSize: Boolean, zoomPercent: Int) = Unit

        override fun evaluateJavaScript(
            session: EngineSessionHandle,
            currentUrl: String,
            script: String,
            onResult: (JavaScriptEvalResult) -> Unit,
        ) = session.evaluateJavaScript(currentUrl, script, onResult)

        override fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
            shutdownListener = listener
        }

        fun simulateUnexpectedShutdown() {
            _initialized.value = false
            shutdownListener?.invoke()
        }
    }

    private object NoOpCallbacks : EngineSessionCallbacks {
        override fun onLocationChange(session: EngineSessionHandle, url: String?) = Unit
        override fun onCanGoBack(session: EngineSessionHandle, canGoBack: Boolean) = Unit
        override fun onCanGoForward(session: EngineSessionHandle, canGoForward: Boolean) = Unit
        override fun onLoadRequest(session: io.veko.browser.tab.EngineSessionHandle, request: io.veko.browser.tab.EngineLoadRequest): Boolean = true
        override fun onNewSession(session: EngineSessionHandle, uri: String): EngineSessionHandle? = null
        override fun onLoadError(session: EngineSessionHandle, uri: String?, errorCode: Int): String? = null
        override fun onPageStart(session: EngineSessionHandle, url: String) = Unit
        override fun onProgressChange(session: EngineSessionHandle, progress: Int) = Unit
        override fun onPageStop(session: EngineSessionHandle, success: Boolean) = Unit
        override fun onSecurityChange(session: EngineSessionHandle, isSecure: Boolean, origin: String?) = Unit
        override fun onSessionStateChange(session: EngineSessionHandle, serializedState: String) = Unit
        override fun onTitleChange(session: EngineSessionHandle, title: String?) = Unit
        override fun onCloseRequest(session: EngineSessionHandle) = Unit
        override fun onKill(session: EngineSessionHandle) = Unit
        override fun onCrash(session: EngineSessionHandle) = Unit
    }
}
