package io.veko.browser.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.rounded.Home
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.testTag
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
import io.veko.browser.data.repository.AppLanguage
import io.veko.browser.data.repository.AutoplayPolicy
import io.veko.browser.data.repository.SearchEngine
import io.veko.browser.data.repository.SuggestionSource
import io.veko.browser.data.repository.VekoSettings
import io.veko.browser.engine.EngineAvailability
import io.veko.browser.engine.EngineAvailabilityStatus
import io.veko.browser.engine.EngineType
import io.veko.browser.permission.OriginPermissionsRecord
import io.veko.browser.permission.SitePermissionDecision
import io.veko.browser.permission.SitePermissionType
import io.veko.browser.tab.FindInPageState
import io.veko.browser.tab.SecurityState
import io.veko.browser.tab.Tab
import io.veko.browser.tab.UrlNavigationPolicy
import io.veko.browser.ui.home.HomeScreen
import io.veko.browser.ui.i18n.LocalVekoStrings
import io.veko.browser.ui.navigation.BrowserBottomBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình trình duyệt chính của Veko:
 * - Bọc duy nhất 1 Engine Platform View (`ChromiumSurfaceHostView` hoặc `GeckoView`) bằng AndroidView để tái sử dụng bề mặt vẽ (SurfaceView) xuyên suốt mọi tab.
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
    val homeUiState by viewModel.homeUiState.collectAsStateWithLifecycle()
    val mviState by viewModel.mviState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineAvailabilities by viewModel.engineAvailabilities.collectAsStateWithLifecycle()
    val initializedEngines by viewModel.initializedEngines.collectAsStateWithLifecycle()
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
    var isHomeSearchFocused by remember { mutableStateOf(false) }
    var urlDraft by remember { mutableStateOf("") }
    var isTabSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isLibrarySheetOpen by rememberSaveable { mutableStateOf(false) }
    var libraryInitialSection by rememberSaveable { mutableIntStateOf(0) }
    var isSettingsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isDownloadsSheetOpen by rememberSaveable { mutableStateOf(false) }
    var isSecuritySheetOpen by remember { mutableStateOf(false) }

    val strings = LocalVekoStrings.current
    val showToastMessage: (String) -> Unit = remember(coroutineScope, snackbarHostState) {
        { message ->
            coroutineScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    val exportBookmarksLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/html"),
    ) { targetUri ->
        if (targetUri != null) {
            viewModel.exportBookmarksHtml { html ->
                coroutineScope.launch(Dispatchers.IO) {
                    val ok = runCatching {
                        context.contentResolver.openOutputStream(targetUri)?.use { out ->
                            out.write(html.toByteArray(Charsets.UTF_8))
                        } != null
                    }.getOrDefault(false)
                    if (ok) {
                        withContext(Dispatchers.Main) {
                            showToastMessage(strings.bookmarksExportedToast)
                        }
                    }
                }
            }
        }
    }

    val importBookmarksLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { sourceUri ->
        if (sourceUri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val rawText = runCatching {
                    context.contentResolver.openInputStream(sourceUri)?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    }
                }.getOrNull()
                if (!rawText.isNullOrBlank()) {
                    viewModel.importBookmarksFromText(rawText) { imported ->
                        showToastMessage(strings.bookmarksImportedToast(imported))
                    }
                }
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.downloadEvents.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.mviEffects.collect { effect ->
            when (effect) {
                is io.veko.browser.mvi.BrowserMviEffect.ShowMessage -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
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

    // Ưu tiên nút Back hệ thống: Đóng Omnibox / Ô tìm kiếm Home -> Đóng Tìm trong trang -> Thoát Fullscreen -> Lùi lịch sử trang web
    BackHandler(
        enabled = isEditingUrl ||
            isHomeSearchFocused ||
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
            isHomeSearchFocused -> {
                isHomeSearchFocused = false
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
        // Vùng hiển thị nội dung web: tái sử dụng cùng 1 SurfaceView giữa các tab cùng EngineType,
        // và hoán đổi sạch qua key(activeTab.engineType) khi chuyển giữa hai Engine khác nhau.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (activeTab != null) {
                val activeEngineType = activeTab.engineType
                key(activeEngineType) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            viewModel.createEnginePlatformView(ctx, activeEngineType).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                viewModel.onAttachPlatformView(this, activeEngineType)
                            }
                        },
                        update = { engineView ->
                            viewModel.onAttachPlatformView(engineView, activeEngineType)
                        },
                        onRelease = { engineView ->
                            viewModel.onDetachPlatformView(engineView)
                        },
                    )
                }
            }

            // Trang chủ Veko Horizon Deck hiển thị khi tab ở trạng thái about:blank mà vẫn giữ nguyên GeckoView bên dưới
            val showNewTabStart = activeTab != null &&
                !isEditingUrl &&
                !isFullScreen &&
                !activeTab.sessionState.isLoading &&
                !activeTab.sessionState.isCrashed &&
                !activeTab.sessionState.hasLoadError &&
                UrlNavigationPolicy.isNewTabBlankUrl(activeTab.sessionState.url)

            LaunchedEffect(showNewTabStart) {
                if (!showNewTabStart) {
                    isHomeSearchFocused = false
                }
            }

            if (showNewTabStart && activeTab != null) {
                HomeScreen(
                    state = homeUiState,
                    onAddressQueryChanged = viewModel::onUpdateOmniboxQuery,
                    onSubmitAddressOrSearch = { input ->
                        isHomeSearchFocused = false
                        viewModel.onSubmitInput(input)
                    },
                    onSelectSearchEngine = viewModel::onSetSearchEngine,
                    onAddressFocusChanged = { focused ->
                        isHomeSearchFocused = focused
                        if (!focused) {
                            viewModel.onUpdateOmniboxQuery("")
                        }
                    },
                    onSelectTab = viewModel::onSelectTab,
                    onOpenTabSwitcher = {
                        viewModel.onPrepareTabSwitcher()
                        isTabSheetOpen = true
                    },
                    onOpenBookmarks = {
                        libraryInitialSection = 0
                        isLibrarySheetOpen = true
                    },
                    onOpenHistory = {
                        libraryInitialSection = 1
                        isLibrarySheetOpen = true
                    },
                    onOpenDownloads = { isDownloadsSheetOpen = true },
                    onOpenSettings = { isSettingsSheetOpen = true },
                    onTogglePrivateMode = {
                        if (activeTab.isPrivate) {
                            val existingRegular = uiState.tabs.lastOrNull { !it.isPrivate }
                            if (existingRegular != null) {
                                viewModel.onSelectTab(existingRegular.id)
                            } else {
                                viewModel.onCreateHomeTab(isPrivate = false)
                            }
                        } else {
                            val existingPrivate = uiState.tabs.lastOrNull { it.isPrivate }
                            if (existingPrivate != null) {
                                viewModel.onSelectTab(existingPrivate.id)
                            } else {
                                viewModel.onCreateHomeTab(isPrivate = true)
                            }
                        }
                    },
                    onCloseAllPrivateTabs = {
                        viewModel.onCloseAllPrivateTabs()
                        showToastMessage("Đã đóng toàn bộ tab riêng tư")
                    },
                    onSaveShortcut = viewModel::onSaveShortcut,
                    onDeleteShortcut = viewModel::onDeleteBookmark,
                    onDeleteHistoryItem = viewModel::onDeleteHistoryItem,
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
            } else if (!isHomeSearchFocused) {
                val activeDownloadsCount = downloads.count { it.isInProgress }
                BrowserBottomBar(
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
                    onNavigateHomeClick = viewModel::onNavigateHome,
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
                    onNewTab = { viewModel.onCreateHomeTab(isPrivate = false) },
                    onNewPrivateTab = { viewModel.onCreateHomeTab(isPrivate = true) },
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
                    onOpenLibrary = {
                        libraryInitialSection = 0
                        isLibrarySheetOpen = true
                    },
                    onOpenPrivacyShield = {
                        viewModel.dispatchMvi(
                            io.veko.browser.mvi.BrowserMviAction.UserIntent.SetPrivacySheetVisible(true),
                        )
                    },
                    onOpenSettings = { isSettingsSheetOpen = true },
                )
            }
        }
    }

    // Khay Ma trận Quyền riêng tư Veko (C++20 JNI + nhân lọc Rust adblock-rust 0.13.4)
    if (mviState.isPrivacySheetOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.dispatchMvi(
                    io.veko.browser.mvi.BrowserMviAction.UserIntent.SetPrivacySheetVisible(false),
                )
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            VekoPrivacyShieldSheetContent(
                mviState = mviState,
                onDispatch = viewModel::dispatchMvi,
            )
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
                preferredEngine = settings.preferredEngine,
                engineAvailabilities = engineAvailabilities,
                onSelectTab = { id ->
                    viewModel.onSelectTab(id)
                    isTabSheetOpen = false
                },
                onCloseTab = viewModel::onCloseTab,
                onCloseAllPrivateTabs = {
                    viewModel.onCloseAllPrivateTabs()
                    showToastMessage("Đã đóng toàn bộ tab riêng tư")
                },
                onNewRegularTab = { chosenEngine ->
                    viewModel.onCreateHomeTabWithExplicitEngine(
                        isPrivate = false,
                        engineType = chosenEngine,
                        onResultMessage = showToastMessage,
                    )
                    if (engineAvailabilities[chosenEngine]?.isSelectable != false) {
                        isTabSheetOpen = false
                    }
                },
                onNewPrivateTab = { chosenEngine ->
                    viewModel.onCreateHomeTabWithExplicitEngine(
                        isPrivate = true,
                        engineType = chosenEngine,
                        onResultMessage = showToastMessage,
                    )
                    if (engineAvailabilities[chosenEngine]?.isSelectable != false) {
                        isTabSheetOpen = false
                    }
                },
                onUnavailableEngineTapped = { blockedAvailability ->
                    showToastMessage(
                        blockedAvailability.unavailabilityReason
                            ?: "Nhân ${blockedAvailability.engineName} hiện chưa khả dụng.",
                    )
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
                initialSection = libraryInitialSection,
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
                onExportBookmarks = { exportBookmarksLauncher.launch("veko-bookmarks.html") },
                onImportBookmarks = {
                    importBookmarksLauncher.launch(arrayOf("text/html", "application/json", "text/plain"))
                },
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
                activeTab = activeTab,
                engineAvailabilities = engineAvailabilities,
                initializedEngines = initializedEngines,
                persistedPermissions = persistedSitePermissions,
                onSelectAppLanguage = viewModel::onSetAppLanguage,
                onSelectPreferredEngine = { chosen ->
                    viewModel.onSelectPreferredEngine(
                        engineType = chosen,
                        onResultMessage = showToastMessage,
                    )
                },
                onOpenCurrentPageInEngine = { targetEngine ->
                    viewModel.onOpenCurrentUrlInEngine(
                        engineType = targetEngine,
                        onResultMessage = showToastMessage,
                    )
                    isSettingsSheetOpen = false
                },
                onDynamicColorChange = viewModel::onSetDynamicColor,
                onSelectAutoplayPolicy = viewModel::onSetAutoplayPolicy,
                onClearDataOnExitChange = viewModel::onSetClearDataOnExit,
                onShredDataNow = {
                    viewModel.onShredBrowsingTraceNow()
                    showToastMessage(strings.shredDataDoneToast)
                },
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
                                        "Tìm \"$trimmed\" bằng ${searchEngine.label}"
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
                                "Tìm bằng ${searchEngine.label} hoặc nhập URL..."
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
    preferredEngine: EngineType,
    engineAvailabilities: Map<EngineType, EngineAvailability>,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseAllPrivateTabs: () -> Unit,
    onNewRegularTab: (EngineType) -> Unit,
    onNewPrivateTab: (EngineType) -> Unit,
    onUnavailableEngineTapped: (EngineAvailability) -> Unit,
) {
    val strings = LocalVekoStrings.current
    val privateCount = tabs.count { it.isPrivate }
    var newTabEngine by remember(preferredEngine) { mutableStateOf(preferredEngine) }

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
                text = strings.openTabsHeader(tabs.size),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onNewPrivateTab(newTabEngine) },
                    modifier = Modifier.testTag("tab_sheet_new_private_button"),
                ) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(strings.privateButton)
                }
                Button(
                    onClick = { onNewRegularTab(newTabEngine) },
                    modifier = Modifier.testTag("tab_sheet_new_tab_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(strings.newTabMenu)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = strings.newTabEngineLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EngineType.entries.forEach { candidate ->
                val availability = engineAvailabilities[candidate]
                val selectable = availability?.isSelectable ?: (candidate == EngineType.GECKO)
                val selected = newTabEngine == candidate && selectable
                FilterChip(
                    modifier = Modifier.testTag("new_tab_engine_chip_${candidate.storageKey}"),
                    selected = selected,
                    onClick = {
                        if (selectable) {
                            newTabEngine = candidate
                        } else if (availability != null) {
                            onUnavailableEngineTapped(availability)
                        }
                    },
                    enabled = true,
                    label = {
                        val suffix = when {
                            candidate.isRecommendedDefault -> strings.defaultBadge
                            !selectable -> strings.awaitingSdkBadge
                            else -> ""
                        }
                        Text(
                            text = "${candidate.shortBadge}$suffix",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
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
                Text(strings.closeAllPrivateTabsBanner(privateCount))
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
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        text = tab.engineType.shortBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySheetContent(
    initialSection: Int = 0,
    bookmarks: List<BookmarkEntity>,
    history: List<HistoryEntity>,
    historyQuery: String,
    onHistoryQueryChange: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onExportBookmarks: () -> Unit,
    onImportBookmarks: () -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    onClearHistory: () -> Unit,
) {
    val strings = LocalVekoStrings.current
    var selectedSection by rememberSaveable(initialSection) { mutableIntStateOf(initialSection) }

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
                label = { Text(strings.bookmarksTab(bookmarks.size)) },
            )
            FilterChip(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                label = { Text(strings.historyTab(history.size)) },
            )
            Spacer(modifier = Modifier.weight(1f))
            if (selectedSection == 0) {
                TextButton(
                    onClick = onImportBookmarks,
                    modifier = Modifier.testTag("bookmarks_import_button"),
                ) {
                    Text(strings.importBookmarksLabel)
                }
                if (bookmarks.isNotEmpty()) {
                    TextButton(
                        onClick = onExportBookmarks,
                        modifier = Modifier.testTag("bookmarks_export_button"),
                    ) {
                        Text(strings.exportBookmarksLabel)
                    }
                }
            } else if (history.isNotEmpty() || historyQuery.isNotBlank()) {
                TextButton(onClick = onClearHistory) {
                    Text(strings.clearAllLabel)
                }
            }
        }

        if (selectedSection == 1) {
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = historyQuery,
                onValueChange = onHistoryQueryChange,
                placeholder = { Text(strings.searchHistoryHint) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = if (historyQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onHistoryQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear")
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
                EmptyOverlayHint(strings.emptyBookmarksHint)
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
                        "\"$historyQuery\""
                    } else {
                        strings.emptyHistoryHint
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSheetContent(
    settings: VekoSettings,
    activeTab: Tab?,
    engineAvailabilities: Map<EngineType, EngineAvailability>,
    initializedEngines: Set<EngineType>,
    persistedPermissions: List<OriginPermissionsRecord>,
    onSelectAppLanguage: (AppLanguage) -> Unit,
    onSelectPreferredEngine: (EngineType) -> Unit,
    onOpenCurrentPageInEngine: (EngineType) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onSelectAutoplayPolicy: (AutoplayPolicy) -> Unit,
    onClearDataOnExitChange: (Boolean) -> Unit,
    onShredDataNow: () -> Unit,
    onSearchEngineSelect: (SearchEngine) -> Unit,
    onResetPageZoom: () -> Unit,
    onStepPageZoom: (Int) -> Unit,
    onRevokeOrigin: (String) -> Unit,
    onClearAllPermissions: () -> Unit,
) {
    val strings = LocalVekoStrings.current

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 600.dp),
    ) {
        item {
            Text(
                text = strings.settingsTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = strings.languageSectionTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_language_picker"),
            ) {
                AppLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = settings.appLanguage == lang,
                        onClick = { onSelectAppLanguage(lang) },
                        label = {
                            Text(
                                text = if (lang == AppLanguage.SYSTEM) {
                                    strings.systemDefaultLanguage
                                } else {
                                    lang.nativeLabel
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        modifier = Modifier.testTag("language_chip_${lang.storageKey}"),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = strings.engineSectionTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("settings_engine_section_title"),
            )
            Text(
                text = strings.engineSectionSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
        }

        items(EngineType.entries, key = { "engine_option_${it.name}" }) { engineType ->
            val availability = engineAvailabilities[engineType]
            val isSelected = settings.preferredEngine == engineType
            val isSelectable = availability?.isSelectable ?: (engineType == EngineType.GECKO)
            val isLiveInMemory = engineType in initializedEngines

            EngineOptionCard(
                engineType = engineType,
                availability = availability,
                isSelected = isSelected,
                isSelectable = isSelectable,
                isLiveInMemory = isLiveInMemory,
                onClick = { onSelectPreferredEngine(engineType) },
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        item {
            val preferredAvailability = engineAvailabilities[settings.preferredEngine]
            if (activeTab != null &&
                activeTab.engineType != settings.preferredEngine &&
                preferredAvailability?.isSelectable == true
            ) {
                OutlinedButton(
                    onClick = { onOpenCurrentPageInEngine(settings.preferredEngine) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(strings.openCurrentUrlInEngine(settings.preferredEngine.shortBadge))
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.clearOnExitTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = strings.clearOnExitSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.clearDataOnExit,
                    onCheckedChange = onClearDataOnExitChange,
                    modifier = Modifier.testTag("settings_clear_on_exit_switch"),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onShredDataNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_shred_data_button"),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(strings.shredDataNowButton)
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = strings.autoplaySectionTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AutoplayPolicy.entries.forEach { policy ->
                    FilterChip(
                        selected = settings.autoplayPolicy == policy,
                        onClick = { onSelectAutoplayPolicy(policy) },
                        label = { Text(strings.autoplayPolicyLabel(policy)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("autoplay_policy_${policy.storageKey}"),
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.dynamicColorTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = strings.dynamicColorSubtitle,
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
                text = strings.pageZoomSectionTitle,
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
                    Icon(Icons.Rounded.ZoomOut, contentDescription = "-")
                }
                Text(
                    text = if (settings.autoFontSize) {
                        strings.textZoomAuto
                    } else {
                        strings.textZoomCustom(settings.pageZoomPercent)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onStepPageZoom(15) }) {
                    Icon(Icons.Rounded.ZoomIn, contentDescription = "+")
                }
                if (!settings.autoFontSize) {
                    TextButton(onClick = onResetPageZoom) {
                        Text(strings.resetLabel)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = strings.defaultSearchEngineTitle,
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
                Text(text = engine.label, style = MaterialTheme.typography.bodyLarge)
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
                    text = strings.savedSitePermissionsTitle(persistedPermissions.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (persistedPermissions.isNotEmpty()) {
                    TextButton(onClick = onClearAllPermissions) {
                        Text(strings.clearAllLabel)
                    }
                }
            }
        }

        if (persistedPermissions.isEmpty()) {
            item {
                Text(
                    text = strings.noSavedPermissionsHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            items(persistedPermissions, key = { it.origin }) { record ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = record.origin,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val summary = record.decisions.entries.joinToString(" • ") { (type, dec) ->
                            val stateText = if (dec == SitePermissionDecision.ALLOW) "Cho phép" else "Chặn"
                            "${type.displayLabel}: $stateText"
                        }
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onRevokeOrigin(record.origin) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Thu hồi quyền của ${record.origin}")
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
private fun EngineOptionCard(
    engineType: EngineType,
    availability: EngineAvailability?,
    isSelected: Boolean,
    isSelectable: Boolean,
    isLiveInMemory: Boolean,
    onClick: () -> Unit,
) {
    val strings = LocalVekoStrings.current
    val borderColor = when {
        isSelected && isSelectable -> MaterialTheme.colorScheme.primary
        !isSelectable -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val containerColor = when {
        isSelected && isSelectable -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        !isSelectable -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val statusText = when (availability?.status) {
        EngineAvailabilityStatus.AVAILABLE ->
            if (isLiveInMemory) strings.engineReadyInRam else strings.engineReadyLazy
        EngineAvailabilityStatus.BLOCKED -> strings.engineBlockedSdk
        EngineAvailabilityStatus.UNAVAILABLE -> strings.engineUnavailable
        null -> if (engineType == EngineType.GECKO) strings.engineReadyLazy else "..."
    }
    val abiSummary = if (availability != null) {
        val compat = if (availability.abiCompatible) "OK" else "N/A"
        "$compat (${availability.deviceAbis.joinToString()})"
    } else {
        "—"
    }
    val versionSummary = if (availability != null) {
        val commitSuffix = availability.pinnedCommit?.take(12)?.let { " ($it)" }.orEmpty()
        "${availability.engineVersion}$commitSuffix"
    } else {
        "—"
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("engine_card_${engineType.storageKey}")
            .border(
                width = if (isSelected && isSelectable) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(16.dp),
            )
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = isSelected && isSelectable,
                    onClick = onClick,
                    enabled = isSelectable,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append(availability?.engineName ?: engineType.displayName)
                            if (engineType.isRecommendedDefault) {
                                append(strings.recommendedDefaultSuffix)
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelectable) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            EngineMetaLine(
                label = if (strings.code == "vi") "Phiên bản Engine" else "Engine Version",
                value = versionSummary,
            )
            EngineMetaLine(
                label = "Rendering / JS",
                value = "${availability?.renderingEngine ?: engineType.renderingEngineName} • " +
                    "${availability?.javascriptEngine ?: engineType.javascriptEngineName}",
            )
            EngineMetaLine(
                label = if (strings.code == "vi") "Tương thích ABI" else "ABI Compatibility",
                value = abiSummary,
            )
            EngineMetaLine(
                label = if (strings.code == "vi") "Kiểm định SDK" else "SDK Validation",
                value = availability?.sdkValidationStatus?.displayLabel ?: "—",
            )

            val reason = availability?.unavailabilityReason
            if (!isSelectable && !reason.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = if (strings.code == "vi") "Lý do khóa chọn:" else "Unavailable reason:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        if (availability.missingArtifacts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = (if (strings.code == "vi") "Tạo tác còn thiếu: " else "Missing artifacts: ") +
                                    availability.missingArtifacts.joinToString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineMetaLine(
    label: String,
    value: String,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
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
