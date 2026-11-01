package io.veko.browser.engine

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.engine.gecko.GeckoSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.StorageController
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quản lý duy nhất một thực thể [GeckoRuntime] trong suốt vòng đời tiến trình ứng dụng.
 *
 * Bất biến hiệu năng & cô lập (Dual-Engine):
 * - Chỉ khởi tạo lười (`getOrCreateRuntime`) khi có tab `EngineType.GECKO` thực sự mở.
 * - Cô lập hoàn toàn hồ sơ đĩa và bộ nhớ cookie/cache của Mozilla Gecko khỏi `ChromiumBrowserContext`.
 */
@Singleton
class BrowserRuntimeManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    private var geckoRuntime: GeckoRuntime? = null
    private var onShutdownListener: (() -> Unit)? = null
    private var pendingAutoFontSize: Boolean = true
    private var pendingZoomFactor: Float = 1.0f
    private var pendingCookieMode: CookieControlMode = CookieControlMode.BLOCK_THIRD_PARTY

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    val isRuntimeCreated: Boolean
        get() = geckoRuntime != null

    @MainThread
    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        onShutdownListener = listener
    }

    @MainThread
    fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        checkMainThread("applyPageZoomSettings")
        val factor = (zoomPercent.coerceIn(75, 150) / 100f)
        pendingAutoFontSize = autoFontSize
        pendingZoomFactor = factor
        val runtime = geckoRuntime ?: return
        val settings = runtime.settings
        if (autoFontSize && zoomPercent == 100) {
            settings.setAutomaticFontSizeAdjustment(true)
        } else {
            settings.setAutomaticFontSizeAdjustment(false)
            settings.setFontSizeFactor(factor)
        }
    }

    @MainThread
    fun applyCookiePolicy(mode: CookieControlMode) {
        checkMainThread("applyCookiePolicy")
        pendingCookieMode = mode
        val runtime = geckoRuntime ?: return
        runtime.settings.contentBlocking
            .setCookieBehavior(GeckoSettings.mapRegularCookieBehavior(mode))
            .setCookieBehaviorPrivateMode(GeckoSettings.mapPrivateCookieBehavior(mode))
    }

    @MainThread
    fun clearStorageData(
        includeCookies: Boolean,
        includeCache: Boolean,
    ) {
        checkMainThread("clearStorageData")
        val runtime = geckoRuntime ?: return
        var flags = 0L
        if (includeCookies) {
            flags = flags or
                StorageController.ClearFlags.COOKIES or
                StorageController.ClearFlags.DOM_STORAGES or
                StorageController.ClearFlags.AUTH_SESSIONS
        }
        if (includeCache) {
            flags = flags or StorageController.ClearFlags.ALL_CACHES
        }
        if (flags != 0L) {
            runCatching { runtime.storageController.clearData(flags) }
        }
    }

    @MainThread
    fun onLowMemory(level: Int) {
        checkMainThread("onLowMemory")
        val runtime = geckoRuntime ?: return
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            runCatching {
                runtime.storageController.clearData(StorageController.ClearFlags.IMAGE_CACHE)
            }
        }
    }

    /**
     * Khởi tạo lười (lazy) trên UI thread ngay khi tab `GECKO` đầu tiên cần mở session.
     */
    @MainThread
    fun getOrCreateRuntime(): GeckoRuntime {
        checkMainThread("getOrCreateRuntime")
        geckoRuntime?.let { return it }

        val runtimeSettings = GeckoSettings.buildRuntimeSettings(
            cookieMode = pendingCookieMode,
            autoFontSize = pendingAutoFontSize,
            zoomFactor = pendingZoomFactor,
        )

        return GeckoRuntime.create(appContext, runtimeSettings).also { created ->
            created.delegate = GeckoRuntime.Delegate {
                Log.w(TAG, "GeckoRuntime stopped (onShutdown).")
                geckoRuntime = null
                _isInitialized.value = false
                onShutdownListener?.invoke()
            }
            geckoRuntime = created
            _isInitialized.value = true
        }
    }

    @MainThread
    fun shutdown() {
        checkMainThread("shutdown")
        val runtime = geckoRuntime ?: return
        geckoRuntime = null
        _isInitialized.value = false
        runCatching { runtime.shutdown() }
    }

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
