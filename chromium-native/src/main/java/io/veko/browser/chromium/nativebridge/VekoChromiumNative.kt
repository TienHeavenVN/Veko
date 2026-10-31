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
    val effectiveUrl: String
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
    val activeFilterRules: Long
) {
    val totalBlocked: Long
        get() = adsBlocked + trackersBlocked + mixedContentBlocked
}

/**
 * Cầu nối JNI trực tiếp xuống thư viện C++20 `libveko_chromium_native.so` (biên dịch bằng GN + Ninja)
 * và nhân lọc Rust `brave/adblock-rust` 0.13.4.
 */
object VekoChromiumNative {

    private val loaded = AtomicBoolean(false)

    fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        synchronized(this) {
            if (loaded.get()) return true
            val isAndroid = System.getProperty("java.vm.name")
                ?.contains("Dalvik", ignoreCase = true) == true ||
                System.getProperty("java.specification.vendor")
                    ?.contains("Android", ignoreCase = true) == true

            if (!isAndroid) {
                val explicitDir = System.getProperty("veko.native.lib.dir")
                val candidates = buildList {
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
                    return true
                }
            }

            System.loadLibrary("veko_chromium_native")
            loaded.set(true)
            return true
        }
    }

    fun getEngineMetadataJson(): String {
        ensureLoaded()
        return nativeGetEngineMetadata()
    }

    fun isUpstreamContentLinked(): Boolean {
        ensureLoaded()
        return nativeIsUpstreamContentLinked()
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
        cookieModeOrdinal: Int
    ): Boolean {
        ensureLoaded()
        return nativeSetCookie(contextHandle, requestUrl, topFrameUrl, cookieKv, cookieModeOrdinal)
    }

    fun getCookies(
        contextHandle: Long,
        requestUrl: String,
        topFrameUrl: String,
        cookieModeOrdinal: Int
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
        recordStats: Boolean = true
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
            recordStats = recordStats
        )
        val actionCode = raw.getOrNull(0)?.toIntOrNull() ?: 0
        val netError = raw.getOrNull(1)?.toIntOrNull() ?: 0
        val effectiveUrl = raw.getOrNull(2).orEmpty()
        return NativeUrlDecision(
            action = NativePipelineAction.fromCode(actionCode),
            chromiumNetError = netError,
            effectiveUrl = effectiveUrl
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
            activeFilterRules = values.getOrElse(6) { 0L }
        )
    }

    fun resetFilterStats() {
        ensureLoaded()
        nativeResetFilterStats()
    }

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
        cookieModeOrdinal: Int
    ): Boolean

    @JvmStatic
    private external fun nativeGetCookies(
        contextHandle: Long,
        requestUrl: String,
        topFrameUrl: String,
        cookieModeOrdinal: Int
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
        recordStats: Boolean
    ): Array<String>

    @JvmStatic
    private external fun nativeEvaluateTlsCertificateError(netError: Int, host: String): Boolean

    @JvmStatic
    private external fun nativeGetFilterStats(): LongArray

    @JvmStatic
    private external fun nativeResetFilterStats()
}
