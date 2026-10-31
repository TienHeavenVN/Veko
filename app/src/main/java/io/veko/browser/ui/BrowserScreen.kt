package io.veko.browser.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.veko.browser.BrowserViewModel
import io.veko.browser.data.local.BookmarkEntity
import io.veko.browser.data.local.HistoryEntity
import io.veko.browser.data.repository.AddressSuggestion
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SuggestionSource
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.permission.SitePermissionDecision
import io.veko.browser.permission.SitePermissionType
import io.veko.browser.tab.FindInPageState
import io.veko.browser.tab.SecurityState
import io.veko.browser.tab.Tab
import io.veko.browser.tab.TabSessionState
import io.veko.browser.tab.UrlNavigationPolicy
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

/**
 * Màn hình trình duyệt chính của Veko:
 * - Bọc duy nhất 1 GeckoView bằng AndroidView để tái sử dụng bề mặt vẽ (SurfaceView) xuyên suốt mọi tab.
 * - Đặt thanh AddressBar + TabBar ở cạnh dưới cho thao tác một tay (one-handed UX), tương thích edge-to-edge & IME.
 * - Tích hợp Omnibox tự chọn toàn bộ URL, gợi ý thông minh, Tìm trong trang, Quyền trang web, Tải xuống và Hộp thoại Web.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.filteredHistory.collectAsStateWithLifecycle()
    val historyQuery by viewModel.historySearchQuery.collectAsStateWithLifecycle()
    val omniboxSuggestions by viewModel.omniboxSuggestions.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val isBookmarked by viewModel.isActiveTabBookmarked.collectAsStateWithLifecycle()
    val activeSitePermissions by viewModel.activeSitePermissions.collectAsStateWithLifecycle()
    val persistedSitePermissions by viewModel.allPersistedSitePermissions.collectAsStateWithLifecycle()

    val activeTab = uiState.activeTab
    val isPrivateActive = activeTab?.isPrivate == true
    val isFullScreen = activeTab?.sessionState?.isFullScreen == true
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val foregroundToken = remember { Any() }

    // Không lưu nháp URL của tab riêng tư vào Bundle rememberSaveable để tránh rò rỉ dữ liệu riêng tư xuống đĩa hệ thống
    var isEditingUrl by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf("") }
    var isTabSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isLibrarySheetOpen by rememberSaveable { mutableStateOf(false) }
    var isSettingsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isDownloadsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isSecuritySheetOpen by remember { mutableStateOf(false) }

    val showToastMessage: (String) -> Unit = remember(coroutineScope, snackbarHostState) {
        { message ->
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.downloadEvents.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // Gắn vòng đời Activity (ON_START / ON_STOP) kèm token định danh để không làm lệch trạng thái khi tái tạo Activity
    DisposableEffect(lifecycleOwner, foregroundToken) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground(foregroundToken)
                Lifecycle.Event.ON_STOP -> {
                    if (isPrivateActive) {
                        urlDraft = ""
                        isEditingUrl = false
                        viewModel.onUpdateOmniboxQuery("")
                    }
                    viewModel.onBackground(foregroundToken)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onBackground(foregroundToken)
        }
    }

    // Ưu tiên nút Back hệ thống: Đóng Omnibox -> Đóng Tìm trong trang -> Thoát Fullscreen -> Lùi lịch sử trang web
    BackHandler(
        enabled = isEditingUrl ||
            uiState.findInPage.isVisible ||
            isFullScreen ||
            activeTab?.sessionState?.canGoBack == true,
    ) {
        when {
            isEditingUrl -> {
                isEditingUrl = false
                viewModel.onUpdateOmniboxQuery("")
                focusManager.clearFocus()
            }
            uiState.findInPage.isVisible -> {
                viewModel.onCloseFindInPage()
                focusManager.clearFocus()
            }
            isFullScreen -> {
                viewModel.onExitFullScreen()
            }
            else -> {
                viewModel.onBack()
            }
        }
    }

    val rootModifier = if (isFullScreen) {
        modifier
            .fillMaxSize()
            .background(Color.Black)
    } else {
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    }

    Column(modifier = rootModifier) {
        // Vùng hiển thị nội dung web luôn giữ nguyên AndroidView (GeckoView) để không tái tạo SurfaceView
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    GeckoView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        viewModel.onAttachView(this)
                    }
                },
                update = { geckoView ->
                    viewModel.onAttachView(geckoView)
                },
                onRelease = { geckoView ->
                    viewModel.onDetachView(geckoView)
                },
            )

            // Trang bắt đầu tối giản khi mở Tab mới (about:blank) mà vẫn giữ nguyên GeckoView bên dưới
            val showNewTabStart = activeTab != null &&
                !isEditingUrl &&
                !isFullScreen &&
                !activeTab.sessionState.isLoading &&
                !activeTab.sessionState.isCrashed &&
                !activeTab.sessionState.hasLoadError &&
                UrlNavigationPolicy.isNewTabBlankUrl(activeTab.sessionState.url)

            if (showNewTabStart && activeTab != null) {
                NewTabStartOverlay(
                    isPrivate = activeTab.isPrivate,
                    searchEngine = settings.searchEngine,
                    bookmarks = if (activeTab.isPrivate) emptyList() else bookmarks.take(8),
                    onStartTyping = {
                        urlDraft = ""
                        viewModel.onUpdateOmniboxQuery("")
                        isEditingUrl = true
                    },
                    onOpenBookmark = viewModel::onSubmitInput,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Lớp phủ khôi phục khi tiến trình con của tab bị crash bất ngờ
            if (activeTab?.sessionState?.isCrashed == true) {
                CrashedTabOverlay(
                    onReload = viewModel::onReloadOrStop,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Thanh hành động nổi giúp người dùng thoát/thử lại ngay khi trang lỗi mạng hoặc DNS
            if (activeTab?.sessionState?.hasLoadError == true && !activeTab.sessionState.isLoading && !isEditingUrl) {
                LoadErrorRecoveryBanner(
                    canGoBack = activeTab.sessionState.canGoBack,
                    onRetry = viewModel::onReloadOrStop,
                    onBack = viewModel::onBack,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
            )

            // Thanh tiến trình mảnh đặt sát mép dưới vùng web, gần tầm mắt ngón cái
            androidx.compose.animation.AnimatedVisibility(
                visible = activeTab?.sessionState?.isLoading == true && !isFullScreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                LinearProgressIndicator(
                    progress = { (activeTab?.sessionState?.progress ?: 0) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
        }

        // Thanh công cụ Tìm trong trang đặt ngay trên thanh điều hướng dưới đáy
        if (uiState.findInPage.isVisible && !isEditingUrl && !isFullScreen) {
            FindInPageBar(
                state = uiState.findInPage,
                onQueryChange = viewModel::onUpdateFindQuery,
                onFindPrevious = { viewModel.onFindNext(true) },
                onFindNext = { viewModel.onFindNext(false) },
                onToggleMatchCase = viewModel::onToggleFindMatchCase,
                onClose = {
                    viewModel.onCloseFindInPage()
                    focusManager.clearFocus()
                },
            )
        }

        // Thanh điều hướng một tay dưới đáy (ẩn hoàn toàn khi xem video toàn màn hình)
        if (!isFullScreen) {
            if (isEditingUrl) {
                OmniboxInputPanel(
                    initialText = urlDraft,
                    currentTabUrl = activeTab?.sessionState?.url.orEmpty(),
                    isPrivate = isPrivateActive,
                    searchEngine = settings.searchEngine,
                    suggestions = if (isPrivateActive) {
                        // Trong chế độ riêng tư vẫn hỗ trợ gợi ý Dấu trang nhưng không lưu nháp truy vấn
                        omniboxSuggestions.filter { it.source == SuggestionSource.BOOKMARK }
                    } else {
                        omniboxSuggestions
                    },
                    onQueryChanged = viewModel::onUpdateOmniboxQuery,
                    onCopyCurrentUrl = { url ->
                        if (copyPlainTextToClipboard(context, "URL", UrlNavigationPolicy.stripUserInfo(url))) {
                            showToastMessage("Đã sao chép địa chỉ trang")
                        }
                    },
                    onDismiss = {
                        isEditingUrl = false
                        viewModel.onUpdateOmniboxQuery("")
                        focusManager.clearFocus()
                    },
                    onSubmit = { submitted ->
                        isEditingUrl = false
                        viewModel.onUpdateOmniboxQuery("")
                        focusManager.clearFocus()
                        viewModel.onSubmitInput(submitted)
                    },
                )
            } else {
                val activeDownloadsCount = downloads.count { it.isInProgress }
                GestureBottomToolbar(
                    activeTab = activeTab,
                    tabCount = uiState.tabs.size,
                    isBookmarked = isBookmarked,
                    settings = settings,
                    activeDownloadsCount = activeDownloadsCount,
                    onSecurityClick = { isSecuritySheetOpen = true },
                    onAddressClick = {
                        val currentUrl = activeTab?.sessionState?.url.orEmpty()
                        urlDraft = if (
                            UrlNavigationPolicy.isNewTabBlankUrl(currentUrl) ||
                            UrlNavigationPolicy.isInternalErrorDataUrl(currentUrl)
                        ) {
                            ""
                        } else {
                            UrlNavigationPolicy.stripUserInfo(currentUrl)
                        }
                        viewModel.onUpdateOmniboxQuery(urlDraft)
                        isEditingUrl = true
                    },
                    onBackClick = viewModel::onBack,
                    onForwardClick = viewModel::onForward,
                    onReloadOrStopClick = viewModel::onReloadOrStop,
                    onOpenTabSheet = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.onPrepareTabSwitcher()
                        isTabSheetOpen = true
                    },
                    onSwipeHorizontalTab = { direction ->
                        if (viewModel.onSwipeAddressBar(direction)) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onNewTab = { viewModel.onCreateTab(settings.searchEngine.homeUrl, false) },
                    onNewPrivateTab = { viewModel.onCreateTab(TabSessionState.DEFAULT_HOME_URL, true) },
                    onToggleBookmark = viewModel::onToggleBookmark,
                    onOpenFindInPage = viewModel::onOpenFindInPage,
                    onStepPageZoom = viewModel::onStepPageZoom,
                    onResetPageZoom = viewModel::onResetPageZoom,
                    onToggleDesktopMode = viewModel::onToggleDesktopMode,
                    onShareCurrentPage = {
                        val url = activeTab?.sessionState?.url.orEmpty()
                        if (UrlNavigationPolicy.isRecordableHttpUrl(url)) {
                            sharePageOrLink(context, url, activeTab?.sessionState?.title)
                        } else {
                            showToastMessage("Chưa có liên kết web để chia sẻ")
                        }
                    },
                    onCopyCurrentUrl = {
                        val url = activeTab?.sessionState?.url.orEmpty()
                        if (UrlNavigationPolicy.isRecordableHttpUrl(url)) {
                            if (copyPlainTextToClipboard(context, "URL", UrlNavigationPolicy.stripUserInfo(url))) {
                                showToastMessage("Đã sao chép liên kết")
                            }
                        }
                    },
                    onOpenDownloads = { isDownloadsSheetOpen = true },
                    onOpenLibrary = { isLibrarySheetOpen = true },
                    onOpenSettings = { isSettingsSheetOpen = true },
                )
            }
        }
    }

    // Khay thông tin bảo mật kết nối & quyền theo Origin
    if (isSecuritySheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isSecuritySheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SiteSecuritySheetContent(
                activeTab = activeTab,
                originPermissions = activeSitePermissions,
                onCopyUrl = { url ->
                    if (copyPlainTextToClipboard(context, "URL", url)) {
                        showToastMessage("Đã sao chép địa chỉ")
                    }
                    isSecuritySheetOpen = false
                },
                onSetPermission = viewModel::onSetOriginPermission,
                onRevokeAllForOrigin = viewModel::onRevokeOriginPermissions,
            )
        }
    }

    // Khay quản lý Tab với thẻ xem trước & vuốt ngang đóng tab (SwipeToDismissBox)
    if (isTabSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isTabSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            TabSwitcherSheetContent(
                tabs = uiState.tabs,
                activeTabId = uiState.activeTabId,
                onSelectTab = { id ->
                    viewModel.onSelectTab(id)
                    isTabSheetOpen = false
                },
                onCloseTab = viewModel::onCloseTab,
                onCloseAllPrivateTabs = {
                    viewModel.onCloseAllPrivateTabs()
                    showToastMessage("Đã đóng toàn bộ tab riêng tư")
                },
                onNewRegularTab = {
                    viewModel.onCreateTab(settings.searchEngine.homeUrl, false)
                    isTabSheetOpen = false
                },
                onNewPrivateTab = {
                    viewModel.onCreateTab(TabSessionState.DEFAULT_HOME_URL, true)
                    isTabSheetOpen = false
                },
            )
        }
    }

    // Khay Thư viện (Dấu trang & Tìm kiếm Lịch sử từ Room)
    if (isLibrarySheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                isLibrarySheetOpen = false
                viewModel.onUpdateHistorySearchQuery("")
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            LibrarySheetContent(
                bookmarks = bookmarks,
                history = history,
                historyQuery = historyQuery,
                onHistoryQueryChange = viewModel::onUpdateHistorySearchQuery,
                onOpenUrl = { url ->
                    viewModel.onSubmitInput(url)
                    viewModel.onUpdateHistorySearchQuery("")
                    isLibrarySheetOpen = false
                },
                onDeleteBookmark = viewModel::onDeleteBookmark,
                onDeleteHistoryItem = viewModel::onDeleteHistoryItem,
                onClearHistory = viewModel::onClearHistory,
            )
        }
    }

    // Khay Tệp tải xuống
    if (isDownloadsSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isDownloadsSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            DownloadsSheetContent(
                downloads = downloads,
                onOpenFile = { item ->
                    if (!openDownloadedItem(context, item)) {
                        showToastMessage("Không tìm thấy ứng dụng phù hợp để mở tệp này")
                    }
                },
                onCancel = viewModel::onCancelDownload,
                onRetry = viewModel::onRetryDownload,
                onRemove = viewModel::onRemoveDownload,
                onClearFinished = viewModel::onClearFinishedDownloads,
            )
        }
    }

    // Khay Cài đặt (Material You, Công cụ tìm kiếm, Thu phóng & Quyền trang web đã lưu)
    if (isSettingsSheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { isSettingsSheetOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            SettingsSheetContent(
                settings = settings,
                persistedPermissions = persistedSitePermissions,
                onDynamicColorChange = viewModel::onSetDynamicColor,
                onSearchEngineSelect = viewModel::onSetSearchEngine,
                onResetPageZoom = viewModel::onResetPageZoom,
                onStepPageZoom = viewModel::onStepPageZoom,
                onRevokeOrigin = { origin -> viewModel.onRevokeOriginPermissions(origin, false) },
                onClearAllPermissions = viewModel::onClearAllSitePermissions,
            )
        }
    }

    // Menu ngữ cảnh khi nhấn giữ liên kết hoặc hình ảnh/phương tiện trên web
    uiState.contextMenu?.let { contextMenu ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::onDismissContextMenu,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            WebContextMenuSheet(
                menu = contextMenu,
                onOpenInNewTab = { url, priv -> viewModel.onCreateTab(url, priv) },
                onOpenInBackgroundTab = { url, priv ->
                    viewModel.onOpenInBackgroundTab(url, priv)
                    showToastMessage("Đã mở liên kết trong tab nền")
                },
                onCopyText = { text ->
                    if (copyPlainTextToClipboard(context, "Liên kết", UrlNavigationPolicy.stripUserInfo(text))) {
                        showToastMessage("Đã sao chép liên kết")
                    }
                },
                onShareUrl = { url, title -> sharePageOrLink(context, url, title) },
                onDownloadUrl = { url, priv ->
                    viewModel.onEnqueueDirectDownload(url = url, isPrivate = priv)
                    showToastMessage("Đã bắt đầu tải xuống")
                },
                onDismiss = viewModel::onDismissContextMenu,
            )
        }
    }

    // Hộp thoại chuẩn Web (alert/confirm/prompt/<select>/beforeunload/slow-script)
    uiState.activeWebPrompt?.let { webPrompt ->
        WebPromptHost(
            prompt = webPrompt,
            onClearPromptState = { viewModel.onDismissWebPrompt(cancelUnderlying = false) },
        )
    }

    // Hộp thoại xin quyền Vị trí / Máy ảnh / Micrô theo từng Origin
    uiState.activePermissionPrompt?.let { permPrompt ->
        SitePermissionDialog(
            prompt = permPrompt,
            onResolve = viewModel::onResolvePermissionPrompt,
        )
    }

    // Hộp thoại xác nhận trước khi mở ứng dụng bên ngoài (mailto:, tel:, sms:, geo:, market:)
    uiState.externalAppPrompt?.let { extPrompt ->
        ExternalAppConfirmDialog(
            prompt = extPrompt,
            onConfirmLaunch = { targetUri ->
                if (!launchExternalAppUri(context, targetUri)) {
                    showToastMessage("Không có ứng dụng hỗ trợ liên kết ${extPrompt.scheme}:")
                }
            },
            onDismiss = viewModel::onDismissExternalAppPrompt,
        )
    }
}

@Composable
private fun GestureBottomToolbar(
    activeTab: Tab?,
    tabCount: Int,
    isBookmarked: Boolean,
    settings: VekoSettings,
    activeDownloadsCount: Int,
    onSecurityClick: () -> Unit,
    onAddressClick: () -> Unit,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onReloadOrStopClick: () -> Unit,
    onOpenTabSheet: () -> Unit,
    onSwipeHorizontalTab: (Int) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenFindInPage: () -> Unit,
    onStepPageZoom: (Int) -> Unit,
    onResetPageZoom: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onShareCurrentPage: () -> Unit,
    onCopyCurrentUrl: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var gestureConsumed by remember { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }

    val securityState = activeTab?.sessionState?.securityState ?: SecurityState.LOCAL_OR_UNKNOWN
    val containerColor = if (activeTab?.isPrivate == true) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            IconButton(
                onClick = onBackClick,
                enabled = activeTab?.sessionState?.canGoBack == true,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại")
            }

            if (activeTab?.sessionState?.canGoForward == true) {
                IconButton(onClick = onForwardClick) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Tiến tới")
                }
            }

            // Viên thuốc địa chỉ hỗ trợ cử chỉ: vuốt ngang đổi tab, vuốt lên mở khay tab, chạm để gõ URL
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(containerColor)
                    .pointerInput(tabCount) {
                        detectDragGestures(
                            onDragStart = {
                                dragDx = 0f
                                dragDy = 0f
                                gestureConsumed = false
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (gestureConsumed) return@detectDragGestures
                                dragDx += dragAmount.x
                                dragDy += dragAmount.y
                                // Vuốt lên vượt ngưỡng 56px -> mở ngay khay chuyển tab một tay
                                if (dragDy < -56f && abs(dragDy) > abs(dragDx)) {
                                    gestureConsumed = true
                                    onOpenTabSheet()
                                } else if (abs(dragDx) > 72f && abs(dragDx) > abs(dragDy)) {
                                    // Vuốt sang trái (+1 tab kế) hoặc sang phải (-1 tab trước)
                                    gestureConsumed = true
                                    onSwipeHorizontalTab(if (dragDx < 0f) 1 else -1)
                                }
                            },
                        )
                    },
            ) {
                // Nút huy hiệu bảo mật tách riêng (đủ vùng chạm 48dp) để kiểm tra HTTPS/HTTP & thu hồi quyền Origin
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .sizeIn(minWidth = 44.dp, minHeight = 48.dp)
                        .clickable(onClick = onSecurityClick)
                        .padding(start = 10.dp, end = 6.dp),
                ) {
                    SecurityBadgeIcon(
                        isPrivate = activeTab?.isPrivate == true,
                        securityState = securityState,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clickable(onClick = onAddressClick)
                        .padding(end = 12.dp),
                ) {
                    if (securityState == SecurityState.INSECURE) {
                        Text(
                            text = "HTTP • ",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = UrlNavigationPolicy.formatDisplayHost(activeTab?.sessionState?.url),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            IconButton(onClick = onReloadOrStopClick) {
                Icon(
                    imageVector = if (activeTab?.sessionState?.isLoading == true) {
                        Icons.Rounded.Close
                    } else {
                        Icons.Rounded.Refresh
                    },
                    contentDescription = "Tải lại hoặc dừng",
                )
            }

            // Nút đếm số lượng tab đảm bảo vùng chạm chuẩn 48dp
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onOpenTabSheet),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .border(
                            width = 1.8.dp,
                            color = if (activeTab?.isPrivate == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            shape = RoundedCornerShape(9.dp),
                        ),
                ) {
                    Text(
                        text = tabCount.coerceAtLeast(1).toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Box {
                IconButton(onClick = { isMenuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Công cụ trang web và cài đặt")
                }
                WebsiteToolsDropdownMenu(
                    expanded = isMenuExpanded,
                    activeTab = activeTab,
                    isBookmarked = isBookmarked,
                    settings = settings,
                    activeDownloadsCount = activeDownloadsCount,
                    onDismiss = { isMenuExpanded = false },
                    onNewTab = onNewTab,
                    onNewPrivateTab = onNewPrivateTab,
                    onToggleBookmark = onToggleBookmark,
                    onOpenFindInPage = onOpenFindInPage,
                    onStepPageZoom = onStepPageZoom,
                    onResetPageZoom = onResetPageZoom,
                    onToggleDesktopMode = onToggleDesktopMode,
                    onShareCurrentPage = onShareCurrentPage,
                    onCopyCurrentUrl = onCopyCurrentUrl,
                    onOpenDownloads = onOpenDownloads,
                    onOpenLibrary = onOpenLibrary,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun WebsiteToolsDropdownMenu(
    expanded: Boolean,
    activeTab: Tab?,
    isBookmarked: Boolean,
    settings: VekoSettings,
    activeDownloadsCount: Int,
    onDismiss: () -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenFindInPage: () -> Unit,
    onStepPageZoom: (Int) -> Unit,
    onResetPageZoom: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onShareCurrentPage: () -> Unit,
    onCopyCurrentUrl: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hasHttpPage = UrlNavigationPolicy.isRecordableHttpUrl(activeTab?.sessionState?.url)
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        DropdownMenuItem(
            text = { Text("Tab mới") },
            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            onClick = {
                onDismiss()
                onNewTab()
            },
        )
        DropdownMenuItem(
            text = { Text("Tab riêng tư mới") },
            leadingIcon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
            onClick = {
                onDismiss()
                onNewPrivateTab()
            },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (isBookmarked) "Bỏ dấu trang" else "Lưu dấu trang") },
            leadingIcon = {
                Icon(
                    imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = null,
                )
            },
            enabled = hasHttpPage,
            onClick = {
                onDismiss()
                onToggleBookmark()
            },
        )
        DropdownMenuItem(
            text = { Text("Tìm trong trang") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenFindInPage()
            },
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (activeTab?.sessionState?.isDesktopMode == true) {
                        "Trang web cho máy tính (Đang bật)"
                    } else {
                        "Trang web cho máy tính"
                    },
                )
            },
            leadingIcon = { Icon(Icons.Rounded.DesktopWindows, contentDescription = null) },
            trailingIcon = if (activeTab?.sessionState?.isDesktopMode == true) {
                { Icon(Icons.Rounded.Check, contentDescription = null) }
            } else {
                null
            },
            onClick = {
                onDismiss()
                onToggleDesktopMode()
            },
        )
        // Hàng điều chỉnh thu phóng cỡ chữ trực tiếp trong menu công cụ
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = { onStepPageZoom(-15) }) {
                Icon(Icons.Rounded.ZoomOut, contentDescription = "Thu nhỏ chữ")
            }
            TextButton(
                onClick = onResetPageZoom,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = if (settings.autoFontSize) "Cỡ chữ: Tự động" else "Cỡ chữ: ${settings.pageZoomPercent}%",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(onClick = { onStepPageZoom(15) }) {
                Icon(Icons.Rounded.ZoomIn, contentDescription = "Phóng to chữ")
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Chia sẻ trang") },
            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
            enabled = hasHttpPage,
            onClick = {
                onDismiss()
                onShareCurrentPage()
            },
        )
        DropdownMenuItem(
            text = { Text("Sao chép liên kết") },
            leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
            enabled = hasHttpPage,
            onClick = {
                onDismiss()
                onCopyCurrentUrl()
            },
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (activeDownloadsCount > 0) {
                        "Tệp tải xuống ($activeDownloadsCount đang tải)"
                    } else {
                        "Tệp tải xuống"
                    },
                )
            },
            leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenDownloads()
            },
        )
        DropdownMenuItem(
            text = { Text("Dấu trang & Lịch sử") },
            leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenLibrary()
            },
        )
        DropdownMenuItem(
            text = { Text("Cài đặt Veko") },
            leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenSettings()
            },
        )
    }
}

/**
 * Omnibox thông minh: tự động bôi đen toàn bộ URL khi mở, hỗ trợ xóa nhanh, dán & đi, và gợi ý Dấu trang/Lịch sử.
 */
