package io.veko.browser.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.veko.browser.tab.EngineSessionBridge
import io.veko.browser.tab.GeckoEngineSessionBridge
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

    @Binds
    @Singleton
    abstract fun bindEngineSessionBridge(
        impl: GeckoEngineSessionBridge,
    ): EngineSessionBridge
}
