package io.veko.browser.data.repository

import io.veko.browser.data.local.BookmarkDao
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryDao
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.tab.UrlNavigationPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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
}
