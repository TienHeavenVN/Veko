package io.veko.browser.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks ORDER BY createdAtEpochMs DESC")
    fun observeAllBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE url = :url LIMIT 1)")
    fun observeIsBookmarked(url: String): Flow<Boolean>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}
