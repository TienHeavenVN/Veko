package io.veko.browser.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class VekoDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
}
