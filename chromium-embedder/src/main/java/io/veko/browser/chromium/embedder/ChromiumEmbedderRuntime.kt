package io.veko.browser.chromium.embedder

import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import java.io.File

data class ChromiumEmbedderReadiness(
    val pinnedChromiumVersion: String,
    val nativeJniBridgeLoaded: Boolean,
    val rustAdblockLoaded: Boolean,
    val upstreamBlinkV8ContentLinked: Boolean,
    val activeRenderingEngine: String,
    val technicalBlockerSummary: String?
)

/**
 * Quản lý vòng đời Chromium Content Embedder (`BrowserContext` thường và `Off-The-Record`):
 * Báo cáo trung thực trạng thái liên kết `libcontent` (Blink + V8); tuyệt đối không giả lập bằng `android.webkit.WebView`.
 */
class ChromiumEmbedderRuntime(
    private val appFilesDir: File
) {

    val regularBrowserContext: ChromiumBrowserContext by lazy {
        ChromiumBrowserContext.createRegular(File(appFilesDir, "chromium_profile"))
    }

    private var activeOffTheRecordContext: ChromiumBrowserContext? = null
    private val otrLock = Any()

    fun acquireOffTheRecordContext(): ChromiumBrowserContext = synchronized(otrLock) {
        val current = activeOffTheRecordContext
        if (current != null && !current.isDestroyed) {
            return current
        }
        ChromiumBrowserContext.createOffTheRecord().also {
            activeOffTheRecordContext = it
        }
    }

    fun releaseOffTheRecordContextIfUnused(activePrivateTabCount: Int): Boolean = synchronized(otrLock) {
        if (activePrivateTabCount > 0) return false
        val current = activeOffTheRecordContext ?: return false
        current.close()
        activeOffTheRecordContext = null
        true
    }

    fun hasActiveOffTheRecordContext(): Boolean = synchronized(otrLock) {
        val current = activeOffTheRecordContext
        current != null && !current.isDestroyed
    }

    fun probeReadiness(): ChromiumEmbedderReadiness {
        val metadataJson = VekoChromiumNative.getEngineMetadataJson()
        val upstreamLinked = VekoChromiumNative.isUpstreamContentLinked()
        val rustReady = metadataJson.contains("adblock-rust")
        return ChromiumEmbedderReadiness(
            pinnedChromiumVersion = PINNED_CHROMIUM_VERSION,
            nativeJniBridgeLoaded = true,
            rustAdblockLoaded = rustReady,
            upstreamBlinkV8ContentLinked = upstreamLinked,
            activeRenderingEngine = if (upstreamLinked) {
                "Chromium Content $PINNED_CHROMIUM_VERSION (Blink + V8)"
            } else {
                "GeckoView 150.0.20260415091642 (Phase 6 Gate Active) + Veko Native C++20/Rust Shield"
            },
            technicalBlockerSummary = if (upstreamLinked) {
                null
            } else {
                "Upstream Chromium //content + Blink + V8 requires ~100 GB disk / >=32 GB RAM build host (chromium.googlesource.com); standard CI runner holds 16 GB RAM / ~30 GB disk. Keeping GeckoView rendering active per Phase 6 safety gate."
            }
        )
    }

    companion object {
        const val PINNED_CHROMIUM_VERSION = "156.0.8078.85"
    }
}
