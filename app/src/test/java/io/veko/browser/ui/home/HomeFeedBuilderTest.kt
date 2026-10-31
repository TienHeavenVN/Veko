package io.veko.browser.ui.home

import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.AddressSuggestion
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SuggestionSource
import io.veko.browser.download.DownloadItem
import io.veko.browser.download.DownloadStatus
import io.veko.browser.tab.Tab
import io.veko.browser.tab.TabSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HomeFeedBuilderTest {

    private fun resolveRepoRoot(): File {
        var current: File? = File(".").canonicalFile
        while (current != null) {
            if (File(current, "settings.gradle.kts").isFile && File(current, "app/build.gradle.kts").isFile) {
                return current
            }
            current = current.parentFile
        }
        error("Could not locate repository root")
    }

    @Test
    fun `private mode returns strictly empty HomeFeedSnapshot and never leaks regular bookmarks, tabs or history`() {
        val bookmarks = listOf(
            BookmarkEntity(id = 1L, url = "https://mozilla.org", title = "Mozilla", createdAtEpochMs = 1_000L),
        )
        val allTabs = listOf(
            Tab(
                id = "reg-open",
                isPrivate = false,
                sessionState = TabSessionState(url = "https://kotlinlang.org", title = "Kotlin"),
            ),
            Tab(
                id = "priv-active",
                isPrivate = true,
                sessionState = TabSessionState(url = "about:blank", title = "Trang mới"),
            ),
        )
        val history = listOf(
            HistoryEntity(id = 10L, url = "https://developer.android.com", title = "Android", visitedAtEpochMs = 2_000L),
        )

        val snapshot = HomeFeedBuilder.build(
            isPrivate = true,
            bookmarks = bookmarks,
            allTabs = allTabs,
            activeTabId = "priv-active",
            recentHistory = history,
            nowEpochMs = 60_000L,
        )

        assertTrue(snapshot.shortcuts.isEmpty())
        assertTrue(snapshot.otherOpenRegularTabs.isEmpty())
        assertTrue(snapshot.recentVisits.isEmpty())
        assertFalse(snapshot.hasContinueContent)
    }

    @Test
    fun `regular mode deduplicates open tabs from recent history, strips userinfo and excludes private or blank tabs`() {
        val now = 1_000_000L
        val bookmarks = listOf(
            BookmarkEntity(
                id = 1L,
                url = "https://admin:secret@developer.android.com/jetpack",
                title = "Jetpack",
                createdAtEpochMs = 100L,
            ),
            BookmarkEntity(
                id = 2L,
                url = "javascript:alert(1)",
                title = "Injected",
                createdAtEpochMs = 200L,
            ),
        )
        val allTabs = listOf(
            Tab(
                id = "home-active",
                isPrivate = false,
                lastAccessedEpochMs = 900L,
                sessionState = TabSessionState(url = "about:blank", title = "Trang mới"),
            ),
            Tab(
                id = "reg-older",
                isPrivate = false,
                lastAccessedEpochMs = 500L,
                sessionState = TabSessionState(url = "https://mozilla.org/en-US/", title = "Mozilla"),
            ),
            Tab(
                id = "reg-newer",
                isPrivate = false,
                lastAccessedEpochMs = 800L,
                sessionState = TabSessionState(url = "https://kotlinlang.org/docs", title = "Kotlin Docs"),
            ),
            Tab(
                id = "priv-hidden",
                isPrivate = true,
                lastAccessedEpochMs = 950L,
                sessionState = TabSessionState(url = "https://secret-private.example.org", title = "Secret"),
            ),
        )
        val history = listOf(
            HistoryEntity(id = 1L, url = "https://mozilla.org/en-US", title = "Mozilla Dup", visitedAtEpochMs = now - 30_000L),
            HistoryEntity(id = 2L, url = "https://rust-lang.org/", title = "Rust Lang", visitedAtEpochMs = now - 300_000L),
            HistoryEntity(id = 3L, url = "https://rust-lang.org", title = "Rust Older", visitedAtEpochMs = now - 900_000L),
            HistoryEntity(id = 4L, url = "https://user:pw@sqlite.org/index.html", title = "", visitedAtEpochMs = now - 7_200_000L),
        )

        val snapshot = HomeFeedBuilder.build(
            isPrivate = false,
            bookmarks = bookmarks,
            allTabs = allTabs,
            activeTabId = "home-active",
            recentHistory = history,
            nowEpochMs = now,
        )

        assertEquals(1, snapshot.shortcuts.size)
        val shortcut = snapshot.shortcuts.single()
        assertEquals("https://developer.android.com/jetpack", shortcut.url)
        assertEquals("developer.android.com", shortcut.displayHost)
        assertEquals("D", shortcut.monogram)

        assertEquals(listOf("reg-newer", "reg-older"), snapshot.otherOpenRegularTabs.map { it.id })

        assertEquals(2, snapshot.recentVisits.size)
        assertEquals("https://rust-lang.org/", snapshot.recentVisits[0].url)
        assertEquals("5 phút trước", snapshot.recentVisits[0].relativeTimeLabel)
        assertEquals("https://sqlite.org/index.html", snapshot.recentVisits[1].url)
        assertEquals("sqlite.org", snapshot.recentVisits[1].title)
        assertEquals("2 giờ trước", snapshot.recentVisits[1].relativeTimeLabel)
        assertTrue(snapshot.hasContinueContent)
    }

    @Test
    fun `monogram, accent index and relative time formatting handle edge cases deterministically`() {
        assertEquals("K", HomeFeedBuilder.extractDomainMonogram("www.kotlinlang.org"))
        assertEquals("1", HomeFeedBuilder.extractDomainMonogram("127.0.0.1:8080"))
        assertEquals("V", HomeFeedBuilder.extractDomainMonogram(""))

        val idxA = HomeFeedBuilder.computeAccentIndex("mozilla.org")
        val idxB = HomeFeedBuilder.computeAccentIndex("MOZILLA.ORG")
        assertEquals(idxA, idxB)
        assertTrue(idxA in 0..5)

        val base = 10_000_000_000L
        assertEquals("Vừa xong", HomeFeedBuilder.formatRelativeTimeVi(base - 15_000L, base))
        assertEquals("Hôm qua", HomeFeedBuilder.formatRelativeTimeVi(base - 26 * 3_600_000L, base))
        assertEquals("4 ngày trước", HomeFeedBuilder.formatRelativeTimeVi(base - 4 * 86_400_000L, base))
    }

    @Test
    fun `HomeUiState aggregates real tab counts, active downloads and filters history suggestions in private mode`() {
        val tabs = listOf(
            Tab(id = "t1", isPrivate = false, sessionState = TabSessionState(url = "about:blank")),
            Tab(id = "t2", isPrivate = true, sessionState = TabSessionState(url = "about:blank")),
            Tab(id = "t3", isPrivate = true, sessionState = TabSessionState(url = "https://example.org")),
        )
        val downloads = listOf(
            DownloadItem(
                id = "d1",
                url = "https://example.org/a.zip",
                fileName = "a.zip",
                mimeType = "application/zip",
                status = DownloadStatus.IN_PROGRESS,
            ),
            DownloadItem(
                id = "d2",
                url = "https://example.org/b.pdf",
                fileName = "b.pdf",
                mimeType = "application/pdf",
                status = DownloadStatus.COMPLETED,
            ),
        )
        val rawSuggestions = listOf(
            AddressSuggestion(title = "Saved Doc", url = "https://kotlinlang.org", source = SuggestionSource.BOOKMARK),
            AddressSuggestion(title = "Visited Site", url = "https://history.example", source = SuggestionSource.HISTORY),
        )

        val privateState = HomeUiState.from(
            isPrivate = true,
            searchEngine = SearchEngine.DUCKDUCKGO,
            allTabs = tabs,
            activeTabId = "t2",
            bookmarks = listOf(BookmarkEntity(id = 1L, url = "https://kotlinlang.org", title = "Kotlin", createdAtEpochMs = 1L)),
            recentHistory = listOf(HistoryEntity(id = 2L, url = "https://history.example", title = "Hist", visitedAtEpochMs = 2L)),
            downloads = downloads,
            omniboxSuggestions = rawSuggestions,
        )

        assertTrue(privateState.isPrivate)
        assertEquals(2, privateState.privateTabCount)
        assertEquals(3, privateState.quickActions.tabCount)
        assertEquals(1, privateState.quickActions.activeDownloadCount)
        assertEquals(2, privateState.quickActions.totalDownloadCount)
        assertEquals(0, privateState.quickActions.historyCount)
        assertEquals(1, privateState.addressSuggestions.size)
        assertEquals(SuggestionSource.BOOKMARK, privateState.addressSuggestions.single().source)
    }

    @Test
    fun `HomeHeader removes description line below Veko and Home components are cleanly separated`() {
        val root = resolveRepoRoot()
        val homeDir = File(root, "app/src/main/java/io/veko/browser/ui/home")
        val requiredFiles = listOf(
            "HomeScreen.kt",
            "HomeHeader.kt",
            "PrivacyButton.kt",
            "AddressSearchSection.kt",
            "SearchEngineSelector.kt",
            "QuickActionsSection.kt",
            "ShortcutsSection.kt",
            "HomeUiState.kt",
        )
        for (name in requiredFiles) {
            assertTrue("Expected extracted component file $name", File(homeDir, name).isFile)
        }
        assertTrue(
            "Expected shared BrowserBottomBar.kt",
            File(root, "app/src/main/java/io/veko/browser/ui/navigation/BrowserBottomBar.kt").isFile,
        )

        val headerSource = File(homeDir, "HomeHeader.kt").readText()
        assertFalse(
            "Tagline below Veko must be completely removed from HomeHeader",
            headerSource.contains("Trình duyệt nhân GeckoView") ||
                headerSource.contains("Phiên tách biệt trên RAM") ||
                headerSource.contains("alpha = 0") ||
                headerSource.contains("alpha(0"),
        )
    }
}
