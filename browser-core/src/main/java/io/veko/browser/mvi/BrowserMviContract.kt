package io.veko.browser.mvi

import androidx.compose.runtime.Immutable
import io.veko.browser.chromium.embedder.ChromiumEmbedderReadiness
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.VekoSettings
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
    val isPrivacySheetOpen: Boolean = false,
)

/**
 * Mọi hành động (Action / Intent) một chiều trong kiến trúc MVI / UDF của Veko Browser.
 */
sealed interface BrowserMviAction {

    sealed interface UserIntent : BrowserMviAction {
        data class OpenNewTab(val url: String = "about:blank", val isPrivate: Boolean = false) : UserIntent
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
        data class SetStripTrackingParamsEnabled(val enabled: Boolean) : UserIntent
        data class SetHttpsUpgradeEnabled(val enabled: Boolean) : UserIntent
        data class SetBlockMixedContent(val enabled: Boolean) : UserIntent
        data class SetCookieControlMode(val mode: CookieControlMode) : UserIntent
        data class SetSaveSearchHistory(val enabled: Boolean) : UserIntent
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
    }
}

/**
 * Sự kiện một lần (One-shot Effect) phát từ Store ra tầng UI.
 */
sealed interface BrowserMviEffect {
    data class ShowMessage(val message: String) : BrowserMviEffect
}
