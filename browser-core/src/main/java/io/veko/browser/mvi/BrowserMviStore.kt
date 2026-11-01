package io.veko.browser.mvi

import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.privacy.PrivacyShieldEngine
import io.veko.browser.tab.EngineSessionBridge
import io.veko.browser.tab.TabManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * MVI / UDF Store trung tâm của Veko Browser:
 * Nhận `BrowserMviAction.UserIntent`, cập nhật trạng thái qua `BrowserReducer` thuần khiết
 * và thực thi tác dụng phụ bất đồng bộ có kiểm soát.
 */
class BrowserMviStore(
    private val tabManager: TabManager,
    private val settingsRepository: SettingsRepository,
    private val privacyShieldEngine: PrivacyShieldEngine,
    private val engineBridge: EngineSessionBridge? = null,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _state = MutableStateFlow(
        BrowserMviState(
            engineAvailability = engineBridge?.getAvailability(),
            isEngineInitialized = engineBridge?.isEngineInitialized?.value ?: false,
            isNativeShieldLoaded = privacyShieldEngine.isNativeEngineReady,
        ),
    )
    val state: StateFlow<BrowserMviState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BrowserMviEffect>(extraBufferCapacity = 16)
    val effects: SharedFlow<BrowserMviEffect> = _effects.asSharedFlow()

    init {
        scope.launch {
            tabManager.uiState.collect { tabs ->
                applyAction(BrowserMviAction.SystemPatch.TabsUpdated(tabs))
            }
        }
        scope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                privacyShieldEngine.filterListManager.syncTrustedHosts(settings.trustedShieldHosts)
                privacyShieldEngine.refreshTelemetry()
                applyAction(
                    BrowserMviAction.SystemPatch.NativeShieldDiagnosticsUpdated(
                        isNativeLoaded = privacyShieldEngine.isNativeEngineReady,
                    ),
                )
                applyAction(BrowserMviAction.SystemPatch.SettingsUpdated(settings))
            }
        }
        scope.launch {
            privacyShieldEngine.telemetry.collect { telemetry ->
                applyAction(BrowserMviAction.SystemPatch.TelemetryUpdated(telemetry))
            }
        }
        scope.launch {
            combine(
                privacyShieldEngine.filterListManager.subscriptions,
                privacyShieldEngine.filterListManager.customRulesText,
            ) { subs, custom -> subs to custom }.collect { (subs, custom) ->
                applyAction(BrowserMviAction.SystemPatch.FilterRulesUpdated(subs, custom))
            }
        }
        if (engineBridge != null) {
            scope.launch {
                engineBridge.engineAvailability.collect { availability ->
                    applyAction(BrowserMviAction.SystemPatch.EngineAvailabilityUpdated(availability))
                }
            }
            scope.launch {
                engineBridge.isEngineInitialized.collect { initialized ->
                    applyAction(BrowserMviAction.SystemPatch.EngineInitializedUpdated(initialized))
                }
            }
        }
    }

    fun dispatch(intent: BrowserMviAction.UserIntent) {
        applyAction(intent)
        when (intent) {
            is BrowserMviAction.UserIntent.OpenNewTab ->
                tabManager.createTab(url = intent.url, isPrivate = intent.isPrivate, select = true)
            is BrowserMviAction.UserIntent.CloseTab ->
                tabManager.closeTab(intent.tabId)
            is BrowserMviAction.UserIntent.CloseAllPrivateTabs ->
                tabManager.closeAllPrivateTabs()
            is BrowserMviAction.UserIntent.SelectTab ->
                tabManager.selectTab(intent.tabId)
            is BrowserMviAction.UserIntent.SubmitOmnibox ->
                tabManager.loadUrlOrSearch(intent.rawInput)
            is BrowserMviAction.UserIntent.NavigateBack ->
                tabManager.goBack()
            is BrowserMviAction.UserIntent.NavigateForward ->
                tabManager.goForward()
            is BrowserMviAction.UserIntent.ReloadOrStopPage ->
                tabManager.reloadOrStop()
            is BrowserMviAction.UserIntent.ToggleDesktopMode ->
                tabManager.toggleDesktopMode()
            is BrowserMviAction.UserIntent.SetPrivacySheetVisible -> Unit
            is BrowserMviAction.UserIntent.SetAdBlockEnabled ->
                scope.launch(ioDispatcher) { settingsRepository.setAdBlockEnabled(intent.enabled) }
            is BrowserMviAction.UserIntent.SetTrackerBlockEnabled ->
                scope.launch(ioDispatcher) { settingsRepository.setTrackerBlockEnabled(intent.enabled) }
            is BrowserMviAction.UserIntent.SetStrictAntiTracking ->
                scope.launch(ioDispatcher) { settingsRepository.setStrictAntiTracking(intent.enabled) }
            is BrowserMviAction.UserIntent.SetStripTrackingParamsEnabled ->
                scope.launch(ioDispatcher) { settingsRepository.setStripTrackingParamsEnabled(intent.enabled) }
            is BrowserMviAction.UserIntent.SetHttpsUpgradeEnabled ->
                scope.launch(ioDispatcher) { settingsRepository.setHttpsUpgradeEnabled(intent.enabled) }
            is BrowserMviAction.UserIntent.SetBlockMixedContent ->
                scope.launch(ioDispatcher) { settingsRepository.setBlockMixedContent(intent.enabled) }
            is BrowserMviAction.UserIntent.SetCookieControlMode ->
                scope.launch(ioDispatcher) { settingsRepository.setCookieControlMode(intent.mode) }
            is BrowserMviAction.UserIntent.SetSaveSearchHistory ->
                scope.launch(ioDispatcher) { settingsRepository.setSaveSearchHistory(intent.enabled) }
            is BrowserMviAction.UserIntent.SetClearDataOnExit ->
                scope.launch(ioDispatcher) { settingsRepository.setClearDataOnExit(intent.enabled) }
            is BrowserMviAction.UserIntent.SetAutoplayPolicy ->
                scope.launch(ioDispatcher) { settingsRepository.setAutoplayPolicy(intent.policy) }
            is BrowserMviAction.UserIntent.SetAppLanguage ->
                scope.launch(ioDispatcher) { settingsRepository.setAppLanguage(intent.language) }
            is BrowserMviAction.UserIntent.ToggleSiteShieldForHost -> {
                val trusted = !intent.shieldEnabled
                if (intent.isPrivate) {
                    privacyShieldEngine.setOffTheRecordHostPaused(intent.hostOrUrl, paused = trusted)
                    tabManager.reloadOrStop()
                } else {
                    scope.launch(ioDispatcher) {
                        settingsRepository.setTrustedShieldHost(intent.hostOrUrl, trusted = trusted)
                    }
                    tabManager.reloadOrStop()
                }
            }
            is BrowserMviAction.UserIntent.SetFilterSubscriptionEnabled ->
                scope.launch(ioDispatcher) {
                    privacyShieldEngine.filterListManager.setSubscriptionEnabled(
                        subscriptionId = intent.subscriptionId,
                        enabled = intent.enabled,
                    )
                    privacyShieldEngine.refreshTelemetry()
                }
            is BrowserMviAction.UserIntent.SetSearchEngine ->
                scope.launch(ioDispatcher) { settingsRepository.setSearchEngine(intent.engine) }
            is BrowserMviAction.UserIntent.SaveCustomFilterRules ->
                scope.launch(ioDispatcher) {
                    val ok = privacyShieldEngine.filterListManager.updateCustomRules(intent.rulesText)
                    privacyShieldEngine.refreshTelemetry()
                    _effects.tryEmit(
                        BrowserMviEffect.ShowMessage(
                            if (ok) "Đã nạp bộ quy tắc lọc tùy chỉnh vào nhân Rust" else "Không thể nạp quy tắc lọc",
                        ),
                    )
                }
            is BrowserMviAction.UserIntent.ResetPrivacyStats ->
                scope.launch(ioDispatcher) {
                    privacyShieldEngine.resetTelemetry()
                    _effects.tryEmit(BrowserMviEffect.ShowMessage("Đã đặt lại bộ đếm Lá chắn Veko"))
                }
        }
    }

    private fun applyAction(action: BrowserMviAction) {
        _state.update { current -> BrowserReducer.reduce(current, action) }
    }
}
