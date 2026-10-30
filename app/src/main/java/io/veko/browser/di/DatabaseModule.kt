package io.veko.browser.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.veko.browser.data.local.BookmarkDao
import io.veko.browser.data.local.HistoryDao
import io.veko.browser.data.local.VekoDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideVekoDatabase(
        @ApplicationContext context: Context,
    ): VekoDatabase {
        return Room.databaseBuilder(
            context,
            VekoDatabase::class.java,
            "veko_browser.db",
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideHistoryDao(database: VekoDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideBookmarkDao(database: VekoDatabase): BookmarkDao = database.bookmarkDao()
}
