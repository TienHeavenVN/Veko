package io.veko.browser.chromium.nativebridge

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cầu nối JNI kiểu mạnh (strongly-typed) tới C++20 `libveko_chromium_native.so` (GN + Ninja)
 * và `libveko_content_embedder.so` khi SDK Chromium `156.0.8078.85` được liên kết.
 */
object VekoChromiumNative {
    const val LIBRARY_NAME = "veko_chromium_native"
    const val CONTENT_EMBEDDER_LIBRARY_NAME = "veko_content_embedder"

    // Mã lỗi trả về từ C++20 VekoNativeBrowserRuntime
    const val NATIVE_OK = 0
    const val ERR_NOT_INITIALIZED = -1
    const val ERR_ALREADY_SHUTDOWN = -2
    const val ERR_WRONG_THREAD = -3
    const val ERR_INVALID_CONTEXT = -4
    const val ERR_INVALID_BROWSER_HANDLE = -5
    const val ERR_INVALID_ARGUMENT = -6
    const val ERR_BLOCKED_BY_POLICY = -7
    const val ERR_SURFACE_ACQUIRE_FAILED = -8
    const val ERR_NATIVE_EXCEPTION = -9

    private val loaded = AtomicBoolean(false)
    private val contentEmbedderLoaded = AtomicBoolean(false)

    data class NativeDecision(
        val action: String,
        val mutatedUrl: String,
        val matchedRuleReason: String,
    )

    data class NativePipelineStats(
        val totalEvaluated: Long,
        val totalBlocked: Long,
        val totalUpgradedHttps: Long,
        val totalParamsStripped: Long,
    )

    data class NativeRuntimeStatus(
        val errorCode: Int,
        val initialized: Boolean,
        val upstreamContentLinked: Boolean,
        val threadAffinityEnforced: Boolean,
        val activeBrowsers: Int,
        val dataDir: String,
    ) {
        val isOk: Boolean get() = errorCode == NATIVE_OK && initialized
    }

    data class NativeLoadUrlResult(
        val errorCode: Int,
        val pipelineAction: String,
        val finalUrl: String,
        val matchedRuleReason: String,
        val upstreamContentRendered: Boolean,
    ) {
        val accepted: Boolean get() = errorCode == NATIVE_OK
    }

    data class NativeSurfaceSnapshot(
        val attached: Boolean,
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val attachGeneration: Long,
    )

