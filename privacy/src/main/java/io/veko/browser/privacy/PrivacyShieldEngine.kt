package io.veko.browser.privacy

import io.veko.browser.chromium.nativebridge.NativeFilterStatsSnapshot
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.chromium.nativebridge.NativeUrlDecision
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.VekoSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

data class PrivacyPolicyConfig(
    val adBlockEnabled: Boolean = true,
    val trackerBlockEnabled: Boolean = true,
    val strictAntiTracking: Boolean = true,
    val stripTrackingParamsEnabled: Boolean = true,
    val httpsUpgradeEnabled: Boolean = true,
    val blockMixedContent: Boolean = true,
    val cookieControlMode: CookieControlMode = CookieControlMode.BLOCK_THIRD_PARTY,
    val trustedHosts: Set<String> = emptySet(),
)

data class PrivacyShieldTelemetry(
    val totalRequestsChecked: Long = 0L,
    val adsBlocked: Long = 0L,
    val trackersBlocked: Long = 0L,
    val fingerprintersBlocked: Long = 0L,
    val engineSubresourcesBlocked: Long = 0L,
    val cosmeticInjections: Long = 0L,
    val paramsStripped: Long = 0L,
    val httpsUpgrades: Long = 0L,
    val mixedContentBlocked: Long = 0L,
    val activeFilterRules: Long = 0L,
    val recentBlockedItems: List<String> = emptyList(),
) {
    val totalThreatsMitigated: Long
        get() = adsBlocked + trackersBlocked + fingerprintersBlocked +
            engineSubresourcesBlocked + paramsStripped + mixedContentBlocked
}

/**
 * Điều phối lá chắn quyền riêng tư đa tầng của Veko Browser ("Người bảo vệ người dùng giữa Tracking & Quảng cáo"):
 * - Gọi trực tiếp đường ống gốc C++20/Rust (`VekoUrlRequestPipeline` -> `brave/adblock-rust` 0.13.4).
 * - Hợp nhất sự kiện chặn tài nguyên con (Fingerprinting, Cryptomining, Social Pixel, Ad/Tracker) từ Engine.
 * - Hỗ trợ bật/tắt Lá chắn theo từng tên miền trang web (Per-Site Shield Toggle).
 * - Tuyệt đối không lưu nhật ký tên miền bị chặn hay bộ đếm của phiên riêng tư (Off-The-Record).
 */
