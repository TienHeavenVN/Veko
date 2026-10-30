package io.veko.browser.tab

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import io.veko.browser.data.local.BookmarkDao
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryDao
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SettingsBackingStore
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.data.repository.VekoSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TabManagerRegressionTest {

    @Test
    fun `evicts oldest sessions even when serializedEngineState is null and preserves URL and desktop mode`() = runTest {
        val harness = createHarness()
        val view = FakeEngineView()
        harness.tabManager.onAppForeground("activity-1")
        harness.tabManager.attachEngineView(view)

        val createdIds = (1..12).map { index ->
            harness.clockMs += 10L
            harness.tabManager.createTab(
                url = "https://site-$index.example.com/page",
                isPrivate = false,
                select = true,
            )
        }

        // Mở 12 tab liên tiếp khi chưa có bất kỳ onSessionStateChange nào (serializedEngineState == null)
        assertEquals(12, harness.tabManager.uiState.value.tabs.size)
        assertTrue(
            "Live sessions must never exceed MAX_LIVE_SESSIONS (6), got ${harness.tabManager.liveSessionCount()}",
            harness.tabManager.liveSessionCount() <= TabManager.MAX_LIVE_SESSIONS,
        )
        assertEquals(createdIds.last(), harness.tabManager.attachedSessionTabId())
        assertEquals(harness.bridge.sessionForTab(createdIds.last()), view.attachedSession)

        // Không có session nào bị gọi close() quá 1 lần
        harness.bridge.allCreatedSessions.forEach { session ->
            assertTrue("Session closed more than once: ${session.closeCount}", session.closeCount <= 1)
        }

        // Bật Desktop Mode trên tab 12 rồi mở thêm 7 tab nữa để tab 12 bị thu hồi khi vẫn chưa có serializedEngineState
        harness.tabManager.toggleDesktopMode()
        assertTrue(harness.tabManager.uiState.value.activeTab!!.sessionState.isDesktopMode)

        repeat(7) { idx ->
            harness.clockMs += 10L
            harness.tabManager.createTab(url = "https://extra-$idx.example.com", select = true)
        }
        assertFalse("Tab 12 should have been evicted to background", createdIds.last() in harness.tabManager.openSessionIds())

        // Chọn lại tab 1 (đã bị thu hồi sớm nhất): URL không bị mất và tự động nạp lại an toàn
        val firstTabId = createdIds.first()
        val firstTabBeforeSelect = harness.tabManager.uiState.value.tabs.first { it.id == firstTabId }
        assertEquals("https://site-1.example.com/page", firstTabBeforeSelect.sessionState.url)

        harness.clockMs += 10L
        harness.tabManager.selectTab(firstTabId)
        assertEquals(firstTabId, harness.tabManager.attachedSessionTabId())
        val rematerializedFirst = harness.bridge.latestSessionForUrl("https://site-1.example.com/page")
        assertNotNull(rematerializedFirst)
        assertTrue(rematerializedFirst!!.isOpen)

        // Chọn lại tab 12: cả URL lẫn chế độ Desktop đều được khôi phục nguyên vẹn
        harness.clockMs += 10L
        harness.tabManager.selectTab(createdIds.last())
        val rematerializedDesktop = harness.bridge.latestSessionForUrl("https://site-12.example.com/page")
        assertNotNull(rematerializedDesktop)
        assertTrue(rematerializedDesktop!!.desktopModeEnabled)
        assertTrue(harness.tabManager.liveSessionCount() <= TabManager.MAX_LIVE_SESSIONS)
    }

    @Test
    fun `two-tier LRU prefers evicting serialized background tabs first and respects real selectTab access order`() = runTest {
        val harness = createHarness()
        harness.tabManager.onAppForeground("activity-1")
        harness.tabManager.attachEngineView(FakeEngineView())

        val tabIds = (1..6).map { i ->
            harness.clockMs += 10L
            harness.tabManager.createTab(url = "https://lru-$i.example.com", select = true)
        }

        // Chạm lại tab 1 để cập nhật lastAccessedEpochMs mới hơn tab 2..5
        harness.clockMs += 50L
        harness.tabManager.selectTab(tabIds[0])

        // Chỉ tab 4 phát sinh serializedEngineState
        val tab4Session = harness.bridge.latestSessionForUrl("https://lru-4.example.com")!!
        tab4Session.callbacks?.onSessionStateChange(tab4Session, "VALID_STATE_TAB_4")

        // Quay sang tab 6 làm active, sau đó tạo tab 7 -> phải ưu tiên thu hồi tab 4 (đã có serializedEngineState)
        harness.clockMs += 10L
        harness.tabManager.selectTab(tabIds[5])
        harness.clockMs += 10L
        harness.tabManager.createTab(url = "https://lru-7.example.com", select = true)

        assertEquals(TabManager.MAX_LIVE_SESSIONS, harness.tabManager.liveSessionCount())
        assertFalse("Tab 4 with serialized state should be evicted first", tabIds[3] in harness.tabManager.openSessionIds())
        assertTrue("Recently accessed Tab 1 must stay open", tabIds[0] in harness.tabManager.openSessionIds())

        // Tạo tiếp tab 8 khi các tab còn lại đều chưa có serializedEngineState -> phải thu hồi tab 2 (truy cập cũ nhất)
        harness.clockMs += 10L
        harness.tabManager.createTab(url = "https://lru-8.example.com", select = true)
        assertEquals(TabManager.MAX_LIVE_SESSIONS, harness.tabManager.liveSessionCount())
        assertFalse("Oldest unserialized Tab 2 should be evicted next", tabIds[1] in harness.tabManager.openSessionIds())
        assertTrue("Recently accessed Tab 1 must still stay open", tabIds[0] in harness.tabManager.openSessionIds())
    }

    @Test
    fun `popup onNewSession obeys unopened Gecko contract, survives burst of 10 popups and blocks unsafe popup URIs`() = runTest {
        val harness = createHarness(queueUiPosts = true)
        val view = FakeEngineView()
        harness.tabManager.onAppForeground("activity-1")
        harness.tabManager.attachEngineView(view)

        val parentId = harness.tabManager.createTab(url = "https://portal.example.com", select = true)
        val parentSession = harness.bridge.latestSessionForUrl("https://portal.example.com")!!

        // Popup nguy hiểm (javascript: / data:) phải bị từ chối ngay lập tức
        assertNull(parentSession.simulatePopupRequest("javascript:alert(1)"))
        assertNull(parentSession.simulatePopupRequest("data:text/html,<script>evil()</script>"))
        assertEquals(1, harness.tabManager.uiState.value.tabs.size)

        // Mở dồn dập 10 popup hợp lệ: kiểm tra hợp đồng GeckoView (child trả về CHƯA open, parent CHƯA bị đóng lúc handoff)
        repeat(10) { idx ->
            harness.clockMs += 5L
            val child = parentSession.simulatePopupRequest("https://portal.example.com/popup-$idx")
            assertNotNull("Valid popup $idx must return an unopened session", child)
            // Khi GeckoView hoàn tất callback.resolveTo ngay sau onNewSession, child được mở trên runtime của parent
            child!!.openFromEngineHandoff(parentSession)
            harness.flushUiPosts()
        }

        assertEquals(11, harness.tabManager.uiState.value.tabs.size)
        assertTrue(
            "Popup burst must still enforce MAX_LIVE_SESSIONS (6), got ${harness.tabManager.liveSessionCount()}",
            harness.tabManager.liveSessionCount() <= TabManager.MAX_LIVE_SESSIONS,
        )
        val activePopup = harness.tabManager.uiState.value.activeTab!!
        assertEquals("https://portal.example.com/popup-9", activePopup.sessionState.url)
        assertEquals(activePopup.id, harness.tabManager.attachedSessionTabId())

        // window.close() từ popup đóng đúng tab đó và lùi về tab liền kề
        val activePopupSession = view.attachedSession as FakeEngineSession
        activePopupSession.callbacks?.onCloseRequest(activePopupSession)
        assertEquals(10, harness.tabManager.uiState.value.tabs.size)
        assertFalse(harness.tabManager.uiState.value.tabs.any { it.id == activePopup.id })
        assertTrue(parentId in harness.tabManager.uiState.value.tabs.map { it.id })
    }

    @Test
    fun `closing popup before UI handoff runs does not leak session or leave dangling attachedTabId`() = runTest {
        val harness = createHarness(queueUiPosts = true)
        harness.tabManager.onAppForeground("activity-1")
        harness.tabManager.attachEngineView(FakeEngineView())

        harness.tabManager.createTab(url = "https://shop.example.com", select = true)
        val parentSession = harness.bridge.latestSessionForUrl("https://shop.example.com")!!

        val child = parentSession.simulatePopupRequest("https://shop.example.com/checkout") as FakeEngineSession
        val popupTabId = harness.tabManager.uiState.value.activeTabId!!
        child.openFromEngineHandoff(parentSession)

        // Người dùng đóng ngay tab popup trước khi uiPoster kịp chạy
        harness.tabManager.closeTab(popupTabId)
        harness.flushUiPosts()

        assertFalse(popupTabId in harness.tabManager.openSessionIds())
        assertFalse(popupTabId in harness.tabManager.pendingPopupTabIds())
        assertFalse(child.isOpen)
        assertEquals(1, harness.tabManager.uiState.value.tabs.size)
    }

    @Test
    fun `crash, background kill, runtime shutdown and corrupted SessionState recover cleanly without dangling attachedTabId`() = runTest {
        val harness = createHarness()
        val view = FakeEngineView()
        harness.tabManager.onAppForeground("activity-1")
        harness.tabManager.attachEngineView(view)

        val tabId = harness.tabManager.createTab(url = "https://news.example.com/story", select = true)
        val initialSession = view.attachedSession as FakeEngineSession

        // Giả lập SessionState bị hỏng trên tab rồi xảy ra crash nội dung
        initialSession.callbacks?.onSessionStateChange(initialSession, "CORRUPTED_UNPARSEABLE_STATE")
        initialSession.callbacks?.onCrash(initialSession)

        assertNull("attachedTabId must be cleared on crash", harness.tabManager.attachedSessionTabId())
        assertNull("EngineView must release crashed session", view.attachedSession)
        assertTrue(harness.tabManager.uiState.value.activeTab!!.sessionState.isCrashed)

        // Người dùng bấm Tải lại: xóa cờ isCrashed, fallback nạp lại URL gốc khi SessionState hỏng, và chỉ nạp 1 lần
        harness.tabManager.reloadOrStop()
        val recoveredTab = harness.tabManager.uiState.value.activeTab!!
        assertFalse("reloadOrStop must clear isCrashed", recoveredTab.sessionState.isCrashed)
        assertNull("Corrupted serializedEngineState must be cleared after failed restore", recoveredTab.sessionState.serializedEngineState)
        assertEquals(tabId, harness.tabManager.attachedSessionTabId())
        val recoveredSession = view.attachedSession as FakeEngineSession
        assertEquals(listOf("https://news.example.com/story"), recoveredSession.loadedUrls)
        assertEquals("Should not double-reload after materializing", 0, recoveredSession.reloadCount)

        // Trang lỗi nội bộ không được ghi đè mất URL gốc của tab
        val errorPageUrl = recoveredSession.callbacks?.onLoadError(recoveredSession, "https://news.example.com/story", -10)
        assertNotNull(errorPageUrl)
        recoveredSession.callbacks?.onLocationChange(recoveredSession, errorPageUrl)
        assertEquals("https://news.example.com/story", harness.tabManager.uiState.value.activeTab!!.sessionState.url)

        // Khi GeckoRuntime tắt đột ngột (onRuntimeShutdown): không còn session chết, mọi URL vẫn nguyên vẹn
        harness.bridge.triggerRuntimeShutdown()
        assertEquals(0, harness.tabManager.liveSessionCount())
        assertNull(harness.tabManager.attachedSessionTabId())
        assertTrue(harness.tabManager.uiState.value.activeTab!!.sessionState.isCrashed)
        assertEquals("https://news.example.com/story", harness.tabManager.uiState.value.activeTab!!.sessionState.url)
    }

    @Test
    fun `activity recreation overlapping foreground tokens and closing last tab work without losing session`() = runTest {
        val harness = createHarness()
        val oldView = FakeEngineView()
        harness.tabManager.onAppForeground("old-activity")
        harness.tabManager.attachEngineView(oldView)
        val tabId = harness.tabManager.createTab(url = "https://kotlinlang.org", select = true)
        assertEquals(tabId, harness.tabManager.attachedSessionTabId())

        // Activity mới ON_START và gắn GeckoView mới TRƯỚC KHI Activity cũ chạy ON_STOP + onRelease
        val newView = FakeEngineView()
        harness.tabManager.onAppForeground("new-activity")
        harness.tabManager.attachEngineView(newView)
        harness.tabManager.onAppBackground("old-activity")
        harness.tabManager.detachEngineViewByIdentity(oldView.identity)

        assertNull("Old view must be released", oldView.attachedSession)
        assertNotNull("New view must remain attached after old activity stops", newView.attachedSession)
        assertEquals(tabId, harness.tabManager.attachedSessionTabId())

        // Đóng tab cuối cùng: tự mở 1 tab trang chủ mới sạch sẽ
        harness.tabManager.closeTab(tabId)
        assertEquals(1, harness.tabManager.uiState.value.tabs.size)
        val replacement = harness.tabManager.uiState.value.activeTab!!
        assertFalse(replacement.isPrivate)
        assertEquals(SearchEngine.DUCKDUCKGO.homeUrl, replacement.sessionState.url)
        assertEquals(replacement.id, harness.tabManager.attachedSessionTabId())

        // Kiểm tra onTrimMemory thu hồi toàn bộ tab nền
        val bg1 = harness.tabManager.createTab(url = "https://bg1.example.com", select = false)
        val bg2 = harness.tabManager.createTab(url = "https://bg2.example.com", select = false)
        assertEquals(3, harness.tabManager.liveSessionCount())
        harness.tabManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(1, harness.tabManager.liveSessionCount())
        assertFalse(bg1 in harness.tabManager.openSessionIds())
        assertFalse(bg2 in harness.tabManager.openSessionIds())
        assertEquals(3, harness.tabManager.uiState.value.tabs.size)
    }

    @Test
    fun `bootstrapIfNeeded merges tabs created concurrently while disk restore is in flight`() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler)
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()

        val persistedState = SessionPersistenceCodec.encodeRegularTabs(
            BrowserTabsUiState(
                tabs = listOf(
                    Tab(
                        id = "restored-1",
                        sessionState = TabSessionState(url = "https://restored.example.com", title = "Restored"),
                    ),
                ),
                activeTabId = "restored-1",
                isRestoring = false,
            ),
        )

        val delayedStorage = object : TabSessionStorage {
            override suspend fun readPersistedRaw(): Pair<String?, String?> {
                gate.await()
                return persistedState.tabsJson to persistedState.activeRegularTabId
            }

            override suspend fun writePersistedRaw(tabsJson: String, activeRegularTabId: String?) = Unit
        }

        val bridge = FakeEngineSessionBridge()
        val manager = TabManager(
            sessionStorage = delayedStorage,
            engineBridge = bridge,
            dataRepository = BrowserDataRepository(NoOpHistoryDao(), NoOpBookmarkDao(), mainDispatcher),
            settingsRepository = SettingsRepository(InMemorySettingsStore()),
            mainDispatcher = mainDispatcher,
            ioDispatcher = ioDispatcher,
            defaultDispatcher = mainDispatcher,
            uiPoster = { it() },
            isOnMainThread = { true },
            clockMs = { 1_000L },
        )

        manager.bootstrapIfNeeded()
        // Trong lúc đĩa đang đọc dở, một Intent bên ngoài mở tab mới
        val concurrentId = manager.createTab(url = "https://concurrent.example.com", select = true)

        gate.complete(Unit)
        advanceUntilIdle()

        val urls = manager.uiState.value.tabs.map { it.sessionState.url }
        assertEquals(listOf("https://restored.example.com", "https://concurrent.example.com"), urls)
        assertEquals(concurrentId, manager.uiState.value.activeTabId)
    }

    private class Harness(
        val tabManager: TabManager,
        val bridge: FakeEngineSessionBridge,
        val storage: InMemoryTabSessionStorage,
        private val uiQueue: ArrayDeque<() -> Unit>,
        var clockMs: Long = 10_000L,
    ) {
        fun flushUiPosts() {
            while (uiQueue.isNotEmpty()) {
                uiQueue.removeFirst().invoke()
            }
        }
    }

    private fun createHarness(queueUiPosts: Boolean = false): Harness {
        val dispatcher = UnconfinedTestDispatcher()
        val bridge = FakeEngineSessionBridge()
        val storage = InMemoryTabSessionStorage()
        val uiQueue = ArrayDeque<() -> Unit>()
        lateinit var harness: Harness
        val manager = TabManager(
            sessionStorage = storage,
            engineBridge = bridge,
            dataRepository = BrowserDataRepository(NoOpHistoryDao(), NoOpBookmarkDao(), dispatcher),
            settingsRepository = SettingsRepository(InMemorySettingsStore()),
            mainDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            defaultDispatcher = dispatcher,
            uiPoster = { action ->
                if (queueUiPosts) uiQueue.addLast(action) else action()
            },
            isOnMainThread = { true },
            clockMs = { harness.clockMs },
        )
        harness = Harness(manager, bridge, storage, uiQueue)
        return harness
    }
}

