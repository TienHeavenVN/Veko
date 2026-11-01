package io.veko.browser.engine

import android.content.Context
import android.view.View
import io.veko.browser.data.repository.CookieControlMode
import io.veko.browser.tab.EngineSessionCallbacks
import io.veko.browser.tab.EngineSessionHandle
import io.veko.browser.tab.EngineViewHandle
import io.veko.browser.tab.UrlNavigationPolicy
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Kết quả thực thi biểu thức JavaScript có kiểm soát chính sách bảo mật từ tầng Host.
 * Tuyệt đối không ghi log nội dung script hoặc giá trị trả về để bảo vệ token/cookie trang web.
 */
sealed interface JavaScriptEvalResult {
    data class Success(val serializedValue: String?) : JavaScriptEvalResult
    data class BlockedByPolicy(val reason: String) : JavaScriptEvalResult
    data class UnsupportedByEngine(val reason: String) : JavaScriptEvalResult
    data class ExecutionError(val errorCode: String) : JavaScriptEvalResult
}

/**
 * Chính sách kiểm duyệt thực thi JavaScript từ tầng ứng dụng xuống phiên trình duyệt:
 * - Chặn thực thi trên các trang nội bộ/đặc quyền (`about:`, `data:`, `file:`, `blob:`, `content:`, `moz-extension:`, `chrome:`).
 * - Chặn tiền tố `javascript:` và giới hạn kích thước biểu thức để ngăn lạm dụng bộ nhớ.
 */
object JavaScriptEvaluationPolicy {
    const val MAX_SCRIPT_CHARS: Int = 16_384

    fun validate(
        currentUrl: String,
        script: String,
        hostEvaluationAllowed: Boolean = true,
    ): JavaScriptEvalResult.BlockedByPolicy? {
        if (!hostEvaluationAllowed) {
            return JavaScriptEvalResult.BlockedByPolicy("Host JavaScript evaluation is disabled by security policy.")
        }
        val trimmedScript = script.trim()
        if (trimmedScript.isEmpty()) {
            return JavaScriptEvalResult.BlockedByPolicy("JavaScript expression must not be blank.")
        }
        if (trimmedScript.length > MAX_SCRIPT_CHARS) {
            return JavaScriptEvalResult.BlockedByPolicy("JavaScript expression exceeds maximum length ($MAX_SCRIPT_CHARS chars).")
        }
        if (trimmedScript.lowercase(Locale.US).startsWith("javascript:")) {
            return JavaScriptEvalResult.BlockedByPolicy("javascript: URI scheme injection is forbidden.")
        }
        val normalizedOriginUrl = currentUrl.trim()
        if (normalizedOriginUrl.isEmpty() ||
            UrlNavigationPolicy.isNewTabBlankUrl(normalizedOriginUrl) ||
            UrlNavigationPolicy.isInternalErrorDataUrl(normalizedOriginUrl)
        ) {
            return JavaScriptEvalResult.BlockedByPolicy("JavaScript evaluation is blocked on blank or internal error pages.")
        }
        val lowerUrl = normalizedOriginUrl.lowercase(Locale.US)
        if (!lowerUrl.startsWith("https://") && !lowerUrl.startsWith("http://")) {
            return JavaScriptEvalResult.BlockedByPolicy("JavaScript evaluation is restricted to standard HTTP/HTTPS web origins.")
        }
        return null
    }
}

/**
 * Hợp đồng chung cho mọi nhân trình duyệt thực tế trong kiến trúc Dual-Engine của Veko
 * (`GeckoBrowserEngine` cho Mozilla GeckoView và `ChromiumBrowserEngine` cho Chromium Content).
 *
 * Giao diện này hoàn toàn độc lập khỏi các lớp nội bộ của GeckoView (`org.mozilla.geckoview.*`)
 * hay Chromium (`org.chromium.*`), giúp tầng Compose UI và MVI `:browser-core` sạch phụ thuộc.
 */
interface BrowserEngine {
    val engineType: EngineType
    val capabilities: EngineCapabilities
    val isInitialized: StateFlow<Boolean>

    /**
     * Kiểm tra tính khả dụng, tương thích ABI và tính hợp lệ của gói SDK mà KHÔNG khởi chạy
     * tiến trình con hay nạp thư viện native nặng của engine.
     */
    fun checkAvailability(): EngineAvailability

    /**
     * Khởi tạo lười (lazy) runtime của engine khi và chỉ khi có tab thuộc engine này cần mở.
     */
    fun initialize(): Boolean

    /**
     * Giải phóng sạch sẽ runtime, phiên mở và bộ nhớ đệm liên kết với engine.
     */
    fun destroy()

    /**
     * Xử lý sự kiện thiếu bộ nhớ (`ComponentCallbacks2.onTrimMemory`) từ hệ điều hành Android.
     */
    fun onLowMemory(level: Int)

    /**
     * Tạo View nền tảng của engine để gắn vào `AndroidView` trong Jetpack Compose.
     */
    fun createPlatformView(context: Context): View

    /**
     * Bọc View nền tảng trong `EngineViewHandle` dùng `WeakReference` để không rò rỉ `Activity`.
     */
    fun wrapPlatformView(platformView: View): EngineViewHandle

    /**
     * Tạo một phiên duyệt web độc lập (cô lập hoàn toàn giữa Regular và Private/Off-The-Record).
     */
    fun createSession(
        isPrivate: Boolean,
        isDesktopMode: Boolean,
        openImmediately: Boolean,
        callbacks: EngineSessionCallbacks,
    ): EngineSessionHandle

    fun activeSessionCount(): Int

    fun activePrivateSessionCount(): Int

    /**
     * Giải phóng ngữ cảnh duyệt riêng tư của riêng engine này khi không còn tab riêng tư nào thuộc nó.
     */
    fun releasePrivateContextIfUnused(activePrivateTabsForEngine: Int)

    /**
     * Quản lý/xóa dữ liệu lưu trữ (cookie, DOM storage, bộ nhớ đệm) cô lập theo riêng hồ sơ của engine này.
     */
    fun clearBrowsingStorage(
        includeCookies: Boolean = true,
        includeCache: Boolean = true,
        onlyPrivate: Boolean = false,
    )

    fun applyCookiePolicy(mode: CookieControlMode)

    fun applyPageZoom(autoFontSize: Boolean, zoomPercent: Int)

    /**
     * Thực thi JavaScript có kiểm duyệt chính sách bảo mật trên một phiên đang mở của engine.
     */
    fun evaluateJavaScript(
        session: EngineSessionHandle,
        currentUrl: String,
        script: String,
        onResult: (JavaScriptEvalResult) -> Unit,
    )

    fun setOnRuntimeShutdownListener(listener: (() -> Unit)?)
}
