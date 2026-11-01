package io.veko.browser.engine

import androidx.compose.runtime.Immutable

/**
 * Bản mô tả năng lực kỹ thuật của Mozilla GeckoView trong Veko Browser.
 * Giúp tầng điều phối (`TabManager`, `BrowserMviStore`) và giao diện Compose truy vấn năng lực
 * mà không phụ thuộc trực tiếp vào lớp cụ thể `org.mozilla.geckoview.*`.
 */
@Immutable
data class EngineCapabilities(
    val engineType: EngineType = EngineType.GECKO,
    val supportsPrivateBrowsingIsolation: Boolean,
    val supportsSessionStateSerialization: Boolean,
    val supportsDesktopViewportToggle: Boolean,
    val supportsFindInPage: Boolean,
    val supportsPageZoom: Boolean,
    val supportsPageJavaScriptExecution: Boolean,
    val supportsPolicyGatedHostJsEvaluation: Boolean,
    val supportsSitePermissionPrompts: Boolean,
    val supportsSecureDownloads: Boolean,
    val supportsCookiePolicyIsolation: Boolean,
    val supportsMultiProcessCrashRecovery: Boolean,
    val primaryTargetAbi: String = PRIMARY_TARGET_ABI,
    val supportedAbis: Set<String> = DEFAULT_SUPPORTED_ABIS,
) {
    companion object {
        const val PRIMARY_TARGET_ABI = "arm64-v8a"
        val DEFAULT_SUPPORTED_ABIS: Set<String> = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

        fun defaultForGecko(): EngineCapabilities = EngineCapabilities(
            engineType = EngineType.GECKO,
            supportsPrivateBrowsingIsolation = true,
            supportsSessionStateSerialization = true,
            supportsDesktopViewportToggle = true,
            supportsFindInPage = true,
            supportsPageZoom = true,
            supportsPageJavaScriptExecution = true,
            supportsPolicyGatedHostJsEvaluation = false,
            supportsSitePermissionPrompts = true,
            supportsSecureDownloads = true,
            supportsCookiePolicyIsolation = true,
            supportsMultiProcessCrashRecovery = true,
        )
    }
}