internal class InMemoryTabSessionStorage(
    var savedJson: String? = null,
    var savedActiveId: String? = null,
) : TabSessionStorage {
    var writeCount: Int = 0
    override suspend fun readPersistedRaw(): Pair<String?, String?> = savedJson to savedActiveId
    override suspend fun writePersistedRaw(tabsJson: String, activeRegularTabId: String?) {
        savedJson = tabsJson
        savedActiveId = activeRegularTabId
        writeCount++
    }
}

internal class InMemorySettingsStore : SettingsBackingStore {
    private val state = MutableStateFlow(VekoSettings())
    override val flow: Flow<VekoSettings> = state
    override suspend fun updateDynamicColor(enabled: Boolean) {
        state.value = state.value.copy(dynamicColor = enabled)
    }
    override suspend fun updateSearchEngine(engine: SearchEngine) {
        state.value = state.value.copy(searchEngine = engine)
    }
}

internal class FakeEngineView : EngineViewHandle {
    override val identity: Any = Any()
    override var isAlive: Boolean = true
    var attachedSession: EngineSessionHandle? = null

    override fun setSession(session: EngineSessionHandle) {
        check(session.isOpen) { "Cannot attach an unopened session to GeckoView" }
        attachedSession = session
    }

    override fun releaseSession() {
        attachedSession = null
    }

