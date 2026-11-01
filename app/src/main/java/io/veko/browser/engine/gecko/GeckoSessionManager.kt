package io.veko.browser.engine.gecko

import android.content.Context
import android.view.View
import io.veko.browser.tab.EngineSessionCallbacks
import io.veko.browser.tab.EngineSessionHandle
import io.veko.browser.tab.EngineViewHandle
import io.veko.browser.tab.GeckoEngineSessionBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quản lý vòng đời các phiên `GeckoSession` và bề mặt `GeckoView` của nhân Mozilla GeckoView:
 * - Cô lập hoàn toàn phiên thường (`usePrivateMode = false`) và phiên riêng tư (`usePrivateMode = true`).
 * - Hỗ trợ đếm số phiên đang mở và thu hồi sạch toàn bộ phiên riêng tư hoặc toàn bộ phiên khi hủy engine.
 */
@Singleton
class GeckoSessionManager @Inject constructor(
    private val sessionBridge: GeckoEngineSessionBridge,
) {
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

    fun activePrivateSessionCount(): Int = sessionBridge.activePrivateSessionCount()

    fun releasePrivateSessionsIfUnused(activePrivateTabsForEngine: Int) {
        if (activePrivateTabsForEngine <= 0) {
            sessionBridge.closeAllTrackedSessions(onlyPrivate = true)
        }
    }

    fun closeAllSessions() {
        sessionBridge.closeAllTrackedSessions(onlyPrivate = false)
    }

    fun applyPageZoomSettings(autoFontSize: Boolean, zoomPercent: Int) {
        sessionBridge.applyPageZoomSettings(autoFontSize, zoomPercent)
    }

    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?) {
        sessionBridge.setOnRuntimeShutdownListener(listener)
    }
}
