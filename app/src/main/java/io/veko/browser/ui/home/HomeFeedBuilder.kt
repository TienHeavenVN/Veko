package io.veko.browser.ui.home

import androidx.compose.runtime.Immutable
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.tab.Tab
import io.veko.browser.tab.UrlNavigationPolicy
import java.util.Locale
import kotlin.math.abs

@Immutable
data class HomeShortcutItem(
    val id: Long,
    val title: String,
    val url: String,
    val displayHost: String,
    val monogram: String,
    val accentIndex: Int,
)

@Immutable
data class HomeRecentVisitItem(
    val id: Long,
    val title: String,
    val url: String,
    val displayHost: String,
    val monogram: String,
    val relativeTimeLabel: String,
    val accentIndex: Int,
)

@Immutable
data class HomeFeedSnapshot(
    val shortcuts: List<HomeShortcutItem> = emptyList(),
    val otherOpenRegularTabs: List<Tab> = emptyList(),
    val recentVisits: List<HomeRecentVisitItem> = emptyList(),
) {
    val hasContinueContent: Boolean
        get() = otherOpenRegularTabs.isNotEmpty() || recentVisits.isNotEmpty()
}

/**
 * Bộ tổng hợp dữ liệu thực cho Trang chủ Veko từ Room (Bookmarks/History) và TabManager:
 * - Không sinh dữ liệu mẫu giả lập hay gắn cứng website cá nhân.
 * - Khử trùng lặp giữa các tab đang mở và lịch sử truy cập gần đây.
 * - Cô lập tuyệt đối khi ở Chế độ Riêng tư (isPrivate = true).
 */
object HomeFeedBuilder {

    private const val ACCENT_PALETTE_SIZE = 6

    fun build(
        isPrivate: Boolean,
        bookmarks: List<BookmarkEntity>,
        allTabs: List<Tab>,
        activeTabId: String?,
        recentHistory: List<HistoryEntity>,
        nowEpochMs: Long = System.currentTimeMillis(),
        maxShortcuts: Int = 8,
        maxOpenTabs: Int = 6,
        maxRecentVisits: Int = 5,
    ): HomeFeedSnapshot {
        // Trong chế độ riêng tư, tuyệt đối không hiển thị lịch sử, dấu trang hay tab thường sang vùng riêng tư
        if (isPrivate) {
            return HomeFeedSnapshot()
        }

        val shortcuts = ArrayList<HomeShortcutItem>(maxShortcuts)
        for (bm in bookmarks) {
            if (shortcuts.size >= maxShortcuts) break
            if (!UrlNavigationPolicy.isRecordableHttpUrl(bm.url)) continue
            val safeUrl = UrlNavigationPolicy.stripUserInfo(bm.url)
            val host = UrlNavigationPolicy.formatDisplayHost(safeUrl)
            shortcuts.add(
                HomeShortcutItem(
                    id = bm.id,
                    title = bm.title.trim().ifBlank { host },
                    url = safeUrl,
                    displayHost = host,
                    monogram = extractDomainMonogram(host),
                    accentIndex = computeAccentIndex(host),
                ),
            )
        }

        val otherOpenRegularTabs = allTabs
            .asSequence()
            .filter { tab ->
                !tab.isPrivate &&
                    tab.id != activeTabId &&
                    !UrlNavigationPolicy.isNewTabBlankUrl(tab.sessionState.url) &&
                    UrlNavigationPolicy.isRecordableHttpUrl(tab.sessionState.url)
            }
            .sortedWith(
                compareByDescending<Tab> { it.lastAccessedEpochMs }
                    .thenByDescending { it.accessSequence },
            )
            .take(maxOpenTabs)
            .toList()

        val seenCanonicalUrls = HashSet<String>(otherOpenRegularTabs.size + maxRecentVisits)
        for (openTab in otherOpenRegularTabs) {
            seenCanonicalUrls.add(canonicalUrlKey(openTab.sessionState.url))
        }

        val recentVisits = ArrayList<HomeRecentVisitItem>(maxRecentVisits)
        for (entry in recentHistory) {
            if (recentVisits.size >= maxRecentVisits) break
            if (!UrlNavigationPolicy.isRecordableHttpUrl(entry.url)) continue
            val safeUrl = UrlNavigationPolicy.stripUserInfo(entry.url)
            val key = canonicalUrlKey(safeUrl)
            if (!seenCanonicalUrls.add(key)) continue
            val host = UrlNavigationPolicy.formatDisplayHost(safeUrl)
            recentVisits.add(
                HomeRecentVisitItem(
                    id = entry.id,
                    title = entry.title.trim().ifBlank { host },
                    url = safeUrl,
                    displayHost = host,
                    monogram = extractDomainMonogram(host),
                    relativeTimeLabel = formatRelativeTimeVi(entry.visitedAtEpochMs, nowEpochMs),
                    accentIndex = computeAccentIndex(host),
                ),
            )
        }

        return HomeFeedSnapshot(
            shortcuts = shortcuts,
            otherOpenRegularTabs = otherOpenRegularTabs,
            recentVisits = recentVisits,
        )
    }

    fun extractDomainMonogram(displayHost: String): String {
        val clean = displayHost.trim().removePrefix("www.").removePrefix("[")
        val firstAlphaNum = clean.firstOrNull { it.isLetterOrDigit() } ?: 'V'
        return firstAlphaNum.toString().uppercase(Locale.ROOT)
    }

    fun computeAccentIndex(seed: String): Int {
        if (seed.isEmpty()) return 0
        var hash = 0
        for (ch in seed.lowercase(Locale.ROOT)) {
            hash = 31 * hash + ch.code
        }
        return abs(hash) % ACCENT_PALETTE_SIZE
    }

    fun formatRelativeTimeVi(visitedAtEpochMs: Long, nowEpochMs: Long): String {
        val deltaMs = (nowEpochMs - visitedAtEpochMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> "Vừa xong"
            minutes < 60L -> "$minutes phút trước"
            hours < 24L -> "$hours giờ trước"
            days == 1L -> "Hôm qua"
            days < 30L -> "$days ngày trước"
            else -> "${days / 30L} tháng trước"
        }
    }

    private fun canonicalUrlKey(url: String): String =
        UrlNavigationPolicy.stripUserInfo(url).trim().trimEnd('/').lowercase(Locale.ROOT)
}
