package io.veko.browser.ui.home

import androidx.compose.runtime.Immutable
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.AddressSuggestion
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SuggestionSource
import io.veko.browser.download.DownloadItem
import io.veko.browser.tab.Tab

@Immutable
data class HomeQuickActionsState(
    val tabCount: Int = 1,
    val bookmarkCount: Int = 0,
    val historyCount: Int = 0,
    val activeDownloadCount: Int = 0,
    val totalDownloadCount: Int = 0,
)

/**
 * Trạng thái UI bất biến của Trang chủ Veko, phản ánh 100% dữ liệu thực từ TabManager, Room và DownloadManager.
 */
@Immutable
data class HomeUiState(
    val isPrivate: Boolean = false,
    val privateTabCount: Int = 0,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val quickActions: HomeQuickActionsState = HomeQuickActionsState(),
    val feed: HomeFeedSnapshot = HomeFeedSnapshot(),
    val addressSuggestions: List<AddressSuggestion> = emptyList(),
) {
    companion object {
        fun from(
            isPrivate: Boolean,
            searchEngine: SearchEngine,
            allTabs: List<Tab>,
            activeTabId: String?,
            bookmarks: List<BookmarkEntity>,
            recentHistory: List<HistoryEntity>,
            downloads: List<DownloadItem>,
            omniboxSuggestions: List<AddressSuggestion>,
            nowEpochMs: Long = System.currentTimeMillis(),
        ): HomeUiState {
            val feed = HomeFeedBuilder.build(
                isPrivate = isPrivate,
                bookmarks = bookmarks,
                allTabs = allTabs,
                activeTabId = activeTabId,
                recentHistory = recentHistory,
                nowEpochMs = nowEpochMs,
            )
            val safeSuggestions = if (isPrivate) {
                omniboxSuggestions.filter { it.source == SuggestionSource.BOOKMARK }
            } else {
                omniboxSuggestions
            }
            return HomeUiState(
                isPrivate = isPrivate,
                privateTabCount = allTabs.count { it.isPrivate },
                searchEngine = searchEngine,
                quickActions = HomeQuickActionsState(
                    tabCount = allTabs.size.coerceAtLeast(1),
                    bookmarkCount = bookmarks.size,
                    historyCount = if (isPrivate) 0 else recentHistory.size,
                    activeDownloadCount = downloads.count { it.isInProgress },
                    totalDownloadCount = downloads.size,
                ),
                feed = feed,
                addressSuggestions = safeSuggestions,
            )
        }
    }
}
