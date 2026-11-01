package io.veko.browser.chromium.nativebridge

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class NativePipelineAction(val code: Int) {
    ALLOW(0),
    BLOCK_AD(1),
    BLOCK_TRACKER(2),
    REWRITE_CLEAN_URL(3),
    UPGRADE_TO_HTTPS(4),
    BLOCK_MIXED_CONTENT(5),
    BLOCK_UNSAFE_SCHEME(6);

    companion object {
        fun fromCode(code: Int): NativePipelineAction =
            entries.firstOrNull { it.code == code } ?: ALLOW
    }
}

data class NativeUrlDecision(
    val action: NativePipelineAction,
    val chromiumNetError: Int,
    val effectiveUrl: String,
) {
    val isBlocked: Boolean
        get() = action == NativePipelineAction.BLOCK_AD ||
            action == NativePipelineAction.BLOCK_TRACKER ||
            action == NativePipelineAction.BLOCK_MIXED_CONTENT ||
            action == NativePipelineAction.BLOCK_UNSAFE_SCHEME
}

data class NativeFilterStatsSnapshot(
    val totalRequestsChecked: Long,
    val adsBlocked: Long,
    val trackersBlocked: Long,
    val paramsStripped: Long,
    val httpsUpgrades: Long,
    val mixedContentBlocked: Long,
    val activeFilterRules: Long,
) {
    val totalBlocked: Long
        get() = adsBlocked + trackersBlocked + mixedContentBlocked
}

