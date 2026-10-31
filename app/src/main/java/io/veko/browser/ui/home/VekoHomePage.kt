package io.veko.browser.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.tab.Tab
import io.veko.browser.tab.UrlNavigationPolicy
import io.veko.browser.ui.readClipboardPlainText

private data class EditingShortcutDraft(
    val initialUrl: String = "",
    val initialTitle: String = "",
    val isEditingExisting: Boolean = false,
)

/**
 * Trang chủ thương hiệu riêng của Veko ("Veko Horizon Deck"):
 * - Ngôn ngữ Bento tối giản với biểu trưng V-Prism Cobalt-Cyan, không sao chép bố cục Firefox.
 * - Thích ứng đa kích thước: 1 cột trên điện thoại dọc (< 640dp) và lưới Bento 2 cột trên màn hình ngang / gập / máy tính bảng.
 * - Sử dụng 100% dữ liệu thực từ Room (Bookmarks/History) & TabManager; cô lập hoàn toàn khi ở Chế độ Riêng tư.
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
    val context = LocalContext.current
    val clipboardPreview = remember { readClipboardPlainText(context) }

    val feed = remember(isPrivate, bookmarks, allTabs, activeTabId, recentHistory) {
        HomeFeedBuilder.build(
            isPrivate = isPrivate,
            bookmarks = bookmarks,
            allTabs = allTabs,
            activeTabId = activeTabId,
            recentHistory = recentHistory,
        )
    }

    var isManagingShortcuts by rememberSaveable { mutableStateOf(false) }
    var shortcutDialogDraft by remember { mutableStateOf<EditingShortcutDraft?>(null) }
    var shortcutErrorText by remember { mutableStateOf<String?>(null) }

    val privateTabCount = remember(allTabs) { allTabs.count { it.isPrivate } }
    val primaryGlow = MaterialTheme.colorScheme.primary.copy(alpha = if (isPrivate) 0.08f else 0.12f)
    val secondaryGlow = MaterialTheme.colorScheme.secondary.copy(alpha = 0.07f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val isWideLayout = maxWidth >= 640.dp

        // Ánh sáng nền Horizon cực nhẹ ở rìa trên, không cấp phát Bitmap nên giữ nguyên 60/120fps
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.85f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryGlow, secondaryGlow, Color.Transparent),
                    center = Offset(size.width * 0.2f, 0f),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(size.width * 0.2f, 0f),
            )
        }

        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(
                start = if (isWideLayout) 28.dp else 16.dp,
                end = if (isWideLayout) 28.dp else 16.dp,
                top = 16.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "veko_hero_header") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.widthIn(max = 1040.dp).fillMaxWidth(),
                ) {
                    VekoBrandHeader(
                        isPrivate = isPrivate,
                        onSwitchToPrivate = onSwitchToPrivateTab,
                        onSwitchToRegular = onSwitchToRegularTab,
                        onOpenSettings = onOpenSettings,
                    )

                    VekoCommandSurface(
                        isPrivate = isPrivate,
                        searchEngine = searchEngine,
                        clipboardPreview = clipboardPreview,
                        onStartTyping = onStartTyping,
                        onSelectSearchEngine = onSelectSearchEngine,
                        onPasteAndGo = onPasteAndGo,
                    )

                    VekoBrowserHubStrip(
                        tabCount = allTabs.size.coerceAtLeast(1),
                        bookmarkCount = bookmarks.size,
                        activeDownloadCount = activeDownloadCount,
                        totalDownloadCount = totalDownloadCount,
                        onOpenTabSwitcher = onOpenTabSwitcher,
                        onOpenBookmarks = onOpenBookmarks,
                        onOpenHistory = onOpenHistory,
                        onOpenDownloads = onOpenDownloads,
                    )
                }
            }

            if (isWideLayout) {
                // Bố cục 2 cột Bento tối ưu cho máy tính bảng, màn hình gập và điện thoại xoay ngang
                item(key = "veko_wide_bento_workspace") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .widthIn(max = 1040.dp)
                            .fillMaxWidth(),
                    ) {
                        if (isPrivate) {
                            PrivateStealthVaultCard(
                                privateTabCount = privateTabCount,
                                onCloseAllPrivateTabs = onCloseAllPrivateTabs,
                                onOpenRegularTab = onSwitchToRegularTab,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            VekoQuickAccessSection(
                                shortcuts = feed.shortcuts,
                                isManaging = isManagingShortcuts,
                                onToggleManage = { isManagingShortcuts = !isManagingShortcuts },
                                onAddShortcut = {
                                    shortcutErrorText = null
                                    shortcutDialogDraft = EditingShortcutDraft()
                                },
                                onOpenShortcut = onOpenUrl,
                                onEditShortcut = { item ->
                                    shortcutErrorText = null
                                    shortcutDialogDraft = EditingShortcutDraft(
                                        initialUrl = item.url,
                                        initialTitle = item.title,
                                        isEditingExisting = true,
                                    )
                                },
                                onDeleteShortcut = onDeleteShortcut,
                                onViewAllBookmarks = onOpenBookmarks,
                                modifier = Modifier.weight(1f),
                            )

                            VekoContinueBrowsingSection(
                                openTabs = feed.otherOpenRegularTabs,
                                recentVisits = feed.recentVisits,
                                showPlaceholderWhenEmpty = true,
                                onSelectTab = onSelectTab,
                                onOpenUrl = onOpenUrl,
                                onDeleteHistoryItem = onDeleteHistoryItem,
                                onOpenAllHistory = onOpenHistory,
                                modifier = Modifier.weight(1.08f),
                            )
                        }
                    }
                }
            } else {
                // Bố cục dọc công thái học một tay cho màn hình điện thoại nhỏ & tiêu chuẩn
                if (isPrivate) {
                    item(key = "veko_private_vault") {
                        PrivateStealthVaultCard(
                            privateTabCount = privateTabCount,
                            onCloseAllPrivateTabs = onCloseAllPrivateTabs,
                            onOpenRegularTab = onSwitchToRegularTab,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    item(key = "veko_quick_access") {
                        VekoQuickAccessSection(
                            shortcuts = feed.shortcuts,
                            isManaging = isManagingShortcuts,
                            onToggleManage = { isManagingShortcuts = !isManagingShortcuts },
                            onAddShortcut = {
                                shortcutErrorText = null
                                shortcutDialogDraft = EditingShortcutDraft()
                            },
                            onOpenShortcut = onOpenUrl,
                            onEditShortcut = { item ->
                                shortcutErrorText = null
                                shortcutDialogDraft = EditingShortcutDraft(
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

                    if (feed.hasContinueContent) {
                        item(key = "veko_continue_browsing") {
                            VekoContinueBrowsingSection(
                                openTabs = feed.otherOpenRegularTabs,
                                recentVisits = feed.recentVisits,
                                showPlaceholderWhenEmpty = false,
                                onSelectTab = onSelectTab,
                                onOpenUrl = onOpenUrl,
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

@Composable
private fun VekoBrandHeader(
    isPrivate: Boolean,
    onSwitchToPrivate: () -> Unit,
    onSwitchToRegular: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        VekoPrismEmblem(isPrivate = isPrivate, size = 38.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Veko",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (isPrivate) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "Riêng tư",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Text(
                text = if (isPrivate) {
                    "Phiên tách biệt trên RAM • Không lưu dấu vết cục bộ"
                } else {
                    "Trình duyệt nhân GeckoView gọn nhẹ & tôn trọng dữ liệu"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Surface(
            shape = CircleShape,
            color = if (isPrivate) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier
                .clip(CircleShape)
                .clickable { if (isPrivate) onSwitchToRegular() else onSwitchToPrivate() },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = if (isPrivate) Icons.Rounded.Public else Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = if (isPrivate) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isPrivate) "Tab thường" else "Riêng tư",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isPrivate) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Cài đặt Veko",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Biểu trưng hình học V-Prism đặc trưng của thương hiệu Veko, vẽ thuần vector qua Canvas.
 */
