package io.veko.browser.engine

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quản lý duy nhất một thực thể [GeckoRuntime] trong suốt vòng đời tiến trình ứng dụng.
 * GeckoView nghiêm cấm tạo nhiều hơn 1 GeckoRuntime trên mỗi OS process.
 */
@Singleton
class BrowserRuntimeManager @Inject constructor(
    // Bắt buộc dùng ApplicationContext để tránh rò rỉ (leak) MainActivity khi xoay màn hình
    @ApplicationContext private val appContext: Context,
) {
    private var geckoRuntime: GeckoRuntime? = null
    private var onShutdownListener: (() -> Unit)? = null

    @MainThread
    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        onShutdownListener = listener
    }

    /**
     * Khởi tạo lười (lazy) trên UI thread ngay khi tab đầu tiên cần mở session.
     */
    @MainThread
    fun getOrCreateRuntime(): GeckoRuntime {
        checkMainThread("getOrCreateRuntime")
        geckoRuntime?.let { return it }

        // Bật Enhanced Tracking Protection + SafeBrowsing + cô lập cookie bên thứ ba mặc định cho Veko
        val contentBlocking = ContentBlocking.Settings.Builder()
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
            .antiTracking(ContentBlocking.AntiTracking.DEFAULT)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY)
            .build()

        // Tắt about:config ở bản phát hành để giữ an toàn bề mặt cấu hình nội bộ của engine
        val runtimeSettings = GeckoRuntimeSettings.Builder()
            .contentBlocking(contentBlocking)
            .loginAutofillEnabled(true)
            .automaticFontSizeAdjustment(true)
            .aboutConfigEnabled(false)
            .build()
            .apply {
                // Tự động nâng cấp HTTPS trong chế độ riêng tư, hạn chế rò rỉ plaintext
                setAllowInsecureConnections(GeckoRuntimeSettings.HTTPS_ONLY_PRIVATE)
            }

        return GeckoRuntime.create(appContext, runtimeSettings).also { created ->
            created.delegate = GeckoRuntime.Delegate {
                // Khi GeckoRuntime tự tắt do lỗi nghiêm trọng, xóa tham chiếu và thông báo TabManager dọn session chết
                Log.w(TAG, "GeckoRuntime đã dừng (onShutdown).")
                geckoRuntime = null
                onShutdownListener?.invoke()
            }
            geckoRuntime = created
        }
    }

    /**
     * Đồng bộ thay đổi cấu hình hệ thống (ngôn ngữ, dark mode, font scale) xuống Gecko.
     */
    @MainThread
    fun onConfigurationChanged(newConfig: Configuration) {
        checkMainThread("onConfigurationChanged")
        geckoRuntime?.configurationChanged(newConfig)
    }

    private fun checkMainThread(methodName: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BrowserRuntimeManager.$methodName() bắt buộc gọi trên Android Main/UI Thread."
        }
    }

    private companion object {
        const val TAG = "BrowserRuntimeManager"
    }
}
