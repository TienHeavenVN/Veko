package io.veko.browser.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.data.local.BookmarkDao
import io.veko.browser.data.local.HistoryDao
import io.veko.browser.data.local.VekoDatabase
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.download.VekoDownloadManager
import io.veko.browser.mvi.BrowserMviStore
import io.veko.browser.permission.SitePermissionManager
import io.veko.browser.privacy.FilterListManager
import io.veko.browser.privacy.PrivacyShieldEngine
import io.veko.browser.tab.EngineSessionBridge
import io.veko.browser.tab.TabManager
import java.io.File
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
        ).build()
    }

    @Provides
    fun provideHistoryDao(db: VekoDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideBookmarkDao(db: VekoDatabase): BookmarkDao = db.bookmarkDao()

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

    @Provides
    @Singleton
    fun provideFilterListManager(
        @ApplicationContext context: Context,
    ): FilterListManager = FilterListManager(
        customRulesStorageFile = File(context.filesDir, "privacy/custom_filter_rules.txt"),
    )

    @Provides
    @Singleton
    fun providePrivacyShieldEngine(
        filterListManager: FilterListManager,
    ): PrivacyShieldEngine = PrivacyShieldEngine(filterListManager)

    @Provides
    @Singleton
    fun provideChromiumEmbedderRuntime(
        @ApplicationContext context: Context,
    ): ChromiumEmbedderRuntime = ChromiumEmbedderRuntime(context.filesDir)

    @Provides
    @Singleton
    fun provideTabManager(
        @ApplicationContext context: Context,
        engineBridge: EngineSessionBridge,
        dataRepository: BrowserDataRepository,
        settingsRepository: SettingsRepository,
        sitePermissionManager: SitePermissionManager,
        downloadManager: VekoDownloadManager,
        privacyShieldEngine: PrivacyShieldEngine,
        chromiumEmbedderRuntime: ChromiumEmbedderRuntime,
    ): TabManager = TabManager(
        appContext = context,
        engineBridge = engineBridge,
        dataRepository = dataRepository,
        settingsRepository = settingsRepository,
        sitePermissionManager = sitePermissionManager,
        downloadManager = downloadManager,
        privacyShieldEngine = privacyShieldEngine,
        chromiumEmbedderRuntime = chromiumEmbedderRuntime,
    )

    @Provides
    @Singleton
    fun provideBrowserMviStore(
        tabManager: TabManager,
        settingsRepository: SettingsRepository,
        privacyShieldEngine: PrivacyShieldEngine,
        chromiumEmbedderRuntime: ChromiumEmbedderRuntime,
    ): BrowserMviStore = BrowserMviStore(
        tabManager = tabManager,
        settingsRepository = settingsRepository,
        privacyShieldEngine = privacyShieldEngine,
        chromiumEmbedderRuntime = chromiumEmbedderRuntime,
    )
}