    override fun capturePixelsAsync(onResult: (Bitmap?) -> Unit) = Unit
}

internal class FakeEngineSession(
    val isPrivate: Boolean,
    var desktopModeEnabled: Boolean,
    openImmediately: Boolean,
    var callbacks: EngineSessionCallbacks?,
) : EngineSessionHandle {
    override var isOpen: Boolean = false
        private set

    var openCount: Int = 0
    var closeCount: Int = 0
    var reloadCount: Int = 0
    var active: Boolean = false
    var highPriority: Boolean = false
    val loadedUrls = mutableListOf<String>()

    init {
        if (openImmediately) {
            open()
        }
    }

    override fun open() {
        check(!isOpen) { "GeckoSession is already open" }
        isOpen = true
        openCount++
    }

    fun openFromEngineHandoff(parent: FakeEngineSession) {
        if (isOpen) {
            throw AssertionError("Must use an unopened GeckoSession instance")
        }
        check(parent.isOpen) { "Parent session was closed before popup handoff completed" }
        open()
    }

    fun simulatePopupRequest(uri: String): FakeEngineSession? {
        val result = callbacks?.onNewSession(this, uri) as? FakeEngineSession ?: return null
        if (result.isOpen) {
            throw AssertionError("onNewSession MUST return an unopened session")
        }
        check(this.isOpen) { "Parent session must remain open when onNewSession returns" }
        return result
    }

    override fun close() {
        check(isOpen) { "Attempted to close an already closed session" }
        isOpen = false
        closeCount++
    }

    override fun clearCallbacks() {
        callbacks = null
    }

    override fun loadUri(url: String) {
        check(isOpen) { "Cannot loadUri on closed session" }
        loadedUrls += url
    }

    override fun restoreStateFromString(encodedState: String): Boolean {
        check(isOpen) { "Cannot restoreState on closed session" }
        return encodedState.startsWith("VALID_STATE")
    }

    override fun setActive(active: Boolean) {
        this.active = active
    }

    override fun setPriorityHigh(high: Boolean) {
        this.highPriority = high
    }

    override fun setDesktopMode(enabled: Boolean) {
        this.desktopModeEnabled = enabled
    }

    override fun goBack() = Unit
    override fun goForward() = Unit
    override fun reload() {
        reloadCount++
    }
    override fun stop() = Unit
}

