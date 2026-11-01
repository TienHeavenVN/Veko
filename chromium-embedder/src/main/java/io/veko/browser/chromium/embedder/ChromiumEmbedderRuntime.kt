package io.veko.browser.chromium.embedder

import android.content.Context
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import java.io.File

data class ChromiumEmbedderReadiness(
    val pinnedChromiumVersion: String,
    val nativeJniBridgeLoaded: Boolean,
    val rustAdblockLoaded: Boolean,
    val upstreamBlinkV8ContentLinked: Boolean,
    val activeRenderingEngine: String,
    val technicalBlockerSummary: String?,
    val pinnedChromiumCommit: String = ChromiumEmbedderRuntime.PINNED_CHROMIUM_COMMIT,
    val pakAndIcuAssetsPresent: Boolean = false,
    val childProcessServicesConfigured: Boolean = false,
) {
    /**
     * Chỉ trả về `true` khi toàn bộ ngăn xếp Chromium Content `156.0.8078.85`
     * (`libveko_content_embedder.so` + `.pak`/`icudtl.dat` + `ChildProcessService`) đã liên kết thực tế.
     */
    val isFullUpstreamRenderingReady: Boolean
        get() = nativeJniBridgeLoaded &&
            upstreamBlinkV8ContentLinked &&
            pakAndIcuAssetsPresent &&
            childProcessServicesConfigured
}

/**
 * Quản lý vòng đời Chromium Content Embedder (`BrowserContext` thường và `Off-The-Record`):
 * Báo cáo trung thực trạng thái liên kết `libveko_content_embedder.so` (Blink + V8);
 * tuyệt đối không giả lập bằng Android System WebView hoặc UI placeholder.
 */
class ChromiumEmbedderRuntime(
    private val appFilesDir: File,
) {
    private var regularContextInstance: ChromiumBrowserContext? = null
    private var activeOffTheRecordContext: ChromiumBrowserContext? = null
    private val contextLock = Any()

    val regularBrowserContext: ChromiumBrowserContext
        get() = synchronized(contextLock) {
            val current = regularContextInstance
            if (current != null && !current.isDestroyed) {
                return@synchronized current
            }
            ensureNativeRuntimeInitialized()
            ChromiumBrowserContext.createRegular(File(appFilesDir, "chromium_profile")).also {
                regularContextInstance = it
            }
        }

    fun ensureNativeRuntimeInitialized(): VekoChromiumNative.NativeRuntimeStatus {
        val runtimeDir = File(appFilesDir, "chromium_runtime").apply { mkdirs() }
        return VekoChromiumNative.initialize(
            appDataDir = runtimeDir.absolutePath,
            enforceUiThread = false,
        )
    }

    fun acquireOffTheRecordContext(): ChromiumBrowserContext = synchronized(contextLock) {
        val current = activeOffTheRecordContext
        if (current != null && !current.isDestroyed) {
            return current
        }
        ensureNativeRuntimeInitialized()
        ChromiumBrowserContext.createOffTheRecord().also {
            activeOffTheRecordContext = it
        }
    }

    fun releaseOffTheRecordContextIfUnused(activePrivateTabCount: Int): Boolean = synchronized(contextLock) {
        if (activePrivateTabCount > 0) return false
        val current = activeOffTheRecordContext ?: return false
        current.close()
        activeOffTheRecordContext = null
        true
    }

    fun hasActiveOffTheRecordContext(): Boolean = synchronized(contextLock) {
        val current = activeOffTheRecordContext
        current != null && !current.isDestroyed
    }

    fun probeReadiness(context: Context? = null): ChromiumEmbedderReadiness {
        val initStatus = ensureNativeRuntimeInitialized()
        val metadataJson = VekoChromiumNative.getEngineMetadataJson()
        val soLinked = initStatus.upstreamContentLinked || VekoChromiumNative.isUpstreamContentLinked()
        val rustReady = metadataJson.contains("adblock-rust")
        val assetsPresent = if (context != null) {
            probeRequiredChromiumAssets(context)
        } else {
            File(appFilesDir, "veko_content.pak").isFile && File(appFilesDir, "icudtl.dat").isFile
        }
        val childServicesReady = BaseVekoChromiumProcessService.isUpstreamChildServiceAvailable()
        val fullUpstreamReady = soLinked && assetsPresent && childServicesReady

        return ChromiumEmbedderReadiness(
            pinnedChromiumVersion = PINNED_CHROMIUM_VERSION,
            nativeJniBridgeLoaded = initStatus.isOk,
            rustAdblockLoaded = rustReady,
            upstreamBlinkV8ContentLinked = fullUpstreamReady,
            activeRenderingEngine = if (fullUpstreamReady) {
                "Chromium Content $PINNED_CHROMIUM_VERSION (Blink + V8)"
            } else {
                "GeckoView 150.0.20260511200624 (Phase 6 Safety Gate Active) + Veko Native C++20/Rust Shield"
            },
            technicalBlockerSummary = if (fullUpstreamReady) {
                null
            } else {
                "Upstream Chromium 156.0.8078.85 (0c5c70590a80) //content + Blink + V8 requires ~110 GB disk / >=24 GB RAM Linux build host via scripts/build-chromium-android.sh & scripts/import-chromium-sdk.sh; standard GitHub runner provides 16 GB RAM. Keeping GeckoView rendering active until verified SDK bundle is imported."
            },
            pinnedChromiumCommit = PINNED_CHROMIUM_COMMIT,
            pakAndIcuAssetsPresent = assetsPresent,
            childProcessServicesConfigured = childServicesReady,
        )
    }

    fun shutdown() = synchronized(contextLock) {
        activeOffTheRecordContext?.close()
        activeOffTheRecordContext = null
        regularContextInstance?.close()
        regularContextInstance = null
        if (VekoChromiumNative.isLibraryLoaded()) {
            VekoChromiumNative.shutdown()
        }
    }

    private fun probeRequiredChromiumAssets(context: Context): Boolean =
        runCatching {
            val topAssets = context.assets.list("").orEmpty().toSet()
            REQUIRED_PAK_ASSETS.all { it in topAssets }
        }.getOrDefault(false)

    companion object {
        const val PINNED_CHROMIUM_VERSION = "156.0.8078.85"
        const val PINNED_CHROMIUM_COMMIT = "0c5c70590a804c285f0cafe8e9c4c20afcfbc5b5"
        private val REQUIRED_PAK_ASSETS = listOf("veko_content.pak", "icudtl.dat")
    }
}