@Composable
private fun VekoPrismEmblem(
    isPrivate: Boolean,
    size: Dp,
) {
    val bgBrush = if (isPrivate) {
        Brush.linearGradient(
            colors = listOf(Color(0xFF0D9488), Color(0xFF0284C7)),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFF1D4ED8), Color(0xFF06B6D4)),
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(13.dp))
            .background(bgBrush),
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            val w = this.size.width
            val h = this.size.height
            val vPath = Path().apply {
                moveTo(w * 0.14f, h * 0.20f)
                lineTo(w * 0.50f, h * 0.84f)
                lineTo(w * 0.86f, h * 0.20f)
            }
            drawPath(
                path = vPath,
                color = Color.White,
                style = Stroke(
                    width = w * 0.19f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            drawCircle(
                color = Color(0xFFA5F3FC),
                radius = w * 0.10f,
                center = Offset(w * 0.50f, h * 0.24f),
            )
        }
    }
}

/**
 * Bề mặt tìm kiếm & điều hướng trung tâm: chạm 1 lần để gõ URL/từ khóa, đổi công cụ tìm kiếm tại chỗ hoặc Dán & Đi.
 */
@Composable
private fun VekoCommandSurface(
    isPrivate: Boolean,
    searchEngine: SearchEngine,
    clipboardPreview: String?,
    onStartTyping: () -> Unit,
    onSelectSearchEngine: (SearchEngine) -> Unit,
    onPasteAndGo: (String) -> Unit,
) {
    var engineMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp),
            ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onStartTyping),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Icon(
                        imageVector = if (isPrivate) Icons.Rounded.Shield else Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isPrivate) {
                            "Tìm riêng tư hoặc nhập địa chỉ..."
                        } else {
                            "Nhập địa chỉ website hoặc từ khóa..."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "Mở thanh nhập địa chỉ",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { engineMenuExpanded = true },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = searchEngine.label,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Đổi công cụ tìm kiếm",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = engineMenuExpanded,
                        onDismissRequest = { engineMenuExpanded = false },
                    ) {
                        SearchEngine.entries.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.label) },
                                trailingIcon = if (engine == searchEngine) {
                                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    engineMenuExpanded = false
                                    onSelectSearchEngine(engine)
                                },
                            )
                        }
                    }
                }

                if (!clipboardPreview.isNullOrBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onPasteAndGo(clipboardPreview) },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentPaste,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Dán & Truy cập",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Thanh truy cập nhanh 1 chạm tới các công cụ cốt lõi có thật của trình duyệt.
 */
