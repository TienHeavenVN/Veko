package io.veko.browser.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.veko.browser.tab.Tab
import io.veko.browser.tab.UrlNavigationPolicy

/**
 * Khu vực Tiếp tục duyệt web: hiển thị các tab thường đang mở dở và lịch sử truy cập gần đây thực tế.
 */
@Composable
fun ContinueBrowsingSection(
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
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Tiếp tục duyệt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (recentVisits.isNotEmpty()) {
                    TextButton(onClick = onOpenAllHistory) {
                        Text("Xem lịch sử")
                    }
                }
            }

            if (openTabs.isEmpty() && recentVisits.isEmpty() && showPlaceholderWhenEmpty) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tab đang mở dở và trang vừa đọc sẽ hiển thị tại đây.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (openTabs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tab đang mở (${openTabs.size})",
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
                    text = "Vừa truy cập",
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
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.width(204.dp),
    ) {
        Column {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
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
                    text = host,
                    style = MaterialTheme.typography.labelSmall,
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
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Xóa ${visit.title} khỏi lịch sử gần đây",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Thẻ thông báo gọn, trung thực khi đang ở Chế độ Riêng tư trên Trang chủ.
 */
@Composable
fun PrivateModeNoticeCard(
    privateTabCount: Int,
    onCloseAllPrivateTabs: () -> Unit,
    onOpenRegularTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)),
        modifier = modifier.testTag("home_private_notice"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Chế độ Riêng tư",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Lịch sử, nháp tìm kiếm và quyền trang web trong tab riêng tư chỉ giữ trên RAM và tự xóa khi đóng tab. Lưu ý: Chế độ này không ẩn lưu lượng mạng trước ISP hoặc trang web đích.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onOpenRegularTab,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Mở tab thường", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (privateTabCount > 0) {
                    FilledTonalButton(
                        onClick = onCloseAllPrivateTabs,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Đóng hết ($privateTabCount)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