@Composable
private fun OmniboxInputPanel(
    initialText: String,
    currentTabUrl: String,
    isPrivate: Boolean,
    searchEngine: SearchEngine,
    suggestions: List<AddressSuggestion>,
    onQueryChanged: (String) -> Unit,
    onCopyCurrentUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    // Giữ trạng thái trên RAM (remember) kèm TextRange(0, length) để tự động chọn toàn bộ URL ngay khi người dùng chạm vào
    var textValue by remember(initialText) {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(0, initialText.length),
            ),
        )
    }
    val focusRequester = remember { FocusRequester() }
    val clipboardPreview = remember { readClipboardPlainText(context) }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val trimmed = textValue.text.trim()
    val normalizedPreview = remember(trimmed, searchEngine) {
        if (trimmed.isEmpty()) "" else UrlNavigationPolicy.normalizeUserInput(trimmed, searchEngine)
    }
    val isDirectUrlCandidate = remember(trimmed, normalizedPreview, searchEngine) {
        trimmed.isNotEmpty() && !normalizedPreview.startsWith(searchEngine.homeUrl)
    }

    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Danh sách gợi ý từ Dấu trang & Lịch sử nằm ngay phía trên ô nhập, không bị bàn phím che khuất
            if (suggestions.isNotEmpty() || trimmed.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    if (trimmed.isNotEmpty()) {
                        item(key = "direct_action") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSubmit(trimmed) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    imageVector = if (isDirectUrlCandidate) Icons.Rounded.Public else Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isDirectUrlCandidate) {
                                        "Đi tới $normalizedPreview"
                                    } else {
                                        "Tìm \"$trimmed\" bằng ${searchEngine.displayName}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    items(suggestions, key = { "${it.source}_${it.url}" }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSubmit(item.url) }
                                .padding(horizontal = 16.dp, vertical = 9.dp),
                        ) {
                            Icon(
                                imageVector = if (item.source == SuggestionSource.BOOKMARK) {
                                    Icons.Rounded.Bookmark
                                } else {
                                    Icons.Rounded.History
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = UrlNavigationPolicy.stripUserInfo(item.url),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
            }

            // Hàng thao tác nhanh: Sao chép URL hiện tại & Dán từ bộ nhớ tạm
            val hasCopyableCurrent = UrlNavigationPolicy.isRecordableHttpUrl(currentTabUrl)
            if (hasCopyableCurrent || !clipboardPreview.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (hasCopyableCurrent) {
                        FilterChip(
                            selected = false,
                            onClick = { onCopyCurrentUrl(currentTabUrl) },
                            label = { Text("Sao chép URL") },
                            leadingIcon = {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                    if (!clipboardPreview.isNullOrBlank() && clipboardPreview != initialText) {
                        FilterChip(
                            selected = false,
                            onClick = { onSubmit(clipboardPreview) },
                            label = { Text("Dán & Truy cập") },
                            leadingIcon = {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Đóng thanh nhập địa chỉ")
                }
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { updated ->
                        textValue = updated
                        onQueryChanged(updated.text)
                    },
                    placeholder = {
                        Text(
                            if (isPrivate) {
                                "Tìm riêng tư hoặc nhập địa chỉ web..."
                            } else {
                                "Tìm bằng ${searchEngine.displayName} hoặc nhập URL..."
                            },
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    trailingIcon = if (textValue.text.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = {
                                    textValue = TextFieldValue("")
                                    onQueryChanged("")
                                },
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Xóa nội dung đang nhập")
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (textValue.text.isNotBlank()) onSubmit(textValue.text)
                        },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilledTonalButton(
                    onClick = {
                        if (textValue.text.isNotBlank()) onSubmit(textValue.text)
                    },
                ) {
                    Text("Đi")
                }
            }
        }
    }
}

/**
 * Thanh Tìm trong trang kết nối trực tiếp với GeckoView SessionFinder.
 */
@Composable
private fun FindInPageBar(
    state: FindInPageState,
    onQueryChange: (String) -> Unit,
    onFindPrevious: () -> Unit,
    onFindNext: () -> Unit,
    onToggleMatchCase: () -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = { Text("Tìm trong trang...") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onFindNext() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (state.query.isNotBlank()) {
                Text(
                    text = if (state.notFound) {
                        "0/0"
                    } else {
                        "${state.currentMatch}/${state.totalMatches}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.notFound) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            FilterChip(
                selected = state.matchCase,
                onClick = onToggleMatchCase,
                label = { Text("Aa") },
            )
            IconButton(
                onClick = onFindPrevious,
                enabled = state.totalMatches > 0,
            ) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Kết quả trước")
            }
            IconButton(
                onClick = onFindNext,
                enabled = state.totalMatches > 0,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Kết quả tiếp theo")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "Đóng tìm trong trang")
            }
        }
    }
}

@Composable
private fun NewTabStartOverlay(
    isPrivate: Boolean,
    searchEngine: SearchEngine,
    bookmarks: List<BookmarkEntity>,
    onStartTyping: () -> Unit,
    onOpenBookmark: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
        ) {
            Icon(
                imageVector = if (isPrivate) Icons.Rounded.Shield else Icons.Rounded.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isPrivate) "Duyệt web riêng tư" else "Veko Browser",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (isPrivate) {
                    "Veko không lưu lịch sử, dấu trang tạm hay quyền trang web của tab riêng tư xuống bộ nhớ thiết bị. Hoạt động mạng vẫn có thể hiển thị với nhà mạng hoặc trang web bạn truy cập."
                } else {
                    "Nhanh, gọn và tôn trọng quyền riêng tư trên nền Mozilla GeckoView."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onStartTyping),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Tìm bằng ${searchEngine.displayName} hoặc nhập địa chỉ...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (bookmarks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Dấu trang nhanh",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start),
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                ) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenBookmark(bm.url) },
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = bm.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = UrlNavigationPolicy.formatDisplayHost(bm.url),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadErrorRecoveryBanner(
    canGoBack: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = "Không thể kết nối tới trang này",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (canGoBack) {
                TextButton(onClick = onBack) {
                    Text("Quay lại")
                }
            }
            FilledTonalButton(onClick = onRetry) {
                Text("Thử lại")
            }
        }
    }
}

@Composable
private fun TabSwitcherSheetContent(
    tabs: List<Tab>,
    activeTabId: String?,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllPrivateTabs: () -> Unit,
    onNewRegularTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
) {
    val privateCount = tabs.count { it.isPrivate }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Các tab đang mở (${tabs.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onNewPrivateTab) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Riêng tư")
                }
                Button(onClick = onNewRegularTab) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tab mới")
                }
            }
        }

        if (privateCount > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCloseAllPrivateTabs,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Đóng toàn bộ tab riêng tư ($privateCount)")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Lưới tự thích ứng theo chiều rộng màn hình (2 cột trên điện thoại, 3-4 cột trên màn hình gập & máy tính bảng)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.heightIn(min = 240.dp, max = 460.dp),
        ) {
            items(tabs, key = { it.id }) { tab ->
                val dismissState = rememberSwipeToDismissBoxState()
                SwipeToDismissBox(
                    state = dismissState,
                    onDismiss = { _ -> onCloseTab(tab.id) },
                    backgroundContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.errorContainer),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    },
                ) {
                    TabPreviewCard(
                        tab = tab,
                        isSelected = tab.id == activeTabId,
                        onSelect = { onSelectTab(tab.id) },
                        onClose = { onCloseTab(tab.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TabPreviewCard(
    tab: Tab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = if (isSelected) 4.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = if (isSelected) 2.5.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (tab.isPrivate) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    )
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
            ) {
                if (tab.isPrivate) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = "Tab riêng tư",
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = tab.sessionState.title.ifBlank {
                        UrlNavigationPolicy.formatDisplayHost(tab.sessionState.url)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Đóng tab",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.18f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                val preview = tab.previewBitmap
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = tab.sessionState.title,
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = UrlNavigationPolicy.formatDisplayHost(tab.sessionState.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySheetContent(
    bookmarks: List<BookmarkEntity>,
    history: List<HistoryEntity>,
    historyQuery: String,
    onHistoryQueryChange: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    onClearHistory: () -> Unit,
) {
    var selectedSection by rememberSaveable { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp, max = 560.dp)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                label = { Text("Dấu trang (${bookmarks.size})") },
            )
            FilterChip(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                label = { Text("Lịch sử (${history.size})") },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (selectedSection == 1 && (history.isNotEmpty() || historyQuery.isNotBlank())) {
                TextButton(onClick = onClearHistory) {
                    Text("Xóa hết")
                }
            }
        }

        if (selectedSection == 1) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = historyQuery,
                onValueChange = onHistoryQueryChange,
                placeholder = { Text("Tìm trong lịch sử duyệt web...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (historyQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onHistoryQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Xóa từ khóa tìm lịch sử")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedSection == 0) {
            if (bookmarks.isEmpty()) {
                EmptyOverlayHint("Chưa có dấu trang nào được lưu.")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(bookmarks, key = { it.id }) { item ->
                        UrlListItemRow(
                            title = item.title,
                            url = item.url,
                            onClick = { onOpenUrl(item.url) },
                            onDelete = { onDeleteBookmark(item.url) },
                        )
                    }
                }
            }
        } else {
            if (history.isEmpty()) {
                EmptyOverlayHint(
                    if (historyQuery.isNotBlank()) {
                        "Không tìm thấy mục lịch sử nào khớp \"$historyQuery\"."
                    } else {
                        "Chưa có lịch sử duyệt web (Tab riêng tư không bao giờ lưu vào đây)."
                    },
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(history, key = { it.id }) { entry ->
                        UrlListItemRow(
                            title = entry.title,
                            url = entry.url,
                            onClick = { onOpenUrl(entry.url) },
                            onDelete = { onDeleteHistoryItem(entry.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlListItemRow(
    title: String,
    url: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = UrlNavigationPolicy.stripUserInfo(url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Xóa mục")
        }
    }
}

@Composable
private fun SettingsSheetContent(
    settings: VekoSettings,
    persistedPermissions: Map<String, Map<SitePermissionType, SitePermissionDecision>>,
    onDynamicColorChange: (Boolean) -> Unit,
    onSearchEngineSelect: (SearchEngine) -> Unit,
    onResetPageZoom: () -> Unit,
    onStepPageZoom: (Int) -> Unit,
    onRevokeOrigin: (String) -> Unit,
    onClearAllPermissions: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp),
    ) {
        item {
            Text(
                text = "Cài đặt Veko",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Màu động Material You",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Đồng bộ bảng màu trình duyệt theo hình nền Android 12+",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.dynamicColor,
                    onCheckedChange = onDynamicColorChange,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Thu phóng văn bản trên trang web",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                IconButton(onClick = { onStepPageZoom(-15) }) {
                    Icon(Icons.Rounded.ZoomOut, contentDescription = "Giảm tỷ lệ chữ")
                }
                Text(
                    text = if (settings.autoFontSize) {
                        "Theo hệ thống Android (Tự động)"
                    } else {
                        "Tỷ lệ tùy chỉnh: ${settings.pageZoomPercent}%"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onStepPageZoom(15) }) {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = "Tăng tỷ lệ chữ")
                }
                if (!settings.autoFontSize) {
                    TextButton(onClick = onResetPageZoom) {
                        Text("Đặt lại")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Công cụ tìm kiếm mặc định",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        items(SearchEngine.entries, key = { it.name }) { engine ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSearchEngineSelect(engine) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(
                    selected = settings.searchEngine == engine,
                    onClick = { onSearchEngineSelect(engine) },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = engine.displayName, style = MaterialTheme.typography.bodyLarge)
            }
        }

        item {
            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Quyền trang web đã lưu (${persistedPermissions.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (persistedPermissions.isNotEmpty()) {
                    TextButton(onClick = onClearAllPermissions) {
                        Text("Xóa tất cả")
                    }
                }
            }
        }

        if (persistedPermissions.isEmpty()) {
            item {
                Text(
                    text = "Chưa có trang web nào được cấp hoặc chặn quyền cố định.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            val entries = persistedPermissions.entries.toList()
            items(entries, key = { it.key }) { (origin, perms) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = origin,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val summary = perms.entries.joinToString(" • ") { (type, dec) ->
                            val stateText = if (dec == SitePermissionDecision.ALLOW) "Cho phép" else "Chặn"
                            "${type.titleVi}: $stateText"
                        }
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRevokeOrigin(origin) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Thu hồi quyền của $origin")
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SecurityBadgeIcon(
    isPrivate: Boolean,
    securityState: SecurityState,
) {
    when {
        isPrivate && securityState != SecurityState.INSECURE -> Icon(
            imageVector = Icons.Rounded.Shield,
            contentDescription = "Chế độ riêng tư — chạm để xem thông tin kết nối",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        securityState == SecurityState.SECURE -> Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = "Kết nối HTTPS an toàn — chạm để quản lý quyền",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
        securityState == SecurityState.INSECURE -> Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = "Cảnh báo kết nối HTTP không mã hóa",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        else -> Icon(
            imageVector = Icons.Rounded.Public,
            contentDescription = "Thông tin trang web",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CrashedTabOverlay(
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp,
        modifier = modifier.padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Tiến trình trang web đã dừng",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Các tab khác và giao diện Veko vẫn an toàn. Nhấn bên dưới để khôi phục trang.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onReload) {
                Text("Khôi phục tab")
            }
        }
    }
}
