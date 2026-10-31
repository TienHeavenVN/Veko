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
import io.veko.browser.tab.UrlNavigationPolicy
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
    fun onForeground(ownerToken: Any) = tabManager.onAppForeground(ownerToken)
    fun onBackground(ownerToken: Any) = tabManager.onAppBackground(ownerToken)
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
        enableEdgeToEdge()

        val initialExternalUrl = if (savedInstanceState == null) {
            consumeExternalHttpUrl(intent)
        } else {
            null
        }
        viewModel.bootstrap(initialExternalUrl = initialExternalUrl)

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
        consumeExternalHttpUrl(intent)?.let { externalUrl ->
            viewModel.onCreateTab(url = externalUrl, isPrivate = false)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runtimeManager.onConfigurationChanged(newConfig)
    }

    /**
     * Trích xuất và đánh dấu đã tiêu thụ liên kết ngoài để tránh mở lặp tab khi Activity bị tái tạo.
     */
    private fun consumeExternalHttpUrl(intent: Intent?): String? {
        if (intent == null) return null
        val isFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        val alreadyConsumed = intent.getBooleanExtra(EXTRA_EXTERNAL_URL_CONSUMED, false)
        val rawUri = runCatching { intent.dataString }.getOrNull()
        val sanitized = UrlNavigationPolicy.sanitizeExternalIntentUri(
            action = intent.action,
            rawUri = rawUri,
            isLaunchedFromHistory = isFromHistory,
            isAlreadyConsumed = alreadyConsumed,
        )
        if (sanitized != null) {
            intent.putExtra(EXTRA_EXTERNAL_URL_CONSUMED, true)
        }
        return sanitized
    }

    private companion object {
        const val EXTRA_EXTERNAL_URL_CONSUMED = "io.veko.browser.extra.EXTERNAL_URL_CONSUMED"
    }
}
