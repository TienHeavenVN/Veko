package io.veko.browser.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.veko.browser.engine.BrowserEngine
import io.veko.browser.engine.gecko.GeckoBrowserEngine
import io.veko.browser.tab.EngineSessionBridge
import javax.inject.Singleton

/**
 * Cung cấp browser engine duy nhất của Veko Browser (`GeckoBrowserEngine` — Mozilla GeckoView)
 * cho tầng quản lý Tab và MVI Store.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindBrowserEngine(
        geckoBrowserEngine: GeckoBrowserEngine,
    ): BrowserEngine

    @Binds
    @Singleton
    abstract fun bindEngineSessionBridge(
        geckoBrowserEngine: GeckoBrowserEngine,
    ): EngineSessionBridge
}
