package io.veko.browser.tab

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.asImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.veko.browser.chromium.embedder.ChromiumEmbedderRuntime
import io.veko.browser.chromium.nativebridge.NativePipelineAction
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SettingsRepository
import io.veko.browser.download.ExternalDownloadPayload
import io.veko.browser.download.VekoDownloadManager
import io.veko.browser.engine.EngineAvailability
import io.veko.browser.engine.EngineType
import io.veko.browser.engine.JavaScriptEvalResult
import io.veko.browser.engine.JavaScriptEvaluationPolicy
import io.veko.browser.permission.SitePermissionDecision
import io.veko.browser.permission.SitePermissionManager
import io.veko.browser.permission.SitePermissionPrompt
import io.veko.browser.permission.SitePermissionType
import io.veko.browser.privacy.PrivacyPolicyConfig
import io.veko.browser.privacy.PrivacyShieldEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// DataStore lưu danh sách tab thường (tuyệt đối không lưu tab riêng tư và đã loại trừ khỏi Android Backup)
private val Context.vekoSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "veko_tabs_session_store",
)

/**
 * Giao diện lưu trữ phiên tách biệt giúp chạy bất đồng bộ trên IO thread và kiểm thử cô lập dữ liệu riêng tư.
 */
interface TabSessionStorage {
    suspend fun readPersistedRaw(): Pair<String?, String?>
    suspend fun writePersistedRaw(tabsJson: String, activeRegularTabId: String?)
}

private class DataStoreTabSessionStorage(
    private val appContext: Context,
) : TabSessionStorage {
    override suspend fun readPersistedRaw(): Pair<String?, String?> {
        val prefs = runCatching { appContext.vekoSessionDataStore.data.first() }.getOrNull()
            ?: return null to null
        return prefs[KEY_TABS_JSON] to prefs[KEY_ACTIVE_TAB_ID]
    }

    override suspend fun writePersistedRaw(tabsJson: String, activeRegularTabId: String?) {
        appContext.vekoSessionDataStore.edit { prefs ->
            prefs[KEY_TABS_JSON] = tabsJson
            if (activeRegularTabId != null) {
                prefs[KEY_ACTIVE_TAB_ID] = activeRegularTabId
            } else {
                prefs.remove(KEY_ACTIVE_TAB_ID)
            }
        }
    }

    private companion object {
        val KEY_TABS_JSON = stringPreferencesKey("persisted_regular_tabs_v1")
        val KEY_ACTIVE_TAB_ID = stringPreferencesKey("persisted_active_tab_id_v1")
    }
}

internal enum class SessionSlotState {
    PENDING_POPUP_OPEN,
    OPEN,
    CLOSED,
}

internal class ManagedSessionSlot(
    val tabId: String,
    val handle: EngineSessionHandle,
    var slotState: SessionSlotState,
)

/**
 * Điều phối vòng đời Tab <-> EngineSession <-> GeckoView theo quy tắc nghiêm ngặt:
 * 1) Chỉ tab đang hiển thị ở foreground mới được attach vào GeckoView và bật setActive(true).
 * 2) Duy trì trần cứng MAX_LIVE_SESSIONS (kể cả khi mở tab/popup dồn dập hoặc khi popup handoff bị trì hoãn).
 * 3) Mọi tác vụ lưu trữ chạy tuần tự qua Mutex trên IO thread; tab riêng tư bị chặn 100% khỏi đĩa.
 */
