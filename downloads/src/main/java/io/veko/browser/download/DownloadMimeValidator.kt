package io.veko.browser.download

import androidx.compose.runtime.Immutable
import java.util.Locale

@Immutable
data class MimeValidationResult(
    val effectiveMimeType: String,
    val isPotentiallyDangerousExecutable: Boolean,
    val wasMimeSpoofed: Boolean,
)

/**
 * Bộ xác thực kiểu MIME tải xuống nghiêm ngặt cho Veko Browser:
 * - Chuẩn hóa RFC 2045 Content-Type và loại bỏ ký tự điều khiển / tham số phụ.
 * - Phát hiện giả mạo kiểu MIME (ví dụ máy chủ trả text/plain hoặc image/png nhưng đuôi tệp là .apk, .dex, .so, .sh, .exe).
 * - Khi phát hiện giả mạo tệp thực thi/gói cài đặt, ép về MIME chuẩn hoặc application/octet-stream để hệ điều hành không tự mở nhầm.
 */
object DownloadMimeValidator {

    private val MIME_TOKEN_REGEX = Regex("^[a-z0-9!#$&^_.+-]{1,64}/[a-z0-9!#$&^_.+-]{1,64}$")

    private val EXECUTABLE_EXT_TO_CANONICAL_MIME = mapOf(
        "apk" to "application/vnd.android.package-archive",
        "apks" to "application/octet-stream",
        "xapk" to "application/octet-stream",
        "dex" to "application/octet-stream",
        "so" to "application/octet-stream",
        "elf" to "application/octet-stream",
        "sh" to "application/x-sh",
        "exe" to "application/vnd.microsoft.portable-executable",
        "msi" to "application/octet-stream",
        "bat" to "application/octet-stream",
        "jar" to "application/java-archive",
    )

    private val SAFE_EXTENSION_TO_MIME = mapOf(
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "json" to "application/json",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "html" to "text/html",
        "htm" to "text/html",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "ogg" to "audio/ogg",
    )

    fun validate(rawContentType: String?, sanitizedFileName: String): MimeValidationResult {
        val ext = sanitizedFileName
            .substringAfterLast('.', missingDelimiterValue = "")
            .trim()
            .lowercase(Locale.ROOT)

        val parsedHeaderMime = rawContentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { MIME_TOKEN_REGEX.matches(it) }

        val canonicalExecMime = EXECUTABLE_EXT_TO_CANONICAL_MIME[ext]
        if (canonicalExecMime != null) {
            val spoofed = parsedHeaderMime != null &&
                parsedHeaderMime != canonicalExecMime &&
                parsedHeaderMime != "application/octet-stream" &&
                parsedHeaderMime != "binary/octet-stream"
            return MimeValidationResult(
                effectiveMimeType = canonicalExecMime,
                isPotentiallyDangerousExecutable = true,
                wasMimeSpoofed = spoofed,
            )
        }

        if (parsedHeaderMime == "application/vnd.android.package-archive") {
            return MimeValidationResult(
                effectiveMimeType = parsedHeaderMime,
                isPotentiallyDangerousExecutable = true,
                wasMimeSpoofed = ext.isNotEmpty() && ext != "apk",
            )
        }

        if (parsedHeaderMime != null &&
            parsedHeaderMime != "application/octet-stream" &&
            parsedHeaderMime != "binary/octet-stream"
        ) {
            return MimeValidationResult(
                effectiveMimeType = parsedHeaderMime,
                isPotentiallyDangerousExecutable = false,
                wasMimeSpoofed = false,
            )
        }

        val fallback = SAFE_EXTENSION_TO_MIME[ext] ?: "application/octet-stream"
        return MimeValidationResult(
            effectiveMimeType = fallback,
            isPotentiallyDangerousExecutable = false,
            wasMimeSpoofed = false,
        )
    }
}