    /**
     * Nạp thư viện native C++20:
     * 1) Trên JVM host test: ưu tiên `-Dveko.native.lib.path` (file `.so`) hoặc `-Dveko.native.lib.dir`.
     * 2) Trên Android APK: gọi `System.loadLibrary("veko_chromium_native")` và thử nạp thêm
     *    `veko_content_embedder` nếu được đóng gói cùng APK.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded.get()) return true

        val explicitFile = System.getProperty("veko.native.lib.path")?.trim().orEmpty()
        if (explicitFile.isNotEmpty()) {
            val candidate = File(explicitFile)
            if (candidate.isFile) {
                System.load(candidate.absolutePath)
                loaded.set(true)
                tryLoadContentEmbedderAdjacent(candidate.parentFile)
                return true
            }
        }

        val explicitDir = System.getProperty("veko.native.lib.dir")?.trim().orEmpty()
        if (explicitDir.isNotEmpty()) {
            val dir = File(explicitDir)
            val soCandidate = File(dir, System.mapLibraryName(LIBRARY_NAME))
            if (soCandidate.isFile) {
                System.load(soCandidate.absolutePath)
                loaded.set(true)
                tryLoadContentEmbedderAdjacent(dir)
                return true
            }
        }

        return runCatching {
            System.loadLibrary(LIBRARY_NAME)
            loaded.set(true)
            runCatching {
                System.loadLibrary(CONTENT_EMBEDDER_LIBRARY_NAME)
                contentEmbedderLoaded.set(true)
            }
            true
        }.getOrElse { false }
    }

    private fun tryLoadContentEmbedderAdjacent(dir: File?) {
        if (dir == null || contentEmbedderLoaded.get()) return
        val embedderSo = File(dir, System.mapLibraryName(CONTENT_EMBEDDER_LIBRARY_NAME))
        if (embedderSo.isFile) {
            runCatching {
                System.load(embedderSo.absolutePath)
                contentEmbedderLoaded.set(true)
            }
        }
    }

    fun isLibraryLoaded(): Boolean = loaded.get()

    fun isContentEmbedderLibraryLoaded(): Boolean =
        contentEmbedderLoaded.get() || (loaded.get() && nativeIsUpstreamContentLinked())

    // ========================================================================
    // API typed bậc cao (Phase 4 Lifecycle, Session, Navigation, Surface)
    // ========================================================================

    fun initialize(appDataDir: String, enforceUiThread: Boolean = true): NativeRuntimeStatus {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        val wire = nativeInitializeRuntime(appDataDir, enforceUiThread)
        return NativeRuntimeStatus(
            errorCode = wire.getOrNull(0)?.toIntOrNull() ?: ERR_NATIVE_EXCEPTION,
            initialized = wire.getOrNull(1) == "1",
            upstreamContentLinked = (wire.getOrNull(2) == "1") || contentEmbedderLoaded.get(),
            threadAffinityEnforced = wire.getOrNull(3) != "0",
            activeBrowsers = wire.getOrNull(4)?.toIntOrNull() ?: 0,
            dataDir = wire.getOrNull(5).orEmpty(),
        )
    }

    fun isInitialized(): Boolean = ensureLoaded() && nativeIsRuntimeInitialized()

    fun createBrowser(contextHandle: Long, desktopMode: Boolean = false): Long {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        return nativeCreateBrowser(contextHandle, desktopMode)
    }

    fun loadUrl(browserHandle: Long, url: String): NativeLoadUrlResult {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        val wire = nativeLoadUrl(browserHandle, url)
        return NativeLoadUrlResult(
            errorCode = wire.getOrNull(0)?.toIntOrNull() ?: ERR_NATIVE_EXCEPTION,
            pipelineAction = wire.getOrNull(1) ?: "REJECT_UNSAFE",
            finalUrl = wire.getOrNull(2).orEmpty(),
            matchedRuleReason = wire.getOrNull(3).orEmpty(),
            upstreamContentRendered = wire.getOrNull(4) == "1",
        )
    }

    fun reload(browserHandle: Long): Boolean =
        ensureLoaded() && nativeReload(browserHandle) == NATIVE_OK

    fun stopLoading(browserHandle: Long): Boolean =
        ensureLoaded() && nativeStopLoading(browserHandle) == NATIVE_OK

    fun goBack(browserHandle: Long): Boolean =
        ensureLoaded() && nativeGoBack(browserHandle)

    fun goForward(browserHandle: Long): Boolean =
        ensureLoaded() && nativeGoForward(browserHandle)

    fun canGoBack(browserHandle: Long): Boolean =
        ensureLoaded() && nativeCanGoBack(browserHandle)

    fun canGoForward(browserHandle: Long): Boolean =
        ensureLoaded() && nativeCanGoForward(browserHandle)

    fun getCurrentUrl(browserHandle: Long): String =
        if (ensureLoaded()) nativeGetCurrentUrl(browserHandle) else ""

    fun getPageTitle(browserHandle: Long): String =
        if (ensureLoaded()) nativeGetPageTitle(browserHandle) else ""

    fun getLoadProgress(browserHandle: Long): Int =
        if (ensureLoaded()) nativeGetLoadProgress(browserHandle) else 0

    fun attachSurface(
        browserHandle: Long,
        surface: Any?,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
    ): Int {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        return nativeAttachSurface(browserHandle, surface, widthPx, heightPx, densityDpi)
    }

    fun resizeSurface(browserHandle: Long, widthPx: Int, heightPx: Int): Int {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        return nativeResizeSurface(browserHandle, widthPx, heightPx)
    }

    fun detachSurface(browserHandle: Long): Int {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        return nativeDetachSurface(browserHandle)
    }

    fun getSurfaceSnapshot(browserHandle: Long): NativeSurfaceSnapshot {
        if (!ensureLoaded()) return NativeSurfaceSnapshot(false, 0, 0, 0, 0L)
        val raw = nativeGetSurfaceState(browserHandle)
        return NativeSurfaceSnapshot(
            attached = raw.getOrElse(0) { 0L } == 1L,
            widthPx = raw.getOrElse(1) { 0L }.toInt(),
            heightPx = raw.getOrElse(2) { 0L }.toInt(),
            densityDpi = raw.getOrElse(3) { 0L }.toInt(),
            attachGeneration = raw.getOrElse(4) { 0L },
        )
    }

    fun destroyBrowser(browserHandle: Long): Boolean =
        ensureLoaded() && nativeDestroyBrowser(browserHandle) == NATIVE_OK

    fun getActiveBrowserCount(): Int =
        if (ensureLoaded()) nativeGetActiveBrowserCount() else 0

    fun shutdown(): Boolean =
        ensureLoaded() && nativeShutdownRuntime() == NATIVE_OK

    // ========================================================================
    // API Privacy Pipeline & BrowserContext hiện hữu (tương thích ngược)
    // ========================================================================

    fun evaluateRequest(
        requestUrl: String,
        initiatorUrl: String,
        resourceType: String = "other",
        httpsUpgrade: Boolean = true,
        stripTrackingParams: Boolean = true,
        adblockEnabled: Boolean = true,
        recordStats: Boolean = true,
    ): NativeDecision {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        val raw = nativeEvaluateUrlRequest(
            requestUrl,
            initiatorUrl,
            resourceType,
            httpsUpgrade,
            stripTrackingParams,
            adblockEnabled,
            recordStats,
        )
        return NativeDecision(
            action = raw.getOrElse(0) { "ALLOW" },
            mutatedUrl = raw.getOrElse(1) { requestUrl },
            matchedRuleReason = raw.getOrElse(2) { "" },
        )
    }

    fun getStats(): NativePipelineStats {
        check(ensureLoaded()) { "Native library lib$LIBRARY_NAME.so is not loaded." }
        val raw = nativeGetPipelineStats()
        return NativePipelineStats(
            totalEvaluated = raw.getOrElse(0) { 0L },
            totalBlocked = raw.getOrElse(1) { 0L },
            totalUpgradedHttps = raw.getOrElse(2) { 0L },
            totalParamsStripped = raw.getOrElse(3) { 0L },
        )
    }

    external fun nativeGetPinnedChromiumVersion(): String
    external fun nativeGetPinnedChromiumCommit(): String
    external fun nativeCreateBrowserContext(offTheRecord: Boolean, storagePath: String): Long
    external fun nativeDestroyBrowserContext(contextHandle: Long): Boolean
    external fun nativeIsOffTheRecord(contextHandle: Long): Boolean
    external fun nativeSetCookie(contextHandle: Long, origin: String, name: String, value: String): Boolean
    external fun nativeGetCookie(contextHandle: Long, origin: String, name: String): String
    external fun nativeActiveContextCount(): Int
    external fun nativeLoadFilterRules(rawFilterList: String): Boolean
    external fun nativeGetFilterRuleCount(): Long
    external fun nativeEvaluateUrlRequest(
        requestUrl: String,
        initiatorUrl: String,
        resourceType: String,
        httpsUpgrade: Boolean,
        stripTrackingParams: Boolean,
        adblockEnabled: Boolean,
        recordStats: Boolean,
    ): Array<String>
    external fun nativeGetPipelineStats(): LongArray
    external fun nativeResetPipelineStats()

    // Phase 4 native external declarations
    external fun nativeInitializeRuntime(appDataDir: String, enforceUiThread: Boolean): Array<String>
    external fun nativeIsRuntimeInitialized(): Boolean
    external fun nativeIsUpstreamContentLinked(): Boolean
    external fun nativeCreateBrowser(contextHandle: Long, desktopMode: Boolean): Long
    external fun nativeLoadUrl(browserHandle: Long, rawUrl: String): Array<String>
    external fun nativeReload(browserHandle: Long): Int
    external fun nativeStopLoading(browserHandle: Long): Int
    external fun nativeGoBack(browserHandle: Long): Boolean
    external fun nativeGoForward(browserHandle: Long): Boolean
    external fun nativeCanGoBack(browserHandle: Long): Boolean
    external fun nativeCanGoForward(browserHandle: Long): Boolean
    external fun nativeGetCurrentUrl(browserHandle: Long): String
    external fun nativeGetPageTitle(browserHandle: Long): String
    external fun nativeGetLoadProgress(browserHandle: Long): Int
    external fun nativeAttachSurface(
        browserHandle: Long,
        surface: Any?,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
    ): Int
    external fun nativeResizeSurface(browserHandle: Long, widthPx: Int, heightPx: Int): Int
    external fun nativeDetachSurface(browserHandle: Long): Int
    external fun nativeGetSurfaceState(browserHandle: Long): LongArray
    external fun nativeDestroyBrowser(browserHandle: Long): Int
    external fun nativeGetActiveBrowserCount(): Int
    external fun nativeShutdownRuntime(): Int
}
