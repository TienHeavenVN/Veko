package io.veko.browser.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.tab.ChromiumEngineSessionBridge
import io.veko.browser.tab.EngineSessionBridge
import io.veko.browser.tab.GeckoEngineSessionBridge
import javax.inject.Singleton

/**
 * Cung cấp `EngineSessionBridge` theo cổng an toàn trung thực (Phase 6/7 Safety Gate):
 * - Chỉ kích hoạt `ChromiumEngineSessionBridge` cho người dùng khi toàn bộ ngăn xếp
 *   Chromium Content `156.0.8078.85` (`libveko_content_embedder.so` + `.pak`/`icudtl.dat` + `ChildProcessService`)
 *   đã được nhập và kiểm định.
 * - Khi gói nhị phân thượng nguồn chưa được nhập vào APK, duy trì kết xuất thực qua `GeckoEngineSessionBridge`
 *   cùng lá chắn mạng C++20/Rust (`VekoChromiumNative`) mà không bao giờ hiển thị UI giả lập.
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideEngineSessionBridge(
        @ApplicationContext context: Context,
        chromiumEmbedderRuntime: ChromiumEmbedderRuntime,
        chromiumBridge: ChromiumEngineSessionBridge,
        geckoBridge: GeckoEngineSessionBridge,
    ): EngineSessionBridge {
        val readiness = chromiumEmbedderRuntime.probeReadiness(context)
        return if (readiness.isFullUpstreamRenderingReady) {
            chromiumBridge
        } else {
            geckoBridge
        }
    }
}
