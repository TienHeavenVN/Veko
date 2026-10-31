package io.veko.browser.data.repository

import androidx.compose.runtime.Immutable
import io.veko.browser.data.local.BookmarkDao
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryDao
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.tab.UrlNavigationPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class SuggestionSource {
    BOOKMARK,
    HISTORY,
}

@Immutable
data class AddressSuggestion(
    val title: String,
    val url: String,
    val source: SuggestionSource,
)

/**
 * Kho dữ liệu tập trung cho Lịch sử & Dấu trang, chạy hoàn toàn ngoài UI Thread.
 */
@Singleton
class BrowserDataRepository(
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        historyDao: HistoryDao,
        bookmarkDao: BookmarkDao,
    ) : this(historyDao, bookmarkDao, Dispatchers.IO)

    val recentHistory: Flow<List<HistoryEntity>> = historyDao.observeRecentHistory()
    val bookmarks: Flow<List<BookmarkEntity>> = bookmarkDao.observeAllBookmarks()

    fun observeIsBookmarked(url: String): Flow<Boolean> = bookmarkDao.observeIsBookmarked(url)

    suspend fun recordVisitIfRegular(url: String, title: String, isPrivate: Boolean) {
        // Rào chắn kép: chỉ ghi lịch sử cho tab thường với URL http/https hợp lệ, chặn tuyệt đối tab riêng tư và URI nội bộ
        if (isPrivate || !UrlNavigationPolicy.isRecordableHttpUrl(url)) return
        withContext(ioDispatcher) {
            historyDao.insert(
                HistoryEntity(
                    url = url.trim(),
                    title = title.trim().ifBlank { url.trim() },
                ),
            )
        }
    }

    suspend fun toggleBookmark(url: String, title: String): Boolean {
        if (!UrlNavigationPolicy.isRecordableHttpUrl(url)) return false
        return withContext(ioDispatcher) {
            val normalized = url.trim()
            val existing = bookmarkDao.findByUrl(normalized)
            if (existing != null) {
                bookmarkDao.deleteByUrl(normalized)
                false
            } else {
                bookmarkDao.upsert(
                    BookmarkEntity(
                        url = normalized,
                        title = title.trim().ifBlank { normalized },
                    ),
                )
                true
            }
        }
    }

    suspend fun deleteBookmark(url: String) = withContext(ioDispatcher) {
        bookmarkDao.deleteByUrl(url)
    }

    suspend fun deleteHistoryItem(id: Long) = withContext(ioDispatcher) {
        historyDao.deleteById(id)
    }

    suspend fun clearAllHistory() = withContext(ioDispatcher) {
        historyDao.clearAll()
    }

    suspend fun searchHistory(rawQuery: String, limit: Int = 100): List<HistoryEntity> {
        val needle = rawQuery.trim()
        if (needle.isEmpty()) return emptyList()
        return withContext(ioDispatcher) {
            historyDao.searchHistory(needle, limit)
        }
    }

    /**
     * Gợi ý nhanh từ Dấu trang và Lịch sử đã lưu khi người dùng gõ trên thanh địa chỉ (không ghi bất kỳ dữ liệu nào).
     */
    suspend fun searchSuggestions(rawQuery: String, limit: Int = 6): List<AddressSuggestion> {
        val needle = rawQuery.trim()
        if (needle.isEmpty() || needle.length > 256) return emptyList()
        return withContext(ioDispatcher) {
            val lowerNeedle = needle.lowercase(Locale.ROOT)
            val seenUrls = LinkedHashSet<String>()
            val combined = ArrayList<AddressSuggestion>(limit)

            val allBookmarks = runCatching { bookmarkDao.observeAllBookmarks().first() }.getOrDefault(emptyList())
            for (bm in allBookmarks) {
                if (combined.size >= limit) break
                val matches = bm.url.lowercase(Locale.ROOT).contains(lowerNeedle) ||
                    bm.title.lowercase(Locale.ROOT).contains(lowerNeedle)
                if (matches && seenUrls.add(bm.url)) {
                    combined.add(
                        AddressSuggestion(
                            title = bm.title.ifBlank { bm.url },
                            url = bm.url,
                            source = SuggestionSource.BOOKMARK,
                        ),
                    )
                }
            }

            if (combined.size < limit) {
                val historyMatches = runCatching { historyDao.searchHistory(needle, limit * 2) }.getOrDefault(emptyList())
                for (entry in historyMatches) {
                    if (combined.size >= limit) break
                    if (seenUrls.add(entry.url)) {
                        combined.add(
                            AddressSuggestion(
                                title = entry.title.ifBlank { entry.url },
                                url = entry.url,
                                source = SuggestionSource.HISTORY,
                            ),
                        )
                    }
                }
            }
            combined
        }
    }
}