internal class FakeEngineSessionBridge : EngineSessionBridge {
    val allCreatedSessions = mutableListOf<FakeEngineSession>()
    private var shutdownListener: (() -> Unit)? = null

    override fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle {
        return FakeEngineSession(isPrivate, isDesktopMode, openImmediately, callbacks).also {
            allCreatedSessions += it
        }
    }

    override fun wrapGeckoView(geckoView: org.mozilla.geckoview.GeckoView): EngineViewHandle {
        throw UnsupportedOperationException("Use FakeEngineView in JVM unit tests")
    }

    override fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        shutdownListener = listener
    }

    fun triggerRuntimeShutdown() {
        shutdownListener?.invoke()
    }

    fun latestSessionForUrl(url: String): FakeEngineSession? =
        allCreatedSessions.lastOrNull { url in it.loadedUrls }

    fun sessionForTab(tabId: String): FakeEngineSession? =
        allCreatedSessions.lastOrNull()
}

internal class NoOpHistoryDao : HistoryDao {
    val items = mutableListOf<HistoryEntity>()
    private val flow = MutableStateFlow<List<HistoryEntity>>(emptyList())

    override fun observeRecentHistory(limit: Int): Flow<List<HistoryEntity>> = flow
    override suspend fun searchHistory(query: String, limit: Int): List<HistoryEntity> =
        items.filter { it.url.contains(query) || it.title.contains(query) }.take(limit)

