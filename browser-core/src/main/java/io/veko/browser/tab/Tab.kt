package io.veko.browser.tab

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import io.veko.browser.permission.SitePermissionPrompt
import java.util.UUID

/**
 * Mức độ bảo mật kết nối của trang hiện tại để hiển thị biểu tượng ổ khóa trên AddressBar.
 */
enum class SecurityState {
    SECURE,
    INSECURE,
    LOCAL_OR_UNKNOWN,
}

/**
 * Trạng thái điều hướng bất biến (immutable) của một phiên duyệt web, tách rời khỏi GeckoSession.
 * Giúp Compose dùng cơ chế smart-recomposition mà không giữ tham chiếu trực tiếp vào đối tượng JNI.
 */
@Immutable
data class TabSessionState(
    val url: String = DEFAULT_HOME_URL,
    val title: String = "Trang mới",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isDesktopMode: Boolean = false,
    val securityState: SecurityState = SecurityState.LOCAL_OR_UNKNOWN,
    val isCrashed: Boolean = false,
    val hasLoadError: Boolean = false,
    val isFullScreen: Boolean = false,
    val isAtPageTop: Boolean = true,
    // Chuỗi trạng thái gốc do GeckoSession.SessionState tuần tự hóa để khôi phục lịch sử back/forward
    val serializedEngineState: String? = null,
) {
    companion object {
        const val DEFAULT_HOME_URL = "https://duckduckgo.com"
    }
}

/**
 * Mô hình dữ liệu một Tab của Veko.
 * Không nhúng GeckoSession trực tiếp vào data class để tránh lỗi equals/hashCode và rò rỉ bộ nhớ UI.
 */
@Immutable
data class Tab(
    val id: String = UUID.randomUUID().toString(),
    val isPrivate: Boolean = false,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastAccessedEpochMs: Long = System.currentTimeMillis(),
    // Bộ đếm đơn điệu phân định chính xác thứ tự LRU ngay cả khi tạo/chuyển nhiều tab trong cùng 1ms
    val accessSequence: Long = 0L,
    val sessionState: TabSessionState = TabSessionState(),
    val previewBitmap: ImageBitmap? = null,
)

/**
 * Trạng thái thanh Tìm trong trang (Find in Page) kết nối trực tiếp tới Gecko SessionFinder.
 */
@Immutable
data class FindInPageState(
    val isVisible: Boolean = false,
    val query: String = "",
    val currentMatch: Int = 0,
    val totalMatches: Int = 0,
    val notFound: Boolean = false,
    val matchCase: Boolean = false,
)

/**
 * Menu ngữ cảnh khi nhấn giữ lâu vào liên kết hoặc hình ảnh/phương tiện trên website.
 */
@Immutable
data class WebContextMenuState(
    val tabId: String,
    val isPrivate: Boolean,
    val linkUri: String?,
    val linkText: String?,
    val mediaSrcUri: String?,
    val mediaType: Int = MEDIA_NONE,
    val title: String?,
) {
    companion object {
        const val MEDIA_NONE = 0
        const val MEDIA_IMAGE = 1
        const val MEDIA_VIDEO = 2
        const val MEDIA_AUDIO = 3
    }
}

@Immutable
data class WebChoiceOption(
    val id: String,
    val label: String,
    val disabled: Boolean = false,
    val selected: Boolean = false,
    val isGroupHeader: Boolean = false,
)

/**
 * Mô hình hộp thoại tương tác từ trang web (JS alert/confirm/prompt, HTML <select>, Slow Script).
 */
@Immutable
sealed interface WebDialogPrompt {
    val id: String
    val tabId: String
    fun cancel()

    @Immutable
    data class Alert(
        override val id: String,
        override val tabId: String,
        val title: String?,
        val message: String,
        val onDismiss: () -> Unit,
    ) : WebDialogPrompt {
        override fun cancel() = onDismiss()
    }

    @Immutable
    data class Confirm(
        override val id: String,
        override val tabId: String,
        val title: String?,
        val message: String,
        val positiveLabel: String = "Đồng ý",
        val negativeLabel: String = "Hủy",
        val onDecision: (Boolean) -> Unit,
    ) : WebDialogPrompt {
        override fun cancel() = onDecision(false)
    }

    @Immutable
    data class TextInput(
        override val id: String,
        override val tabId: String,
        val title: String?,
        val message: String,
        val defaultValue: String,
        val onConfirm: (String) -> Unit,
        val onDismiss: () -> Unit,
    ) : WebDialogPrompt {
        override fun cancel() = onDismiss()
    }

    @Immutable
    data class ChoiceSelect(
        override val id: String,
        override val tabId: String,
        val title: String?,
        val isMultiple: Boolean,
        val options: List<WebChoiceOption>,
        val onConfirmIds: (List<String>) -> Unit,
        val onDismiss: () -> Unit,
    ) : WebDialogPrompt {
        override fun cancel() = onDismiss()
    }

    @Immutable
    data class SlowScript(
        override val id: String,
        override val tabId: String,
        val scriptFileName: String,
        val onAction: (stopScript: Boolean) -> Unit,
    ) : WebDialogPrompt {
        override fun cancel() = onAction(false)
    }
}

/**
 * Yêu cầu xác nhận mở ứng dụng bên ngoài (mailto:, tel:, geo:, market:) khi người dùng chủ động nhấn liên kết.
 */
@Immutable
data class ExternalAppPrompt(
    val id: String = UUID.randomUUID().toString(),
    val tabId: String,
    val scheme: String,
    val uri: String,
)

/**
 * Trạng thái tổng thể của toàn bộ danh sách Tab và các công cụ tương tác trang phát qua StateFlow.
 */
@Immutable
data class BrowserTabsUiState(
    val tabs: List<Tab> = emptyList(),
    val activeTabId: String? = null,
    val isRestoring: Boolean = true,
    val findInPage: FindInPageState = FindInPageState(),
    val contextMenu: WebContextMenuState? = null,
    val activeWebPrompt: WebDialogPrompt? = null,
    val activePermissionPrompt: SitePermissionPrompt? = null,
    val externalAppPrompt: ExternalAppPrompt? = null,
) {
    val activeTab: Tab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    val activeTabIndex: Int
        get() = tabs.indexOfFirst { it.id == activeTabId }

    val privateTabCount: Int
        get() = tabs.count { it.isPrivate }
}
