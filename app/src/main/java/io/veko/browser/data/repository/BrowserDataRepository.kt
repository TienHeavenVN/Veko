package io.veko.browser.data.repository

import io.veko.browser.data.local.BookmarkDao
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryDao
import io.veko.browser.data.local.HistoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kho dữ liệu tập trung cho Lịch sử & Dấu trang, chạy hoàn toàn ngoài UI Thread.
 */
@Singleton
class BrowserDataRepository @Inject constructor(
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao,
) {
    val recentHistory: Flow<List<HistoryEntity>> = historyDao.observeRecentHistory()
    val bookmarks: Flow<List<BookmarkEntity>> = bookmarkDao.observeAllBookmarks()

    fun observeIsBookmarked(url: String): Flow<Boolean> = bookmarkDao.observeIsBookmarked(url)

    suspend fun recordVisitIfRegular(url: String, title: String, isPrivate: Boolean) {
        // Rào chắn kép: không bao giờ ghi lịch sử nếu là tab riêng tư hoặc trang nội bộ rỗng
        if (isPrivate || url.isBlank() || url == "about:blank" || url.startsWith("data:")) return
        withContext(Dispatchers.IO) {
            historyDao.insert(
                HistoryEntity(
                    url = url,
                    title = title.ifBlank { url },
                ),
            )
        }
    }

    suspend fun toggleBookmark(url: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val existing = bookmarkDao.findByUrl(url)
        if (existing != null) {
            bookmarkDao.deleteByUrl(url)
            false
        } else {
            bookmarkDao.upsert(
                BookmarkEntity(
                    url = url,
                    title = title.ifBlank { url },
                ),
            )
            true
        }
    }

    suspend fun deleteBookmark(url: String) = withContext(Dispatchers.IO) {
        bookmarkDao.deleteByUrl(url)
    }

    suspend fun deleteHistoryItem(id: Long) = withContext(Dispatchers.IO) {
        historyDao.deleteById(id)
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        historyDao.clearAll()
    }
}