class PrivacyShieldEngine(
    val filterListManager: FilterListManager = FilterListManager(),
) {

    private val recentBlockedLock = Any()
    private val recentBlockedRing = ArrayDeque<String>(MAX_RECENT_BLOCKED)

    private val otrTrustedHostsLock = Any()
    private val otrTrustedHosts = LinkedHashSet<String>()

    private val extraAdsBlocked = AtomicLong(0L)
    private val extraTrackersBlocked = AtomicLong(0L)
    private val fingerprintersBlocked = AtomicLong(0L)
    private val engineSubresourcesBlocked = AtomicLong(0L)
    private val cosmeticInjections = AtomicLong(0L)

    private val _telemetry = MutableStateFlow(PrivacyShieldTelemetry())
    val telemetry: StateFlow<PrivacyShieldTelemetry> = _telemetry.asStateFlow()

    init {
        refreshTelemetry()
    }

    fun isSiteShieldPaused(
        pageUrlOrHost: String,
        persistedTrustedHosts: Set<String>,
        isOffTheRecord: Boolean = false,
    ): Boolean {
        val host = VekoSettings.normalizeHost(pageUrlOrHost)
        if (host.isEmpty()) return false
        if (matchesHostOrParent(host, persistedTrustedHosts)) return true
        if (isOffTheRecord) {
            synchronized(otrTrustedHostsLock) {
                if (matchesHostOrParent(host, otrTrustedHosts)) return true
            }
        }
        return false
    }

    fun setOffTheRecordHostPaused(rawHostOrUrl: String, paused: Boolean) {
        val host = VekoSettings.normalizeHost(rawHostOrUrl)
        if (host.isEmpty()) return
        synchronized(otrTrustedHostsLock) {
            if (paused) otrTrustedHosts.add(host) else otrTrustedHosts.remove(host)
        }
    }

    fun clearOffTheRecordTrustedHosts() {
        synchronized(otrTrustedHostsLock) {
            otrTrustedHosts.clear()
        }
    }

    fun evaluateRequest(
        requestUrl: String,
        topFrameUrl: String = "",
        resourceType: String = "document",
        httpMethod: String = "GET",
        config: PrivacyPolicyConfig = PrivacyPolicyConfig(),
        isOffTheRecord: Boolean = false,
    ): NativeUrlDecision {
        val siteOriginToCheck = topFrameUrl.ifBlank {
            if (resourceType.equals("document", ignoreCase = true) ||
                resourceType.equals("main_frame", ignoreCase = true)
            ) {
                requestUrl
            } else {
                ""
            }
        }
        val sitePaused = siteOriginToCheck.isNotBlank() &&
            isSiteShieldPaused(siteOriginToCheck, config.trustedHosts, isOffTheRecord)

        val decision = VekoChromiumNative.evaluateUrlRequest(
            requestUrl = requestUrl,
            topFrameUrl = topFrameUrl,
            resourceType = resourceType,
            httpMethod = httpMethod,
            blockAds = config.adBlockEnabled && !sitePaused,
            blockTrackers = config.trackerBlockEnabled && !sitePaused,
            stripQueryParams = config.stripTrackingParamsEnabled && !sitePaused,
            httpsUpgrade = config.httpsUpgradeEnabled,
            blockMixedContent = config.blockMixedContent,
            // Phiên Off-The-Record không để lại dấu vết thống kê tên miền truy cập
            recordStats = !isOffTheRecord,
        )

        if (!isOffTheRecord) {
            if (decision.isBlocked) {
                recordRecentBlocked(requestUrl, decision.action)
            }
            refreshTelemetry()
        }
        return decision
    }

    fun getCosmeticCssForPage(
        pageUrl: String,
        config: PrivacyPolicyConfig = PrivacyPolicyConfig(),
        isOffTheRecord: Boolean = false,
    ): String {
        if (!config.adBlockEnabled) return ""
        if (isSiteShieldPaused(pageUrl, config.trustedHosts, isOffTheRecord)) return ""
        val css = runCatching { VekoChromiumNative.getCosmeticCssForUrl(pageUrl) }.getOrDefault("")
        if (css.isNotBlank() && !isOffTheRecord) {
            cosmeticInjections.incrementAndGet()
            refreshTelemetry()
        }
        return css
    }

    /**
     * Ghi nhận sự kiện chặn phần tử theo dõi / quảng cáo / fingerprinting từ tầng C++ của Engine (ví dụ Gecko ETP).
     * Bỏ qua hoàn toàn việc ghi nhật ký nếu thuộc phiên riêng tư (`isOffTheRecord == true`).
     */
    fun recordEngineSubresourceBlock(
        blockedUri: String,
        isAd: Boolean,
        isTracker: Boolean,
        isFingerprinterOrMiner: Boolean,
        isOffTheRecord: Boolean,
    ) {
        if (isOffTheRecord) return
        engineSubresourcesBlocked.incrementAndGet()
        when {
            isFingerprinterOrMiner -> {
                fingerprintersBlocked.incrementAndGet()
                recordRecentBlockedWithLabel(blockedUri, "Fingerprint")
            }
            isAd -> {
                extraAdsBlocked.incrementAndGet()
                recordRecentBlocked(blockedUri, NativePipelineAction.BLOCK_AD)
            }
            isTracker -> {
                extraTrackersBlocked.incrementAndGet()
                recordRecentBlocked(blockedUri, NativePipelineAction.BLOCK_TRACKER)
            }
            else -> {
                recordRecentBlockedWithLabel(blockedUri, "Shield")
            }
        }
        refreshTelemetry()
    }

    fun refreshTelemetry(): PrivacyShieldTelemetry {
        val nativeStats: NativeFilterStatsSnapshot = runCatching {
            VekoChromiumNative.getFilterStats()
        }.getOrElse {
            NativeFilterStatsSnapshot(0, 0, 0, 0, 0, 0, 0)
        }
        val recentSnapshot = synchronized(recentBlockedLock) {
            recentBlockedRing.toList()
        }
        return PrivacyShieldTelemetry(
            totalRequestsChecked = nativeStats.totalRequestsChecked + engineSubresourcesBlocked.get(),
            adsBlocked = nativeStats.adsBlocked + extraAdsBlocked.get(),
            trackersBlocked = nativeStats.trackersBlocked + extraTrackersBlocked.get(),
            fingerprintersBlocked = fingerprintersBlocked.get(),
            engineSubresourcesBlocked = engineSubresourcesBlocked.get(),
            cosmeticInjections = cosmeticInjections.get(),
            paramsStripped = nativeStats.paramsStripped,
            httpsUpgrades = nativeStats.httpsUpgrades,
            mixedContentBlocked = nativeStats.mixedContentBlocked,
            activeFilterRules = nativeStats.activeFilterRules,
            recentBlockedItems = recentSnapshot,
        ).also { _telemetry.value = it }
    }

    fun resetTelemetry() {
        synchronized(recentBlockedLock) {
            recentBlockedRing.clear()
        }
        extraAdsBlocked.set(0L)
        extraTrackersBlocked.set(0L)
        fingerprintersBlocked.set(0L)
        engineSubresourcesBlocked.set(0L)
        cosmeticInjections.set(0L)
        runCatching { VekoChromiumNative.resetFilterStats() }
        refreshTelemetry()
    }

    private fun matchesHostOrParent(host: String, candidates: Set<String>): Boolean {
        if (candidates.isEmpty()) return false
        if (host in candidates) return true
        return candidates.any { trusted -> trusted.isNotEmpty() && host.endsWith(".$trusted") }
    }

    private fun recordRecentBlocked(rawUrl: String, action: NativePipelineAction) {
        val tag = when (action) {
            NativePipelineAction.BLOCK_AD -> "Ad"
            NativePipelineAction.BLOCK_TRACKER -> "Tracker"
            NativePipelineAction.BLOCK_MIXED_CONTENT -> "Mixed HTTP"
            NativePipelineAction.BLOCK_UNSAFE_SCHEME -> "Unsafe URI"
            else -> "Blocked"
        }
        recordRecentBlockedWithLabel(rawUrl, tag)
    }

    private fun recordRecentBlockedWithLabel(rawUrl: String, tag: String) {
        val host = extractHostLabel(rawUrl)
        if (host.isBlank()) return
        val entry = "[$tag] $host"
        synchronized(recentBlockedLock) {
            recentBlockedRing.remove(entry)
            recentBlockedRing.addFirst(entry)
            while (recentBlockedRing.size > MAX_RECENT_BLOCKED) {
                recentBlockedRing.removeLast()
            }
        }
    }

    private fun extractHostLabel(rawUrl: String): String {
        val afterScheme = rawUrl.substringAfter("://", missingDelimiterValue = rawUrl)
        return afterScheme
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()
    }

    companion object {
        private const val MAX_RECENT_BLOCKED = 16
    }
}
