package io.veko.browser.privacy

import io.veko.browser.chromium.nativebridge.NativeFilterStatsSnapshot
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.chromium.nativebridge.NativeUrlDecision
import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.data.repository.CookieControlMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrivacyPolicyConfig(
    val adBlockEnabled: Boolean = true,
    val trackerBlockEnabled: Boolean = true,
    val stripTrackingParamsEnabled: Boolean = true,
    val httpsUpgradeEnabled: Boolean = true,
    val blockMixedContent: Boolean = true,
    val cookieControlMode: CookieControlMode = CookieControlMode.BLOCK_THIRD_PARTY
)

data class PrivacyShieldTelemetry(
    val totalRequestsChecked: Long = 0L,
    val adsBlocked: Long = 0L,
    val trackersBlocked: Long = 0L,
    val paramsStripped: Long = 0L,
    val httpsUpgrades: Long = 0L,
    val mixedContentBlocked: Long = 0L,
    val activeFilterRules: Long = 0L,
    val recentBlockedItems: List<String> = emptyList()
) {
    val totalThreatsMitigated: Long
        get() = adsBlocked + trackersBlocked + paramsStripped + mixedContentBlocked
}

/**
 * Điều phối lá chắn quyền riêng tư của Veko Browser:
 * - Gọi trực tiếp đường ống gốc C++20/Rust (`VekoUrlRequestPipeline` -> `brave/adblock-rust` 0.13.4).
 * - Không bao giờ lưu nhật ký tên miền bị chặn của phiên riêng tư (Off-The-Record) ra bộ nhớ trạng thái.
 */
class PrivacyShieldEngine(
    val filterListManager: FilterListManager = FilterListManager()
) {

    private val recentBlockedLock = Any()
    private val recentBlockedRing = ArrayDeque<String>(MAX_RECENT_BLOCKED)

    private val _telemetry = MutableStateFlow(PrivacyShieldTelemetry())
    val telemetry: StateFlow<PrivacyShieldTelemetry> = _telemetry.asStateFlow()

    init {
        refreshTelemetry()
    }

    fun evaluateRequest(
        requestUrl: String,
        topFrameUrl: String = "",
        resourceType: String = "document",
        httpMethod: String = "GET",
        config: PrivacyPolicyConfig = PrivacyPolicyConfig(),
        isOffTheRecord: Boolean = false
    ): NativeUrlDecision {
        val decision = VekoChromiumNative.evaluateUrlRequest(
            requestUrl = requestUrl,
            topFrameUrl = topFrameUrl,
            resourceType = resourceType,
            httpMethod = httpMethod,
            blockAds = config.adBlockEnabled,
            blockTrackers = config.trackerBlockEnabled,
            stripQueryParams = config.stripTrackingParamsEnabled,
            httpsUpgrade = config.httpsUpgradeEnabled,
            blockMixedContent = config.blockMixedContent,
            // Phiên Off-The-Record không để lại dấu vết thống kê tên miền truy cập
            recordStats = !isOffTheRecord
        )

        if (!isOffTheRecord) {
            if (decision.isBlocked) {
                recordRecentBlocked(requestUrl, decision.action)
            }
            refreshTelemetry()
        }
        return decision
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
            totalRequestsChecked = nativeStats.totalRequestsChecked,
            adsBlocked = nativeStats.adsBlocked,
            trackersBlocked = nativeStats.trackersBlocked,
            paramsStripped = nativeStats.paramsStripped,
            httpsUpgrades = nativeStats.httpsUpgrades,
            mixedContentBlocked = nativeStats.mixedContentBlocked,
            activeFilterRules = nativeStats.activeFilterRules,
            recentBlockedItems = recentSnapshot
        ).also { _telemetry.value = it }
    }

    fun resetTelemetry() {
        synchronized(recentBlockedLock) {
            recentBlockedRing.clear()
        }
        VekoChromiumNative.resetFilterStats()
        refreshTelemetry()
    }

    private fun recordRecentBlocked(rawUrl: String, action: NativePipelineAction) {
        val host = extractHostLabel(rawUrl)
        if (host.isBlank()) return
        val tag = when (action) {
            NativePipelineAction.BLOCK_AD -> "Ad"
            NativePipelineAction.BLOCK_TRACKER -> "Tracker"
            NativePipelineAction.BLOCK_MIXED_CONTENT -> "Mixed HTTP"
            NativePipelineAction.BLOCK_UNSAFE_SCHEME -> "Unsafe URI"
            else -> "Blocked"
        }
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
        private const val MAX_RECENT_BLOCKED = 12
    }
}
