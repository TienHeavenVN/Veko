package io.veko.browser

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.download.VekoDownloadManager
import io.veko.browser.engine.gecko.GeckoBrowserEngine
import io.veko.browser.privacy.PrivacyShieldEngine
import io.veko.browser.tab.BrowserHostApplication
import io.veko.browser.tab.TabManager
import javax.inject.Inject

/**
 * Application gốc dùng Hilt DI và cấu hình Coil 3 ở chế độ thuần ngoại tuyến (Offline-Only):
 * - Không khởi tạo `GeckoRuntime` hay Room DB trong các tiến trình con cô lập (:tab, :gpu, :media, :utility).
 * - Khóa cứng `networkCachePolicy(CachePolicy.DISABLED)` và `diskCachePolicy(CachePolicy.DISABLED)` trên Coil 3
 *   để mọi ảnh giao diện chỉ giải mã trên RAM và không bao giờ phát sinh truy vấn mạng ngoài luồng bảo vệ của Veko.
 */
@HiltAndroidApp
class VekoApplication : Application(), BrowserHostApplication {

    @Inject
    lateinit var tabManager: Lazy<TabManager>

    @Inject
    lateinit var geckoBrowserEngine: Lazy<GeckoBrowserEngine>

    @Inject
    lateinit var dataRepository: Lazy<BrowserDataRepository>

    @Inject
    lateinit var downloadManager: Lazy<VekoDownloadManager>

    @Inject
    lateinit var privacyShieldEngine: Lazy<PrivacyShieldEngine>

    private var isMainBrowserProcess: Boolean = false
    private var tabManagerAccessed: Boolean = false

    override fun onCreate() {
        super.onCreate()
        isMainBrowserProcess = resolveIsMainProcess()
        if (isMainBrowserProcess) {
            SingletonImageLoader.setSafe { ctx ->
                ImageLoader.Builder(ctx)
                    .networkCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .build()
            }
        }
    }

    override fun markTabManagerReady() {
        tabManagerAccessed = true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!isMainBrowserProcess) return
        if (tabManagerAccessed && ::tabManager.isInitialized) {
            tabManager.get().onTrimMemory(level)
        } else if (::geckoBrowserEngine.isInitialized) {
            geckoBrowserEngine.get().onTrimMemory(level)
        }
    }

    private fun resolveIsMainProcess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName() == packageName
        } else {
            true
        }
    }
}
