package io.veko.browser

import android.app.Application
import android.os.Build
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.download.VekoDownloadManager
import io.veko.browser.tab.TabManager
import javax.inject.Inject

/**
 * Application gốc dùng Hilt DI.
 * QUYẾT ĐỊNH THIẾT KẾ: Không khởi tạo GeckoRuntime trong onCreate() vì GeckoView tự sinh các
 * child service process (:tab, :gpu, :socket) cũng chạy qua Application.onCreate().
 */
@HiltAndroidApp
class VekoApplication : Application() {

    // Dùng dagger.Lazy để không khởi tạo TabManager hay Room DB ở các tiến trình con của Gecko
    @Inject
    lateinit var tabManager: Lazy<TabManager>

    @Inject
    lateinit var dataRepository: Lazy<BrowserDataRepository>

    @Inject
    lateinit var downloadManager: Lazy<VekoDownloadManager>

    private var isMainBrowserProcess: Boolean = false
    private var tabManagerAccessed: Boolean = false

    override fun onCreate() {
        super.onCreate()
        isMainBrowserProcess = resolveIsMainProcess()
    }

    internal fun markTabManagerReady() {
        tabManagerAccessed = true
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Chỉ thu hồi session nền trên tiến trình UI chính khi hệ thống báo thiếu RAM
        if (isMainBrowserProcess && tabManagerAccessed) {
            tabManager.get().onTrimMemory(level)
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