class TabManager @VisibleForTesting constructor(
    private val sessionStorage: TabSessionStorage,
    private val engineBridge: EngineSessionBridge,
    private val dataRepository: BrowserDataRepository,
    private val settingsRepository: SettingsRepository,
    private val mainDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val defaultDispatcher: CoroutineDispatcher,
    private val uiPoster: (() -> Unit) -> Unit,
    private val isOnMainThread: () -> Boolean,
    private val clockMs: () -> Long,
    private val sitePermissionManager: SitePermissionManager? = null,
    private val downloadManager: VekoDownloadManager? = null,
    private val privacyShieldEngine: PrivacyShieldEngine? = null,
    private val chromiumEmbedderRuntime: ChromiumEmbedderRuntime? = null,
) {
    constructor(
        appContext: Context,
        engineBridge: EngineSessionBridge,
        dataRepository: BrowserDataRepository,
        settingsRepository: SettingsRepository,
        sitePermissionManager: SitePermissionManager,
        downloadManager: VekoDownloadManager,
        privacyShieldEngine: PrivacyShieldEngine? = null,
        chromiumEmbedderRuntime: ChromiumEmbedderRuntime? = null,
    ) : this(
        sessionStorage = DataStoreTabSessionStorage(appContext),
        engineBridge = engineBridge,
        dataRepository = dataRepository,
        settingsRepository = settingsRepository,
        mainDispatcher = Dispatchers.Main.immediate,
        ioDispatcher = Dispatchers.IO,
        defaultDispatcher = Dispatchers.Default,
        uiPoster = { block -> Handler(Looper.getMainLooper()).post(block) },
        isOnMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        clockMs = { System.currentTimeMillis() },
        sitePermissionManager = sitePermissionManager,
        downloadManager = downloadManager,
        privacyShieldEngine = privacyShieldEngine,
        chromiumEmbedderRuntime = chromiumEmbedderRuntime,
    ) {
        (appContext as? BrowserHostApplication)?.markTabManagerReady()
    }

    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val accessCounter = AtomicLong(1L)

    private val _uiState = MutableStateFlow(BrowserTabsUiState())
    val uiState: StateFlow<BrowserTabsUiState> = _uiState.asStateFlow()

    @Volatile
    private var currentSearchEngine: SearchEngine = SearchEngine.DUCKDUCKGO

    @Volatile
    private var currentPreferredEngine: EngineType = EngineType.GECKO

    @Volatile
    private var currentPrivacyPolicy: PrivacyPolicyConfig = PrivacyPolicyConfig()

    // Chỉ chứa các session chưa đóng (PENDING_POPUP_OPEN hoặc OPEN)
    private val liveSessions = LinkedHashMap<String, ManagedSessionSlot>()

    // Lưu mối liên kết popupId -> openerId để bảo vệ cả tab cha lẫn tab con trong lúc chờ Gecko mở popup
    private val pendingPopupOpeners = HashMap<String, String>()

    // Theo dõi các popup bị người dùng đóng trước khi Gecko hoàn tất open() để tự đóng ngay khi Gecko vừa mở xong
    private val cancelledUnopenedPopups = HashSet<EngineSessionHandle>()

    // Bọc bề mặt hiển thị bằng EngineViewHandle (dùng WeakReference nội bộ để không leak Activity/View)
    private var activeViewHandle: EngineViewHandle? = null
    private var attachedTabId: String? = null

    // Cầu nối xin quyền hệ điều hành Android gắn theo vòng đời MainActivity (xóa sạch khi Activity hủy)
    private var osPermissionRequester: ((List<String>, (Boolean) -> Unit) -> Unit)? = null

    // Theo dõi tập token của LifecycleOwner đang ở ON_START để không bị lệch khi Activity tái tạo
    private val foregroundOwnerTokens = LinkedHashSet<Any>()
    private val isAppInForeground: Boolean
        get() = foregroundOwnerTokens.isNotEmpty()

    private var hasBootstrapped: Boolean = false

    // Tuần tự hóa việc ghi DataStore bằng Mutex + số hiệu phiên bản để tránh ghi đè dữ liệu cũ lên mới
    private val persistMutex = Mutex()
    private val requestedPersistRevision = AtomicLong(0L)
    private var completedPersistRevision: Long = 0L
    private var debouncedPersistJob: Job? = null

    init {
        val multiBridge = engineBridge as? MultiEngineSessionBridge
        if (multiBridge != null) {
            multiBridge.setOnEngineShutdownListener { engineType ->
                if (isOnMainThread()) {
                    onEngineShutdown(engineType)
                } else {
                    uiPoster { onEngineShutdown(engineType) }
                }
            }
        } else {
            engineBridge.setOnRuntimeShutdownListener {
                if (isOnMainThread()) {
                    onRuntimeShutdown()
                } else {
                    uiPoster { onRuntimeShutdown() }
                }
            }
        }
        mainScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSearchEngine = settings.searchEngine
                currentPreferredEngine = resolveTabEngine(settings.preferredEngine)
                currentPrivacyPolicy = PrivacyPolicyConfig(
                    adBlockEnabled = settings.adBlockEnabled,
                    trackerBlockEnabled = settings.trackerBlockEnabled,
                    strictAntiTracking = settings.strictAntiTracking,
                    stripTrackingParamsEnabled = settings.stripTrackingParamsEnabled,
                    httpsUpgradeEnabled = settings.httpsUpgradeEnabled,
                    blockMixedContent = settings.blockMixedContent,
                    cookieControlMode = settings.cookieControlMode,
                    trustedHosts = settings.trustedShieldHosts,
                )
                val applyRuntimePrefs = {
                    engineBridge.applyPageZoomSettings(settings.autoFontSize, settings.pageZoomPercent)
                    (engineBridge as? MultiEngineSessionBridge)?.applyCookiePolicy(settings.cookieControlMode)
                    Unit
                }
                if (isOnMainThread()) {
                    applyRuntimePrefs()
                } else {
                    uiPoster(applyRuntimePrefs)
                }
            }
        }
    }

    fun isEngineSelectable(engineType: EngineType): Boolean {
        val multi = engineBridge as? MultiEngineSessionBridge
        return multi?.isEngineSelectable(engineType) ?: (engineType == EngineType.GECKO)
    }

    fun getEngineAvailability(engineType: EngineType): EngineAvailability? =
        (engineBridge as? MultiEngineSessionBridge)?.getAvailability(engineType)

    private fun resolveTabEngine(requested: EngineType): EngineType {
        val multi = engineBridge as? MultiEngineSessionBridge ?: return requested
        return multi.resolveExecutableEngine(requested)
    }

    // Chỉ số chỉ đọc phục vụ giám sát trần MAX_LIVE_SESSIONS trong kiểm thử thiết bị/emulator thực tế
    val activeLiveSessionCount: Int
        get() = liveSessions.size

    @VisibleForTesting
    fun liveSessionCount(): Int = liveSessions.size

    @VisibleForTesting
    fun openSessionIds(): Set<String> =
        liveSessions.values
            .filter { it.slotState == SessionSlotState.OPEN && it.handle.isOpen }
            .mapTo(LinkedHashSet()) { it.tabId }

    @VisibleForTesting
    fun pendingPopupTabIds(): Set<String> =
        liveSessions.values
            .filter { it.slotState == SessionSlotState.PENDING_POPUP_OPEN }
            .mapTo(LinkedHashSet()) { it.tabId }

    @VisibleForTesting
    fun attachedSessionTabId(): String? = attachedTabId

    @MainThread
    fun setOsPermissionRequester(requester: ((List<String>, (Boolean) -> Unit) -> Unit)?) {
        checkMainThread("setOsPermissionRequester")
        osPermissionRequester = requester
    }

    /**
     * Khôi phục phiên làm việc trước từ DataStore (hoặc mở tab mặc định) mà không ghi đè tab mở sớm.
     */
    @MainThread
    fun bootstrapIfNeeded(initialExternalUrl: String? = null) {
        checkMainThread("bootstrapIfNeeded")
        if (hasBootstrapped) {
            initialExternalUrl?.let { createTab(url = it, isPrivate = false, select = true) }
            return
        }
        hasBootstrapped = true

        mainScope.launch {
            val decoded = withContext(ioDispatcher) {
                val (rawJson, rawActiveId) = sessionStorage.readPersistedRaw()
                SessionPersistenceCodec.decodePersistedTabs(
                    rawJson = rawJson,
                    persistedActiveId = rawActiveId,
                    nowEpochMs = clockMs(),
                )
            }

            val concurrentTabs = _uiState.value.tabs
            val concurrentIds = concurrentTabs.mapTo(HashSet()) { it.id }
            val mergedRestored = decoded.tabs
                .filterNot { it.id in concurrentIds }
                .map { restored ->
                    val executableEngine = resolveTabEngine(restored.engineType)
                    if (executableEngine == restored.engineType) {
                        restored
                    } else {
                        // Tuyệt đối không nạp nhầm chuỗi trạng thái của engine này sang engine khác
                        restored.copy(
                            engineType = executableEngine,
                            sessionState = restored.sessionState.copy(serializedEngineState = null),
                        )
                    }
                }
            val combinedTabs = mergedRestored + concurrentTabs

            if (combinedTabs.isNotEmpty()) {
                val chosenActiveId = _uiState.value.activeTabId
                    ?.takeIf { id -> combinedTabs.any { it.id == id } }
                    ?: decoded.activeTabId?.takeIf { id -> combinedTabs.any { it.id == id } }
                    ?: combinedTabs.first().id

                _uiState.update { prev ->
                    prev.copy(
                        tabs = combinedTabs,
                        activeTabId = chosenActiveId,
                        isRestoring = false,
                    )
                }

                if (initialExternalUrl != null) {
                    createTab(url = initialExternalUrl, isPrivate = false, select = true)
                } else {
                    syncActiveSessionAttachment()
                    evictLeastRecentlyUsedSessionsIfNeeded()
                }
            } else {
                _uiState.update { it.copy(isRestoring = false) }
                createTab(
                    url = initialExternalUrl ?: ABOUT_BLANK,
                    isPrivate = false,
                    select = true,
                )
            }
        }
    }

    /**
     * Tạo tab mới (hỗ trợ cả mở trực tiếp foreground hoặc mở ngầm trong nền với select = false).
     * Khi `engineType == null`, dùng nhân mặc định hiện tại (`currentPreferredEngine`) mà KHÔNG đụng chạm
     * tới nhân của các tab đang mở sẵn.
     */
    @MainThread
    fun createTab(
        url: String = TabSessionState.DEFAULT_HOME_URL,
        isPrivate: Boolean = false,
        select: Boolean = true,
        engineType: EngineType? = null,
    ): String {
        checkMainThread("createTab")
        val resolvedEngine = resolveTabEngine(engineType ?: currentPreferredEngine)
        val normalizedUrl = UrlNavigationPolicy.normalizeUserInput(url, currentSearchEngine)
        val initialTitle = "Trang mới"
        val initialSecurity = computeInitialSecurityState(normalizedUrl)
        val now = clockMs()
        val seq = accessCounter.getAndIncrement()
        val newTab = Tab(
            isPrivate = isPrivate,
            engineType = resolvedEngine,
            createdAtEpochMs = now,
            lastAccessedEpochMs = now,
            accessSequence = seq,
            sessionState = TabSessionState(
                url = normalizedUrl,
                title = initialTitle,
                securityState = initialSecurity,
            ),
        )

        val becameActive = select || _uiState.value.activeTabId == null
        if (becameActive) {
            clearActiveFindInPageInternal()
        }

        // Chỉ khởi tạo Off-The-Record BrowserContext của Chromium khi tab thực sự chạy trên Chromium
        // (hoặc khi bộ kiểm thử đơn vị truyền trực tiếp chromiumEmbedderRuntime cho FakeEngineSessionBridge)
        if (isPrivate && (resolvedEngine == EngineType.CHROMIUM || engineBridge !is MultiEngineSessionBridge)) {
            chromiumEmbedderRuntime?.acquireOffTheRecordContext()
        }

        val handle = buildConfiguredSession(
            tabId = newTab.id,
            engineType = resolvedEngine,
            isPrivate = isPrivate,
            isDesktopMode = false,
            openImmediately = true,
        )
        liveSessions[newTab.id] = ManagedSessionSlot(
            tabId = newTab.id,
            handle = handle,
            slotState = SessionSlotState.OPEN,
        )

        // Đưa tab vào _uiState.tabs trước khi gọi loadUri để các callback đồng bộ (ChromiumEngineSessionBridge)
        // cập nhật chính xác URL/Security/CanGoBack ngay trong lần nạp đầu tiên
        _uiState.update { current ->
            current.copy(
                tabs = current.tabs + newTab,
                activeTabId = if (becameActive) newTab.id else current.activeTabId,
                isRestoring = false,
                contextMenu = null,
            )
        }

        if (normalizedUrl != ABOUT_BLANK) {
            handle.loadUri(normalizedUrl)
        }

        if (becameActive) {
            syncActiveSessionAttachment()
        } else {
            handle.setActive(false)
            handle.setPriorityHigh(false)
        }

        evictLeastRecentlyUsedSessionsIfNeeded()
        if (!isPrivate) {
            schedulePersistTabs()
        }
        return newTab.id
    }

    /**
     * Tạo tab mới với yêu cầu chỉ định rõ nhân trình duyệt:
     * Trả về `null` trung thực nếu nhân được yêu cầu chưa sẵn sàng (không bao giờ âm thầm thế bằng nhân khác).
     */
    @MainThread
    fun createTabWithExplicitEngine(
        url: String = ABOUT_BLANK,
        isPrivate: Boolean = false,
        engineType: EngineType,
        select: Boolean = true,
    ): String? {
        checkMainThread("createTabWithExplicitEngine")
        if (!isEngineSelectable(engineType)) {
            return null
        }
        return createTab(
            url = url,
            isPrivate = isPrivate,
            select = select,
            engineType = engineType,
        )
    }

    /**
     * Mở trang hiện tại sang một tab mới sử dụng nhân trình duyệt được chọn:
     * Bảo toàn nguyên vẹn tab cũ (không thay thế ngầm, không làm hỏng phiên đang mở) và giữ URL hiện tại.
     */
    @MainThread
    fun openCurrentUrlInNewTabWithEngine(
        targetEngine: EngineType,
        select: Boolean = true,
    ): String? {
        checkMainThread("openCurrentUrlInNewTabWithEngine")
        if (!isEngineSelectable(targetEngine)) {
            return null
        }
        val active = _uiState.value.activeTab
        val rawUrl = active?.sessionState?.url.orEmpty()
        val preservedUrl = if (
            rawUrl.isBlank() ||
            UrlNavigationPolicy.isNewTabBlankUrl(rawUrl) ||
            UrlNavigationPolicy.isInternalErrorDataUrl(rawUrl)
        ) {
            ABOUT_BLANK
        } else {
            UrlNavigationPolicy.sanitizeRestoredUrl(rawUrl)
        }
        return createTab(
            url = preservedUrl,
            isPrivate = active?.isPrivate == true,
            select = select,
            engineType = targetEngine,
        )
    }

    /**
     * Mở liên kết trong tab nền mà không làm gián đoạn trang người dùng đang đọc.
     */
    @MainThread
    fun openInBackgroundTab(
        url: String,
        isPrivate: Boolean,
        engineType: EngineType? = null,
    ): String = createTab(url = url, isPrivate = isPrivate, select = false, engineType = engineType)

    /**
     * Chuyển sang tab có sẵn theo ID và cập nhật dấu thời gian truy cập LRU thực tế.
     */
    @MainThread
    fun selectTab(tabId: String) {
        checkMainThread("selectTab")
        val current = _uiState.value
        val targetTab = current.tabs.firstOrNull { it.id == tabId } ?: return
        val now = clockMs()
        val seq = accessCounter.getAndIncrement()

        if (current.activeTabId == tabId) {
            updateTab(tabId) { it.copy(lastAccessedEpochMs = now, accessSequence = seq) }
            syncActiveSessionAttachment()
            return
        }

        clearActiveFindInPageInternal()
        val previousTab = current.activeTab
        _uiState.update { state ->
            state.copy(
                activeTabId = tabId,
                contextMenu = null,
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) {
                        tab.copy(lastAccessedEpochMs = now, accessSequence = seq)
                    } else {
                        tab
                    }
                },
            )
        }
        syncActiveSessionAttachment()
        if (!targetTab.isPrivate || previousTab?.isPrivate == false) {
            schedulePersistTabs()
        }
    }

    /**
     * Chuyển tab liền kề khi người dùng vuốt ngang trên thanh địa chỉ (delta = -1 hoặc +1).
     */
    @MainThread
    fun switchRelativeTab(offset: Int): Boolean {
        checkMainThread("switchRelativeTab")
        val state = _uiState.value
        val currentIdx = state.activeTabIndex
        if (currentIdx == -1 || state.tabs.size <= 1) return false
        val targetIdx = (currentIdx + offset).coerceIn(0, state.tabs.lastIndex)
        if (targetIdx == currentIdx) return false
        selectTab(state.tabs[targetIdx].id)
        return true
    }

    /**
     * Đóng một tab: hủy hộp thoại treo của tab, tháo khỏi GeckoView, đóng session đúng 1 lần và dọn dữ liệu riêng tư nếu hết tab riêng tư.
     */
    @MainThread
    fun closeTab(tabId: String) {
        checkMainThread("closeTab")
        val state = _uiState.value
        val closingIdx = state.tabs.indexOfFirst { it.id == tabId }
        if (closingIdx == -1) return
        val closingTab = state.tabs[closingIdx]

        dismissDialogsForTab(tabId)
        if (state.activeTabId == tabId) {
            clearActiveFindInPageInternal()
        }

        // Bắt buộc tháo và đóng session trước khi cập nhật phiên kế tiếp
        closeAndRemoveSession(tabId)

        val remaining = state.tabs.filterNot { it.id == tabId }
        if (closingTab.isPrivate) {
            val remainingPrivateSameEngine = remaining.count {
                it.isPrivate && it.engineType == closingTab.engineType
            }
            (engineBridge as? MultiEngineSessionBridge)?.releasePrivateContextIfUnused(
                engineType = closingTab.engineType,
                activePrivateTabsForEngine = remainingPrivateSameEngine,
            )
            if (closingTab.engineType == EngineType.CHROMIUM || engineBridge !is MultiEngineSessionBridge) {
                if (remainingPrivateSameEngine == 0) {
                    chromiumEmbedderRuntime?.releaseOffTheRecordContextIfUnused(activePrivateTabCount = 0)
                }
            }
            if (remaining.none { it.isPrivate }) {
                sitePermissionManager?.clearPrivateSessionPermissions()
                privacyShieldEngine?.clearOffTheRecordTrustedHosts()
                downloadManager?.clearFinishedDownloads(onlyPrivate = true)
            }
        }

        if (remaining.isEmpty()) {
            _uiState.value = BrowserTabsUiState(tabs = emptyList(), activeTabId = null, isRestoring = false)
            createTab(url = ABOUT_BLANK, isPrivate = false, select = true)
            schedulePersistTabs(immediate = true)
            return
        }

        val now = clockMs()
        val seq = accessCounter.getAndIncrement()
        val nextActiveId = if (state.activeTabId != tabId && remaining.any { it.id == state.activeTabId }) {
            state.activeTabId
        } else {
            remaining[closingIdx.coerceAtMost(remaining.lastIndex)].id
        }

        _uiState.update { current ->
            current.copy(
                tabs = remaining.map { tab ->
                    if (tab.id == nextActiveId && state.activeTabId == tabId) {
                        tab.copy(lastAccessedEpochMs = now, accessSequence = seq)
                    } else {
                        tab
                    }
                },
                activeTabId = nextActiveId,
            )
        }
        syncActiveSessionAttachment()
        if (!closingTab.isPrivate) {
            schedulePersistTabs(immediate = true)
        }
    }

    /**
     * Đóng toàn bộ các tab riêng tư chỉ bằng 1 chạm và xóa sạch quyền tạm thời trên RAM.
     */
    @MainThread
    fun closeAllPrivateTabs() {
        checkMainThread("closeAllPrivateTabs")
        val privateIds = _uiState.value.tabs.filter { it.isPrivate }.map { it.id }
        if (privateIds.isEmpty()) return
        for (id in privateIds) {
            closeTab(id)
        }
        sitePermissionManager?.clearPrivateSessionPermissions()
        privacyShieldEngine?.clearOffTheRecordTrustedHosts()
        downloadManager?.clearFinishedDownloads(onlyPrivate = true)
        (engineBridge as? MultiEngineSessionBridge)?.let { multi ->
            for (engine in EngineType.entries) {
                multi.releasePrivateContextIfUnused(engine, activePrivateTabsForEngine = 0)
            }
        }
        chromiumEmbedderRuntime?.releaseOffTheRecordContextIfUnused(activePrivateTabCount = 0)
    }

    /**
     * Khởi tạo View nền tảng theo đúng `EngineType` của tab đang hiển thị (chỉ tạo View cho engine được chọn).
     */
    @MainThread
    fun createPlatformView(
        context: Context,
        engineType: EngineType? = null,
    ): View {
        val targetEngine = resolveTabEngine(
            engineType ?: _uiState.value.activeTab?.engineType ?: currentPreferredEngine,
        )
        val multi = engineBridge as? MultiEngineSessionBridge
        return if (multi != null) {
            multi.createPlatformView(context, targetEngine)
        } else {
            engineBridge.createPlatformView(context)
        }
    }

    /**
     * Gắn bề mặt hiển thị trình duyệt từ AndroidView trong Compose vào bộ điều phối.
     */
    @MainThread
    fun attachPlatformView(
        platformView: View,
        engineType: EngineType? = null,
    ) {
        val multi = engineBridge as? MultiEngineSessionBridge
        val handle = if (multi != null) {
            val targetEngine = resolveTabEngine(
                engineType ?: _uiState.value.activeTab?.engineType ?: currentPreferredEngine,
            )
            multi.wrapPlatformView(platformView, targetEngine)
        } else {
            engineBridge.wrapPlatformView(platformView)
        }
        attachEngineView(handle)
    }

    @MainThread
    @VisibleForTesting
    fun attachEngineView(viewHandle: EngineViewHandle) {
        checkMainThread("attachPlatformView")
        val previousHandle = activeViewHandle
        val previousIdentity = previousHandle?.identity
        val incomingIdentity = viewHandle.identity

        if (previousHandle != null && previousIdentity !== incomingIdentity) {
            previousHandle.releaseSession()
            attachedTabId?.let { prevTabId ->
                liveSessions[prevTabId]?.handle?.let { session ->
                    session.setActive(false)
                    session.setPriorityHigh(false)
                }
            }
            attachedTabId = null
        }
        activeViewHandle = viewHandle
        syncActiveSessionAttachment()
    }

    /**
     * Tháo bề mặt hiển thị khi Composable bị hủy để không giữ tham chiếu đến View/Activity cũ.
     */
    @MainThread
    fun detachPlatformView(platformView: View) {
        detachEngineViewByIdentity(platformView)
    }

    @MainThread
    @VisibleForTesting
    fun detachEngineViewByIdentity(viewIdentity: Any) {
        checkMainThread("detachPlatformView")
        val currentHandle = activeViewHandle ?: return
        if (currentHandle.identity === viewIdentity || !currentHandle.isAlive) {
            currentHandle.releaseSession()
            attachedTabId?.let { oldId ->
                liveSessions[oldId]?.handle?.let { session ->
                    session.setActive(false)
                    session.setPriorityHigh(false)
                }
            }
            activeViewHandle = null
            attachedTabId = null
        }
    }

    @MainThread
    fun onAppForeground(ownerToken: Any = DEFAULT_FOREGROUND_TOKEN) {
        checkMainThread("onAppForeground")
        foregroundOwnerTokens.add(ownerToken)
        syncActiveSessionAttachment()
    }

    @MainThread
    fun onAppBackground(ownerToken: Any = DEFAULT_FOREGROUND_TOKEN) {
        checkMainThread("onAppBackground")
        foregroundOwnerTokens.remove(ownerToken)
        if (foregroundOwnerTokens.isNotEmpty()) return

        captureActiveTabPreview()
        activeViewHandle?.releaseSession()
        attachedTabId?.let { activeId ->
            liveSessions[activeId]?.handle?.let { session ->
                session.setActive(false)
                session.setPriorityHigh(false)
            }
        }
        attachedTabId = null
        schedulePersistTabs(immediate = true)
    }

    @MainThread
    fun captureActiveTabPreview() {
        checkMainThread("captureActiveTabPreview")
        val viewHandle = activeViewHandle ?: return
        val tabId = attachedTabId ?: return

        viewHandle.capturePixelsAsync { rawBitmap ->
            if (rawBitmap == null) return@capturePixelsAsync
            mainScope.launch {
                val scaledImage = withContext(defaultDispatcher) {
                    val targetWidth = PREVIEW_TARGET_WIDTH_PX.coerceAtMost(rawBitmap.width)
                    val ratio = targetWidth.toFloat() / rawBitmap.width.toFloat()
                    val targetHeight = (rawBitmap.height * ratio).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
                    if (scaled !== rawBitmap && !rawBitmap.isRecycled) {
                        rawBitmap.recycle()
                    }
                    scaled.asImageBitmap()
                }
                updateTab(tabId) { it.copy(previewBitmap = scaledImage) }
            }
        }
    }

    @MainThread
    fun loadUrlOrSearch(rawInput: String) {
        checkMainThread("loadUrlOrSearch")
        val activeTab = _uiState.value.activeTab ?: return
        val targetUrl = UrlNavigationPolicy.normalizeUserInput(rawInput, currentSearchEngine)
        val initialSecurity = computeInitialSecurityState(targetUrl)
        clearActiveFindInPageInternal()
        touchTabAccess(activeTab.id) { state ->
            state.copy(
                url = targetUrl,
                securityState = initialSecurity,
                isCrashed = false,
                hasLoadError = false,
            )
        }
        val refreshedTab = _uiState.value.activeTab ?: return
        val session = getOrMaterializeSession(refreshedTab, autoLoadOnCreate = false)
        session.loadUri(targetUrl)
        syncActiveSessionAttachment()
        if (!refreshedTab.isPrivate) {
            schedulePersistTabs()
        }
    }

    /**
     * Đưa tab hiện hành trở về Trang chủ Veko (about:blank) tức thì mà không phát sinh lưu lượng mạng.
     */
    @MainThread
    fun navigateHome() {
        checkMainThread("navigateHome")
        val activeTab = _uiState.value.activeTab ?: return
        if (UrlNavigationPolicy.isNewTabBlankUrl(activeTab.sessionState.url) &&
            !activeTab.sessionState.hasLoadError &&
            !activeTab.sessionState.isCrashed
        ) {
            return
        }
        dismissDialogsForTab(activeTab.id)
        clearActiveFindInPageInternal()
        liveSessions[activeTab.id]
            ?.takeIf { it.slotState == SessionSlotState.OPEN && it.handle.isOpen }
            ?.handle
            ?.let { handle ->
                handle.stop()
                handle.loadUri(ABOUT_BLANK)
            }
        touchTabAccess(activeTab.id) { state ->
            state.copy(
                url = ABOUT_BLANK,
                title = "Trang mới",
                progress = 0,
                isLoading = false,
                securityState = SecurityState.LOCAL_OR_UNKNOWN,
                isCrashed = false,
                hasLoadError = false,
                isFullScreen = false,
                serializedEngineState = null,
            )
        }
        if (!activeTab.isPrivate) {
            schedulePersistTabs()
        }
    }

    @MainThread
    fun goBack() {
        checkMainThread("goBack")
        val activeTab = _uiState.value.activeTab ?: return
        if (activeTab.sessionState.isFullScreen) {
            exitFullScreen()
            return
        }
        if (activeTab.sessionState.canGoBack) {
            touchTabAccess(activeTab.id) { it.copy(hasLoadError = false) }
            liveSessions[activeTab.id]?.takeIf { it.slotState == SessionSlotState.OPEN }?.handle?.goBack()
        }
    }

    @MainThread
    fun goForward() {
        checkMainThread("goForward")
        val activeTab = _uiState.value.activeTab ?: return
        if (activeTab.sessionState.canGoForward) {
            touchTabAccess(activeTab.id) { it.copy(hasLoadError = false) }
            liveSessions[activeTab.id]?.takeIf { it.slotState == SessionSlotState.OPEN }?.handle?.goForward()
        }
    }

    @MainThread
    fun exitFullScreen() {
        checkMainThread("exitFullScreen")
        val activeTab = _uiState.value.activeTab ?: return
        liveSessions[activeTab.id]?.takeIf { it.slotState == SessionSlotState.OPEN }?.handle?.exitFullScreen()
        updateTabSessionState(activeTab.id) { it.copy(isFullScreen = false) }
    }

    @MainThread
    fun reloadOrStop() {
        checkMainThread("reloadOrStop")
        val activeTab = _uiState.value.activeTab ?: return
        val hadLiveOpenSession = liveSessions[activeTab.id]?.let {
            it.slotState == SessionSlotState.OPEN && it.handle.isOpen
        } == true
        val wasCrashed = activeTab.sessionState.isCrashed
        val hadError = activeTab.sessionState.hasLoadError

        touchTabAccess(activeTab.id) { state ->
            state.copy(isCrashed = false, hasLoadError = false)
        }
        val updatedTab = _uiState.value.activeTab ?: return
        val session = getOrMaterializeSession(updatedTab, autoLoadOnCreate = true)

        if (wasCrashed || !hadLiveOpenSession) {
            // getOrMaterializeSession đã khôi phục SessionState hoặc nạp lại URL; chỉ cần gắn lại vào GeckoView
            syncActiveSessionAttachment()
        } else if (updatedTab.sessionState.isLoading) {
            session.stop()
            updateTabSessionState(updatedTab.id) { it.copy(isLoading = false) }
        } else if (hadError) {
            val safeRetryUrl = UrlNavigationPolicy.sanitizeRestoredUrl(updatedTab.sessionState.url)
            session.loadUri(safeRetryUrl)
        } else {
            session.reload()
        }
    }

    @MainThread
    fun toggleDesktopMode() {
        checkMainThread("toggleDesktopMode")
        val activeTab = _uiState.value.activeTab ?: return
        val nextDesktop = !activeTab.sessionState.isDesktopMode
        touchTabAccess(activeTab.id) { it.copy(isDesktopMode = nextDesktop, isCrashed = false, hasLoadError = false) }
        val updatedTab = _uiState.value.activeTab ?: return
        val hadLiveSession = liveSessions.containsKey(updatedTab.id)
        val session = getOrMaterializeSession(updatedTab, autoLoadOnCreate = true)
        session.setDesktopMode(nextDesktop)
        if (hadLiveSession) {
            session.reload()
        }
        syncActiveSessionAttachment()
        if (!updatedTab.isPrivate) {
            schedulePersistTabs()
        }
    }

    // --- Công cụ Tìm trong trang (Find in Page qua SessionFinder) ---

    @MainThread
    fun openFindInPage() {
        checkMainThread("openFindInPage")
        _uiState.update { it.copy(findInPage = FindInPageState(isVisible = true, matchCase = it.findInPage.matchCase)) }
    }

    @MainThread
    fun updateFindInPageQuery(query: String) {
        checkMainThread("updateFindInPageQuery")
        val matchCase = _uiState.value.findInPage.matchCase
        _uiState.update {
            it.copy(
                findInPage = it.findInPage.copy(
                    isVisible = true,
                    query = query,
                    currentMatch = 0,
                    totalMatches = 0,
                    notFound = false,
                ),
            )
        }
        executeFindInPage(query = query, backwards = false, matchCase = matchCase)
    }

    @MainThread
    fun findNextInPage(backwards: Boolean) {
        checkMainThread("findNextInPage")
        val state = _uiState.value.findInPage
        if (!state.isVisible || state.query.isBlank()) return
        executeFindInPage(query = state.query, backwards = backwards, matchCase = state.matchCase)
    }

    @MainThread
    fun toggleFindMatchCase() {
        checkMainThread("toggleFindMatchCase")
        val nextMatchCase = !_uiState.value.findInPage.matchCase
        val query = _uiState.value.findInPage.query
        _uiState.update { it.copy(findInPage = it.findInPage.copy(matchCase = nextMatchCase)) }
        if (query.isNotBlank()) {
            executeFindInPage(query = query, backwards = false, matchCase = nextMatchCase)
        }
    }

    @MainThread
    fun closeFindInPage() {
        checkMainThread("closeFindInPage")
        clearActiveFindInPageInternal()
    }

    @MainThread
    private fun executeFindInPage(query: String, backwards: Boolean, matchCase: Boolean) {
        val activeId = _uiState.value.activeTabId ?: return
        val handle = liveSessions[activeId]?.takeIf { it.slotState == SessionSlotState.OPEN }?.handle ?: return
        if (query.isBlank()) {
            handle.clearFindInPage()
            return
        }
        handle.findInPage(query = query, backwards = backwards, matchCase = matchCase) { found, current, total ->
            if (_uiState.value.activeTabId != activeId || !_uiState.value.findInPage.isVisible) return@findInPage
            if (_uiState.value.findInPage.query != query) return@findInPage
            _uiState.update { st ->
                st.copy(
                    findInPage = st.findInPage.copy(
                        currentMatch = if (found) current else 0,
                        totalMatches = if (found) total else 0,
                        notFound = !found,
                    ),
                )
            }
        }
    }

    @MainThread
    private fun clearActiveFindInPageInternal() {
        _uiState.value.activeTabId?.let { id ->
            liveSessions[id]?.handle?.clearFindInPage()
        }
        if (_uiState.value.findInPage.isVisible || _uiState.value.findInPage.query.isNotEmpty()) {
            _uiState.update { it.copy(findInPage = FindInPageState()) }
        }
    }

    // --- Điều khiển Menu ngữ cảnh, Hộp thoại Web & Quyền trang web ---

    @MainThread
    fun dismissContextMenu() {
        _uiState.update { it.copy(contextMenu = null) }
    }

    @MainThread
    fun dismissWebPrompt(cancelUnderlying: Boolean = true) {
        val current = _uiState.value.activeWebPrompt ?: return
        _uiState.update { it.copy(activeWebPrompt = null) }
        if (cancelUnderlying) {
            current.cancel()
        }
    }

    @MainThread
    fun resolvePermissionPrompt(allow: Boolean, remember: Boolean) {
        val prompt = _uiState.value.activePermissionPrompt ?: return
        _uiState.update { it.copy(activePermissionPrompt = null) }
        prompt.onResult(allow, remember)
    }

    @MainThread
    fun dismissExternalAppPrompt() {
        _uiState.update { it.copy(externalAppPrompt = null) }
    }

    /**
     * Thực thi JavaScript có kiểm duyệt chính sách bảo mật trên tab đang hoạt động.
     */
    @MainThread
    fun evaluateJavaScriptInActiveTab(
        script: String,
        onResult: (JavaScriptEvalResult) -> Unit,
    ) {
        checkMainThread("evaluateJavaScriptInActiveTab")
        val activeTab = _uiState.value.activeTab ?: run {
            onResult(JavaScriptEvalResult.BlockedByPolicy("No active tab available."))
            return
        }
        JavaScriptEvaluationPolicy.validate(
            currentUrl = activeTab.sessionState.url,
            script = script,
        )?.let { blocked ->
            onResult(blocked)
            return
        }
        val handle = liveSessions[activeTab.id]
            ?.takeIf { it.slotState == SessionSlotState.OPEN && it.handle.isOpen }
            ?.handle
            ?: run {
                onResult(JavaScriptEvalResult.ExecutionError("ACTIVE_SESSION_NOT_OPEN"))
                return
            }
        handle.evaluateJavaScript(
            currentUrl = activeTab.sessionState.url,
            script = script,
            onResult = onResult,
        )
    }

    /**
     * Khi hệ điều hành báo thiếu bộ nhớ, đóng toàn bộ session nền không đang hiển thị nhưng bảo toàn nguyên vẹn Tab UI & URL.
     */
    @MainThread
    fun onTrimMemory(level: Int) {
        if (!isOnMainThread()) {
            uiPoster { onTrimMemory(level) }
            return
        }
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            return
        }

        val protectedIds = buildProtectedSessionIds()
        val backgroundIds = liveSessions.keys.filter { it !in protectedIds }
        for (bgId in backgroundIds) {
            preserveTabMetadataBeforeEviction(bgId)
            closeAndRemoveSession(bgId)
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                updateTab(bgId) { it.copy(previewBitmap = null) }
            }
        }
        (engineBridge as? MultiEngineSessionBridge)?.onLowMemory(level)
    }

    /**
     * Khi toàn bộ Runtime dừng bất ngờ (hoặc một Engine cụ thể dừng trong chế độ Dual-Engine),
     * chỉ tháo và đánh dấu crash các tab thuộc Engine bị ảnh hưởng mà không làm hỏng Engine còn lại.
     */
    @MainThread
    @VisibleForTesting
    fun onRuntimeShutdown() {
        onEngineShutdown(affectedEngine = null)
    }

    @MainThread
    @VisibleForTesting
    fun onEngineShutdown(affectedEngine: EngineType?) {
        checkMainThread("onEngineShutdown")
        val affectedTabIdsByEngine = if (affectedEngine == null) {
            null
        } else {
            _uiState.value.tabs
                .filter { it.engineType == affectedEngine }
                .mapTo(HashSet()) { it.id }
        }

        val attachedTabMatches = attachedTabId != null &&
            (affectedTabIdsByEngine == null || attachedTabId in affectedTabIdsByEngine)
        if (attachedTabMatches || activeViewHandle?.engineType == affectedEngine) {
            activeViewHandle?.releaseSession()
            attachedTabId = null
        }

        val previouslyLiveIds = liveSessions.keys.filter { tabId ->
            affectedTabIdsByEngine == null || tabId in affectedTabIdsByEngine
        }
        if (affectedEngine == null) {
            pendingPopupOpeners.clear()
            cancelledUnopenedPopups.clear()
        } else {
            val affectedSet = previouslyLiveIds.toSet()
            pendingPopupOpeners.entries.removeAll { (popupId, openerId) ->
                popupId in affectedSet || openerId in affectedSet
            }
            cancelledUnopenedPopups.removeAll { it.engineType == affectedEngine }
        }

        for (tabId in previouslyLiveIds) {
            val slot = liveSessions.remove(tabId) ?: continue
            slot.slotState = SessionSlotState.CLOSED
            slot.handle.clearCallbacks()
        }

        if (previouslyLiveIds.isNotEmpty()) {
            val affected = previouslyLiveIds.toSet()
            _uiState.update { state ->
                state.copy(
                    activeWebPrompt = state.activeWebPrompt?.takeUnless { it.tabId in affected },
                    activePermissionPrompt = state.activePermissionPrompt?.takeUnless { it.tabId in affected },
                    contextMenu = state.contextMenu?.takeUnless { it.tabId in affected },
                    tabs = state.tabs.map { tab ->
                        if (tab.id in affected) {
                            tab.copy(
                                sessionState = tab.sessionState.copy(
                                    isLoading = false,
                                    isFullScreen = false,
                                    isCrashed = true,
                                ),
                            )
                        } else {
                            tab
                        }
                    },
                )
            }
        }
    }

    /**
     * Điều phối "Single Active Session": tháo session cũ, chỉ gắn session đã OPEN và đảm bảo attachedTabId luôn hợp lệ.
     */
    @MainThread
    private fun syncActiveSessionAttachment() {
        if (attachedTabId != null && !liveSessions.containsKey(attachedTabId)) {
            activeViewHandle?.releaseSession()
            attachedTabId = null
        }

        val view = activeViewHandle
        if (view != null && !view.isAlive) {
            activeViewHandle = null
            attachedTabId = null
            return
        }
        if (view == null) return

        val activeTab = _uiState.value.activeTab
        if (!isAppInForeground || activeTab == null) {
            if (attachedTabId != null) {
                view.releaseSession()
                attachedTabId?.let { oldId ->
                    liveSessions[oldId]?.handle?.let { oldSession ->
                        oldSession.setActive(false)
                        oldSession.setPriorityHigh(false)
                    }
                }
                attachedTabId = null
            }
            return
        }

        // Không được gắn hoặc tự gọi open() lên session con đang chờ GeckoView mở từ onNewSession
        val existingSlot = liveSessions[activeTab.id]
        if (existingSlot?.slotState == SessionSlotState.PENDING_POPUP_OPEN) {
            return
        }

        if (attachedTabId == activeTab.id && existingSlot != null && existingSlot.handle.isOpen) {
            existingSlot.handle.setActive(true)
            existingSlot.handle.setPriorityHigh(true)
            return
        }

        if (attachedTabId != null && attachedTabId != activeTab.id) {
            view.releaseSession()
            liveSessions[attachedTabId]?.handle?.let { previousSession ->
                previousSession.setActive(false)
                previousSession.setPriorityHigh(false)
            }
            attachedTabId = null
        }

        // Trong chế độ Dual-Engine, chỉ gắn phiên vào View khi View khớp đúng EngineType của tab đang mở
        // (tránh gắn chéo phiên khi Compose đang hoán đổi AndroidView theo key(activeTab.engineType))
        if (engineBridge is MultiEngineSessionBridge && view.engineType != activeTab.engineType) {
            return
        }

        val targetSession = getOrMaterializeSession(activeTab, autoLoadOnCreate = true)
        if (!targetSession.isOpen) return

        targetSession.setActive(true)
        targetSession.setPriorityHigh(true)
        view.setSession(targetSession)
        attachedTabId = activeTab.id
    }

    @MainThread
    @VisibleForTesting
    fun getOrMaterializeSession(
        tab: Tab,
        autoLoadOnCreate: Boolean = true,
    ): EngineSessionHandle {
        checkMainThread("getOrMaterializeSession")
        liveSessions[tab.id]?.let { slot ->
            if (slot.slotState == SessionSlotState.PENDING_POPUP_OPEN) {
                return slot.handle
            }
            if (!slot.handle.isOpen) {
                slot.handle.open()
                slot.slotState = SessionSlotState.OPEN
                if (autoLoadOnCreate) {
                    restoreOrLoadTabContent(tab, slot.handle)
                }
            }
            return slot.handle
        }

        val handle = buildConfiguredSession(
            tabId = tab.id,
            engineType = resolveTabEngine(tab.engineType),
            isPrivate = tab.isPrivate,
            isDesktopMode = tab.sessionState.isDesktopMode,
            openImmediately = true,
        )
        liveSessions[tab.id] = ManagedSessionSlot(
            tabId = tab.id,
            handle = handle,
            slotState = SessionSlotState.OPEN,
        )

        if (autoLoadOnCreate) {
            restoreOrLoadTabContent(tab, handle)
        }

        evictLeastRecentlyUsedSessionsIfNeeded(extraProtectedIds = setOf(tab.id))
        return handle
    }

    @MainThread
    private fun restoreOrLoadTabContent(tab: Tab, handle: EngineSessionHandle) {
        val encoded = tab.sessionState.serializedEngineState
        if (!encoded.isNullOrBlank()) {
            val restoredOk = handle.restoreStateFromString(encoded)
            if (restoredOk) return
            updateTabSessionState(tab.id) { it.copy(serializedEngineState = null) }
        }
        val fallbackUrl = UrlNavigationPolicy.sanitizeRestoredUrl(tab.sessionState.url)
        if (fallbackUrl != tab.sessionState.url) {
            updateTabSessionState(tab.id) { it.copy(url = fallbackUrl) }
        }
        if (fallbackUrl != ABOUT_BLANK) {
            handle.loadUri(fallbackUrl)
        }
    }

    /**
     * Giới hạn nghiêm ngặt tối đa MAX_LIVE_SESSIONS phiên mở đồng thời theo LRU hai tầng,
     * đồng thời bảo vệ tuyệt đối tab active, tab đang attach và cặp cha-con popup đang handoff.
     */
    @MainThread
    @VisibleForTesting
    fun evictLeastRecentlyUsedSessionsIfNeeded(extraProtectedIds: Set<String> = emptySet()) {
        checkMainThread("evictLeastRecentlyUsedSessionsIfNeeded")
        pruneOrphanLiveSessions()
        if (liveSessions.size <= MAX_LIVE_SESSIONS) return

        val protectedIds = buildProtectedSessionIds() + extraProtectedIds
        val candidates = _uiState.value.tabs
            .filter { tab -> tab.id in liveSessions && tab.id !in protectedIds }
            .sortedWith(
                compareBy<Tab>(
                    { if (it.sessionState.serializedEngineState != null) 0 else 1 },
                    { it.lastAccessedEpochMs },
                    { it.accessSequence },
                ),
            )

        val overflowCount = liveSessions.size - MAX_LIVE_SESSIONS
        if (overflowCount <= 0) return

        candidates.take(overflowCount).forEach { victim ->
            preserveTabMetadataBeforeEviction(victim.id)
            closeAndRemoveSession(victim.id)
        }
    }

    @MainThread
    private fun buildProtectedSessionIds(): Set<String> = buildSet {
        _uiState.value.activeTabId?.let { add(it) }
        attachedTabId?.let { add(it) }
        for ((popupId, slot) in liveSessions) {
            if (slot.slotState == SessionSlotState.PENDING_POPUP_OPEN) {
                add(popupId)
                // Giữ sống cả session cha để mWindow.runtime của Gecko không bị null khi resolveTo hoàn tất trễ
                pendingPopupOpeners[popupId]?.let { openerId -> add(openerId) }
            }
        }
    }

    @MainThread
    private fun pruneOrphanLiveSessions() {
        val uiTabIds = _uiState.value.tabs.mapTo(HashSet()) { it.id }
        val orphanIds = liveSessions.keys.filter { it !in uiTabIds }
        for (orphanId in orphanIds) {
            closeAndRemoveSession(orphanId)
        }
    }

    @MainThread
    private fun preserveTabMetadataBeforeEviction(tabId: String) {
        updateTabSessionState(tabId) { current ->
            val safeUrl = UrlNavigationPolicy.sanitizeRestoredUrl(current.url)
            current.copy(
                url = safeUrl,
                isLoading = false,
                isFullScreen = false,
            )
        }
    }

    /**
     * Đóng và xóa session đúng 1 lần duy nhất; nếu là popup chưa được Gecko mở thì đánh dấu hủy để đóng ngay khi Gecko mở trễ.
     */
    @MainThread
    private fun closeAndRemoveSession(tabId: String) {
        if (attachedTabId == tabId) {
            activeViewHandle?.releaseSession()
            attachedTabId = null
        }
        pendingPopupOpeners.remove(tabId)
        val slot = liveSessions.remove(tabId) ?: return
        if (slot.slotState == SessionSlotState.CLOSED) return

        val wasPendingUnopenedPopup = slot.slotState == SessionSlotState.PENDING_POPUP_OPEN && !slot.handle.isOpen
        slot.slotState = SessionSlotState.CLOSED

        if (wasPendingUnopenedPopup) {
            // Không xóa callback hay gọi open() lúc này; giữ bẫy nhỏ để đóng ngay nếu Gecko gọi session.open() trễ
            cancelledUnopenedPopups.add(slot.handle)
            return
        }

        cancelledUnopenedPopups.remove(slot.handle)
        slot.handle.clearCallbacks()
        if (slot.handle.isOpen) {
            slot.handle.setActive(false)
            slot.handle.setPriorityHigh(false)
            slot.handle.close()
        }
    }

    @MainThread
    private fun dismissDialogsForTab(tabId: String) {
        if (_uiState.value.activeWebPrompt?.tabId == tabId) {
            val prompt = _uiState.value.activeWebPrompt
            _uiState.update { it.copy(activeWebPrompt = null) }
            prompt?.cancel()
        }
        if (_uiState.value.activePermissionPrompt?.tabId == tabId) {
            val permPrompt = _uiState.value.activePermissionPrompt
            _uiState.update { it.copy(activePermissionPrompt = null) }
            permPrompt?.onResult(false, false)
        }
        if (_uiState.value.contextMenu?.tabId == tabId) {
            _uiState.update { it.copy(contextMenu = null) }
        }
        if (_uiState.value.externalAppPrompt?.tabId == tabId) {
            _uiState.update { it.copy(externalAppPrompt = null) }
        }
    }

    @MainThread
    private fun buildConfiguredSession(
        tabId: String,
        engineType: EngineType = EngineType.GECKO,
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
    ): EngineSessionHandle {
        val callbacks = object : EngineSessionCallbacks {
            override fun onPopupSessionOpened(session: EngineSessionHandle) {
                if (session in cancelledUnopenedPopups) {
                    cancelledUnopenedPopups.remove(session)
                    session.clearCallbacks()
                    if (session.isOpen) {
                        session.setActive(false)
                        session.setPriorityHigh(false)
                        session.close()
                    }
                    return
                }
                val slot = liveSessions[tabId] ?: return
                if (slot.handle === session && slot.slotState == SessionSlotState.PENDING_POPUP_OPEN && session.isOpen) {
                    completePopupSessionHandoff(popupTabId = tabId, isPrivate = isPrivate)
                }
            }

            override fun onLocationChange(session: EngineSessionHandle, url: String?) {
                if (!isCurrentSessionForTab(tabId, session)) return
                if (url.isNullOrBlank() || url == ABOUT_BLANK) return
                // Không ghi đè địa chỉ gốc bằng data: URI của trang lỗi nội bộ để nút Tải lại hoạt động đúng
                if (UrlNavigationPolicy.isInternalErrorDataUrl(url)) return

                updateTabSessionState(tabId) { state ->
                    val oldOrigin = UrlNavigationPolicy.extractOrigin(state.url)
                    val newOrigin = UrlNavigationPolicy.extractOrigin(url)
                    val updatedSecurity = if (oldOrigin != newOrigin) {
                        computeInitialSecurityState(url)
                    } else {
                        state.securityState
                    }
                    state.copy(
                        url = url,
                        securityState = updatedSecurity,
                        isCrashed = false,
                    )
                }
                if (!isPrivate) {
                    schedulePersistTabs()
                }
            }

            override fun onCanGoBack(session: EngineSessionHandle, canGoBack: Boolean) {
                if (!isCurrentSessionForTab(tabId, session)) return
                updateTabSessionState(tabId) { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: EngineSessionHandle, canGoForward: Boolean) {
                if (!isCurrentSessionForTab(tabId, session)) return
                updateTabSessionState(tabId) { it.copy(canGoForward = canGoForward) }
            }

            override fun onLoadRequest(
                session: EngineSessionHandle,
                request: EngineLoadRequest,
            ): Boolean {
                if (!isCurrentSessionForTab(tabId, session)) return false
                val allowedInBrowser = UrlNavigationPolicy.evaluateWebLoadRequest(
                    uri = request.uri,
                    triggerUri = request.triggerUri,
                    targetWindow = request.targetWindow,
                    isRedirect = request.isRedirect,
                    hasUserGesture = request.hasUserGesture,
                    isDirectNavigation = request.isDirectNavigation,
                )
                if (allowedInBrowser) {
                    val shield = privacyShieldEngine ?: return true
                    val shieldDecision = shield.evaluateRequest(
                        requestUrl = request.uri,
                        topFrameUrl = request.triggerUri.orEmpty(),
                        resourceType = "document",
                        httpMethod = "GET",
                        config = currentPrivacyPolicy,
                        isOffTheRecord = isPrivate,
                    )
                    if (shieldDecision.isBlocked) {
                        return false
                    }
                    if (shieldDecision.action == NativePipelineAction.REWRITE_CLEAN_URL &&
                        shieldDecision.effectiveUrl.isNotBlank() &&
                        shieldDecision.effectiveUrl != request.uri
                    ) {
                        session.loadUri(shieldDecision.effectiveUrl)
                        return false
                    }
                    if (!request.isRedirect &&
                        shieldDecision.action == NativePipelineAction.UPGRADE_TO_HTTPS &&
                        shieldDecision.effectiveUrl.isNotBlank() &&
                        shieldDecision.effectiveUrl != request.uri
                    ) {
                        session.loadUri(shieldDecision.effectiveUrl)
                        return false
                    }
                    return true
                }

                // Liên kết mở ứng dụng ngoài (mailto:, tel:, geo:, market:) chỉ được gợi ý khi có chạm thật của người dùng
                val externalCandidate = UrlNavigationPolicy.classifyExternalAppUri(
                    uri = request.uri,
                    isRedirect = request.isRedirect,
                    hasUserGesture = request.hasUserGesture,
                )
                if (externalCandidate != null && _uiState.value.activeTabId == tabId) {
                    val (scheme, safeExternalUri) = externalCandidate
                    _uiState.update {
                        it.copy(
                            externalAppPrompt = ExternalAppPrompt(
                                tabId = tabId,
                                scheme = scheme,
                                uri = safeExternalUri,
                            ),
                        )
                    }
                }
                return false
            }

            override fun onNewSession(session: EngineSessionHandle, uri: String): EngineSessionHandle? {
                if (!isCurrentSessionForTab(tabId, session) || !session.isOpen) return null
                val parentTab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return null
                val safePopupUri = UrlNavigationPolicy.sanitizePopupTargetUri(
                    rawUri = uri,
                    parentUrl = parentTab.sessionState.url,
                ) ?: return null

                // Cập nhật dấu thời gian truy cập cho tab cha đang mở popup để chuỗi window.open liên tiếp không thu hồi nhầm tab cha
                touchTabAccess(tabId)
                clearActiveFindInPageInternal()

                val now = clockMs()
                val seq = accessCounter.getAndIncrement()
                val popupTab = Tab(
                    isPrivate = isPrivate,
                    engineType = parentTab.engineType,
                    createdAtEpochMs = now,
                    lastAccessedEpochMs = now,
                    accessSequence = seq,
                    sessionState = TabSessionState(
                        url = safePopupUri,
                        securityState = computeInitialSecurityState(safePopupUri),
                    ),
                )

                // Hợp đồng bắt buộc của GeckoView: session con trả về từ onNewSession PHẢI chưa open()
                val unopenedChild = buildConfiguredSession(
                    tabId = popupTab.id,
                    engineType = parentTab.engineType,
                    isPrivate = isPrivate,
                    isDesktopMode = false,
                    openImmediately = false,
                )
                liveSessions[popupTab.id] = ManagedSessionSlot(
                    tabId = popupTab.id,
                    handle = unopenedChild,
                    slotState = SessionSlotState.PENDING_POPUP_OPEN,
                )
                pendingPopupOpeners[popupTab.id] = tabId

                _uiState.update { current ->
                    current.copy(
                        tabs = current.tabs + popupTab,
                        activeTabId = popupTab.id,
                        isRestoring = false,
                        contextMenu = null,
                    )
                }

                // Kiểm tra ở nhịp UI kế tiếp; nếu engine handoff bị trì hoãn (chưa isOpen), giữ nguyên PENDING_POPUP_OPEN tới khi onPopupSessionOpened báo sẵn sàng
                uiPoster {
                    completePopupSessionHandoff(popupTabId = popupTab.id, isPrivate = isPrivate)
                }
                return unopenedChild
            }

            override fun onLoadError(
                session: EngineSessionHandle,
                uri: String?,
                errorCode: Int,
            ): String? {
                if (!isCurrentSessionForTab(tabId, session)) return null
                val targetUri = uri?.takeIf { it.isNotBlank() && !UrlNavigationPolicy.isInternalErrorDataUrl(it) }
                    ?: _uiState.value.tabs.firstOrNull { it.id == tabId }?.sessionState?.url
                updateTabSessionState(tabId) { it.copy(isLoading = false, hasLoadError = true) }
                return UrlNavigationPolicy.buildSafeErrorPageDataUrl(targetUri, errorCode)
            }

            override fun onPageStart(session: EngineSessionHandle, url: String) {
                if (!isCurrentSessionForTab(tabId, session)) return
                if (url == ABOUT_BLANK || UrlNavigationPolicy.isInternalErrorDataUrl(url)) return
                updateTabSessionState(tabId) { current ->
                    val oldOrigin = UrlNavigationPolicy.extractOrigin(current.url)
                    val newOrigin = UrlNavigationPolicy.extractOrigin(url)
                    val prelimSecurity = if (oldOrigin != newOrigin) {
                        computeInitialSecurityState(url)
                    } else {
                        current.securityState
                    }
                    current.copy(
                        isLoading = true,
                        progress = 5,
                        securityState = prelimSecurity,
                        isCrashed = false,
                        hasLoadError = false,
                        isAtPageTop = true,
                    )
                }
            }

            override fun onProgressChange(session: EngineSessionHandle, progress: Int) {
                if (!isCurrentSessionForTab(tabId, session)) return
                updateTabSessionState(tabId) { it.copy(progress = progress.coerceIn(0, 100)) }
            }

            override fun onPageStop(session: EngineSessionHandle, success: Boolean) {
                if (!isCurrentSessionForTab(tabId, session)) return
                updateTabSessionState(tabId) { it.copy(isLoading = false, progress = 100) }

                if (success) {
                    val finishedTab = _uiState.value.tabs.firstOrNull { it.id == tabId }
                    if (finishedTab != null && !finishedTab.isPrivate && !finishedTab.sessionState.hasLoadError) {
                        mainScope.launch {
                            dataRepository.recordVisitIfRegular(
                                url = finishedTab.sessionState.url,
                                title = finishedTab.sessionState.title,
                                isPrivate = finishedTab.isPrivate,
                            )
                        }
                    }
                }

                if (success && attachedTabId == tabId && isAppInForeground) {
                    mainScope.launch {
                        delay(250)
                        if (attachedTabId == tabId && isAppInForeground) {
                            captureActiveTabPreview()
                        }
                    }
                }
            }

            override fun onSecurityChange(session: EngineSessionHandle, isSecure: Boolean, origin: String?) {
                if (!isCurrentSessionForTab(tabId, session)) return
                val currentUrl = _uiState.value.tabs.firstOrNull { it.id == tabId }?.sessionState?.url
                val security = when {
                    isSecure -> SecurityState.SECURE
                    origin?.startsWith("http:", ignoreCase = true) == true ||
                        currentUrl?.startsWith("http://", ignoreCase = true) == true -> SecurityState.INSECURE
                    else -> SecurityState.LOCAL_OR_UNKNOWN
                }
                updateTabSessionState(tabId) { it.copy(securityState = security) }
            }

            override fun onSessionStateChange(session: EngineSessionHandle, serializedState: String) {
                if (!isCurrentSessionForTab(tabId, session)) return
                if (serializedState.isBlank()) return
                updateTabSessionState(tabId) { it.copy(serializedEngineState = serializedState) }
                if (!isPrivate) {
                    schedulePersistTabs()
                }
            }

            override fun onTitleChange(session: EngineSessionHandle, title: String?) {
                if (!isCurrentSessionForTab(tabId, session)) return
                if (!title.isNullOrBlank()) {
                    val currentTab = _uiState.value.tabs.firstOrNull { it.id == tabId }
                    if (currentTab?.sessionState?.hasLoadError == true) return
                    updateTabSessionState(tabId) { it.copy(title = title) }
                    if (!isPrivate) {
                        schedulePersistTabs()
                    }
                }
            }

            override fun onFullScreen(session: EngineSessionHandle, fullScreen: Boolean) {
                if (!isCurrentSessionForTab(tabId, session)) return
                updateTabSessionState(tabId) { it.copy(isFullScreen = fullScreen) }
            }

            override fun onScrollChanged(session: EngineSessionHandle, scrollX: Int, scrollY: Int) {
                if (!isCurrentSessionForTab(tabId, session)) return
                val atTop = scrollY <= 2
                val currentAtTop = _uiState.value.tabs.firstOrNull { it.id == tabId }?.sessionState?.isAtPageTop
                if (currentAtTop != atTop) {
                    updateTabSessionState(tabId) { it.copy(isAtPageTop = atTop) }
                }
            }

            override fun onContextMenu(
                session: EngineSessionHandle,
                linkUri: String?,
                linkText: String?,
                mediaSrcUri: String?,
                mediaType: Int,
                title: String?,
            ) {
                if (!isCurrentSessionForTab(tabId, session)) return
                if (_uiState.value.activeTabId != tabId) return
                val cleanLink = linkUri?.trim()?.takeIf {
                    UrlNavigationPolicy.isRecordableHttpUrl(it) ||
                        UrlNavigationPolicy.classifyExternalAppUri(it, isRedirect = false, hasUserGesture = true) != null
                }
                val cleanMedia = mediaSrcUri?.trim()?.takeIf { UrlNavigationPolicy.isRecordableHttpUrl(it) }
                if (cleanLink == null && cleanMedia == null) return

                _uiState.update {
                    it.copy(
                        contextMenu = WebContextMenuState(
                            tabId = tabId,
                            isPrivate = isPrivate,
                            linkUri = cleanLink,
                            linkText = linkText?.trim()?.takeIf(String::isNotBlank),
                            mediaSrcUri = cleanMedia,
                            mediaType = mediaType,
                            title = title?.trim()?.takeIf(String::isNotBlank),
                        ),
                    )
                }
            }

            override fun onExternalResponse(session: EngineSessionHandle, payload: ExternalDownloadPayload) {
                if (!isCurrentSessionForTab(tabId, session) || downloadManager == null) {
                    runCatching { payload.bodyStream?.close() }
                    return
                }
                downloadManager.enqueueExternalResponse(payload)
            }

            override fun onWebPrompt(session: EngineSessionHandle, prompt: WebDialogPrompt) {
                if (!isCurrentSessionForTab(tabId, session) || _uiState.value.activeTabId != tabId) {
                    prompt.cancel()
                    return
                }
                val boundPrompt = bindTabIdToPrompt(prompt, tabId)
                val previous = _uiState.value.activeWebPrompt
                _uiState.update { it.copy(activeWebPrompt = boundPrompt) }
                previous?.cancel()
            }

            override fun onContentPermission(
                session: EngineSessionHandle,
                uri: String,
                permissionType: SitePermissionType,
                onDecision: (SitePermissionDecision) -> Unit,
            ) {
                if (!isCurrentSessionForTab(tabId, session) || sitePermissionManager == null) {
                    onDecision(SitePermissionDecision.DENY)
                    return
                }
                val existing = sitePermissionManager.getDecision(uri, permissionType, isPrivate)
                if (existing != SitePermissionDecision.PROMPT) {
                    onDecision(existing)
                    return
                }
                if (_uiState.value.activeTabId != tabId) {
                    onDecision(SitePermissionDecision.DENY)
                    return
                }
                val origin = UrlNavigationPolicy.extractOrigin(uri) ?: run {
                    onDecision(SitePermissionDecision.DENY)
                    return
                }
                enqueuePermissionConsentPrompt(
                    tabId = tabId,
                    origin = origin,
                    isPrivate = isPrivate,
                    types = listOf(permissionType),
                ) { allow, remember ->
                    val resolved = if (allow) SitePermissionDecision.ALLOW else SitePermissionDecision.DENY
                    sitePermissionManager.recordDecision(
                        rawUrlOrOrigin = origin,
                        types = listOf(permissionType),
                        decision = resolved,
                        isPrivate = isPrivate,
                        remember = remember,
                    )
                    onDecision(resolved)
                }
            }

            override fun onMediaPermission(
                session: EngineSessionHandle,
                uri: String,
                needsCamera: Boolean,
                needsMicrophone: Boolean,
                onDecision: (Boolean) -> Unit,
            ) {
                if (!isCurrentSessionForTab(tabId, session) || sitePermissionManager == null) {
                    onDecision(false)
                    return
                }
                val requested = buildList {
                    if (needsCamera) add(SitePermissionType.CAMERA)
                    if (needsMicrophone) add(SitePermissionType.MICROPHONE)
                }
                if (requested.isEmpty()) {
                    onDecision(false)
                    return
                }

                val decisions = requested.map { sitePermissionManager.getDecision(uri, it, isPrivate) }
                if (decisions.any { it == SitePermissionDecision.DENY }) {
                    onDecision(false)
                    return
                }
                if (decisions.all { it == SitePermissionDecision.ALLOW }) {
                    onDecision(true)
                    return
                }
                if (_uiState.value.activeTabId != tabId) {
                    onDecision(false)
                    return
                }
                val origin = UrlNavigationPolicy.extractOrigin(uri) ?: run {
                    onDecision(false)
                    return
                }
                enqueuePermissionConsentPrompt(
                    tabId = tabId,
                    origin = origin,
                    isPrivate = isPrivate,
                    types = requested,
                ) { allow, remember ->
                    val resolved = if (allow) SitePermissionDecision.ALLOW else SitePermissionDecision.DENY
                    sitePermissionManager.recordDecision(
                        rawUrlOrOrigin = origin,
                        types = requested,
                        decision = resolved,
                        isPrivate = isPrivate,
                        remember = remember,
                    )
                    onDecision(allow)
                }
            }

            override fun onAndroidPermissionsRequest(
                session: EngineSessionHandle,
                permissions: List<String>,
                onDecision: (Boolean) -> Unit,
            ) {
                if (!isCurrentSessionForTab(tabId, session) || _uiState.value.activeTabId != tabId) {
                    onDecision(false)
                    return
                }
                val requester = osPermissionRequester
                if (requester == null) {
                    onDecision(false)
                } else {
                    requester(permissions, onDecision)
                }
            }

            override fun onCloseRequest(session: EngineSessionHandle) {
                if (!isCurrentSessionForTab(tabId, session)) return
                closeTab(tabId)
            }

            override fun onKill(session: EngineSessionHandle) {
                if (!isCurrentSessionForTab(tabId, session)) return
                handleContentProcessDeath(tabId = tabId, wasCrash = false)
            }

            override fun onCrash(session: EngineSessionHandle) {
                if (!isCurrentSessionForTab(tabId, session)) return
                handleContentProcessDeath(tabId = tabId, wasCrash = true)
            }
        }

        val multiBridge = engineBridge as? MultiEngineSessionBridge
        return if (multiBridge != null) {
            multiBridge.createSessionForEngine(
                engineType = engineType,
                isPrivate = isPrivate,
                isDesktopMode = isDesktopMode,
                openImmediately = openImmediately,
                callbacks = callbacks,
            )
        } else {
            engineBridge.createSession(
                isPrivate = isPrivate,
                isDesktopMode = isDesktopMode,
                openImmediately = openImmediately,
                callbacks = callbacks,
            )
        }
    }

    @MainThread
    private fun enqueuePermissionConsentPrompt(
        tabId: String,
        origin: String,
        isPrivate: Boolean,
        types: List<SitePermissionType>,
        onResolved: (allow: Boolean, remember: Boolean) -> Unit,
    ) {
        val previous = _uiState.value.activePermissionPrompt
        val next = SitePermissionPrompt(
            id = UUID.randomUUID().toString(),
            tabId = tabId,
            origin = origin,
            isPrivate = isPrivate,
            requestedTypes = types,
            onResult = onResolved,
        )
        _uiState.update { it.copy(activePermissionPrompt = next) }
        previous?.onResult(false, false)
    }

    private fun bindTabIdToPrompt(prompt: WebDialogPrompt, tabId: String): WebDialogPrompt = when (prompt) {
        is WebDialogPrompt.Alert -> prompt.copy(tabId = tabId)
        is WebDialogPrompt.Confirm -> prompt.copy(tabId = tabId)
        is WebDialogPrompt.TextInput -> prompt.copy(tabId = tabId)
        is WebDialogPrompt.ChoiceSelect -> prompt.copy(tabId = tabId)
        is WebDialogPrompt.SlowScript -> prompt.copy(tabId = tabId)
    }

    /**
     * Hoàn tất gắn kết popup an toàn: TUYỆT ĐỐI KHÔNG tự gọi open() sớm khi Gecko chưa chạy xong resolveTo
     * (tránh AssertionError "Must use an unopened GeckoSession instance" khi popup handoff bị trì hoãn).
     */
    @MainThread
    private fun completePopupSessionHandoff(popupTabId: String, isPrivate: Boolean) {
        val slot = liveSessions[popupTabId] ?: run {
            pendingPopupOpeners.remove(popupTabId)
            return
        }
        val popupTab = _uiState.value.tabs.firstOrNull { it.id == popupTabId }
        if (popupTab == null) {
            closeAndRemoveSession(popupTabId)
            return
        }

        // Nếu engine chưa kịp mở child session (handoff bị trễ), tiếp tục giữ PENDING_POPUP_OPEN và đợi onPopupSessionOpened
        if (!slot.handle.isOpen) {
            return
        }

        slot.slotState = SessionSlotState.OPEN
        pendingPopupOpeners.remove(popupTabId)

        syncActiveSessionAttachment()
        evictLeastRecentlyUsedSessionsIfNeeded()
        if (!isPrivate) {
            schedulePersistTabs()
        }
    }

    @MainThread
    private fun handleContentProcessDeath(tabId: String, wasCrash: Boolean) {
        dismissDialogsForTab(tabId)
        preserveTabMetadataBeforeEviction(tabId)
        closeAndRemoveSession(tabId)

        if (wasCrash) {
            updateTabSessionState(tabId) { it.copy(isCrashed = true, isLoading = false, isFullScreen = false) }
        } else {
            updateTabSessionState(tabId) { it.copy(isLoading = false, isFullScreen = false) }
            if (_uiState.value.activeTabId == tabId && isAppInForeground) {
                syncActiveSessionAttachment()
            }
        }
    }

    private fun computeInitialSecurityState(url: String): SecurityState = when {
        url.startsWith("http://", ignoreCase = true) -> SecurityState.INSECURE
        else -> SecurityState.LOCAL_OR_UNKNOWN
    }

    private fun isCurrentSessionForTab(tabId: String, session: EngineSessionHandle): Boolean =
        liveSessions[tabId]?.handle === session

    private fun touchTabAccess(
        tabId: String,
        sessionTransform: ((TabSessionState) -> TabSessionState)? = null,
    ) {
        val now = clockMs()
        val seq = accessCounter.getAndIncrement()
        updateTab(tabId) { tab ->
            tab.copy(
                lastAccessedEpochMs = now,
                accessSequence = seq,
                sessionState = sessionTransform?.invoke(tab.sessionState) ?: tab.sessionState,
            )
        }
    }

    private fun updateTab(tabId: String, transform: (Tab) -> Tab) {
        _uiState.update { state ->
            state.copy(tabs = state.tabs.map { if (it.id == tabId) transform(it) else it })
        }
    }

    private fun updateTabSessionState(tabId: String, transform: (TabSessionState) -> TabSessionState) {
        updateTab(tabId) { tab -> tab.copy(sessionState = transform(tab.sessionState)) }
    }

    private fun schedulePersistTabs(immediate: Boolean = false) {
        val targetRev = requestedPersistRevision.incrementAndGet()
        debouncedPersistJob?.cancel()

        if (immediate) {
            mainScope.launch(NonCancellable) {
                flushLatestSnapshotUnderLock(targetRev)
            }
        } else {
            debouncedPersistJob = mainScope.launch {
                delay(PERSIST_DEBOUNCE_MS)
                flushLatestSnapshotUnderLock(targetRev)
            }
        }
    }

    private suspend fun flushLatestSnapshotUnderLock(minRevision: Long) {
        persistMutex.withLock {
            val latestRev = requestedPersistRevision.get()
            if (completedPersistRevision >= latestRev && completedPersistRevision >= minRevision) {
                return
            }
            val snapshot = _uiState.value
            if (snapshot.isRestoring && snapshot.tabs.isEmpty()) {
                return
            }
            withContext(ioDispatcher) {
                val encoded = SessionPersistenceCodec.encodeRegularTabs(snapshot)
                sessionStorage.writePersistedRaw(
                    tabsJson = encoded.tabsJson,
                    activeRegularTabId = encoded.activeRegularTabId,
                )
            }
            completedPersistRevision = latestRev
        }
    }

    private fun checkMainThread(methodName: String) {
        check(isOnMainThread()) {
            "TabManager.$methodName() bắt buộc gọi trên Android Main/UI Thread."
        }
    }

    companion object {
        const val ABOUT_BLANK = "about:blank"
        const val PREVIEW_TARGET_WIDTH_PX = 360
        const val MAX_LIVE_SESSIONS = 6
        const val PERSIST_DEBOUNCE_MS = 600L
        private val DEFAULT_FOREGROUND_TOKEN = Any()
    }
}
