package io.veko.browser.tab

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.compose.ui.graphics.asImageBitmap
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.veko.browser.VekoApplication
import io.veko.browser.data.repository.BrowserDataRepository
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SettingsRepository
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
import org.mozilla.geckoview.GeckoView
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

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
 * 2) Duy trì trần cứng MAX_LIVE_SESSIONS (kể cả khi mở tab dồn dập lúc chưa có serializedEngineState).
 * 3) Mọi tác vụ lưu trữ chạy tuần tự qua Mutex trên IO thread; tab riêng tư bị chặn 100% khỏi đĩa.
 */
@Singleton
class TabManager internal constructor(
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
) {
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        engineBridge: EngineSessionBridge,
        dataRepository: BrowserDataRepository,
        settingsRepository: SettingsRepository,
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
    ) {
        (appContext as? VekoApplication)?.markTabManagerReady()
    }

    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val accessCounter = AtomicLong(1L)

    private val _uiState = MutableStateFlow(BrowserTabsUiState())
    val uiState: StateFlow<BrowserTabsUiState> = _uiState.asStateFlow()

    @Volatile
    private var currentSearchEngine: SearchEngine = SearchEngine.DUCKDUCKGO

    // Chỉ chứa các session chưa đóng (PENDING_POPUP_OPEN hoặc OPEN)
    private val liveSessions = LinkedHashMap<String, ManagedSessionSlot>()

    // Bọc bề mặt hiển thị bằng EngineViewHandle (dùng WeakReference nội bộ để không leak Activity/View)
    private var activeViewHandle: EngineViewHandle? = null
    private var attachedTabId: String? = null

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
        engineBridge.setOnRuntimeShutdownListener {
            if (isOnMainThread()) {
                onRuntimeShutdown()
            } else {
                uiPoster { onRuntimeShutdown() }
            }
        }
        mainScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSearchEngine = settings.searchEngine
            }
        }
    }

    internal fun liveSessionCount(): Int = liveSessions.size

    internal fun openSessionIds(): Set<String> =
        liveSessions.values
            .filter { it.slotState == SessionSlotState.OPEN && it.handle.isOpen }
            .mapTo(LinkedHashSet()) { it.tabId }

    internal fun pendingPopupTabIds(): Set<String> =
        liveSessions.values
            .filter { it.slotState == SessionSlotState.PENDING_POPUP_OPEN }
            .mapTo(LinkedHashSet()) { it.tabId }

    internal fun attachedSessionTabId(): String? = attachedTabId

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
            val mergedRestored = decoded.tabs.filterNot { it.id in concurrentIds }
            val combinedTabs = mergedRestored + concurrentTabs

            if (combinedTabs.isNotEmpty()) {
                val chosenActiveId = _uiState.value.activeTabId
                    ?.takeIf { id -> combinedTabs.any { it.id == id } }
                    ?: decoded.activeTabId?.takeIf { id -> combinedTabs.any { it.id == id } }
                    ?: combinedTabs.first().id

                _uiState.value = BrowserTabsUiState(
                    tabs = combinedTabs,
                    activeTabId = chosenActiveId,
                    isRestoring = false,
                )

                if (initialExternalUrl != null) {
                    createTab(url = initialExternalUrl, isPrivate = false, select = true)
                } else {
                    syncActiveSessionAttachment()
                    evictLeastRecentlyUsedSessionsIfNeeded()
                }
            } else {
                _uiState.update { it.copy(isRestoring = false) }
                createTab(
                    url = initialExternalUrl ?: currentSearchEngine.homeUrl,
                    isPrivate = false,
                    select = true,
                )
            }
        }
    }

    /**
     * Tạo tab mới và tùy chọn chuyển ngay sang tab đó.
     */
    @MainThread
    fun createTab(
        url: String = TabSessionState.DEFAULT_HOME_URL,
        isPrivate: Boolean = false,
        select: Boolean = true,
    ): String {
        checkMainThread("createTab")
        val normalizedUrl = UrlNavigationPolicy.normalizeUserInput(url, currentSearchEngine)
        val now = clockMs()
        val seq = accessCounter.getAndIncrement()
        val newTab = Tab(
            isPrivate = isPrivate,
            createdAtEpochMs = now,
            lastAccessedEpochMs = now,
            accessSequence = seq,
            sessionState = TabSessionState(url = normalizedUrl),
        )

        val handle = buildConfiguredSession(
            tabId = newTab.id,
            isPrivate = isPrivate,
            isDesktopMode = false,
            openImmediately = true,
        )
        liveSessions[newTab.id] = ManagedSessionSlot(
            tabId = newTab.id,
            handle = handle,
            slotState = SessionSlotState.OPEN,
        )
        handle.loadUri(normalizedUrl)

        val becameActive = select || _uiState.value.activeTabId == null
        _uiState.update { current ->
            current.copy(
                tabs = current.tabs + newTab,
                activeTabId = if (becameActive) newTab.id else current.activeTabId,
                isRestoring = false,
            )
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

        val previousTab = current.activeTab
        _uiState.update { state ->
            state.copy(
                activeTabId = tabId,
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
     * Đóng một tab: tháo khỏi GeckoView trước (nếu đang gắn), đóng session đúng 1 lần và chọn tab kế cận an toàn.
     */
    @MainThread
    fun closeTab(tabId: String) {
        checkMainThread("closeTab")
        val state = _uiState.value
        val closingIdx = state.tabs.indexOfFirst { it.id == tabId }
        if (closingIdx == -1) return
        val closingTab = state.tabs[closingIdx]

        // Bắt buộc tháo và đóng session trước khi cập nhật phiên kế tiếp
        closeAndRemoveSession(tabId)

        val remaining = state.tabs.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            _uiState.value = BrowserTabsUiState(tabs = emptyList(), activeTabId = null, isRestoring = false)
            createTab(url = currentSearchEngine.homeUrl, isPrivate = false, select = true)
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

        _uiState.value = state.copy(
            tabs = remaining.map { tab ->
                if (tab.id == nextActiveId && state.activeTabId == tabId) {
                    tab.copy(lastAccessedEpochMs = now, accessSequence = seq)
                } else {
                    tab
                }
            },
            activeTabId = nextActiveId,
        )
        syncActiveSessionAttachment()
        if (!closingTab.isPrivate) {
            schedulePersistTabs(immediate = true)
        }
    }

    /**
     * Gắn thực thể GeckoView duy nhất từ AndroidView trong Compose vào bộ điều phối.
     */
    @MainThread
    fun attachGeckoView(geckoView: GeckoView) {
        attachEngineView(engineBridge.wrapGeckoView(geckoView))
    }

    @MainThread
    internal fun attachEngineView(viewHandle: EngineViewHandle) {
        checkMainThread("attachGeckoView")
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
     * Tháo GeckoView khi Composable bị hủy để không giữ tham chiếu đến View/Activity cũ.
     */
    @MainThread
    fun detachGeckoView(geckoView: GeckoView) {
        detachEngineViewByIdentity(geckoView)
    }

    @MainThread
    internal fun detachEngineViewByIdentity(viewIdentity: Any) {
        checkMainThread("detachGeckoView")
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

    /**
     * Khi LifecycleOwner lên ON_START: ghi nhận token để tránh bị ON_STOP của Activity cũ ghi đè.
     */
    @MainThread
    fun onAppForeground(ownerToken: Any = DEFAULT_FOREGROUND_TOKEN) {
        checkMainThread("onAppForeground")
        foregroundOwnerTokens.add(ownerToken)
        syncActiveSessionAttachment()
    }

    /**
     * Khi LifecycleOwner xuống ON_STOP: chỉ detach và hạ ưu tiên khi không còn Activity nào hiển thị.
     */
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

    /**
     * Chụp ảnh thu nhỏ của tab đang mở trước khi người dùng bật khay chuyển tab (Tab Switcher).
     */
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
        touchTabAccess(activeTab.id) { state ->
            state.copy(url = targetUrl, isCrashed = false)
        }
        val refreshedTab = _uiState.value.activeTab ?: return
        val session = getOrMaterializeSession(refreshedTab, autoLoadOnCreate = false)
        session.loadUri(targetUrl)
        syncActiveSessionAttachment()
        if (!refreshedTab.isPrivate) {
            schedulePersistTabs()
        }
    }

    @MainThread
    fun goBack() {
        checkMainThread("goBack")
        val activeTab = _uiState.value.activeTab ?: return
        if (activeTab.sessionState.canGoBack) {
            touchTabAccess(activeTab.id)
            liveSessions[activeTab.id]?.takeIf { it.slotState == SessionSlotState.OPEN }?.handle?.goBack()
        }
    }

    @MainThread
    fun goForward() {
        checkMainThread("goForward")
        val activeTab = _uiState.value.activeTab ?: return
        if (activeTab.sessionState.canGoForward) {
            touchTabAccess(activeTab.id)
            liveSessions[activeTab.id]?.takeIf { it.slotState == SessionSlotState.OPEN }?.handle?.goForward()
        }
    }

    @MainThread
    fun reloadOrStop() {
        checkMainThread("reloadOrStop")
        val activeTab = _uiState.value.activeTab ?: return
        val hadLiveOpenSession = liveSessions[activeTab.id]?.let {
            it.slotState == SessionSlotState.OPEN && it.handle.isOpen
        } == true
        val wasCrashed = activeTab.sessionState.isCrashed

        touchTabAccess(activeTab.id) { state ->
            if (state.isCrashed) state.copy(isCrashed = false) else state
        }
        val updatedTab = _uiState.value.activeTab ?: return
        val session = getOrMaterializeSession(updatedTab, autoLoadOnCreate = true)

        if (wasCrashed || !hadLiveOpenSession) {
            // getOrMaterializeSession đã khôi phục SessionState hoặc nạp lại URL; chỉ cần gắn lại vào GeckoView
            syncActiveSessionAttachment()
        } else if (updatedTab.sessionState.isLoading) {
            session.stop()
        } else {
            session.reload()
        }
    }

    @MainThread
    fun toggleDesktopMode() {
        checkMainThread("toggleDesktopMode")
        val activeTab = _uiState.value.activeTab ?: return
        val nextDesktop = !activeTab.sessionState.isDesktopMode
        touchTabAccess(activeTab.id) { it.copy(isDesktopMode = nextDesktop, isCrashed = false) }
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
    }

    /**
     * Khi toàn bộ GeckoRuntime dừng bất ngờ, tháo GeckoView và dọn toàn bộ tham chiếu session chết.
     */
    @MainThread
    internal fun onRuntimeShutdown() {
        checkMainThread("onRuntimeShutdown")
        activeViewHandle?.releaseSession()
        attachedTabId = null

        val previouslyLiveIds = liveSessions.keys.toList()
        for (tabId in previouslyLiveIds) {
            val slot = liveSessions.remove(tabId) ?: continue
            slot.slotState = SessionSlotState.CLOSED
            slot.handle.clearCallbacks()
        }

        if (previouslyLiveIds.isNotEmpty()) {
            val affected = previouslyLiveIds.toSet()
            _uiState.update { state ->
                state.copy(
                    tabs = state.tabs.map { tab ->
                        if (tab.id in affected) {
                            tab.copy(
                                sessionState = tab.sessionState.copy(
                                    isLoading = false,
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
        // Nếu attachedTabId trỏ tới session không còn tồn tại trong liveSessions thì dọn ngay lập tức
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

        val targetSession = getOrMaterializeSession(activeTab, autoLoadOnCreate = true)
        if (!targetSession.isOpen) return

        targetSession.setActive(true)
        targetSession.setPriorityHigh(true)
        view.setSession(targetSession)
        attachedTabId = activeTab.id
    }

    /**
     * Khởi tạo hoặc khôi phục lười EngineSessionHandle; tự động fallback về nạp URL an toàn nếu SessionState hỏng.
     */
    @MainThread
    internal fun getOrMaterializeSession(
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
            // SessionState bị hỏng hoặc không tương thích phiên bản: xóa chuỗi lỗi để lần sau không lặp lại
            updateTabSessionState(tab.id) { it.copy(serializedEngineState = null) }
        }
        val fallbackUrl = UrlNavigationPolicy.sanitizeRestoredUrl(tab.sessionState.url)
        if (fallbackUrl != tab.sessionState.url) {
            updateTabSessionState(tab.id) { it.copy(url = fallbackUrl) }
        }
        handle.loadUri(fallbackUrl)
    }

    /**
     * Giới hạn nghiêm ngặt tối đa MAX_LIVE_SESSIONS phiên mở đồng thời theo LRU hai tầng:
     * - Ưu tiên thu hồi tab nền đã có serializedEngineState trước.
     * - Nếu vẫn vượt trần (mở nhanh nhiều tab khi chưa kịp có serializedEngineState), thu hồi tiếp tab nền cũ nhất
     *   nhưng bảo toàn đầy đủ URL/title/isDesktopMode trên UI để nạp lại liền mạch khi người dùng quay lại.
     */
    @MainThread
    internal fun evictLeastRecentlyUsedSessionsIfNeeded(extraProtectedIds: Set<String> = emptySet()) {
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
        for ((id, slot) in liveSessions) {
            if (slot.slotState == SessionSlotState.PENDING_POPUP_OPEN) {
                add(id)
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
            )
        }
    }

    /**
     * Đóng và xóa session đúng 1 lần duy nhất; tự động tháo khỏi GeckoView trước nếu đang được gắn.
     */
    @MainThread
    private fun closeAndRemoveSession(tabId: String) {
        if (attachedTabId == tabId) {
            activeViewHandle?.releaseSession()
            attachedTabId = null
        }
        val slot = liveSessions.remove(tabId) ?: return
        if (slot.slotState == SessionSlotState.CLOSED) return
        slot.slotState = SessionSlotState.CLOSED
        slot.handle.clearCallbacks()
        if (slot.handle.isOpen) {
            slot.handle.setActive(false)
            slot.handle.setPriorityHigh(false)
            slot.handle.close()
        }
    }

    @MainThread
    private fun buildConfiguredSession(
        tabId: String,
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
    ): EngineSessionHandle {
        val callbacks = object : EngineSessionCallbacks {
            override fun onLocationChange(session: EngineSessionHandle, url: String?) {
                if (!isCurrentSessionForTab(tabId, session)) return
                if (url.isNullOrBlank() || url == ABOUT_BLANK) return
                // Không ghi đè địa chỉ gốc bằng data: URI của trang lỗi nội bộ để nút Tải lại hoạt động đúng
                if (UrlNavigationPolicy.isInternalErrorDataUrl(url)) return

                updateTabSessionState(tabId) { state ->
                    state.copy(url = url, isCrashed = false)
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
                return UrlNavigationPolicy.evaluateWebLoadRequest(
                    uri = request.uri,
                    triggerUri = request.triggerUri,
                    targetWindow = request.targetWindow,
                    isRedirect = request.isRedirect,
                    hasUserGesture = request.hasUserGesture,
                    isDirectNavigation = request.isDirectNavigation,
                )
            }

            override fun onNewSession(session: EngineSessionHandle, uri: String): EngineSessionHandle? {
                if (!isCurrentSessionForTab(tabId, session) || !session.isOpen) return null
                val parentTab = _uiState.value.tabs.firstOrNull { it.id == tabId } ?: return null
                val safePopupUri = UrlNavigationPolicy.sanitizePopupTargetUri(
                    rawUri = uri,
                    parentUrl = parentTab.sessionState.url,
                ) ?: return null

                val now = clockMs()
                val seq = accessCounter.getAndIncrement()
                val popupTab = Tab(
                    isPrivate = isPrivate,
                    createdAtEpochMs = now,
                    lastAccessedEpochMs = now,
                    accessSequence = seq,
                    sessionState = TabSessionState(url = safePopupUri),
                )

                // Hợp đồng bắt buộc của GeckoView: session con trả về từ onNewSession PHẢI chưa open()
                val unopenedChild = buildConfiguredSession(
                    tabId = popupTab.id,
                    isPrivate = isPrivate,
                    isDesktopMode = false,
                    openImmediately = false,
                )
                liveSessions[popupTab.id] = ManagedSessionSlot(
                    tabId = popupTab.id,
                    handle = unopenedChild,
                    slotState = SessionSlotState.PENDING_POPUP_OPEN,
                )

                _uiState.update { current ->
                    current.copy(
                        tabs = current.tabs + popupTab,
                        activeTabId = popupTab.id,
                        isRestoring = false,
                    )
                }

                // Hoàn tất gắn GeckoView & thu hồi LRU ở nhịp UI kế tiếp sau khi GeckoView đã mở child session trên runtime cha
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
                return UrlNavigationPolicy.buildSafeErrorPageDataUrl(targetUri, errorCode)
            }

            override fun onPageStart(session: EngineSessionHandle, url: String) {
                if (!isCurrentSessionForTab(tabId, session)) return
                if (url == ABOUT_BLANK || UrlNavigationPolicy.isInternalErrorDataUrl(url)) return
                updateTabSessionState(tabId) { it.copy(isLoading = true, progress = 5, isCrashed = false) }
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
                    if (finishedTab != null && !finishedTab.isPrivate) {
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
                val security = when {
                    isSecure -> SecurityState.SECURE
                    origin?.startsWith("http:", ignoreCase = true) == true -> SecurityState.INSECURE
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
                    updateTabSessionState(tabId) { it.copy(title = title) }
                    if (!isPrivate) {
                        schedulePersistTabs()
                    }
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

        return engineBridge.createSession(
            isPrivate = isPrivate,
            isDesktopMode = isDesktopMode,
            openImmediately = openImmediately,
            callbacks = callbacks,
        )
    }

    @MainThread
    private fun completePopupSessionHandoff(popupTabId: String, isPrivate: Boolean) {
        val slot = liveSessions[popupTabId] ?: return
        val popupTab = _uiState.value.tabs.firstOrNull { it.id == popupTabId }
        if (popupTab == null) {
            // Người dùng đã đóng tab popup ngay trước khi nhịp UI kế tiếp chạy
            closeAndRemoveSession(popupTabId)
            return
        }

        slot.slotState = SessionSlotState.OPEN
        if (!slot.handle.isOpen) {
            slot.handle.open()
            if (popupTab.sessionState.url != ABOUT_BLANK) {
                slot.handle.loadUri(popupTab.sessionState.url)
            }
        }

        syncActiveSessionAttachment()
        evictLeastRecentlyUsedSessionsIfNeeded()
        if (!isPrivate) {
            schedulePersistTabs()
        }
    }

    /**
     * Xử lý an toàn khi tiến trình nội dung (:tab) bị OS thu hồi hoặc gặp crash, không bao giờ để attachedTabId treo.
     */
    @MainThread
    private fun handleContentProcessDeath(tabId: String, wasCrash: Boolean) {
        preserveTabMetadataBeforeEviction(tabId)
        closeAndRemoveSession(tabId)

        if (wasCrash) {
            updateTabSessionState(tabId) { it.copy(isCrashed = true, isLoading = false) }
        } else {
            updateTabSessionState(tabId) { it.copy(isLoading = false) }
            if (_uiState.value.activeTabId == tabId && isAppInForeground) {
                syncActiveSessionAttachment()
            }
        }
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

    /**
     * Lưu bất đồng bộ tuần tự qua Mutex: lệnh lưu khẩn cấp (immediate) không bao giờ bị hủy bởi lệnh debounce sau đó.
     */
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
            // Nếu đang khôi phục dở dang và chưa có tab nào thì không ghi đè rỗng lên dữ liệu cũ trên đĩa
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

    internal companion object {
        const val ABOUT_BLANK = "about:blank"
        const val PREVIEW_TARGET_WIDTH_PX = 360
        const val MAX_LIVE_SESSIONS = 6
        const val PERSIST_DEBOUNCE_MS = 600L
        private val DEFAULT_FOREGROUND_TOKEN = Any()
    }
}
