package io.veko.browser.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Lưới 4 tác vụ nhanh đồng nhất trên Trang chủ: Tab, Dấu trang, Lịch sử và Tải xuống.
 */
@Composable
fun QuickActionsSection(
    state: HomeQuickActionsState,
    onOpenTabs: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home_quick_actions"),
    ) {
        val compact = maxWidth < 360.dp
        val itemSpacing = if (compact) 6.dp else 10.dp

        Row(
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            modifier = Modifier.fillMaxWidth(),
        ) {
            QuickActionItem(
                label = "Tab",
                talkBackLabel = "Quản lý tab, đang mở ${state.tabCount} tab",
                icon = null,
                tabCounterValue = state.tabCount,
                badgeCount = null,
                highlighted = false,
                compact = compact,
                onClick = onOpenTabs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_action_tabs"),
            )
            QuickActionItem(
                label = "Dấu trang",
                talkBackLabel = if (state.bookmarkCount > 0) {
                    "Mở dấu trang, đã lưu ${state.bookmarkCount} trang"
                } else {
                    "Mở dấu trang"
                },
                icon = Icons.Rounded.BookmarkBorder,
                badgeCount = state.bookmarkCount.takeIf { it > 0 },
                highlighted = false,
                compact = compact,
                onClick = onOpenBookmarks,
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_action_bookmarks"),
            )
            QuickActionItem(
                label = "Lịch sử",
                talkBackLabel = "Mở lịch sử duyệt web",
                icon = Icons.Rounded.History,
                badgeCount = null,
                highlighted = false,
                compact = compact,
                onClick = onOpenHistory,
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_action_history"),
            )
            QuickActionItem(
                label = "Tải xuống",
                talkBackLabel = when {
                    state.activeDownloadCount > 0 -> "Mở tệp tải xuống, đang tải ${state.activeDownloadCount} tệp"
                    state.totalDownloadCount > 0 -> "Mở tệp tải xuống, có ${state.totalDownloadCount} tệp"
                    else -> "Mở tệp tải xuống"
                },
                icon = Icons.Rounded.FileDownload,
                badgeCount = when {
                    state.activeDownloadCount > 0 -> state.activeDownloadCount
                    state.totalDownloadCount > 0 -> state.totalDownloadCount
                    else -> null
                },
                highlighted = state.activeDownloadCount > 0,
                compact = compact,
                onClick = onOpenDownloads,
                modifier = Modifier
                    .weight(1f)
                    .testTag("quick_action_downloads"),
            )
        }
    }
}

/**
 * Mục hành động đơn lẻ trong QuickActionsSection với kích thước đồng nhất, trạng thái nhấn Material 3 và hỗ trợ TalkBack.
 */
@Composable
fun QuickActionItem(
    label: String,
    talkBackLabel: String,
    icon: ImageVector?,
    tabCounterValue: Int? = null,
    badgeCount: Int? = null,
    highlighted: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
            },
        ),
        modifier = modifier
            .heightIn(min = 68.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = talkBackLabel
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(
                horizontal = if (compact) 4.dp else 8.dp,
                vertical = 10.dp,
            ),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp),
            ) {
                if (tabCounterValue != null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(22.dp)
                            .border(
                                width = 1.7.dp,
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(6.dp),
                            ),
                    ) {
                        Text(
                            text = if (tabCounterValue > 99) "99+" else tabCounterValue.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
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
                        modifier = Modifier.size(21.dp),
                    )
                }

                if (badgeCount != null && tabCounterValue == null) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = label,
                style = if (compact) {
                    MaterialTheme.typography.labelSmall
                } else {
                    MaterialTheme.typography.labelMedium
                },
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
