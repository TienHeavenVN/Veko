package io.veko.browser.security

import androidx.compose.runtime.Immutable
import io.veko.browser.tab.UrlNavigationPolicy
import java.util.Locale

/**
 * Mã lỗi chứng chỉ TLS chuẩn hóa theo bảng mã net::Error của Chromium (net/base/net_error_list.h).
 */
enum class TlsCertificateError(val chromiumNetError: Int, val summaryVi: String) {
    CERT_COMMON_NAME_INVALID(-200, "Tên miền trên chứng chỉ không khớp với địa chỉ trang web"),
    CERT_DATE_INVALID(-201, "Chứng chỉ TLS đã hết hạn hoặc chưa đến thời điểm hiệu lực"),
    CERT_AUTHORITY_INVALID(-202, "Tổ chức phát hành chứng chỉ (CA) không nằm trong danh sách tin cậy"),
    CERT_CONTAINS_ERRORS(-203, "Cấu trúc chứng chỉ TLS bị hỏng"),
    CERT_NO_REVOCATION_MECHANISM(-204, "Chứng chỉ thiếu cơ chế kiểm tra thu hồi"),
    CERT_UNABLE_TO_CHECK_REVOCATION(-205, "Không thể xác minh trạng thái thu hồi chứng chỉ"),
    CERT_REVOKED(-206, "Chứng chỉ TLS đã bị thu hồi khẩn cấp"),
    CERT_INVALID(-207, "Chứng chỉ TLS không hợp lệ"),
    CERT_WEAK_SIGNATURE_ALGORITHM(-208, "Chứng chỉ dùng thuật toán chữ ký yếu đã ngừng hỗ trợ"),
    CERT_WEAK_KEY(-211, "Khóa công khai của chứng chỉ quá ngắn, có nguy cơ bị phá mã"),
    SSL_PINNED_KEY_NOT_IN_CERT_CHAIN(-150, "Chuỗi chứng chỉ vi phạm khóa ghim bảo mật (Key Pinning / HSTS)"),
    SSL_VERSION_OR_CIPHER_MISMATCH(-113, "Máy chủ chỉ hỗ trợ phiên bản TLS/Cipher cũ không an toàn"),
    UNKNOWN_TLS_FAILURE(-299, "Lỗi thiết lập kết nối mã hóa TLS"),
    ;

    companion object {
        fun fromChromiumNetError(netError: Int): TlsCertificateError =
            entries.firstOrNull { it.chromiumNetError == netError } ?: UNKNOWN_TLS_FAILURE
    }
}

@Immutable
data class TlsValidationVerdict(
    val allowProceed: Boolean,
    val allowUserBypass: Boolean,
    val error: TlsCertificateError,
    val sanitizedHost: String,
    val technicalCode: Int,
)

enum class MixedContentDecision {
    ALLOW,
    UPGRADE_TO_HTTPS,
    BLOCK_MIXED_CONTENT,
}

/**
 * Chính sách Bảo mật TLS & Mixed Content bắt buộc của Veko Browser:
 * - TUYỆT ĐỐI KHÔNG cho phép bỏ qua (bypass) bất kỳ lỗi chứng chỉ TLS không hợp lệ nào (allowProceed = false, allowUserBypass = false).
 * - Hạn chế nghiêm ngặt Mixed Content (tài nguyên http:// nhúng bên trong trang gốc https://).
 */
object TlsCertificatePolicy {

    // Các loại tài nguyên thực thi/nhạy cảm bắt buộc chặn ngay lập tức nếu tải qua HTTP từ trang HTTPS
    private val ACTIVE_RESOURCE_TYPES = setOf(
        "script",
        "stylesheet",
        "sub_frame",
        "subdocument",
        "xmlhttprequest",
        "fetch",
        "websocket",
        "worker",
        "shared_worker",
        "service_worker",
        "object",
        "csp_report",
    )

    fun evaluateCertificateError(
        chromiumNetError: Int,
        rawUrlOrHost: String?,
        strictHstsActive: Boolean = true,
    ): TlsValidationVerdict {
        val mapped = TlsCertificateError.fromChromiumNetError(chromiumNetError)
        val safeHost = UrlNavigationPolicy.formatDisplayHost(rawUrlOrHost).take(128)
        // Quy tắc bất biến: không bao giờ cấp quyền vượt rào TLS lỗi dưới bất kỳ cờ cấu hình nào
        return TlsValidationVerdict(
            allowProceed = false,
            allowUserBypass = false,
            error = mapped,
            sanitizedHost = safeHost,
            technicalCode = if (strictHstsActive && chromiumNetError == 0) -207 else chromiumNetError,
        )
    }

    /**
     * Đánh giá Mixed Content khi một trang gốc HTTPS yêu cầu tải tài nguyên con HTTP không mã hóa.
     */
    fun evaluateMixedContent(
        topFrameUrl: String?,
        resourceUrl: String?,
        resourceType: String,
        blockAllMixedContent: Boolean,
        autoUpgradePassiveHttps: Boolean = true,
    ): MixedContentDecision {
        val topTrimmed = topFrameUrl?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val resTrimmed = resourceUrl?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (!topTrimmed.startsWith("https://") || !resTrimmed.startsWith("http://")) {
            return MixedContentDecision.ALLOW
        }

        // Cho phép loopback cục bộ theo chuẩn W3C Secure Contexts (http://127.0.0.1 / http://[::1])
        val resOrigin = UrlNavigationPolicy.extractOrigin(resourceUrl).orEmpty()
        if (resOrigin.startsWith("http://127.0.0.1") || resOrigin.startsWith("http://[::1]")) {
            return MixedContentDecision.ALLOW
        }

        val normalizedType = resourceType.trim().lowercase(Locale.ROOT)
        if (blockAllMixedContent || normalizedType in ACTIVE_RESOURCE_TYPES) {
            return MixedContentDecision.BLOCK_MIXED_CONTENT
        }
        return if (autoUpgradePassiveHttps) {
            MixedContentDecision.UPGRADE_TO_HTTPS
        } else {
            MixedContentDecision.BLOCK_MIXED_CONTENT
        }
    }

    fun upgradeHttpUrlToHttps(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true)) return null
        val upgraded = "https://" + trimmed.substring(7)
        return if (UrlNavigationPolicy.isRecordableHttpUrl(upgraded)) upgraded else null
    }
}
