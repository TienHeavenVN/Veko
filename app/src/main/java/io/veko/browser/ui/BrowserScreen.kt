package io.veko.browser.ui

import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.veko.browser.BrowserViewModel
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.tab.SecurityState
import io.veko.browser.tab.Tab
import io.veko.browser.tab.TabSessionState
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

/**
 * Màn hình trình duyệt chính của Veko:
 * - Bọc duy nhất 1 GeckoView bằng AndroidView để tái sử dụng bề mặt vẽ (SurfaceView) xuyên suốt mọi tab.
 * - Đặt thanh AddressBar + TabBar ở cạnh dưới cho thao tác một tay (one-handed UX).
 * - Tích hợp cử chỉ vuốt ngang đổi tab, vuốt lên mở khay xem trước tab, cùng Dấu trang/Lịch sử/Cài đặt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.recentHistory.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isActiveTabBookmarked.collectAsStateWithLifecycle()

    val activeTab = uiState.activeTab
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current

    var isEditingUrl by rememberSaveable { mutableStateOf(false) }
    var urlDraft by rememberSaveable { mutableStateOf("") }
    var isTabSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isLibrarySheetOpen by rememberSaveable { mutableStateOf(false) }
    var isSettingsSheetOpen by rememberSaveable { mutableStateOf(false) }

    // Gắn vòng đời Activity (ON_START / ON_STOP) để tự động detach GeckoSession khi app xuống nền
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Ưu tiên nút Back hệ thống cho lịch sử trang web trước khi thoát ứng dụng
    BackHandler(enabled = isEditingUrl || activeTab?.sessionState?.canGoBack == true) {
        if (isEditingUrl) {
            isEditingUrl = false
            focusManager.clearFocus()
        } else {
            viewModel.onBack()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Vùng hiển thị nội dung web chiếm trọn không gian phía trên
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    GeckoView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // Gắn GeckoView duy nhất vào TabManager ngay khi khởi tạo View
                        viewModel.onAttachView(this)
                    }
                },
                update = { geckoView ->
                    viewModel.onAttachView(geckoView)
                },
                onRelease = { geckoView ->
                    // Giải phóng session an toàn khi AndroidView rời cây Compose
                    viewModel.onDetachView(geckoView)
                },
            )

            // Lớp phủ khôi phục khi tiến trình con của tab bị crash bất ngờ
            if (activeTab?.sessionState?.isCrashed == true) {
                CrashedTabOverlay(
                    onReload = viewModel::onReloadOrStop,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Thanh tiến trình mảnh đặt sát mép dưới vùng web, gần tầm mắt ngón cái
            androidx.compose.animation.AnimatedVisibility(
                visible = activeTab?.sessionState?.isLoading == true,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                LinearProgressIndicator(
                    progress = { (activeTab?.sessionState?.progress ?: 0) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
        }

        // Thanh điều hướng một tay dưới đáy: tích hợp AddressBar + Gesture + Tab Counter + Menu
        if (isEditingUrl) {
            OmniboxInputBar(
                initialText = urlDraft,
                onDismiss = {
                    isEditingUrl = false
                    focusManager.clearFocus()
                },
                onSubmit = { submitted ->
                    isEditingUrl = false
                    focusManager.clearFocus()
                    viewModel.onSubmitInput(submitted)
                },
            )
        } else {
            GestureBottomToolbar(
                activeTab = activeTab,
                tabCount = uiState.tabs.size,
                isBookmarked = isBookmarked,
                onAddressClick = {
                    urlDraft = activeTab?.sessionState?.url.orEmpty()
                    isEditingUrl = true
                },
                onBackClick = viewModel::onBack,
                onForwardClick = viewModel::onForward,
                onReloadOrStopClick = viewModel::onReloadOrStop,
                onOpenTabSheet = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.onPrepareTabSwitcher()
                    isTabSheetOpen = true
                },
                onSwipeHorizontalTab = { direction ->
                    if (viewModel.onSwipeAddressBar(direction)) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onToggleBookmark = viewModel::onToggleBookmark,
                onToggleDesktopMode = viewModel::onToggleDesktopMode,
                onOpenLibrary = { isLibrarySheetOpen = true },
                onOpenSettings = { isSettingsSheetOpen = true },
            )
        }
    }

    // Khay quản lý Tab dạng Bottom Sheet với thẻ xem trước (Snapshot Preview)
    if (isTabSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isTabSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            TabSwitcherSheetContent(
                tabs = uiState.tabs,
                activeTabId = uiState.activeTabId,
                onSelectTab = { id ->
                    viewModel.onSelectTab(id)
                    isTabSheetOpen = false
                },
                onCloseTab = viewModel::onCloseTab,
                onNewRegularTab = {
                    viewModel.onCreateTab(settings.searchEngine.homeUrl, false)
                    isTabSheetOpen = false
                },
                onNewPrivateTab = {
                    viewModel.onCreateTab(TabSessionState.DEFAULT_HOME_URL, true)
                    isTabSheetOpen = false
                },
            )
        }
    }

    // Khay Thư viện (Dấu trang & Lịch sử từ Room)
    if (isLibrarySheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isLibrarySheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            LibrarySheetContent(
                bookmarks = bookmarks,
                history = history,
                onOpenUrl = { url ->
                    viewModel.onSubmitInput(url)
                    isLibrarySheetOpen = false
                },
                onDeleteBookmark = viewModel::onDeleteBookmark,
                onDeleteHistoryItem = viewModel::onDeleteHistoryItem,
                onClearHistory = viewModel::onClearHistory,
            )
        }
    }

    // Khay Cài đặt (Material You Dynamic Color + Công cụ tìm kiếm mặc định)
    if (isSettingsSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isSettingsSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SettingsSheetContent(
                settings = settings,
                onDynamicColorChange = viewModel::onSetDynamicColor,
                onSearchEngineSelect = viewModel::onSetSearchEngine,
            )
        }
    }
}

@Composable
private fun GestureBottomToolbar(
    activeTab: Tab?,
    tabCount: Int,
    isBookmarked: Boolean,
    onAddressClick: () -> Unit,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onReloadOrStopClick: () -> Unit,
    onOpenTabSheet: () -> Unit,
    onSwipeHorizontalTab: (Int) -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var gestureConsumed by remember { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }

    val containerColor = if (activeTab?.isPrivate == true) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            IconButton(
                onClick = onBackClick,
                enabled = activeTab?.sessionState?.canGoBack == true,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại")
            }

            if (activeTab?.sessionState?.canGoForward == true) {
                IconButton(onClick = onForwardClick) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Tiến tới")
                }
            }

            // Viên thuốc địa chỉ hỗ trợ cử chỉ: vuốt ngang đổi tab, vuốt lên mở khay tab, chạm để gõ URL
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(containerColor)
                    .pointerInput(tabCount) {
                        detectDragGestures(
                            onDragStart = {
                                dragDx = 0f
                                dragDy = 0f
                                gestureConsumed = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (gestureConsumed) return@detectDragGestures
                                dragDx += dragAmount.x
                                dragDy += dragAmount.y
                                // Vuốt lên vượt ngưỡng 56px -> mở ngay khay chuyển tab một tay
                                if (dragDy < -56f && abs(dragDy) > abs(dragDx)) {
                                    gestureConsumed = true
                                    onOpenTabSheet()
                                } else if (abs(dragDx) > 72f && abs(dragDx) > abs(dragDy)) {
                                    // Vuốt sang trái (+1 tab kế) hoặc sang phải (-1 tab trước)
                                    gestureConsumed = true
                                    onSwipeHorizontalTab(if (dragDx < 0f) 1 else -1)
                                }
                            },
                        )
                    }
                    .clickable(onClick = onAddressClick)
                    .padding(horizontal = 14.dp),
            ) {
                SecurityBadgeIcon(
                    isPrivate = activeTab?.isPrivate == true,
                    securityState = activeTab?.sessionState?.securityState ?: SecurityState.LOCAL_OR_UNKNOWN,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDisplayHost(activeTab?.sessionState?.url),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            IconButton(onClick = onReloadOrStopClick) {
                Icon(
                    imageVector = if (activeTab?.sessionState?.isLoading == true) {
                        Icons.Rounded.Close
                    } else {
                        Icons.Rounded.Refresh
                    },
                    contentDescription = "Tải lại hoặc dừng",
                )
            }

            // Nút đếm số lượng tab bo góc đặc trưng của trình duyệt di động hiện đại
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(
                        width = 1.8.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable(onClick = onOpenTabSheet),
            ) {
                Text(
                    text = tabCount.coerceAtLeast(1).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            Box {
                IconButton(onClick = { isMenuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Tùy chọn thêm")
                }
                DropdownMenu(
                    expanded = isMenuExpanded,
                    onDismissRequest = { isMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (isBookmarked) "Bỏ dấu trang" else "Lưu dấu trang") },
                        leadingIcon = {
                            Icon(
                                imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            isMenuExpanded = false
                            onToggleBookmark()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (activeTab?.sessionState?.isDesktopMode == true) {
                                    "Chế độ di động"
                                } else {
                                    "Trang web cho máy tính"
                                },
                            )
                        },
                        leadingIcon = { Icon(Icons.Rounded.DesktopWindows, contentDescription = null) },
                        onClick = {
                            isMenuExpanded = false
                            onToggleDesktopMode()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Dấu trang & Lịch sử") },
                        leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
                        onClick = {
                            isMenuExpanded = false
                            onOpenLibrary()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Cài đặt Veko") },
                        leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        onClick = {
                            isMenuExpanded = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OmniboxInputBar(
    initialText: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Tìm kiếm hoặc nhập địa chỉ web...") },
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { onSubmit(text) },
                ),
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = { text = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Xóa")
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onDismiss) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Hủy")
            }
        }
    }
}

@Composable
private fun TabSwitcherSheetContent(
    tabs: List<Tab>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewRegularTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Tab đang mở (${tabs.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onNewPrivateTab) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Riêng tư")
                }
                Button(onClick = onNewRegularTab) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tab mới")
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
        ) {
            items(items = tabs, key = { it.id }) { tab ->
                val isSelected = tab.id == activeTabId
                val borderColor = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    tab.isPrivate -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = if (isSelected) 4.dp else 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = borderColor,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .clickable { onSelectTab(tab.id) },
                ) {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        ) {
                            if (tab.isPrivate) {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = "Tab riêng tư",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = tab.sessionState.title.ifBlank { formatDisplayHost(tab.sessionState.url) },
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { onCloseTab(tab.id) },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Đóng tab",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

                        // Hiển thị ảnh chụp xem trước đã hạ độ phân giải hoặc khung nền đại diện nhẹ
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.85f)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            val preview = tab.previewBitmap
                            if (preview != null) {
                                Image(
                                    bitmap = preview,
                                    contentDescription = tab.sessionState.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    text = formatDisplayHost(tab.sessionState.url),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySheetContent(
    bookmarks: List<BookmarkEntity>,
    history: List<HistoryEntity>,
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    onClearHistory: () -> Unit,
) {
    var showingBookmarks by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showingBookmarks,
                    onClick = { showingBookmarks = true },
                    label = { Text("Dấu trang (${bookmarks.size})") },
                )
                FilterChip(
                    selected = !showingBookmarks,
                    onClick = { showingBookmarks = false },
                    label = { Text("Lịch sử (${history.size})") },
                )
            }
            if (!showingBookmarks && history.isNotEmpty()) {
                TextButton(onClick = onClearHistory) {
                    Text("Xóa tất cả")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showingBookmarks) {
                if (bookmarks.isEmpty()) {
                    item {
                        Text(
                            text = "Chưa có dấu trang nào được lưu.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    items(items = bookmarks, key = { it.id }) { item ->
                        LibraryRowItem(
                            title = item.title,
                            url = item.url,
                            onClick = { onOpenUrl(item.url) },
                            onDelete = { onDeleteBookmark(item.url) },
                        )
                    }
                }
            } else {
                if (history.isEmpty()) {
                    item {
                        Text(
                            text = "Lịch sử duyệt web trống.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                } else {
                    items(items = history, key = { it.id }) { entry ->
                        LibraryRowItem(
                            title = entry.title,
                            url = entry.url,
                            onClick = { onOpenUrl(entry.url) },
                            onDelete = { onDeleteHistoryItem(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryRowItem(
    title: String,
    url: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Xóa mục")
            }
        }
    }
}

@Composable
private fun SettingsSheetContent(
    settings: VekoSettings,
    onDynamicColorChange: (Boolean) -> Unit,
    onSearchEngineSelect: (SearchEngine) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Cài đặt Veko",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Màu động Material You",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Tự động đồng bộ màu giao diện theo hình nền Android 12+",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.dynamicColor,
                onCheckedChange = onDynamicColorChange,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Công cụ tìm kiếm mặc định",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))

        SearchEngine.entries.forEach { engine ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSearchEngineSelect(engine) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(
                    selected = settings.searchEngine == engine,
                    onClick = { onSearchEngineSelect(engine) },
                )
                Text(
                    text = engine.label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun SecurityBadgeIcon(
    isPrivate: Boolean,
    securityState: SecurityState,
) {
    when {
        isPrivate -> Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = "Chế độ riêng tư",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp),
        )
        securityState == SecurityState.SECURE -> Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = "Kết nối HTTPS an toàn",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(16.dp),
        )
        securityState == SecurityState.INSECURE -> Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = "Kết nối không mã hóa",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp),
        )
        else -> Icon(
            imageVector = Icons.Rounded.Public,
            contentDescription = "Trang web",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CrashedTabOverlay(
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        modifier = modifier.padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = "Tab này vừa gặp sự cố",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tiến trình nội dung đã được cô lập an toàn. Chạm để khôi phục trang.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onReload) {
                Text("Tải lại tab")
            }
        }
    }
}

private fun formatDisplayHost(rawUrl: String?): String {
    if (rawUrl.isNullOrBlank()) return "Tìm kiếm hoặc nhập địa chỉ"
    val host = runCatching { Uri.parse(rawUrl).host }.getOrNull()
    return host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: rawUrl
}
