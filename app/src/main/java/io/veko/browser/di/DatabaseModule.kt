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
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.download.VekoDownloadManager
import io.veko.browser.permission.SitePermissionManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideVekoDatabase(
        @ApplicationContext context: Context,
    ): VekoDatabase {
        // Không bật fallbackToDestructiveMigration để bảo vệ an toàn dữ liệu lịch sử & dấu trang hiện có
        return Room.databaseBuilder(
            context,
            VekoDatabase::class.java,
            "veko_browser.db",
        ).build()
    }

    @Provides
    fun provideHistoryDao(database: VekoDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideBookmarkDao(database: VekoDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    @Singleton
    fun provideBrowserDataRepository(
        historyDao: HistoryDao,
        bookmarkDao: BookmarkDao,
    ): BrowserDataRepository = BrowserDataRepository(historyDao, bookmarkDao)

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository = SettingsRepository(context)

    @Provides
    @Singleton
    fun provideSitePermissionManager(
        @ApplicationContext context: Context,
    ): SitePermissionManager = SitePermissionManager(context)

    @Provides
    @Singleton
    fun provideVekoDownloadManager(
        @ApplicationContext context: Context,
    ): VekoDownloadManager = VekoDownloadManager(context)
}