/**
 * Cầu nối JNI kiểu mạnh (strongly-typed) tới thư viện C++20 `libveko_chromium_native.so` (GN + Ninja),
 * nhân lọc Rust `brave/adblock-rust` 0.13.4, và `libveko_content_embedder.so` khi SDK Chromium `156.0.8078.85` được liên kết.
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
        val pipelineAction: NativePipelineAction,
        val chromiumNetError: Int,
        val finalUrl: String,
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

    fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        synchronized(this) {
            if (loaded.get()) return true
            val isAndroid = System.getProperty("java.vm.name")
                ?.contains("Dalvik", ignoreCase = true) == true ||
                System.getProperty("java.specification.vendor")
                    ?.contains("Android", ignoreCase = true) == true

            if (!isAndroid) {
                val explicitPath = System.getProperty("veko.native.lib.path")
                val explicitDir = System.getProperty("veko.native.lib.dir")
                val candidates = buildList {
                    if (!explicitPath.isNullOrBlank()) {
                        add(File(explicitPath))
                    }
                    if (!explicitDir.isNullOrBlank()) {
                        add(File(explicitDir, "libveko_chromium_native.so"))
                    }
                    add(File("out/host_x64/libveko_chromium_native.so"))
                    add(File("../out/host_x64/libveko_chromium_native.so"))
                }
                val hostSo = candidates.firstOrNull { it.isFile }
                if (hostSo != null) {
                    System.load(hostSo.absolutePath)
                    loaded.set(true)
                    tryLoadContentEmbedderAdjacent(hostSo.parentFile)
                    return true
                }
            }

            System.loadLibrary(LIBRARY_NAME)
            loaded.set(true)
            runCatching {
                System.loadLibrary(CONTENT_EMBEDDER_LIBRARY_NAME)
                contentEmbedderLoaded.set(true)
            }
            return true
        }
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
    // Nhóm 1: Metadata, BrowserContext, Cookie Partition & Privacy Pipeline
    // ========================================================================

    fun getEngineMetadataJson(): String {
        ensureLoaded()
        return nativeGetEngineMetadata()
    }

    fun isUpstreamContentLinked(): Boolean {
        ensureLoaded()
        return nativeIsUpstreamContentLinked() || contentEmbedderLoaded.get()
    }

    fun createBrowserContext(isOffTheRecord: Boolean, diskStoragePath: String): Long {
        ensureLoaded()
        return nativeCreateBrowserContext(isOffTheRecord, diskStoragePath)
    }

    fun isOffTheRecordContext(contextHandle: Long): Boolean {
        ensureLoaded()
        return nativeIsOffTheRecordContext(contextHandle)
    }

    fun getContextStoragePath(contextHandle: Long): String {
        ensureLoaded()
        return nativeGetContextStoragePath(contextHandle)
    }

    fun setCookie(
        contextHandle: Long,
        requestUrl: String,
        topFrameUrl: String,
        cookieKv: String,
        cookieModeOrdinal: Int,
    ): Boolean {
        ensureLoaded()
        return nativeSetCookie(contextHandle, requestUrl, topFrameUrl, cookieKv, cookieModeOrdinal)
    }

    fun getCookies(
        contextHandle: Long,
        requestUrl: String,
        topFrameUrl: String,
        cookieModeOrdinal: Int,
    ): String {
        ensureLoaded()
        return nativeGetCookies(contextHandle, requestUrl, topFrameUrl, cookieModeOrdinal)
    }

    fun clearStoragePartition(contextHandle: Long): Boolean {
        ensureLoaded()
        return nativeClearStoragePartition(contextHandle)
    }

    fun destroyBrowserContext(contextHandle: Long): Boolean {
        ensureLoaded()
        return nativeDestroyBrowserContext(contextHandle)
    }

    fun getActiveOffTheRecordContextCount(): Int {
        ensureLoaded()
        return nativeGetActiveOffTheRecordContextCount()
    }

    fun reloadFilterRules(customRulesUtf8: String, includeBuiltin: Boolean = true): Boolean {
        ensureLoaded()
        return nativeReloadFilterRules(customRulesUtf8, includeBuiltin)
    }

    fun evaluateUrlRequest(
        requestUrl: String,
        topFrameUrl: String,
        resourceType: String,
        httpMethod: String = "GET",
        blockAds: Boolean = true,
        blockTrackers: Boolean = true,
        stripQueryParams: Boolean = true,
        httpsUpgrade: Boolean = true,
        blockMixedContent: Boolean = true,
        recordStats: Boolean = true,
    ): NativeUrlDecision {
        ensureLoaded()
        val raw = nativeEvaluateUrlRequest(
            requestUrl = requestUrl,
            topFrameUrl = topFrameUrl,
            resourceType = resourceType,
            httpMethod = httpMethod,
            blockAds = blockAds,
            blockTrackers = blockTrackers,
            stripQueryParams = stripQueryParams,
            httpsUpgrade = httpsUpgrade,
            blockMixedContent = blockMixedContent,
            recordStats = recordStats,
        )
        val actionCode = raw.getOrNull(0)?.toIntOrNull() ?: 0
        val netError = raw.getOrNull(1)?.toIntOrNull() ?: 0
        val effectiveUrl = raw.getOrNull(2).orEmpty()
        return NativeUrlDecision(
            action = NativePipelineAction.fromCode(actionCode),
            chromiumNetError = netError,
            effectiveUrl = effectiveUrl,
        )
    }

    fun evaluateTlsCertificateError(netError: Int, host: String): Boolean {
        ensureLoaded()
        return nativeEvaluateTlsCertificateError(netError, host)
    }

    fun getFilterStats(): NativeFilterStatsSnapshot {
        ensureLoaded()
        val values = nativeGetFilterStats()
        return NativeFilterStatsSnapshot(
            totalRequestsChecked = values.getOrElse(0) { 0L },
            adsBlocked = values.getOrElse(1) { 0L },
            trackersBlocked = values.getOrElse(2) { 0L },
            paramsStripped = values.getOrElse(3) { 0L },
            httpsUpgrades = values.getOrElse(4) { 0L },
            mixedContentBlocked = values.getOrElse(5) { 0L },
            activeFilterRules = values.getOrElse(6) { 0L },
        )
    }

    fun resetFilterStats() {
        ensureLoaded()
        nativeResetFilterStats()
    }

    // ========================================================================
    // Nhóm 2 (Phase 4): Runtime Lifecycle, Browser Session, Navigation & Surface
    // ========================================================================

    fun initialize(appDataDir: String, enforceUiThread: Boolean = true): NativeRuntimeStatus {
        ensureLoaded()
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

    fun isInitialized(): Boolean {
        ensureLoaded()
        return nativeIsRuntimeInitialized()
    }

    fun createBrowser(contextHandle: Long, desktopMode: Boolean = false): Long {
        ensureLoaded()
        return nativeCreateBrowser(contextHandle, desktopMode)
    }

    fun loadUrl(browserHandle: Long, url: String): NativeLoadUrlResult {
        ensureLoaded()
        val wire = nativeLoadUrl(browserHandle, url)
        val actionCode = wire.getOrNull(1)?.toIntOrNull() ?: 0
        return NativeLoadUrlResult(
            errorCode = wire.getOrNull(0)?.toIntOrNull() ?: ERR_NATIVE_EXCEPTION,
            pipelineAction = NativePipelineAction.fromCode(actionCode),
            chromiumNetError = wire.getOrNull(2)?.toIntOrNull() ?: 0,
            finalUrl = wire.getOrNull(3).orEmpty(),
            upstreamContentRendered = wire.getOrNull(4) == "1",
        )
    }

    fun reload(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeReload(browserHandle) == NATIVE_OK
    }

    fun stopLoading(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeStopLoading(browserHandle) == NATIVE_OK
    }

    fun goBack(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeGoBack(browserHandle)
    }

    fun goForward(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeGoForward(browserHandle)
    }

    fun canGoBack(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeCanGoBack(browserHandle)
    }

    fun canGoForward(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeCanGoForward(browserHandle)
    }

    fun getCurrentUrl(browserHandle: Long): String {
        ensureLoaded()
        return nativeGetCurrentUrl(browserHandle)
    }

    fun getPageTitle(browserHandle: Long): String {
        ensureLoaded()
        return nativeGetPageTitle(browserHandle)
    }

    fun getLoadProgress(browserHandle: Long): Int {
        ensureLoaded()
        return nativeGetLoadProgress(browserHandle)
    }

    fun setDesktopMode(browserHandle: Long, desktopMode: Boolean): Boolean {
        ensureLoaded()
        return nativeSetDesktopMode(browserHandle, desktopMode) == NATIVE_OK
    }

    fun isDesktopMode(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeIsDesktopMode(browserHandle)
    }

    fun attachSurface(
        browserHandle: Long,
        surface: Any?,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
    ): Int {
        ensureLoaded()
        return nativeAttachSurface(browserHandle, surface, widthPx, heightPx, densityDpi)
    }

    fun resizeSurface(browserHandle: Long, widthPx: Int, heightPx: Int): Int {
        ensureLoaded()
        return nativeResizeSurface(browserHandle, widthPx, heightPx)
    }

    fun detachSurface(browserHandle: Long): Int {
        ensureLoaded()
        return nativeDetachSurface(browserHandle)
    }

    fun getSurfaceSnapshot(browserHandle: Long): NativeSurfaceSnapshot {
        ensureLoaded()
        val raw = nativeGetSurfaceState(browserHandle)
        return NativeSurfaceSnapshot(
            attached = raw.getOrElse(0) { 0L } == 1L,
            widthPx = raw.getOrElse(1) { 0L }.toInt(),
            heightPx = raw.getOrElse(2) { 0L }.toInt(),
            densityDpi = raw.getOrElse(3) { 0L }.toInt(),
            attachGeneration = raw.getOrElse(4) { 0L },
        )
    }

    fun destroyBrowser(browserHandle: Long): Boolean {
        ensureLoaded()
        return nativeDestroyBrowser(browserHandle) == NATIVE_OK
    }

    fun getActiveBrowserCount(): Int {
        ensureLoaded()
        return nativeGetActiveBrowserCount()
    }

    fun shutdown(): Boolean {
        ensureLoaded()
        return nativeShutdownRuntime() == NATIVE_OK
    }

    // ========================================================================
    // Khai báo JNI external (@JvmStatic khớp jclass trong C++)
    // ========================================================================

    @JvmStatic
    private external fun nativeGetEngineMetadata(): String

    @JvmStatic
    private external fun nativeIsUpstreamContentLinked(): Boolean

    @JvmStatic
    private external fun nativeCreateBrowserContext(isOffTheRecord: Boolean, diskStoragePath: String): Long

    @JvmStatic
    private external fun nativeIsOffTheRecordContext(contextHandle: Long): Boolean

    @JvmStatic
    private external fun nativeGetContextStoragePath(contextHandle: Long): String

    @JvmStatic
    private external fun nativeSetCookie(
        contextHandle: Long,
        requestUrl: String,
        topFrameUrl: String,
        cookieKv: String,
        cookieModeOrdinal: Int,
    ): Boolean

    @JvmStatic
    private external fun nativeGetCookies(
        contextHandle: Long,
        requestUrl: String,
        topFrameUrl: String,
        cookieModeOrdinal: Int,
    ): String

    @JvmStatic
    private external fun nativeClearStoragePartition(contextHandle: Long): Boolean

    @JvmStatic
    private external fun nativeDestroyBrowserContext(contextHandle: Long): Boolean

    @JvmStatic
    private external fun nativeGetActiveOffTheRecordContextCount(): Int

    @JvmStatic
    private external fun nativeReloadFilterRules(customRulesUtf8: String, includeBuiltin: Boolean): Boolean

    @JvmStatic
    private external fun nativeEvaluateUrlRequest(
        requestUrl: String,
        topFrameUrl: String,
        resourceType: String,
        httpMethod: String,
        blockAds: Boolean,
        blockTrackers: Boolean,
        stripQueryParams: Boolean,
        httpsUpgrade: Boolean,
        blockMixedContent: Boolean,
        recordStats: Boolean,
    ): Array<String>

    @JvmStatic
    private external fun nativeEvaluateTlsCertificateError(netError: Int, host: String): Boolean

    @JvmStatic
    private external fun nativeGetFilterStats(): LongArray

    @JvmStatic
    private external fun nativeResetFilterStats()

    @JvmStatic
    private external fun nativeInitializeRuntime(appDataDir: String, enforceUiThread: Boolean): Array<String>

    @JvmStatic
    private external fun nativeIsRuntimeInitialized(): Boolean

    @JvmStatic
    private external fun nativeCreateBrowser(contextHandle: Long, desktopMode: Boolean): Long

    @JvmStatic
    private external fun nativeLoadUrl(browserHandle: Long, rawUrl: String): Array<String>

    @JvmStatic
    private external fun nativeReload(browserHandle: Long): Int

    @JvmStatic
    private external fun nativeStopLoading(browserHandle: Long): Int

    @JvmStatic
    private external fun nativeGoBack(browserHandle: Long): Boolean

    @JvmStatic
    private external fun nativeGoForward(browserHandle: Long): Boolean

    @JvmStatic
    private external fun nativeCanGoBack(browserHandle: Long): Boolean

    @JvmStatic
    private external fun nativeCanGoForward(browserHandle: Long): Boolean

    @JvmStatic
    private external fun nativeGetCurrentUrl(browserHandle: Long): String

    @JvmStatic
    private external fun nativeGetPageTitle(browserHandle: Long): String

    @JvmStatic
    private external fun nativeGetLoadProgress(browserHandle: Long): Int

    @JvmStatic
    private external fun nativeSetDesktopMode(browserHandle: Long, desktopMode: Boolean): Int

    @JvmStatic
    private external fun nativeIsDesktopMode(browserHandle: Long): Boolean

    @JvmStatic
    private external fun nativeAttachSurface(
        browserHandle: Long,
        surface: Any?,
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
    ): Int

    @JvmStatic
    private external fun nativeResizeSurface(browserHandle: Long, widthPx: Int, heightPx: Int): Int

    @JvmStatic
    private external fun nativeDetachSurface(browserHandle: Long): Int

    @JvmStatic
    private external fun nativeGetSurfaceState(browserHandle: Long): LongArray

    @JvmStatic
    private external fun nativeDestroyBrowser(browserHandle: Long): Int

    @JvmStatic
    private external fun nativeGetActiveBrowserCount(): Int

    @JvmStatic
    private external fun nativeShutdownRuntime(): Int
}
