package io.veko.browser.engine.chromium

import android.content.Context
import android.view.View
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.tab.ChromiumEngineSessionBridge
import io.veko.browser.tab.EngineSessionCallbacks
import io.veko.browser.tab.EngineSessionHandle
import io.veko.browser.tab.EngineViewHandle
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quản lý vòng đời các phiên duyệt web của nhân Chromium Content `156.0.8078.85`
 * (`Regular BrowserContext` trên đĩa riêng `chromium_profile` và `Off-The-Record BrowserContext` thuần RAM).
 *
 * Tuyệt đối không dùng chung thư mục profile, cookie hay DOM storage với Mozilla GeckoView.
 */
@Singleton
class ChromiumSessionManager @Inject constructor(
    private val embedderRuntime: ChromiumEmbedderRuntime,
    private val sessionBridge: ChromiumEngineSessionBridge,
) {
    fun initializeRuntime(): Boolean =
        embedderRuntime.ensureNativeRuntimeInitialized().isOk

    fun createPlatformView(context: Context): View =
        sessionBridge.createPlatformView(context)

    fun wrapPlatformView(platformView: View): EngineViewHandle =
        sessionBridge.wrapPlatformView(platformView)

    fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle = sessionBridge.createSession(
        isPrivate = isPrivate,
        isDesktopMode = isDesktopMode,
        openImmediately = openImmediately,
        callbacks = callbacks,
    )

    fun activeSessionCount(): Int = sessionBridge.activeSessionCount()

    fun activePrivateSessionCount(): Int = sessionBridge.activePrivateCount()

    fun releasePrivateContextIfUnused(activePrivateTabsForEngine: Int) {
        if (activePrivateTabsForEngine <= 0) {
            sessionBridge.closeAllTrackedSessions(onlyPrivate = true)
            embedderRuntime.releaseOffTheRecordContextIfUnused(activePrivateTabCount = 0)
        }
    }

    fun clearIsolatedStorage(
        appFilesDir: File?,
        includeCookies: Boolean,
        includeCache: Boolean,
        onlyPrivate: Boolean,
    ) {
        if (onlyPrivate) {
            releasePrivateContextIfUnused(activePrivateTabsForEngine = 0)
            return
        }
        releasePrivateContextIfUnused(activePrivateTabsForEngine = 0)
        if (includeCookies || includeCache) {
            appFilesDir?.let { baseDir ->
                val chromiumCacheDir = File(baseDir, "chromium_profile/Cache")
                if (includeCache && chromiumCacheDir.exists()) {
                    runCatching { chromiumCacheDir.deleteRecursively() }
                }
            }
        }
    }

    fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        sessionBridge.applyPageZoomSettings(autoFontSize, zoomPercent)
    }

    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        sessionBridge.setOnRuntimeShutdownListener(listener)
    }

    fun shutdown() {
        sessionBridge.notifyRuntimeShutdown()
    }
}
