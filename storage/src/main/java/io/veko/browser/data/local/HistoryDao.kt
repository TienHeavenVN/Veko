package io.veko.browser.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Query("SELECT * FROM browsing_history ORDER BY visitedAtEpochMs DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int = 200): Flow<List<HistoryEntity>>

    @Query(
        """
        SELECT * FROM browsing_history
        WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%'
        ORDER BY visitedAtEpochMs DESC
        LIMIT :limit
        """,
    )
    suspend fun searchHistory(query: String, limit: Int = 15): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntity)

    @Query("DELETE FROM browsing_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM browsing_history")
    suspend fun clearAll()
}
