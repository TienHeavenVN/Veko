package io.veko.browser.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.ui.readClipboardPlainText

/**
 * Màn hình Trang chủ Veko được phân rã thành các component độc lập, một trách nhiệm:
 * - HomeHeader (Logo + Tên Veko + PrivacyButton + Cài đặt; không có dòng tagline phụ dưới tên Veko)
 * - AddressSearchSection + SearchEngineSelector
 * - QuickActionsSection + QuickActionItem
 * - ShortcutsSection + ShortcutItem + EmptyShortcutsState
 * - ContinueBrowsingSection / PrivateModeNoticeCard
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onAddressQueryChanged: (String) -> Unit,
    onSubmitAddressOrSearch: (String) -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onAddressFocusChanged: (Boolean) -> Unit,
    onSelectTab: (String) -> Unit,
    onOpenTabSwitcher: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onTogglePrivateMode: () -> Unit,
    onCloseAllPrivateTabs: () -> Unit,
    onSaveShortcut: (url: String, title: String?, onResult: (Boolean) -> Unit) -> Unit,
    onDeleteShortcut: (String) -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboardPreview = remember(state.isPrivate) { readClipboardPlainText(context) }

    var isManagingShortcuts by rememberSaveable { mutableStateOf(false) }
    var shortcutDialogDraft by remember { mutableStateOf<ShortcutDraft?>(null) }
    var shortcutErrorText by remember { mutableStateOf<String?>(null) }

    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = if (state.isPrivate) 0.08f else 0.13f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.07f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("home_screen"),
    ) {
        val isWideLayout = maxWidth >= 640.dp
        val isTallMinimalState = !isWideLayout &&
            maxHeight >= 660.dp &&
            !state.feed.hasContinueContent &&
            state.feed.shortcuts.isEmpty()

        val horizontalPadding = when {
            isWideLayout -> 28.dp
            maxWidth < 360.dp -> 12.dp
            else -> 16.dp
        }
        // Khoảng đệm trên có chủ đích khi màn hình trống để bố cục cân đối, không thêm thành phần vô ích
        val topPadding = if (isTallMinimalState) 28.dp else 16.dp

        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.85f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlow, secondaryGlow, Color.Transparent),
                    center = Offset(size.width * 0.22f, 0f),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width * 0.22f, 0f),
            )
        }

        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = topPadding,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier
                .fillMaxSize()
                .imeNestedScroll(),
        ) {
            item(key = "home_hero_block") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .widthIn(max = 1040.dp)
                        .fillMaxWidth(),
                ) {
                    HomeHeader(
                        isPrivate = state.isPrivate,
                        privateTabCount = state.privateTabCount,
                        onTogglePrivateMode = onTogglePrivateMode,
                        onOpenSettings = onOpenSettings,
                    )

                    AddressSearchSection(
                        isPrivate = state.isPrivate,
                        searchEngine = state.searchEngine,
                        clipboardPreview = clipboardPreview,
                        suggestions = state.addressSuggestions,
                        onQueryChanged = onAddressQueryChanged,
                        onSubmit = onSubmitAddressOrSearch,
                        onSelectSearchEngine = onSelectSearchEngine,
                        onPasteAndGo = onSubmitAddressOrSearch,
                        onFocusStateChanged = onAddressFocusChanged,
                    )

                    QuickActionsSection(
                        state = state.quickActions,
                        onOpenTabs = onOpenTabSwitcher,
                        onOpenBookmarks = onOpenBookmarks,
                        onOpenHistory = onOpenHistory,
                        onOpenDownloads = onOpenDownloads,
                    )
                }
            }

            if (isWideLayout) {
                item(key = "home_wide_workspace") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .widthIn(max = 1040.dp)
                            .fillMaxWidth(),
                    ) {
                        if (state.isPrivate) {
                            PrivateModeNoticeCard(
                                privateTabCount = state.privateTabCount,
                                onCloseAllPrivateTabs = onCloseAllPrivateTabs,
                                onOpenRegularTab = onTogglePrivateMode,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            ShortcutsSection(
                                shortcuts = state.feed.shortcuts,
                                isManaging = isManagingShortcuts,
                                onToggleManage = { isManagingShortcuts = !isManagingShortcuts },
                                onAddShortcut = {
                                    shortcutErrorText = null
                                    shortcutDialogDraft = ShortcutDraft()
                                },
                                onOpenShortcut = onSubmitAddressOrSearch,
                                onEditShortcut = { item ->
                                    shortcutErrorText = null
                                    shortcutDialogDraft = ShortcutDraft(
                                        initialUrl = item.url,
                                        initialTitle = item.title,
                                        isEditingExisting = true,
                                    )
                                },
                                onDeleteShortcut = onDeleteShortcut,
                                onViewAllBookmarks = onOpenBookmarks,
                                modifier = Modifier.weight(1f),
                            )

                            ContinueBrowsingSection(
                                openTabs = state.feed.otherOpenRegularTabs,
                                recentVisits = state.feed.recentVisits,
                                showPlaceholderWhenEmpty = true,
                                onSelectTab = onSelectTab,
                                onOpenUrl = onSubmitAddressOrSearch,
                                onDeleteHistoryItem = onDeleteHistoryItem,
                                onOpenAllHistory = onOpenHistory,
                                modifier = Modifier.weight(1.08f),
                            )
                        }
                    }
                }
            } else {
                if (state.isPrivate) {
                    item(key = "home_private_notice") {
                        PrivateModeNoticeCard(
                            privateTabCount = state.privateTabCount,
                            onCloseAllPrivateTabs = onCloseAllPrivateTabs,
                            onOpenRegularTab = onTogglePrivateMode,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    item(key = "home_shortcuts") {
                        ShortcutsSection(
                            shortcuts = state.feed.shortcuts,
                            isManaging = isManagingShortcuts,
                            onToggleManage = { isManagingShortcuts = !isManagingShortcuts },
                            onAddShortcut = {
                                shortcutErrorText = null
                                shortcutDialogDraft = ShortcutDraft()
                            },
                            onOpenShortcut = onSubmitAddressOrSearch,
                            onEditShortcut = { item ->
                                shortcutErrorText = null
                                shortcutDialogDraft = ShortcutDraft(
                                    initialUrl = item.url,
                                    initialTitle = item.title,
                                    isEditingExisting = true,
                                )
                            },
                            onDeleteShortcut = onDeleteShortcut,
                            onViewAllBookmarks = onOpenBookmarks,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.feed.hasContinueContent) {
                        item(key = "home_continue_browsing") {
                            ContinueBrowsingSection(
                                openTabs = state.feed.otherOpenRegularTabs,
                                recentVisits = state.feed.recentVisits,
                                showPlaceholderWhenEmpty = false,
                                onSelectTab = onSelectTab,
                                onOpenUrl = onSubmitAddressOrSearch,
                                onDeleteHistoryItem = onDeleteHistoryItem,
                                onOpenAllHistory = onOpenHistory,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    shortcutDialogDraft?.let { draft ->
        ShortcutEditorDialog(
            draft = draft,
            errorMessage = shortcutErrorText,
            onDismiss = {
                shortcutDialogDraft = null
                shortcutErrorText = null
            },
            onConfirm = { rawUrl, rawTitle ->
                onSaveShortcut(rawUrl, rawTitle) { ok ->
                    if (ok) {
                        shortcutDialogDraft = null
                        shortcutErrorText = null
                    } else {
                        shortcutErrorText = "Vui lòng nhập tên miền hoặc URL http/https hợp lệ (ví dụ: mozilla.org)."
                    }
                }
            },
        )
    }
}