    override suspend fun insert(entry: HistoryEntity) {
        items.add(entry.copy(id = (items.size + 1).toLong()))
        flow.value = items.toList()
    }

    override suspend fun deleteById(id: Long) {
        items.removeAll { it.id == id }
        flow.value = items.toList()
    }

    override suspend fun clearAll() {
        items.clear()
        flow.value = emptyList()
    }
}

internal class NoOpBookmarkDao : BookmarkDao {
    val bookmarks = mutableListOf<BookmarkEntity>()
    private val flow = MutableStateFlow<List<BookmarkEntity>>(emptyList())

    override fun observeAllBookmarks(): Flow<List<BookmarkEntity>> = flow
    override fun observeIsBookmarked(url: String): Flow<Boolean> =
        MutableStateFlow(bookmarks.any { it.url == url })

    override suspend fun findByUrl(url: String): BookmarkEntity? =
        bookmarks.firstOrNull { it.url == url }

    override suspend fun upsert(bookmark: BookmarkEntity) {
        bookmarks.removeAll { it.url == bookmark.url }
        bookmarks.add(bookmark.copy(id = (bookmarks.size + 1).toLong()))
        flow.value = bookmarks.toList()
    }

    override suspend fun deleteByUrl(url: String) {
        bookmarks.removeAll { it.url == url }
        flow.value = bookmarks.toList()
    }
}
