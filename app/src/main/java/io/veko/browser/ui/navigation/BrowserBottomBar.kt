package io.veko.browser.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.tab.SecurityState
import io.veko.browser.tab.Tab
import io.veko.browser.tab.UrlNavigationPolicy
import kotlin.math.abs

/**
 * Thanh điều hướng dưới đáy dùng chung cho cả Trang chủ và màn hình duyệt Web (không tạo 2 thanh cạnh tranh):
 * - Gồm: Trang chủ/Quay lại, Thanh địa chỉ/Tìm kiếm, Tab mới/Tải lại, Bộ chuyển tab và Menu.
 * - Thích ứng màn hình hẹp (< 380dp) để không làm thanh địa chỉ bị thu hẹp quá mức, giữ vùng chạm tối thiểu 48dp.
 */
@Composable
fun BrowserBottomBar(
    activeTab: Tab?,
    tabCount: Int,
    isBookmarked: Boolean,
    settings: VekoSettings,
    activeDownloadsCount: Int,
    onSecurityClick: () -> Unit,
    onAddressClick: () -> Unit,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onNavigateHomeClick: () -> Unit,
    onReloadOrStopClick: () -> Unit,
    onOpenTabSheet: () -> Unit,
    onSwipeHorizontalTab: (Int) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenFindInPage: () -> Unit,
    onStepPageZoom: (Int) -> Unit,
    onResetPageZoom: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onShareCurrentPage: () -> Unit,
    onCopyCurrentUrl: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPrivacyShield: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var gestureConsumed by remember { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }

    val isHomeTab = activeTab == null || (
        UrlNavigationPolicy.isNewTabBlankUrl(activeTab.sessionState.url) &&
            !activeTab.sessionState.isLoading &&
            !activeTab.sessionState.hasLoadError &&
            !activeTab.sessionState.isCrashed
        )
    val securityState = activeTab?.sessionState?.securityState ?: SecurityState.LOCAL_OR_UNKNOWN
    val containerColor = if (activeTab?.isPrivate == true) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val safeTabCount = tabCount.coerceAtLeast(1)

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .testTag("browser_bottom_bar"),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isNarrowBar = maxWidth < 380.dp
            val showInlineForward = !isNarrowBar && activeTab?.sessionState?.canGoForward == true

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isNarrowBar) 2.dp else 4.dp, vertical = 6.dp),
            ) {
                if (activeTab?.sessionState?.canGoBack == true) {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Quay lại trang trước",
                        )
                    }
                } else {
                    IconButton(
                        onClick = onNavigateHomeClick,
                        enabled = !isHomeTab,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bottom_bar_home"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = "Trang chủ Veko",
                            tint = if (isHomeTab) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                if (showInlineForward) {
                    IconButton(
                        onClick = onForwardClick,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "Tiến tới trang sau",
                        )
                    }
                }

                // Viên thuốc địa chỉ giữ đủ độ rộng ưu tiên (weight 1f + widthIn min 120dp), hỗ trợ vuốt ngang/vuốt lên
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = 120.dp)
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(containerColor)
                        .testTag("bottom_bar_address_pill")
                        .pointerInput(safeTabCount) {
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
                                    if (dragDy < -56f && abs(dragDy) > abs(dragDx)) {
                                        gestureConsumed = true
                                        onOpenTabSheet()
                                    } else if (abs(dragDx) > 72f && abs(dragDx) > abs(dragDy)) {
                                        gestureConsumed = true
                                        onSwipeHorizontalTab(if (dragDx < 0f) 1 else -1)
                                    }
                                },
                            )
                        },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .sizeIn(minWidth = 42.dp, minHeight = 48.dp)
                            .clickable(onClick = onSecurityClick)
                            .padding(start = 10.dp, end = 4.dp),
                    ) {
                        BottomBarSecurityIcon(
                            isPrivate = activeTab?.isPrivate == true,
                            securityState = securityState,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable(onClick = onAddressClick)
                            .padding(end = 10.dp),
                    ) {
                        if (!isHomeTab && securityState == SecurityState.INSECURE) {
                            Text(
                                text = "HTTP • ",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = if (isHomeTab) {
                                if (isNarrowBar) "Tìm hoặc nhập URL" else "Tìm kiếm hoặc nhập URL"
                            } else {
                                UrlNavigationPolicy.formatDisplayHost(activeTab?.sessionState?.url)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (isHomeTab) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (isHomeTab) {
                    IconButton(
                        onClick = onNewTab,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bottom_bar_new_tab"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Mở tab mới",
                        )
                    }
                } else {
                    IconButton(
                        onClick = onReloadOrStopClick,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = if (activeTab?.sessionState?.isLoading == true) {
                                Icons.Rounded.Close
                            } else {
                                Icons.Rounded.Refresh
                            },
                            contentDescription = if (activeTab?.sessionState?.isLoading == true) {
                                "Dừng tải trang"
                            } else {
                                "Tải lại trang"
                            },
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onOpenTabSheet)
                        .testTag("bottom_bar_tab_switcher")
                        .semantics {
                            role = Role.Button
                            contentDescription = "Bộ chuyển tab, đang mở $safeTabCount tab"
                        },
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = 1.8.dp,
                                color = if (activeTab?.isPrivate == true) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                shape = RoundedCornerShape(8.dp),
                            ),
                    ) {
                        Text(
                            text = if (safeTabCount > 99) "99+" else safeTabCount.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { isMenuExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("bottom_bar_menu"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Menu công cụ và cài đặt",
                        )
                    }
                    WebsiteToolsDropdownMenu(
                        expanded = isMenuExpanded,
                        activeTab = activeTab,
                        isHomeTab = isHomeTab,
                        showForwardInMenu = isNarrowBar && activeTab?.sessionState?.canGoForward == true,
                        isBookmarked = isBookmarked,
                        settings = settings,
                        activeDownloadsCount = activeDownloadsCount,
                        onDismiss = { isMenuExpanded = false },
                        onForward = onForwardClick,
                        onNavigateHome = onNavigateHomeClick,
                        onNewTab = onNewTab,
                        onNewPrivateTab = onNewPrivateTab,
                        onToggleBookmark = onToggleBookmark,
                        onOpenFindInPage = onOpenFindInPage,
                        onStepPageZoom = onStepPageZoom,
                        onResetPageZoom = onResetPageZoom,
                        onToggleDesktopMode = onToggleDesktopMode,
                        onShareCurrentPage = onShareCurrentPage,
                        onCopyCurrentUrl = onCopyCurrentUrl,
                        onOpenDownloads = onOpenDownloads,
                        onOpenLibrary = onOpenLibrary,
                        onOpenPrivacyShield = onOpenPrivacyShield,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBarSecurityIcon(
    isPrivate: Boolean,
    securityState: SecurityState,
) {
    when {
        isPrivate && securityState != SecurityState.INSECURE -> Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = "Chế độ riêng tư — chạm để xem thông tin kết nối",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp),
        )
        securityState == SecurityState.SECURE -> Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = "Kết nối HTTPS an toàn — chạm để quản lý quyền",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
        securityState == SecurityState.INSECURE -> Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = "Cảnh báo kết nối HTTP không mã hóa",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        else -> Icon(
            imageVector = Icons.Rounded.Public,
            contentDescription = "Thông tin trang web",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun WebsiteToolsDropdownMenu(
    expanded: Boolean,
    activeTab: Tab?,
    isHomeTab: Boolean,
    showForwardInMenu: Boolean,
    isBookmarked: Boolean,
    settings: VekoSettings,
    activeDownloadsCount: Int,
    onDismiss: () -> Unit,
    onForward: () -> Unit,
    onNavigateHome: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenFindInPage: () -> Unit,
    onStepPageZoom: (Int) -> Unit,
    onResetPageZoom: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onShareCurrentPage: () -> Unit,
    onCopyCurrentUrl: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPrivacyShield: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hasHttpPage = UrlNavigationPolicy.isRecordableHttpUrl(activeTab?.sessionState?.url)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        if (showForwardInMenu) {
            DropdownMenuItem(
                text = { Text("Tiến tới trang sau") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onForward()
                },
            )
        }
        if (!isHomeTab) {
            DropdownMenuItem(
                text = { Text("Về Trang chủ Veko") },
                leadingIcon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onNavigateHome()
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Tab mới") },
            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            onClick = {
                onDismiss()
                onNewTab()
            },
        )
        DropdownMenuItem(
            text = { Text("Tab riêng tư mới") },
            leadingIcon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
            onClick = {
                onDismiss()
                onNewPrivateTab()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (isBookmarked) "Bỏ dấu trang" else "Lưu dấu trang") },
            leadingIcon = {
                Icon(
                    imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = null,
                )
            },
            enabled = hasHttpPage,
            onClick = {
                onDismiss()
                onToggleBookmark()
            },
        )
        DropdownMenuItem(
            text = { Text("Tìm trong trang") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenFindInPage()
            },
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (activeTab?.sessionState?.isDesktopMode == true) {
                        "Trang web cho máy tính (Đang bật)"
                    } else {
                        "Trang web cho máy tính"
                    },
                )
            },
            leadingIcon = { Icon(Icons.Rounded.DesktopWindows, contentDescription = null) },
            trailingIcon = if (activeTab?.sessionState?.isDesktopMode == true) {
                { Icon(Icons.Rounded.Check, contentDescription = null) }
            } else {
                null
            },
            onClick = {
                onDismiss()
                onToggleDesktopMode()
            },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = { onStepPageZoom(-15) }) {
                Icon(Icons.Rounded.ZoomOut, contentDescription = "Thu nhỏ chữ")
            }
            TextButton(
                onClick = onResetPageZoom,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (settings.autoFontSize) "Cỡ chữ: Tự động" else "Cỡ chữ: ${settings.pageZoomPercent}%",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(onClick = { onStepPageZoom(15) }) {
                Icon(Icons.Rounded.ZoomIn, contentDescription = "Phóng to chữ")
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Chia sẻ trang") },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            enabled = hasHttpPage,
            onClick = {
                onDismiss()
                onShareCurrentPage()
            },
        )
        DropdownMenuItem(
            text = { Text("Sao chép liên kết") },
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
            enabled = hasHttpPage,
            onClick = {
                onDismiss()
                onCopyCurrentUrl()
            },
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (activeDownloadsCount > 0) {
                        "Tệp tải xuống ($activeDownloadsCount đang tải)"
                    } else {
                        "Tệp tải xuống"
                    },
                )
            },
            leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenDownloads()
            },
        )
        DropdownMenuItem(
            text = { Text("Dấu trang & Lịch sử") },
            leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenLibrary()
            },
        )
        DropdownMenuItem(
            text = { Text("Ma trận Quyền riêng tư") },
            leadingIcon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenPrivacyShield()
            },
        )
        DropdownMenuItem(
            text = { Text("Cài đặt Veko") },
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenSettings()
            },
        )
    }
}
