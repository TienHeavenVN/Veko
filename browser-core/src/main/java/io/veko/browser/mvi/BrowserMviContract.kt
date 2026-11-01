package io.veko.browser.mvi

import androidx.compose.runtime.Immutable
import io.veko.browser.chromium.embedder.ChromiumEmbedderReadiness
import io.veko.browser.data.repository.AppLanguage
import io.veko.browser.data.repository.AutoplayPolicy
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.engine.EngineAvailability
import io.veko.browser.engine.EngineType
import io.veko.browser.privacy.FilterSubscriptionInfo
import io.veko.browser.privacy.PrivacyShieldTelemetry
import io.veko.browser.tab.BrowserTabsUiState

/**
 * Trạng thái bất biến (Immutable MVI State) hợp nhất toàn bộ trạng thái Trình duyệt,
 * Lá chắn Quyền riêng tư C++20/Rust, Cài đặt người dùng và chẩn đoán Chromium Embedder.
 */
@Immutable
data class BrowserMviState(
    val tabsState: BrowserTabsUiState = BrowserTabsUiState(),
    val userSettings: VekoSettings = VekoSettings(),
    val privacyTelemetry: PrivacyShieldTelemetry = PrivacyShieldTelemetry(),
    val filterSubscriptions: List<FilterSubscriptionInfo> = emptyList(),
    val customFilterRules: String = "",
    val embedderReadiness: ChromiumEmbedderReadiness? = null,
    val engineAvailabilities: Map<EngineType, EngineAvailability> = emptyMap(),
    val initializedEngines: Set<EngineType> = emptySet(),
    val isPrivacySheetOpen: Boolean = false,
)

/**
 * Mọi hành động (Action / Intent) một chiều trong kiến trúc MVI / UDF của Veko Browser.
 */
sealed interface BrowserMviAction {

    sealed interface UserIntent : BrowserMviAction {
        data class OpenNewTab(
            val url: String = "about:blank",
            val isPrivate: Boolean = false,
            val engineType: EngineType? = null,
        ) : UserIntent
        data class OpenCurrentPageInEngine(val engineType: EngineType) : UserIntent
        data class SelectPreferredEngine(val engineType: EngineType) : UserIntent
        data class CloseTab(val tabId: String) : UserIntent
        data object CloseAllPrivateTabs : UserIntent
        data class SelectTab(val tabId: String) : UserIntent
        data class SubmitOmnibox(val rawInput: String) : UserIntent
        data object NavigateBack : UserIntent
        data object NavigateForward : UserIntent
        data object ReloadOrStopPage : UserIntent
        data object ToggleDesktopMode : UserIntent
        data class SetPrivacySheetVisible(val visible: Boolean) : UserIntent
        data class SetAdBlockEnabled(val enabled: Boolean) : UserIntent
        data class SetTrackerBlockEnabled(val enabled: Boolean) : UserIntent
        data class SetStrictAntiTracking(val enabled: Boolean) : UserIntent
        data class SetStripTrackingParamsEnabled(val enabled: Boolean) : UserIntent
        data class SetHttpsUpgradeEnabled(val enabled: Boolean) : UserIntent
        data class SetBlockMixedContent(val enabled: Boolean) : UserIntent
        data class SetCookieControlMode(val mode: CookieControlMode) : UserIntent
        data class SetSaveSearchHistory(val enabled: Boolean) : UserIntent
        data class SetClearDataOnExit(val enabled: Boolean) : UserIntent
        data class SetAutoplayPolicy(val policy: AutoplayPolicy) : UserIntent
        data class SetAppLanguage(val language: AppLanguage) : UserIntent
        data class ToggleSiteShieldForHost(
            val hostOrUrl: String,
            val shieldEnabled: Boolean,
            val isPrivate: Boolean = false,
        ) : UserIntent
        data class SetFilterSubscriptionEnabled(
            val subscriptionId: String,
            val enabled: Boolean,
        ) : UserIntent
        data class SetSearchEngine(val engine: SearchEngine) : UserIntent
        data class SaveCustomFilterRules(val rulesText: String) : UserIntent
        data object ResetPrivacyStats : UserIntent
    }

    sealed interface SystemPatch : BrowserMviAction {
        data class TabsUpdated(val tabsState: BrowserTabsUiState) : SystemPatch
        data class SettingsUpdated(val settings: VekoSettings) : SystemPatch
        data class TelemetryUpdated(val telemetry: PrivacyShieldTelemetry) : SystemPatch
        data class FilterRulesUpdated(
            val subscriptions: List<FilterSubscriptionInfo>,
            val customRules: String,
        ) : SystemPatch
        data class EmbedderReadinessLoaded(val readiness: ChromiumEmbedderReadiness) : SystemPatch
        data class EngineAvailabilitiesUpdated(
            val availabilities: Map<EngineType, EngineAvailability>,
        ) : SystemPatch
        data class InitializedEnginesUpdated(
            val initializedEngines: Set<EngineType>,
        ) : SystemPatch
    }
}

/**
 * Sự kiện một lần (One-shot Effect) phát từ Store ra tầng UI.
 */
sealed interface BrowserMviEffect {
    data class ShowMessage(val message: String) : BrowserMviEffect
}
