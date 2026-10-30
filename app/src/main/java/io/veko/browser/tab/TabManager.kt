package io.veko.browser.tab

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Patterns
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
import io.veko.browser.engine.BrowserRuntimeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

// DataStore nhẹ lưu danh sách tab thường (không bao giờ lưu tab riêng tư xuống ổ đĩa)
private val Context.vekoSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "veko_tabs_session_store",
)

/**
 * Điều phối vòng đời Tab <-> GeckoSession <-> GeckoView theo quy tắc nghiêm ngặt:
 * 1) Chỉ tab đang hiển thị ở foreground mới được attach vào GeckoView và bật setActive(true).
 * 2) Mọi lệnh gọi GeckoSession chạy trên MainThread; mọi tác vụ lưu trữ/nén ảnh chạy trên IO/Default.
 * 3) Tab khôi phục từ ổ đĩa khởi tạo lười (lazy) GeckoSession chỉ khi người dùng thực sự chọn tab đó.
 */
@Singleton
class TabManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val runtimeManager: BrowserRuntimeManager,
    private val dataRepository: BrowserDataRepository,
    private val settingsRepository: SettingsRepository,
) {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _uiState = MutableStateFlow(BrowserTabsUiState())
    val uiState: StateFlow<BrowserTabsUiState> = _uiState.asStateFlow()

    @Volatile
    private var currentSearchEngine: SearchEngine = SearchEngine.DUCKDUCKGO

    // Ánh xạ tabId -> GeckoSession đang sống trong RAM (tách biệt khỏi data class Tab của UI)
    private val liveSessions = LinkedHashMap<String, GeckoSession>()

    // Dùng WeakReference giữ GeckoView duy nhất để tránh leak View khi Activity cấu hình lại
    private var geckoViewRef: WeakReference<GeckoView>? = null
    private var attachedTabId: String? = null
    private var isAppInForeground: Boolean = false
    private var hasBootstrapped: Boolean = false
    private var persistJob: Job? = null

    init {
        (appContext as? VekoApplication)?.markTabManagerReady()
        mainScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                currentSearchEngine = settings.searchEngine
            }
        }
    }

    /**
     * Khôi phục phiên làm việc trước từ DataStore (hoặc mở tab mặc định) ngay lần đầu màn hình khởi chạy.
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
            val restored = withContext(Dispatchers.IO) { readPersistedTabsFromDisk() }
            if (restored.first.isNotEmpty()) {
                val targetActiveId = restored.second
                    ?.takeIf { id -> restored.first.any { it.id == id } }
                    ?: restored.first.first().id
                _uiState.value = BrowserTabsUiState(
                    tabs = restored.first,
                    activeTabId = targetActiveId,
                    isRestoring = false,
                )
                // Chỉ khởi tạo GeckoSession thực cho duy nhất tab đang active, các tab khác giữ ở dạng ngủ đông
                if (initialExternalUrl != null) {
                    createTab(url = initialExternalUrl, isPrivate = false, select = true)
                } else {
                    syncActiveSessionAttachment()
                }
            } else {
                _uiState.update { it.copy(isRestoring = false) }
                createTab(
                    url = initialExternalUrl ?: TabSessionState.DEFAULT_HOME_URL,
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
        val normalizedUrl = normalizeInputToUrl(url)
        val newTab = Tab(
            isPrivate = isPrivate,
            sessionState = TabSessionState(url = normalizedUrl),
        )

        val session = buildConfiguredSession(tabId = newTab.id, isPrivate = isPrivate, openImmediately = true)
        liveSessions[newTab.id] = session
        session.loadUri(normalizedUrl)

        _uiState.update { current ->
            current.copy(
                tabs = current.tabs + newTab,
                activeTabId = if (select || current.activeTabId == null) newTab.id else current.activeTabId,
            )
        }

        if (select) {
            syncActiveSessionAttachment()
        } else {
            // Tab mở ngầm phải hạ ưu tiên ngay lập tức để không tranh chấp CPU với tab chính
            session.setActive(false)
            session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
        }

        evictLeastRecentlyUsedSessionsIfNeeded()
        schedulePersistTabs()
        return newTab.id
    }

    /**
     * Chuyển sang tab có sẵn theo ID (gọi khi chạm trong Tab Switcher hoặc vuốt thanh địa chỉ).
     */
    @MainThread
    fun selectTab(tabId: String) {
        checkMainThread("selectTab")
        val current = _uiState.value
        if (current.activeTabId == tabId || current.tabs.none { it.id == tabId }) return

        val now = System.currentTimeMillis()
        _uiState.update { state ->
            state.copy(
                activeTabId = tabId,
                tabs = state.tabs.map { tab ->
                    if (tab.id == tabId) tab.copy(lastAccessedEpochMs = now) else tab
                },
            )
        }
        syncActiveSessionAttachment()
        schedulePersistTabs()
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
     * Đóng một tab: giải phóng khỏi GeckoView nếu đang gắn, đóng GeckoSession gốc để trả RAM native.
     */
    @MainThread
    fun closeTab(tabId: String) {
        checkMainThread("closeTab")
        val state = _uiState.value
        val closingIdx = state.tabs.indexOfFirst { it.id == tabId }
        if (closingIdx == -1) return

        val remaining = state.tabs.filterNot { it.id == tabId }
        val nextActiveId = when {
            state.activeTabId != tabId -> state.activeTabId
            remaining.isEmpty() -> null
            else -> remaining[closingIdx.coerceAtMost(remaining.lastIndex)].id
        }

        // Nếu tab bị đóng đang gắn vào GeckoView, bắt buộc releaseSession() trước khi session.close()
        if (attachedTabId == tabId) {
            geckoViewRef?.get()?.releaseSession()
            attachedTabId = null
        }

        liveSessions.remove(tabId)?.let { session ->
            session.setActive(false)
            session.close()
        }

        if (remaining.isEmpty()) {
            _uiState.value = BrowserTabsUiState(tabs = emptyList(), activeTabId = null, isRestoring = false)
            createTab(url = TabSessionState.DEFAULT_HOME_URL, isPrivate = false, select = true)
        } else {
            _uiState.value = state.copy(tabs = remaining, activeTabId = nextActiveId)
            syncActiveSessionAttachment()
            schedulePersistTabs()
        }
    }

    /**
     * Gắn thực thể GeckoView duy nhất từ AndroidView trong Compose vào bộ điều phối.
     */
    @MainThread
    fun attachGeckoView(geckoView: GeckoView) {
        checkMainThread("attachGeckoView")
        val previousView = geckoViewRef?.get()
        if (previousView !== null && previousView !== geckoView) {
            previousView.releaseSession()
            attachedTabId = null
        }
        geckoViewRef = WeakReference(geckoView)
        syncActiveSessionAttachment()
    }

    /**
     * Tháo GeckoView khi Composable bị hủy để không giữ chặt SurfaceView/TextureView.
     */
    @MainThread
    fun detachGeckoView(geckoView: GeckoView) {
        checkMainThread("detachGeckoView")
        if (geckoViewRef?.get() === geckoView) {
            geckoView.releaseSession()
            geckoViewRef = null
            attachedTabId = null
        }
    }

    /**
     * Khi app lên foreground (ON_START): gắn lại session của tab đang chọn vào GeckoView.
     */
    @MainThread
    fun onAppForeground() {
        checkMainThread("onAppForeground")
        isAppInForeground = true
        syncActiveSessionAttachment()
    }

    /**
     * Khi app xuống background (ON_STOP): chụp preview, detach session và gọi setActive(false) để tiết kiệm pin/RAM.
     */
    @MainThread
    fun onAppBackground() {
        checkMainThread("onAppBackground")
        captureActiveTabPreview()
        isAppInForeground = false
        geckoViewRef?.get()?.releaseSession()
        attachedTabId?.let { activeId ->
            liveSessions[activeId]?.let { session ->
                session.setActive(false)
                session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
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
        val view = geckoViewRef?.get() ?: return
        val tabId = attachedTabId ?: return
        if (view.width <= 0 || view.height <= 0) return

        val captureResult = try {
            view.capturePixels()
        } catch (_: IllegalStateException) {
            // Surface chưa sẵn sàng vẽ khung hình đầu tiên, bỏ qua an toàn
            return
        }

        captureResult.withHandler(mainHandler).accept(
            { rawBitmap ->
                if (rawBitmap == null) return@accept
                mainScope.launch {
                    // Hạ độ phân giải ảnh chụp trên luồng Default để giảm ~16 lần RAM mỗi thẻ preview
                    val scaledImage = withContext(Dispatchers.Default) {
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
            },
            { /* Bỏ qua lỗi chụp khung hình nếu người dùng chuyển trang quá nhanh */ },
        )
    }

    @MainThread
    fun loadUrlOrSearch(rawInput: String) {
        checkMainThread("loadUrlOrSearch")
        val activeTab = _uiState.value.activeTab ?: return
        val targetUrl = normalizeInputToUrl(rawInput)
        val session = getOrMaterializeSession(activeTab)
        if (activeTab.sessionState.isCrashed) {
            updateTabSessionState(activeTab.id) { it.copy(isCrashed = false) }
        }
        session.loadUri(targetUrl)
    }

    @MainThread
    fun goBack() {
        checkMainThread("goBack")
        val activeTab = _uiState.value.activeTab ?: return
        if (activeTab.sessionState.canGoBack) {
            liveSessions[activeTab.id]?.goBack()
        }
    }

    @MainThread
    fun goForward() {
        checkMainThread("goForward")
        val activeTab = _uiState.value.activeTab ?: return
        if (activeTab.sessionState.canGoForward) {
            liveSessions[activeTab.id]?.goForward()
        }
    }

    @MainThread
    fun reloadOrStop() {
        checkMainThread("reloadOrStop")
        val activeTab = _uiState.value.activeTab ?: return
        val session = getOrMaterializeSession(activeTab)
        if (activeTab.sessionState.isLoading) {
            session.stop()
        } else {
            session.reload()
        }
    }

    @MainThread
    fun toggleDesktopMode() {
        checkMainThread("toggleDesktopMode")
        val activeTab = _uiState.value.activeTab ?: return
        val session = getOrMaterializeSession(activeTab)
        val nextDesktop = !activeTab.sessionState.isDesktopMode
        session.settings.userAgentMode = if (nextDesktop) {
            GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        }
        session.settings.viewportMode = if (nextDesktop) {
            GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
        } else {
            GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        }
        updateTabSessionState(activeTab.id) { it.copy(isDesktopMode = nextDesktop) }
        session.reload()
    }

    /**
     * Khi hệ điều hành báo thiếu bộ nhớ, đóng bớt các GeckoSession nền (giữ lại SessionState chuỗi để khôi phục).
     */
    @MainThread
    fun onTrimMemory(level: Int) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onTrimMemory(level) }
            return
        }
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        ) {
            return
        }
        val activeId = _uiState.value.activeTabId
        val backgroundIds = liveSessions.keys.filter { it != activeId }
        for (bgId in backgroundIds) {
            liveSessions.remove(bgId)?.let { session ->
                session.setActive(false)
                session.close()
            }
            // Xóa ảnh bitmap preview của tab nền khi thiếu RAM trầm trọng
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                updateTab(bgId) { it.copy(previewBitmap = null) }
            }
        }
    }

    /**
     * Trái tim của cơ chế "Single Active Session": tháo session cũ, kích hoạt và gắn đúng 1 session mới.
     */
    @MainThread
    private fun syncActiveSessionAttachment() {
        val view = geckoViewRef?.get() ?: return
        val activeTab = _uiState.value.activeTab
        if (!isAppInForeground || activeTab == null) {
            if (attachedTabId != null) {
                view.releaseSession()
                attachedTabId?.let { oldId ->
                    liveSessions[oldId]?.let { oldSession ->
                        oldSession.setActive(false)
                        oldSession.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
                    }
                }
                attachedTabId = null
            }
            return
        }

        if (attachedTabId == activeTab.id && liveSessions.containsKey(activeTab.id)) {
            liveSessions[activeTab.id]?.let { session ->
                session.setActive(true)
                session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            }
            return
        }

        // Bước 1: Tháo và hạ ưu tiên session cũ trước khi gắn session mới vào GeckoView
        if (attachedTabId != null && attachedTabId != activeTab.id) {
            view.releaseSession()
            liveSessions[attachedTabId]?.let { previousSession ->
                previousSession.setActive(false)
                previousSession.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
            }
            attachedTabId = null
        }

        // Bước 2: Khởi tạo hoặc đánh thức session của tab đang chọn rồi gắn vào GeckoView
        val targetSession = getOrMaterializeSession(activeTab)
        targetSession.setActive(true)
        targetSession.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        view.setSession(targetSession)
        attachedTabId = activeTab.id
    }

    /**
     * Trả về GeckoSession đang sống hoặc khôi phục lười từ chuỗi SessionState đã lưu.
     */
    @MainThread
    private fun getOrMaterializeSession(tab: Tab): GeckoSession {
        liveSessions[tab.id]?.let { existing ->
            if (!existing.isOpen) {
                existing.open(runtimeManager.getOrCreateRuntime())
            }
            return existing
        }

        val session = buildConfiguredSession(
            tabId = tab.id,
            isPrivate = tab.isPrivate,
            openImmediately = true,
        )
        liveSessions[tab.id] = session

        val restoredState = tab.sessionState.serializedEngineState?.let { encoded ->
            runCatching { GeckoSession.SessionState.fromString(encoded) }.getOrNull()
        }
        if (restoredState != null) {
            session.restoreState(restoredState)
        } else {
            session.loadUri(tab.sessionState.url)
        }

        evictLeastRecentlyUsedSessionsIfNeeded()
        return session
    }

    /**
     * Giới hạn tối đa MAX_LIVE_SESSIONS phiên Gecko sống đồng thời để RAM không phình khi mở 30+ tab.
     */
    @MainThread
    private fun evictLeastRecentlyUsedSessionsIfNeeded() {
        if (liveSessions.size <= MAX_LIVE_SESSIONS) return
        val activeId = _uiState.value.activeTabId
        val candidates = _uiState.value.tabs
            .filter { it.id != activeId && liveSessions.containsKey(it.id) && it.sessionState.serializedEngineState != null }
            .sortedBy { it.lastAccessedEpochMs }

        val overflowCount = liveSessions.size - MAX_LIVE_SESSIONS
        candidates.take(overflowCount).forEach { victim ->
            liveSessions.remove(victim.id)?.let { session ->
                session.setActive(false)
                session.close()
            }
        }
    }

    /**
     * Cấu hình GeckoSessionSettings + các Delegate chuẩn; lưu ý popup onNewSession BẮT BUỘC trả về session CHƯA open().
     */
    @MainThread
    private fun buildConfiguredSession(
        tabId: String,
        isPrivate: Boolean,
        openImmediately: Boolean,
    ): GeckoSession {
        val sessionSettings = GeckoSessionSettings.Builder()
            .usePrivateMode(isPrivate)
            .useTrackingProtection(true)
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            .build()

        val session = GeckoSession(sessionSettings)
        if (openImmediately) {
            session.open(runtimeManager.getOrCreateRuntime())
        }

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (url.isNullOrBlank() || url == ABOUT_BLANK) return
                updateTabSessionState(tabId) { state ->
                    state.copy(url = url, isCrashed = false)
                }
                schedulePersistTabs()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                updateTabSessionState(tabId) { it.copy(canGoBack = canGoBack) }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                updateTabSessionState(tabId) { it.copy(canGoForward = canGoForward) }
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny> {
                val scheme = runCatching { Uri.parse(request.uri).scheme?.lowercase() }.getOrNull()
                // Chặn các URI nội bộ nguy hiểm nếu phát sinh từ chuyển hướng trang web
                val allowed = scheme in ALLOWED_WEB_SCHEMES
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // Hợp đồng GeckoView: session trả về cho window.open / target="_blank" PHẢI chưa gọi open()
                val popupTab = Tab(
                    isPrivate = isPrivate,
                    sessionState = TabSessionState(url = uri),
                )
                val unopenedChildSession = buildConfiguredSession(
                    tabId = popupTab.id,
                    isPrivate = isPrivate,
                    openImmediately = false,
                )
                liveSessions[popupTab.id] = unopenedChildSession
                _uiState.update { current ->
                    current.copy(tabs = current.tabs + popupTab, activeTabId = popupTab.id)
                }
                // Đợi GeckoView tự open() session con ở khung tiếp theo rồi mới gắn vào GeckoView
                mainHandler.post { syncActiveSessionAttachment() }
                schedulePersistTabs()
                return GeckoResult.fromValue(unopenedChildSession)
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                val safeUri = htmlEscape(uri ?: "unknown")
                val errorHtml = """
                    <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                    <style>body{font-family:sans-serif;padding:32px 20px;background:#0F1424;color:#F2F5FF}
                    .card{max-width:440px;margin:40px auto;padding:24px;border-radius:20px;background:#1A2138}
                    h2{margin-top:0;color:#FF6B6B}code{word-break:break-all;color:#9BA7C7}</style></head>
                    <body><div class="card"><h2>Không thể mở trang</h2>
                    <p>Veko không kết nối được tới địa chỉ:</p><code>$safeUri</code>
                    <p style="margin-top:16px;font-size:13px;opacity:0.75">Mã lỗi Gecko: ${error.code}</p>
                    </div></body></html>
                """.trimIndent()
                return GeckoResult.fromValue("data:text/html;charset=utf-8," + Uri.encode(errorHtml))
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (url == ABOUT_BLANK) return
                updateTabSessionState(tabId) { it.copy(isLoading = true, progress = 5) }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                updateTabSessionState(tabId) { it.copy(progress = progress.coerceIn(0, 100)) }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                updateTabSessionState(tabId) { it.copy(isLoading = false, progress = 100) }
                if (success) {
                    val finishedTab = _uiState.value.tabs.firstOrNull { it.id == tabId }
                    if (finishedTab != null && !finishedTab.isPrivate) {
                        mainScope.launch {
                            dataRepository.recordVisitIfRegular(
                                url = finishedTab.sessionState.url,
                                title = finishedTab.sessionState.title,
                                isPrivate = false,
                            )
                        }
                    }
                }
                // Chụp sẵn ảnh xem trước sau khi trang vẽ xong để Tab Switcher luôn có thumbnail tức thì
                if (success && attachedTabId == tabId && isAppInForeground) {
                    mainScope.launch {
                        delay(250)
                        if (attachedTabId == tabId && isAppInForeground) {
                            captureActiveTabPreview()
                        }
                    }
                }
            }

            override fun onSecurityChange(
                session: GeckoSession,
                securityInfo: GeckoSession.ProgressDelegate.SecurityInformation,
            ) {
                val security = when {
                    securityInfo.isSecure -> SecurityState.SECURE
                    securityInfo.origin?.startsWith("http:") == true -> SecurityState.INSECURE
                    else -> SecurityState.LOCAL_OR_UNKNOWN
                }
                updateTabSessionState(tabId) { it.copy(securityState = security) }
            }

            override fun onSessionStateChange(
                session: GeckoSession,
                sessionState: GeckoSession.SessionState,
            ) {
                val serialized = sessionState.toString()
                updateTabSessionState(tabId) { it.copy(serializedEngineState = serialized) }
                schedulePersistTabs()
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (!title.isNullOrBlank()) {
                    updateTabSessionState(tabId) { it.copy(title = title) }
                    schedulePersistTabs()
                }
            }

            override fun onKill(session: GeckoSession) {
                handleContentProcessDeath(tabId = tabId, wasCrash = false)
            }

            override fun onCrash(session: GeckoSession) {
                handleContentProcessDeath(tabId = tabId, wasCrash = true)
            }
        }

        return session
    }

    /**
     * Phục hồi êm ái khi Android thu hồi tiến trình con (:tab) của Gecko ở nền hoặc trang bị crash.
     */
    @MainThread
    private fun handleContentProcessDeath(tabId: String, wasCrash: Boolean) {
        liveSessions.remove(tabId)?.close()
        if (attachedTabId == tabId && isAppInForeground) {
            attachedTabId = null
            if (wasCrash) {
                updateTabSessionState(tabId) { it.copy(isCrashed = true, isLoading = false) }
            } else {
                // Nếu OS chỉ kill tiến trình con để thu hồi RAM rồi người dùng quay lại, tự khôi phục ngay
                syncActiveSessionAttachment()
            }
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
     * Gom nhóm (debounce) thao tác ghi DataStore xuống IO thread để không gây nghẽn ổ đĩa khi tải trang.
     */
    private fun schedulePersistTabs(immediate: Boolean = false) {
        persistJob?.cancel()
        persistJob = mainScope.launch {
            if (!immediate) delay(600)
            val snapshot = _uiState.value
            withContext(Dispatchers.IO) {
                writePersistedTabsToDisk(snapshot)
            }
        }
    }

    private suspend fun writePersistedTabsToDisk(snapshot: BrowserTabsUiState) {
        // RÀNG BUỘC RIÊNG TƯ: Tuyệt đối lọc bỏ toàn bộ tab isPrivate khỏi dữ liệu lưu xuống ổ đĩa
        val regularTabs = snapshot.tabs.filterNot { it.isPrivate }
        val jsonArray = JSONArray()
        for (tab in regularTabs) {
            val item = JSONObject().apply {
                put("id", tab.id)
                put("url", tab.sessionState.url)
                put("title", tab.sessionState.title)
                put("lastAccessed", tab.lastAccessedEpochMs)
                tab.sessionState.serializedEngineState?.let { stateStr ->
                    if (stateStr.length <= MAX_SERIALIZED_STATE_CHARS) {
                        put("engineState", stateStr)
                    }
                }
            }
            jsonArray.put(item)
        }
        val activeRegularId = snapshot.activeTabId
            ?.takeIf { id -> regularTabs.any { it.id == id } }
            ?: regularTabs.firstOrNull()?.id

        appContext.vekoSessionDataStore.edit { prefs ->
            prefs[KEY_TABS_JSON] = jsonArray.toString()
            if (activeRegularId != null) {
                prefs[KEY_ACTIVE_TAB_ID] = activeRegularId
            } else {
                prefs.remove(KEY_ACTIVE_TAB_ID)
            }
        }
    }

    private suspend fun readPersistedTabsFromDisk(): Pair<List<Tab>, String?> {
        val prefs = runCatching { appContext.vekoSessionDataStore.data.first() }.getOrNull()
            ?: return emptyList<Tab>() to null
        val rawJson = prefs[KEY_TABS_JSON] ?: return emptyList<Tab>() to null
        val activeId = prefs[KEY_ACTIVE_TAB_ID]

        val parsedTabs = runCatching {
            val array = JSONArray(rawJson)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val url = obj.optString("url", TabSessionState.DEFAULT_HOME_URL)
                    val title = obj.optString("title", "Trang mới")
                    val lastAccessed = obj.optLong("lastAccessed", System.currentTimeMillis())
                    val engineState = obj.optString("engineState").takeIf { it.isNotBlank() }
                    add(
                        Tab(
                            id = id,
                            isPrivate = false,
                            lastAccessedEpochMs = lastAccessed,
                            sessionState = TabSessionState(
                                url = url,
                                title = title,
                                serializedEngineState = engineState,
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())

        return parsedTabs to activeId
    }

    /**
     * Phân loại thông minh chuỗi nhập của người dùng thành URL trực tiếp hoặc truy vấn tìm kiếm riêng tư.
     */
    private fun normalizeInputToUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return TabSessionState.DEFAULT_HOME_URL
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("about:", ignoreCase = true) ||
            trimmed.startsWith("data:", ignoreCase = true)
        ) {
            return trimmed
        }
        val looksLikeDomain = !trimmed.contains(' ') &&
            (Patterns.WEB_URL.matcher(trimmed).matches() || trimmed.startsWith("localhost"))
        return if (looksLikeDomain) {
            "https://$trimmed"
        } else {
            currentSearchEngine.buildSearchUrl(trimmed)
        }
    }

    private fun htmlEscape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun checkMainThread(methodName: String) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "TabManager.$methodName() bắt buộc gọi trên Android Main/UI Thread."
        }
    }

    private companion object {
        const val ABOUT_BLANK = "about:blank"
        const val PREVIEW_TARGET_WIDTH_PX = 360
        const val MAX_LIVE_SESSIONS = 6
        const val MAX_SERIALIZED_STATE_CHARS = 2 * 1024 * 1024
        val ALLOWED_WEB_SCHEMES = setOf("http", "https", "about", "data", "blob")
        val KEY_TABS_JSON = stringPreferencesKey("persisted_regular_tabs_v1")
        val KEY_ACTIVE_TAB_ID = stringPreferencesKey("persisted_active_tab_id_v1")
    }
}
