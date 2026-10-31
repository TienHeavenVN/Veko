package io.veko.browser.privacy

import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.tab.BrowserTabsUiState
import io.veko.browser.tab.FakeEngineSessionBridge
import io.veko.browser.tab.FakeEngineView
import io.veko.browser.tab.InMemorySettingsStore
import io.veko.browser.tab.InMemoryTabSessionStorage
import io.veko.browser.tab.NoOpBookmarkDao
import io.veko.browser.tab.NoOpHistoryDao
import io.veko.browser.tab.SessionPersistenceCodec
import io.veko.browser.tab.Tab
import io.veko.browser.tab.TabManager
import io.veko.browser.tab.TabSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyAndPersistenceTest {

    @Test
    fun `SessionPersistenceCodec strictly strips private tabs and survives corrupted or incompatible payloads`() {
        val regularTab = Tab(
            id = "reg-1",
            isPrivate = false,
            sessionState = TabSessionState(
                url = "https://mozilla.org",
                title = "Mozilla",
                isDesktopMode = true,
                serializedEngineState = "VALID_STATE_REG",
            ),
        )
        val privateTab = Tab(
            id = "priv-secret-id",
            isPrivate = true,
            sessionState = TabSessionState(
                url = "https://private-medical.example.org/token=secret123",
                title = "Confidential Portal",
                serializedEngineState = "SECRET_PRIVATE_ENGINE_STATE",
            ),
        )

        val encoded = SessionPersistenceCodec.encodeRegularTabs(
            BrowserTabsUiState(
                tabs = listOf(regularTab, privateTab),
                activeTabId = privateTab.id,
                isRestoring = false,
            ),
        )

        assertFalse(encoded.tabsJson.contains("priv-secret-id"))
        assertFalse(encoded.tabsJson.contains("private-medical.example.org"))
        assertFalse(encoded.tabsJson.contains("Confidential Portal"))
        assertFalse(encoded.tabsJson.contains("SECRET_PRIVATE_ENGINE_STATE"))
        assertEquals("reg-1", encoded.activeRegularTabId)

        val decoded = SessionPersistenceCodec.decodePersistedTabs(
            rawJson = encoded.tabsJson,
            persistedActiveId = encoded.activeRegularTabId,
        )
        assertEquals(1, decoded.tabs.size)
        assertEquals("https://mozilla.org", decoded.tabs.single().sessionState.url)
        assertTrue(decoded.tabs.single().sessionState.isDesktopMode)

        // Phiên bản schema không tương thích trong tương lai trả về danh sách rỗng an toàn, không crash
        val incompatible = SessionPersistenceCodec.decodePersistedTabs(
            rawJson = """{"schemaVersion": 999, "tabs": [{"id": "x", "url": "https://a.com"}]}""",
            persistedActiveId = "x",
        )
        assertTrue(incompatible.tabs.isEmpty())

        // Tương thích ngược với mảng JSON v1, tự động lọc mục hỏng/isPrivate=true và khử độc URL xấu
        val tamperedLegacyJson = """
            [
              {"id": "ok-1", "url": "https://kotlinlang.org", "title": "Kotlin"},
              {"id": "leaked-priv", "isPrivate": true, "url": "https://secret.example.com"},
              {"id": "bad-url", "url": "javascript:alert(1)", "title": "Bad"},
              "not-an-object"
            ]
        """.trimIndent()
        val migrated = SessionPersistenceCodec.decodePersistedTabs(tamperedLegacyJson, "leaked-priv")
        assertEquals(2, migrated.tabs.size)
        assertEquals("ok-1", migrated.activeTabId)
        assertEquals("https://kotlinlang.org", migrated.tabs[0].sessionState.url)
        assertEquals(TabSessionState.DEFAULT_HOME_URL, migrated.tabs[1].sessionState.url)
    }

    @Test
    fun `private browsing never writes to disk or Room history while preserving regular history and bookmarks`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val historyDao = NoOpHistoryDao()
        val bookmarkDao = NoOpBookmarkDao()
        val dataRepo = BrowserDataRepository(historyDao, bookmarkDao, testDispatcher)
        val storage = InMemoryTabSessionStorage()
        val bridge = FakeEngineSessionBridge()

        val manager = TabManager(
            sessionStorage = storage,
            engineBridge = bridge,
            dataRepository = dataRepo,
            settingsRepository = SettingsRepository(InMemorySettingsStore()),
            mainDispatcher = UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = testDispatcher,
            defaultDispatcher = testDispatcher,
            uiPoster = { it() },
            isOnMainThread = { true },
            clockMs = { 50_000L },
        )

        manager.onAppForeground("main")
        manager.attachEngineView(FakeEngineView())

        // Ghi sẵn lịch sử & dấu trang thường để đảm bảo chế độ riêng tư KHÔNG xóa dữ liệu thường của người dùng
        dataRepo.recordVisitIfRegular("https://regular-news.example.com", "Regular News", isPrivate = false)
        dataRepo.toggleBookmark("https://regular-bookmark.example.com", "Saved Bookmark")
        advanceUntilIdle()
        assertEquals(1, historyDao.items.size)
        assertEquals(1, bookmarkDao.bookmarks.size)

        // Mở tab thường trước, sau đó mở tab riêng tư và thực hiện đầy đủ các sự kiện điều hướng
        manager.createTab(url = "https://public.example.com", isPrivate = false, select = true)
        advanceTimeBy(700L)
        advanceUntilIdle()

        val privId = manager.createTab(url = "https://incognito-bank.example.com", isPrivate = true, select = true)
        val privSession = bridge.latestSessionForUrl("https://incognito-bank.example.com")!!
        privSession.callbacks?.onLocationChange(privSession, "https://incognito-bank.example.com/account")
        privSession.callbacks?.onTitleChange(privSession, "Secret Account")
        privSession.callbacks?.onSessionStateChange(privSession, "VALID_STATE_PRIVATE_SECRET")
        privSession.callbacks?.onPageStop(privSession, true)

        // Hạ ứng dụng xuống nền (lưu immediate = true) kèm một thao tác debounce ngay sau đó
        manager.onAppBackground("main")
        advanceTimeBy(1_000L)
        advanceUntilIdle()

        assertNotNull(storage.savedJson)
        assertFalse("Private URL must never appear in persisted DataStore JSON", storage.savedJson!!.contains("incognito-bank"))
        assertFalse("Private title must never appear in persisted DataStore JSON", storage.savedJson!!.contains("Secret Account"))
        assertFalse("Private state must never appear in persisted DataStore JSON", storage.savedJson!!.contains("VALID_STATE_PRIVATE_SECRET"))
        assertFalse("Private tab ID must never appear in persisted DataStore JSON", storage.savedJson!!.contains(privId))
        assertTrue("Regular tab must still be persisted", storage.savedJson!!.contains("https://public.example.com"))

        // Lịch sử Room chỉ chứa đúng trang thường ban đầu, không chứa trang riêng tư hay URI nội bộ
        dataRepo.recordVisitIfRegular("about:blank", "Blank", isPrivate = false)
        dataRepo.recordVisitIfRegular("data:text/html,hello", "Data", isPrivate = false)
        advanceUntilIdle()

        assertEquals(1, historyDao.items.size)
        assertEquals("https://regular-news.example.com", historyDao.items.single().url)
        assertEquals(1, bookmarkDao.bookmarks.size)
        assertEquals("https://regular-bookmark.example.com", bookmarkDao.bookmarks.single().url)
    }

    @Test
    fun `searchSuggestions combines Bookmarks first and History without duplicate URLs`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val historyDao = NoOpHistoryDao()
        val bookmarkDao = NoOpBookmarkDao()
        val dataRepo = BrowserDataRepository(historyDao, bookmarkDao, testDispatcher)

        dataRepo.toggleBookmark("https://kotlinlang.org/docs", "Kotlin Docs")
        dataRepo.recordVisitIfRegular("https://kotlinlang.org/docs", "Kotlin Docs History Duplicate", isPrivate = false)
        dataRepo.recordVisitIfRegular("https://blog.jetbrains.com/kotlin", "Kotlin Blog", isPrivate = false)
        advanceUntilIdle()

        val suggestions = dataRepo.searchSuggestions("kotlin", limit = 6)
        assertEquals(2, suggestions.size)
        assertEquals("https://kotlinlang.org/docs", suggestions[0].url)
        assertEquals("Kotlin Docs", suggestions[0].title)
        assertEquals("https://blog.jetbrains.com/kotlin", suggestions[1].url)
    }
}
