package io.veko.browser.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Bản ghi lịch sử duyệt web của các tab thường (tuyệt đối không lưu tab isPrivate).
 * Đánh chỉ mục theo thời gian giảm dần và URL để gợi ý Omnibox dưới 5ms.
 */
@Entity(
    tableName = "browsing_history",
    indices = [
        Index(value = ["visitedAtEpochMs"]),
        Index(value = ["url"]),
    ],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val url: String,
    val title: String,
    val visitedAtEpochMs: Long = System.currentTimeMillis(),
)
