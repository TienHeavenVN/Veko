package io.veko.browser.mvi

/**
 * Hàm chuyển trạng thái thuần khiết (Pure Deterministic Reducer):
 * Không có tác dụng phụ I/O, đảm bảo luồng dữ liệu một chiều (Unidirectional Data Flow) có thể kiểm thử độc lập 100%.
 */
object BrowserReducer {

    fun reduce(current: BrowserMviState, action: BrowserMviAction): BrowserMviState {
        return when (action) {
            is BrowserMviAction.UserIntent.SetPrivacySheetVisible ->
                current.copy(isPrivacySheetOpen = action.visible)

            is BrowserMviAction.SystemPatch.TabsUpdated ->
                current.copy(tabsState = action.tabsState)

            is BrowserMviAction.SystemPatch.SettingsUpdated ->
                current.copy(userSettings = action.settings)

            is BrowserMviAction.SystemPatch.TelemetryUpdated ->
                current.copy(privacyTelemetry = action.telemetry)

            is BrowserMviAction.SystemPatch.FilterRulesUpdated ->
                current.copy(
                    filterSubscriptions = action.subscriptions,
                    customFilterRules = action.customRules,
                )

            is BrowserMviAction.SystemPatch.EmbedderReadinessLoaded ->
                current.copy(embedderReadiness = action.readiness)

            // Các UserIntent điều phối side-effect qua TabManager/SettingsRepository sẽ phát SystemPatch tương ứng
            is BrowserMviAction.UserIntent -> current
        }
    }
}
