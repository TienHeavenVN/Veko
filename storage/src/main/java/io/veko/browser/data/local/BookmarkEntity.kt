package io.veko.browser.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bản ghi Dấu trang (Bookmark) với ràng buộc duy nhất theo URL.
 */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["url"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val url: String,
    val title: String,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)
