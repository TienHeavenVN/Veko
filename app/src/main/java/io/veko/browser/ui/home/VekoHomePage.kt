package io.veko.browser.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.tab.Tab

/**
 * Cầu nối tương thích ngược sang HomeScreen đã được phân rã component.
 */
@Composable
internal fun VekoHomePage(
    isPrivate: Boolean,
    searchEngine: SearchEngine,
    allTabs: List<Tab>,
    activeTabId: String?,
    bookmarks: List<BookmarkEntity>,
    recentHistory: List<HistoryEntity>,
    activeDownloadCount: Int,
    totalDownloadCount: Int,
    onStartTyping: () -> Unit,
    onPasteAndGo: (String) -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectTab: (String) -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchToPrivateTab: () -> Unit,
    onSwitchToRegularTab: () -> Unit,
    onCloseAllPrivateTabs: () -> Unit,
    onSaveShortcut: (url: String, title: String?, onResult: (Boolean) -> Unit) -> Unit,
    onDeleteShortcut: (String) -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(
        isPrivate,
        searchEngine,
        allTabs,
        activeTabId,
        bookmarks,
        recentHistory,
        activeDownloadCount,
        totalDownloadCount,
    ) {
        val feed = HomeFeedBuilder.build(
            isPrivate = isPrivate,
            bookmarks = bookmarks,
            allTabs = allTabs,
            activeTabId = activeTabId,
            recentHistory = recentHistory,
        )
        HomeUiState(
            isPrivate = isPrivate,
            privateTabCount = allTabs.count { it.isPrivate },
            searchEngine = searchEngine,
            quickActions = HomeQuickActionsState(
                tabCount = allTabs.size.coerceAtLeast(1),
                bookmarkCount = bookmarks.size,
                historyCount = if (isPrivate) 0 else recentHistory.size,
                activeDownloadCount = activeDownloadCount,
                totalDownloadCount = totalDownloadCount,
            ),
            feed = feed,
            addressSuggestions = emptyList(),
        )
    }

    HomeScreen(
        state = state,
        onAddressQueryChanged = {},
        onSubmitAddressOrSearch = onOpenUrl,
        onSelectSearchEngine = onSelectSearchEngine,
        onAddressFocusChanged = { if (it) onStartTyping() },
        onSelectTab = onSelectTab,
        onOpenTabSwitcher = onOpenTabSwitcher,
        onOpenBookmarks = onOpenBookmarks,
        onOpenHistory = onOpenHistory,
        onOpenDownloads = onOpenDownloads,
        onOpenSettings = onOpenSettings,
        onTogglePrivateMode = {
            if (isPrivate) onSwitchToRegularTab() else onSwitchToPrivateTab()
        },
        onCloseAllPrivateTabs = onCloseAllPrivateTabs,
        onSaveShortcut = onSaveShortcut,
        onDeleteShortcut = onDeleteShortcut,
        onDeleteHistoryItem = onDeleteHistoryItem,
        modifier = modifier,
    )
}
