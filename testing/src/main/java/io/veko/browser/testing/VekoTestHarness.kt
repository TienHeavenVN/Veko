package io.veko.browser.testing

import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SettingsBackingStore
import io.veko.browser.data.repository.VekoSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Kho lưu trữ cấu hình trong RAM phục vụ bộ kiểm thử tích hợp MVI / Quyền riêng tư xuyên module.
 */
class InMemoryVekoSettingsStore(
    initial: VekoSettings = VekoSettings(),
) : SettingsBackingStore {

    private val state = MutableStateFlow(initial)
    override val flow: Flow<VekoSettings> = state

    override suspend fun updateDynamicColor(enabled: Boolean) {
        state.value = state.value.copy(dynamicColor = enabled)
    }

    override suspend fun updateSearchEngine(engine: SearchEngine) {
        state.value = state.value.copy(searchEngine = engine)
    }

    override suspend fun updatePageZoom(autoFontSize: Boolean, zoomPercent: Int) {
        state.value = state.value.copy(autoFontSize = autoFontSize, pageZoomPercent = zoomPercent)
    }

    override suspend fun updateAdBlockEnabled(enabled: Boolean) {
        state.value = state.value.copy(adBlockEnabled = enabled)
    }

    override suspend fun updateTrackerBlockEnabled(enabled: Boolean) {
        state.value = state.value.copy(trackerBlockEnabled = enabled)
    }

    override suspend fun updateStripTrackingParamsEnabled(enabled: Boolean) {
        state.value = state.value.copy(stripTrackingParamsEnabled = enabled)
    }

    override suspend fun updateHttpsUpgradeEnabled(enabled: Boolean) {
        state.value = state.value.copy(httpsUpgradeEnabled = enabled)
    }

    override suspend fun updateBlockMixedContent(enabled: Boolean) {
        state.value = state.value.copy(blockMixedContent = enabled)
    }

    override suspend fun updateCookieControlMode(mode: CookieControlMode) {
        state.value = state.value.copy(cookieControlMode = mode)
    }

    override suspend fun updateSaveSearchHistory(enabled: Boolean) {
        state.value = state.value.copy(saveSearchHistory = enabled)
    }
}
