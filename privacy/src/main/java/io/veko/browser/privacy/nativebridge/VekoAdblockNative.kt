package io.veko.browser.privacy.nativebridge

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ShieldPipelineAction(val code: Int) {
    ALLOW(0),
    BLOCK_AD(1),
    BLOCK_TRACKER(2),
    REWRITE_CLEAN_URL(3),
    UPGRADE_TO_HTTPS(4),
    BLOCK_MIXED_CONTENT(5),
    BLOCK_UNSAFE_SCHEME(6);

    companion object {
        fun fromCode(code: Int): ShieldPipelineAction =
            entries.firstOrNull { it.code == code } ?: ALLOW
    }
}

data class ShieldUrlDecision(
    val action: ShieldPipelineAction,
    val reasonCode: Int,
    val effectiveUrl: String,
) {
    val isBlocked: Boolean
        get() = action == ShieldPipelineAction.BLOCK_AD ||
            action == ShieldPipelineAction.BLOCK_TRACKER ||
            action == ShieldPipelineAction.BLOCK_MIXED_CONTENT ||
            action == ShieldPipelineAction.BLOCK_UNSAFE_SCHEME
}

data class ShieldFilterStatsSnapshot(
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
 * Cầu nối JNI độc lập với browser engine kết nối trực tiếp tới thư viện gốc `libveko_adblock_jni.so`
 * và nhân lọc Rust `brave/adblock-rust` 0.13.4 của module `:privacy`.
 */
object VekoAdblockNative {
    const val LIBRARY_NAME = "veko_adblock_jni"
    private const val HOST_SO_FILENAME = "libveko_adblock_jni.so"

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
                val explicitPath = System.getProperty("veko.native.lib.path")
                val explicitDir = System.getProperty("veko.native.lib.dir")
                val candidates = buildList {
                    if (!explicitPath.isNullOrBlank()) {
                        add(File(explicitPath))
                    }
                    if (!explicitDir.isNullOrBlank()) {
                        add(File(explicitDir, HOST_SO_FILENAME))
                    }
                    add(File("out/host_x64/$HOST_SO_FILENAME"))
                    add(File("../out/host_x64/$HOST_SO_FILENAME"))
                }
                val hostSo = candidates.firstOrNull { it.isFile }
                if (hostSo != null) {
                    System.load(hostSo.absolutePath)
                    loaded.set(true)
                    return true
                }
            }

            System.loadLibrary(LIBRARY_NAME)
            loaded.set(true)
            return true
        }
    }

    val isNativeLibraryLoaded: Boolean
        get() = runCatching { ensureLoaded() }.getOrDefault(false)

    fun isLibraryLoaded(): Boolean = loaded.get()

    fun getShieldMetadataJson(): String {
        ensureLoaded()
        return nativeGetShieldMetadata()
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
    ): ShieldUrlDecision {
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
        val reasonCode = raw.getOrNull(1)?.toIntOrNull() ?: 0
        val effectiveUrl = raw.getOrNull(2).orEmpty()
        return ShieldUrlDecision(
            action = ShieldPipelineAction.fromCode(actionCode),
            reasonCode = reasonCode,
            effectiveUrl = effectiveUrl,
        )
    }

    fun evaluateTlsCertificateError(errorCode: Int, host: String): Boolean {
        ensureLoaded()
        return nativeEvaluateTlsCertificateError(errorCode, host)
    }

    fun getFilterStats(): ShieldFilterStatsSnapshot {
        ensureLoaded()
        val values = nativeGetFilterStats()
        return ShieldFilterStatsSnapshot(
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

    fun getCosmeticCssForUrl(pageUrl: String): String {
        if (pageUrl.isBlank()) return ""
        ensureLoaded()
        return nativeGetCosmeticCssForUrl(pageUrl)
    }

    fun releaseNativeResources(): Boolean {
        if (!loaded.get()) return false
        return nativeReleaseResources()
    }

    @JvmStatic
    private external fun nativeGetShieldMetadata(): String

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
    private external fun nativeEvaluateTlsCertificateError(errorCode: Int, host: String): Boolean

    @JvmStatic
    private external fun nativeGetFilterStats(): LongArray

    @JvmStatic
    private external fun nativeResetFilterStats()

    @JvmStatic
    private external fun nativeGetCosmeticCssForUrl(pageUrl: String): String

    @JvmStatic
    private external fun nativeReleaseResources(): Boolean
}
