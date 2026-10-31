package io.veko.browser

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.AddressSuggestion
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.download.DownloadItem
import io.veko.browser.download.DownloadNotificationEvent
import io.veko.browser.download.VekoDownloadManager
import io.veko.browser.engine.BrowserRuntimeManager
import io.veko.browser.permission.OriginPermissionsRecord
import io.veko.browser.permission.SitePermissionDecision
import io.veko.browser.permission.SitePermissionManager
import io.veko.browser.permission.SitePermissionType
import io.veko.browser.tab.BrowserTabsUiState
import io.veko.browser.tab.TabManager
import io.veko.browser.tab.UrlNavigationPolicy
import io.veko.browser.ui.BrowserScreen
import io.veko.browser.ui.theme.VekoTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import javax.inject.Inject

/**
 * ViewModel cầu nối UDF giữa tầng Compose UI, TabManager, Room (History/Bookmark), Quyền trang web, Tải xuống và Settings.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val tabManager: TabManager,
    private val dataRepository: BrowserDataRepository,
    private val settingsRepository: SettingsRepository,
    private val sitePermissionManager: SitePermissionManager,
    private val downloadManager: VekoDownloadManager,
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

    val downloads: StateFlow<List<DownloadItem>> = downloadManager.downloads
    val downloadEvents: Flow<String> = downloadManager.events.map { event ->
        when (event) {
            is DownloadNotificationEvent.Started -> "Đang tải xuống: ${event.fileName}"
            is DownloadNotificationEvent.Completed -> "Đã tải xong: ${event.item.fileName}"
            is DownloadNotificationEvent.Failed -> "Tải thất bại (${event.fileName}): ${event.reason}"
        }
    }
    val allPersistedSitePermissions: StateFlow<List<OriginPermissionsRecord>> = sitePermissionManager.savedOrigins

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

    val activeSitePermissions: StateFlow<Map<SitePermissionType, SitePermissionDecision>> = uiState
        .map { state ->
            val tab = state.activeTab
            (tab?.sessionState?.url.orEmpty()) to (tab?.isPrivate == true)
        }
        .distinctUntilChanged()
        .flatMapLatest { (url, isPrivate) ->
            if (url.isBlank()) {
                flowOf(emptyMap<SitePermissionType, SitePermissionDecision>())
            } else {
                sitePermissionManager.observeOriginPermissions(url, isPrivate)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyMap(),
        )

    private val _omniboxQuery = MutableStateFlow("")

    val omniboxSuggestions: StateFlow<List<AddressSuggestion>> = _omniboxQuery
        .debounce(90L)
        .distinctUntilChanged()
        .flatMapLatest { query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                flowOf(emptyList())
            } else {
                flow { emit(dataRepository.searchSuggestions(trimmed, limit = 6)) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

    val filteredHistory: StateFlow<List<HistoryEntity>> = combine(
        recentHistory,
        _historySearchQuery.debounce(120L).distinctUntilChanged(),
    ) { recent, query ->
        recent to query.trim()
    }
        .flatMapLatest { (recent, trimmed) ->
            if (trimmed.isEmpty()) {
                flowOf(recent)
            } else {
                flow { emit(dataRepository.searchHistory(trimmed)) }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun bootstrap(initialExternalUrl: String?) = tabManager.bootstrapIfNeeded(initialExternalUrl)
    fun onAttachView(view: GeckoView) = tabManager.attachGeckoView(view)
    fun onDetachView(view: GeckoView) = tabManager.detachGeckoView(view)
    fun onForeground(ownerToken: Any) = tabManager.onAppForeground(ownerToken)
    fun onBackground(ownerToken: Any) = tabManager.onAppBackground(ownerToken)
    fun onSubmitInput(input: String) {
        _omniboxQuery.value = ""
        tabManager.loadUrlOrSearch(input)
    }

    fun onUpdateOmniboxQuery(query: String) {
        _omniboxQuery.value = query
    }

    fun onCreateTab(url: String, isPrivate: Boolean) = tabManager.createTab(url = url, isPrivate = isPrivate, select = true)
    fun onCreateHomeTab(isPrivate: Boolean) = tabManager.createTab(url = TabManager.ABOUT_BLANK, isPrivate = isPrivate, select = true)
    fun onNavigateHome() = tabManager.navigateHome()
    fun onOpenInBackgroundTab(url: String, isPrivate: Boolean) = tabManager.openInBackgroundTab(url = url, isPrivate = isPrivate)
    fun onSelectTab(tabId: String) = tabManager.selectTab(tabId)
    fun onCloseTab(tabId: String) = tabManager.closeTab(tabId)
    fun onCloseAllPrivateTabs() = tabManager.closeAllPrivateTabs()
    fun onSwipeAddressBar(offset: Int): Boolean = tabManager.switchRelativeTab(offset)
    fun onPrepareTabSwitcher() = tabManager.captureActiveTabPreview()
    fun onBack() = tabManager.goBack()
    fun onForward() = tabManager.goForward()
    fun onReloadOrStop() = tabManager.reloadOrStop()
    fun onExitFullScreen() = tabManager.exitFullScreen()
    fun onToggleDesktopMode() = tabManager.toggleDesktopMode()

    // Tìm trong trang (Find in Page)
    fun onOpenFindInPage() = tabManager.openFindInPage()
    fun onUpdateFindQuery(query: String) = tabManager.updateFindInPageQuery(query)
    fun onFindNext(backwards: Boolean) = tabManager.findNextInPage(backwards)
    fun onToggleFindMatchCase() = tabManager.toggleFindMatchCase()
    fun onCloseFindInPage() = tabManager.closeFindInPage()

    // Thu phóng trang (Page Zoom)
    fun onStepPageZoom(deltaPercent: Int) {
        val current = settings.value
        val base = if (current.autoFontSize) 100 else current.pageZoomPercent
        val next = (base + deltaPercent).coerceIn(75, 150)
        viewModelScope.launch {
            settingsRepository.setPageZoom(autoFontSize = false, zoomPercent = next)
        }
    }

    fun onResetPageZoom() {
        viewModelScope.launch {
            settingsRepository.setPageZoom(autoFontSize = true, zoomPercent = 100)
        }
    }

    // Dấu trang & Lịch sử
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

    fun onSaveShortcut(
        rawUrlOrDomain: String,
        customTitle: String?,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch {
            val saved = dataRepository.saveShortcutBookmark(rawUrlOrDomain, customTitle)
            onResult(saved)
        }
    }

    fun onUpdateHistorySearchQuery(query: String) {
        _historySearchQuery.value = query
    }

    fun onDeleteHistoryItem(id: Long) {
        viewModelScope.launch { dataRepository.deleteHistoryItem(id) }
    }

    fun onClearHistory() {
        viewModelScope.launch { dataRepository.clearAllHistory() }
    }

    // Quyền trang web & Hộp thoại Web
    fun onResolvePermissionPrompt(allow: Boolean, remember: Boolean) =
        tabManager.resolvePermissionPrompt(allow = allow, remember = remember)

    fun onSetOriginPermission(
        originOrUrl: String,
        type: SitePermissionType,
        decision: SitePermissionDecision,
        isPrivate: Boolean,
    ) {
        sitePermissionManager.recordDecision(
            rawUrlOrOrigin = originOrUrl,
            types = listOf(type),
            decision = decision,
            isPrivate = isPrivate,
            remember = true,
        )
    }

    fun onRevokeOriginPermissions(originOrUrl: String, isPrivate: Boolean) {
        if (isPrivate) {
            for (type in SitePermissionType.entries) {
                sitePermissionManager.revokePermission(originOrUrl, type, isPrivate = true)
            }
        } else {
            sitePermissionManager.revokeAllForOrigin(originOrUrl)
        }
    }

    fun onClearAllSitePermissions() {
        sitePermissionManager.clearAllSavedPermissions()
    }

    fun onDismissContextMenu() = tabManager.dismissContextMenu()
    fun onDismissWebPrompt(cancelUnderlying: Boolean = true) = tabManager.dismissWebPrompt(cancelUnderlying)
    fun onDismissExternalAppPrompt() = tabManager.dismissExternalAppPrompt()

    // Quản lý tải xuống
    fun onEnqueueDirectDownload(url: String, isPrivate: Boolean) {
        downloadManager.enqueueUrlDownload(
            url = url,
            isPrivate = isPrivate,
        )
    }

    fun onCancelDownload(id: String) = downloadManager.cancelDownload(id)
    fun onRetryDownload(id: String) = downloadManager.retryDownload(id)
    fun onRemoveDownload(id: String) = downloadManager.removeDownloadRecord(id)
    fun onClearFinishedDownloads() = downloadManager.clearFinishedDownloads(onlyPrivate = false)

    // Cài đặt trình duyệt
    fun onSetDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    }

    fun onSetSearchEngine(engine: SearchEngine) {
        viewModelScope.launch { settingsRepository.setSearchEngine(engine) }
    }
}

/**
 * Single-Activity duy nhất của Veko.
 * Mọi màn hình (Browser, Omnibox, Tab Switcher, Library, Downloads, Permissions, Settings) đều render bằng Jetpack Compose.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    @Inject
    lateinit var runtimeManager: BrowserRuntimeManager

    @Inject
    lateinit var tabManager: TabManager

    private var pendingOsPermissionCallback: ((Boolean) -> Unit)? = null

    private val osPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val callback = pendingOsPermissionCallback
        pendingOsPermissionCallback = null
        val allGranted = results.isNotEmpty() && results.values.all { it }
        callback?.invoke(allGranted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tabManager.setOsPermissionRequester { permissions, onDecision ->
            val missing = permissions.filter { perm ->
                ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                onDecision(true)
            } else {
                pendingOsPermissionCallback?.invoke(false)
                pendingOsPermissionCallback = onDecision
                osPermissionsLauncher.launch(missing.toTypedArray())
            }
        }

        val initialExternalUrl = if (savedInstanceState == null) {
            consumeExternalHttpUrl(intent)
        } else {
            null
        }
        viewModel.bootstrap(initialExternalUrl = initialExternalUrl)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val isFullScreen = uiState.activeTab?.sessionState?.isFullScreen == true

            // Đồng bộ thanh trạng thái/điều hướng hệ thống khi video HTML5 vào hoặc thoát chế độ toàn màn hình
            LaunchedEffect(isFullScreen) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (isFullScreen) {
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }

            VekoTheme(dynamicColor = settings.dynamicColor) {
                BrowserScreen(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        tabManager.setOsPermissionRequester(null)
        pendingOsPermissionCallback?.invoke(false)
        pendingOsPermissionCallback = null
        super.onDestroy()
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
