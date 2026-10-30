package io.veko.browser.tab

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
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
    val sessionState: TabSessionState = TabSessionState(),
    val previewBitmap: ImageBitmap? = null,
)

/**
 * Trạng thái tổng thể của toàn bộ danh sách Tab phát qua StateFlow xuống tầng Compose UI.
 */
@Immutable
data class BrowserTabsUiState(
    val tabs: List<Tab> = emptyList(),
    val activeTabId: String? = null,
    val isRestoring: Boolean = true,
) {
    val activeTab: Tab?
        get() = tabs.firstOrNull { it.id == activeTabId }

    val activeTabIndex: Int
        get() = tabs.indexOfFirst { it.id == activeTabId }
}
