package io.veko.browser

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.engine.BrowserRuntimeManager
import io.veko.browser.tab.BrowserTabsUiState
import io.veko.browser.tab.TabManager
import io.veko.browser.ui.BrowserScreen
import io.veko.browser.ui.theme.VekoTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import javax.inject.Inject

/**
 * ViewModel cầu nối UDF giữa tầng Compose UI, TabManager, Room (History/Bookmark) và DataStore Settings.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val tabManager: TabManager,
    private val dataRepository: BrowserDataRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState: StateFlow<BrowserTabsUiState> = tabManager.uiState

    val settings: StateFlow<VekoSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = VekoSettings(),
    )

    val recentHistory: StateFlow<List<HistoryEntity>> = dataRepository.recentHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val bookmarks: StateFlow<List<BookmarkEntity>> = dataRepository.bookmarks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    val isActiveTabBookmarked: StateFlow<Boolean> = uiState
        .map { it.activeTab?.sessionState?.url.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { url ->
            if (url.isBlank()) flowOf(false) else dataRepository.observeIsBookmarked(url)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    fun bootstrap(initialExternalUrl: String?) = tabManager.bootstrapIfNeeded(initialExternalUrl)
    fun onAttachView(view: GeckoView) = tabManager.attachGeckoView(view)
    fun onDetachView(view: GeckoView) = tabManager.detachGeckoView(view)
    fun onForeground() = tabManager.onAppForeground()
    fun onBackground() = tabManager.onAppBackground()
    fun onSubmitInput(input: String) = tabManager.loadUrlOrSearch(input)
    fun onCreateTab(url: String, isPrivate: Boolean) = tabManager.createTab(url = url, isPrivate = isPrivate, select = true)
    fun onSelectTab(tabId: String) = tabManager.selectTab(tabId)
    fun onCloseTab(tabId: String) = tabManager.closeTab(tabId)
    fun onSwipeAddressBar(offset: Int): Boolean = tabManager.switchRelativeTab(offset)
    fun onPrepareTabSwitcher() = tabManager.captureActiveTabPreview()
    fun onBack() = tabManager.goBack()
    fun onForward() = tabManager.goForward()
    fun onReloadOrStop() = tabManager.reloadOrStop()
    fun onToggleDesktopMode() = tabManager.toggleDesktopMode()

    fun onToggleBookmark() {
        val activeTab = uiState.value.activeTab ?: return
        viewModelScope.launch {
            dataRepository.toggleBookmark(
                url = activeTab.sessionState.url,
                title = activeTab.sessionState.title,
            )
        }
    }

    fun onDeleteBookmark(url: String) {
        viewModelScope.launch { dataRepository.deleteBookmark(url) }
    }

    fun onDeleteHistoryItem(id: Long) {
        viewModelScope.launch { dataRepository.deleteHistoryItem(id) }
    }

    fun onClearHistory() {
        viewModelScope.launch { dataRepository.clearAllHistory() }
    }

    fun onSetDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun onSetSearchEngine(engine: SearchEngine) {
        viewModelScope.launch { settingsRepository.setSearchEngine(engine) }
    }
}

/**
 * Single-Activity duy nhất của Veko.
 * Mọi màn hình (Browser, Omnibox, Tab Switcher, Library, Settings) đều render bằng Jetpack Compose.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    @Inject
    lateinit var runtimeManager: BrowserRuntimeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bật Edge-to-Edge để nội dung web và thanh cử chỉ dưới đáy hòa quyện chuẩn Material 3
        enableEdgeToEdge()

        val initialExternalUrl = extractExternalHttpUrl(intent)
        viewModel.bootstrap(initialExternalUrl = if (savedInstanceState == null) initialExternalUrl else null)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            VekoTheme(dynamicColor = settings.dynamicColor) {
                BrowserScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Khi Veko đang chạy singleTask và nhận link từ app khác, mở ngay trong tab mới
        extractExternalHttpUrl(intent)?.let { externalUrl ->
            viewModel.onCreateTab(url = externalUrl, isPrivate = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Chuyển tiếp thay đổi xoay màn hình / dark mode xuống GeckoRuntime mà không hủy SurfaceView
        runtimeManager.onConfigurationChanged(newConfig)
    }

    private fun extractExternalHttpUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        val scheme = data.scheme?.lowercase()
        return if (scheme == "http" || scheme == "https") data.toString() else null
    }
}
