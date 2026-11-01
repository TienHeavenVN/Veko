package io.veko.browser.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.veko.browser.engine.BrowserEngineManager
import io.veko.browser.tab.EngineSessionBridge
import io.veko.browser.tab.MultiEngineSessionBridge
import javax.inject.Singleton

/**
 * Cung cấp bộ điều phối Dual-Engine (`BrowserEngineManager`) cho tầng quản lý Tab và MVI:
 * - Mặc định khởi tạo lười Mozilla GeckoView (Gecko + SpiderMonkey) từ kho chính thức `maven.mozilla.org`.
 * - Duy trì nhân thứ cấp Chromium Content `156.0.8078.85` (Blink + V8) đằng sau `ChromiumAvailabilityChecker`,
 *   chỉ cho phép chọn và khởi tạo khi gói nhị phân gốc đã được xác thực đầy đủ.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideMultiEngineSessionBridge(
        browserEngineManager: BrowserEngineManager,
    ): MultiEngineSessionBridge = browserEngineManager

    @Provides
    @Singleton
    fun provideEngineSessionBridge(
        browserEngineManager: BrowserEngineManager,
    ): EngineSessionBridge = browserEngineManager
}