@Composable
private fun VekoBrowserHubStrip(
    tabCount: Int,
    bookmarkCount: Int,
    activeDownloadCount: Int,
    totalDownloadCount: Int,
    onOpenTabSwitcher: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        HubActionPill(
            label = "Tab ($tabCount)",
            icon = null,
            tabBadgeCount = tabCount,
            highlighted = false,
            onClick = onOpenTabSwitcher,
            modifier = Modifier.weight(1f),
        )
        HubActionPill(
            label = if (bookmarkCount > 0) "Đã lưu ($bookmarkCount)" else "Dấu trang",
            icon = Icons.Rounded.BookmarkBorder,
            highlighted = false,
            onClick = onOpenBookmarks,
            modifier = Modifier.weight(1f),
        )
        HubActionPill(
            label = "Lịch sử",
            icon = Icons.Rounded.History,
            highlighted = false,
            onClick = onOpenHistory,
            modifier = Modifier.weight(1f),
        )
        HubActionPill(
            label = when {
                activeDownloadCount > 0 -> "Tải ($activeDownloadCount)"
                totalDownloadCount > 0 -> "Tệp ($totalDownloadCount)"
                else -> "Tải xuống"
            },
            icon = Icons.Rounded.FileDownload,
            highlighted = activeDownloadCount > 0,
            onClick = onOpenDownloads,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HubActionPill(
    label: String,
    icon: ImageVector?,
    tabBadgeCount: Int? = null,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 6.dp),
        ) {
            if (tabBadgeCount != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(18.dp)
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(5.dp),
                        ),
                ) {
                    Text(
                        text = tabBadgeCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlighted) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Khối Lối tắt nhanh (Speed Tiles) đọc trực tiếp từ Room Bookmarks: hỗ trợ thêm, sửa tên và xóa ngay trên trang chủ.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VekoQuickAccessSection(
    shortcuts: List<HomeShortcutItem>,
    isManaging: Boolean,
    onToggleManage: () -> Unit,
    onAddShortcut: () -> Unit,
    onOpenShortcut: (String) -> Unit,
    onEditShortcut: (HomeShortcutItem) -> Unit,
    onDeleteShortcut: (String) -> Unit,
    onViewAllBookmarks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
            shape = RoundedCornerShape(22.dp),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Lối tắt nhanh",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (shortcuts.isNotEmpty()) {
                    TextButton(onClick = onToggleManage) {
                        Text(if (isManaging) "Xong" else "Quản lý")
                    }
                }
                FilledTonalButton(
                    onClick = onAddShortcut,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Thêm", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (shortcuts.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onAddShortcut),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ghim website yêu thích của bạn",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Chưa có lối tắt nào được lưu. Chạm để thêm trang web đầu tiên hoặc lưu dấu trang khi duyệt web.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                val rows = remember(shortcuts) { shortcuts.chunked(2) }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (pair in rows) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            for (item in pair) {
                                SpeedTileCard(
                                    item = item,
                                    isManaging = isManaging,
                                    onClick = {
                                        if (isManaging) onEditShortcut(item) else onOpenShortcut(item.url)
                                    },
                                    onLongClick = { onEditShortcut(item) },
                                    onDelete = { onDeleteShortcut(item.url) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(visible = isManaging) {
                    Text(
                        text = "Mẹo: Chạm vào lối tắt để đổi tên/URL hoặc nhấn biểu tượng thùng rác để gỡ.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }

                if (shortcuts.size >= 8) {
                    Spacer(modifier = Modifier.height(6.dp))
                    TextButton(
                        onClick = onViewAllBookmarks,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Xem toàn bộ dấu trang")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedTileCard(
    item: HomeShortcutItem,
    isManaging: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            DomainMonogramBadge(
                monogram = item.monogram,
                accentIndex = item.accentIndex,
                size = 36.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.displayHost,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isManaging) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Gỡ lối tắt ${item.title}",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Khối Tiếp tục duyệt web: kết hợp các Tab thường đang mở khác (kèm ảnh xem trước thật) và Lịch sử vừa truy cập.
 */
@Composable
private fun VekoContinueBrowsingSection(
    openTabs: List<Tab>,
    recentVisits: List<HomeRecentVisitItem>,
    showPlaceholderWhenEmpty: Boolean,
    onSelectTab: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    onOpenAllHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
            shape = RoundedCornerShape(22.dp),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Tiếp tục duyệt web",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (recentVisits.isNotEmpty()) {
                    TextButton(onClick = onOpenAllHistory) {
                        Text("Toàn bộ lịch sử")
                    }
                }
            }

            if (openTabs.isEmpty() && recentVisits.isEmpty() && showPlaceholderWhenEmpty) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Các tab bạn đang mở dở và những trang vừa đọc sẽ xuất hiện tại đây để mở lại chỉ với 1 chạm.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            if (openTabs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Đang mở ở tab khác (${openTabs.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(openTabs, key = { it.id }) { tab ->
                        OpenTabResumeCard(
                            tab = tab,
                            onClick = { onSelectTab(tab.id) },
                        )
                    }
                }
            }

            if (recentVisits.isNotEmpty()) {
                if (openTabs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Trang vừa truy cập",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (visit in recentVisits) {
                        RecentVisitRow(
                            visit = visit,
                            onClick = { onOpenUrl(visit.url) },
                            onRemove = { onDeleteHistoryItem(visit.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenTabResumeCard(
    tab: Tab,
    onClick: () -> Unit,
) {
    val host = UrlNavigationPolicy.formatDisplayHost(tab.sessionState.url)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .width(206.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Column {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                val preview = tab.previewBitmap
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = tab.sessionState.title,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    DomainMonogramBadge(
                        monogram = HomeFeedBuilder.extractDomainMonogram(host),
                        accentIndex = HomeFeedBuilder.computeAccentIndex(host),
                        size = 34.dp,
                    )
                }
            }
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    text = tab.sessionState.title.ifBlank { host },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$host • Chuyển tới tab",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RecentVisitRow(
    visit: HomeRecentVisitItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        ) {
            DomainMonogramBadge(
                monogram = visit.monogram,
                accentIndex = visit.accentIndex,
                size = 34.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = visit.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${visit.displayHost} • ${visit.relativeTimeLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(38.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Xóa khỏi lịch sử gần đây",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Thẻ thông tin minh bạch khi mở Trang chủ ở Chế độ Riêng tư (không hiển thị bất kỳ dữ liệu phiên thường nào).
 */
@Composable
private fun PrivateStealthVaultCard(
    privateTabCount: Int,
    onCloseAllPrivateTabs: () -> Unit,
    onOpenRegularTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f),
            shape = RoundedCornerShape(22.dp),
        ),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Không gian Riêng tư Veko",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "• Không ghi lịch sử duyệt web, nháp URL hay trạng thái tab xuống Room/DataStore.\n" +
                    "• Quyền Camera, Micro và Vị trí cấp trong tab riêng tư chỉ nằm trên RAM và tự xóa sạch khi đóng tab.\n" +
                    "• Lưu ý: Chế độ riêng tư không ẩn kết nối mạng trước nhà mạng (ISP) hoặc trang web bạn truy cập.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onOpenRegularTab,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Mở tab thường")
                }
                if (privateTabCount > 0) {
                    FilledTonalButton(
                        onClick = onCloseAllPrivateTabs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Đóng hết ($privateTabCount)")
                    }
                }
            }
        }
    }
}

@Composable
private fun DomainMonogramBadge(
    monogram: String,
    accentIndex: Int,
    size: Dp,
) {
    val palette = listOf(
        Color(0xFF2563EB) to Color(0xFFDBEAFE),
        Color(0xFF0891B2) to Color(0xFFCFFAFE),
        Color(0xFF0D9488) to Color(0xFFCCFBF1),
        Color(0xFF4F46E5) to Color(0xFFE0E7FF),
        Color(0xFF0284C7) to Color(0xFFE0F2FE),
        Color(0xFF334155) to Color(0xFFE2E8F0),
    )
    val (fg, bg) = palette[accentIndex.mod(palette.size)]
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(11.dp))
            .background(bg),
    ) {
        Text(
            text = monogram,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
        )
    }
}

@Composable
private fun ShortcutEditorDialog(
    draft: EditingShortcutDraft,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (url: String, title: String?) -> Unit,
) {
    var urlInput by remember(draft) { mutableStateOf(draft.initialUrl) }
    var titleInput by remember(draft) { mutableStateOf(draft.initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (draft.isEditingExisting) Icons.Rounded.Edit else Icons.Rounded.Add,
                contentDescription = null,
            )
        },
        title = {
            Text(if (draft.isEditingExisting) "Chỉnh sửa lối tắt" else "Thêm lối tắt trang web")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Địa chỉ URL hoặc tên miền") },
                    placeholder = { Text("Ví dụ: developer.android.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    label = { Text("Tên hiển thị (tùy chọn)") },
                    placeholder = { Text("Ví dụ: Android Docs") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { onConfirm(urlInput, titleInput) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(urlInput, titleInput) }) {
                Text("Lưu lối tắt")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Hủy")
            }
        },
    )
}
