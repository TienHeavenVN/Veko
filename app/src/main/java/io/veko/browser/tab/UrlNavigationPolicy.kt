package io.veko.browser.tab

import io.veko.browser.data.repository.SearchEngine
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Chính sách chuẩn hóa URL, kiểm soát scheme theo ngữ cảnh điều hướng và sinh trang lỗi an toàn.
 * Không phụ thuộc android.net.Uri hay android.util.Patterns để chạy đồng nhất trên cả Android lẫn JVM test,
 * đồng thời hỗ trợ đầy đủ URL Unicode/WHATWG hợp lệ mà không bao giờ ném ngoại lệ làm crash ứng dụng.
 */
object UrlNavigationPolicy {

    const val TARGET_WINDOW_NONE = 0
    const val TARGET_WINDOW_CURRENT = 1
    const val TARGET_WINDOW_NEW = 2

    private const val ABOUT_BLANK = "about:blank"
    private const val INTERNAL_ERROR_FRAGMENT = "#veko-internal-error"
    private const val MAX_OMNIBOX_INPUT_LENGTH = 32_768
    private const val MAX_SEARCH_FALLBACK_LENGTH = 2_048
    private const val MAX_SAFE_DATA_URI_LENGTH = 2 * 1024 * 1024
    private const val MAX_ERROR_DISPLAY_URI_LENGTH = 512

    private val SCHEME_PREFIX_REGEX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val PORT_PREFIX_REGEX = Regex("^(\\d{1,5})(?:[/?#].*)?$")
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    // Các URI nội bộ duy nhất của Gecko được phép hiển thị tài liệu trống/trang lỗi mạng
    private val SAFE_ENGINE_ABOUT_BASES = setOf(
        "about:blank",
        "about:neterror",
        "about:certerror",
        "about:httpsonlyerror",
    )

    // Chỉ cho phép các MIME thụ động, không chứa script khi điều hướng trực tiếp tới data: URI
    private val SAFE_PASSIVE_DATA_MIME_TYPES = setOf(
        "text/plain",
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp",
        "image/bmp",
        "image/x-icon",
    )

    // Danh sách scheme không bao giờ là tên miền trong biểu thức host:port
    private val KNOWN_URI_SCHEMES = setOf(
        "http", "https", "about", "data", "blob", "javascript", "vbscript",
        "file", "content", "intent", "chrome", "resource", "jar", "moz-extension",
        "view-source", "mailto", "tel", "sms", "smsto", "geo", "market", "ws", "wss", "ftp",
    )

    // Chỉ các scheme liên kết ứng dụng chuẩn mới được hỏi ý kiến mở ngoài trình duyệt khi có tương tác người dùng thật
    private val SAFE_EXTERNAL_APP_SCHEMES = setOf(
        "mailto", "tel", "sms", "smsto", "geo", "market",
    )
    private const val MAX_EXTERNAL_APP_URI_LENGTH = 2_048

    /**
     * Ngữ cảnh 1: Chuẩn hóa chuỗi người dùng nhập vào Omnibox thành URL an toàn hoặc URL tìm kiếm.
     */
    fun normalizeUserInput(
        rawInput: String,
        searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    ): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return TabSessionState.DEFAULT_HOME_URL

        // Nếu chứa ký tự điều khiển ASCII hoặc quá dài -> chuyển thẳng sang tìm kiếm đã làm sạch
        if (containsAsciiControlChars(trimmed) || trimmed.length > MAX_OMNIBOX_INPUT_LENGTH) {
            return searchEngine.buildSearchUrl(sanitizeSearchQuery(trimmed))
        }

        if (trimmed.equals(ABOUT_BLANK, ignoreCase = true)) {
            return ABOUT_BLANK
        }

        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            val normalizedHttp = normalizeHttpUrl(trimmed)
            return normalizedHttp ?: searchEngine.buildSearchUrl(sanitizeSearchQuery(trimmed))
        }

        // Bất kỳ khoảng trắng nào trong chuỗi không có http(s):// đều là truy vấn tìm kiếm
        if (trimmed.any { it.isWhitespace() }) {
            return searchEngine.buildSearchUrl(sanitizeSearchQuery(trimmed))
        }

        // Phân biệt biểu thức host:port (ví dụ localhost:8080/api) với các scheme nguy hiểm (javascript:, file:, data:, about:config)
        val authorityCandidate = trimmed.substringBefore('/').substringBefore('?').substringBefore('#')
        val schemeMatch = SCHEME_PREFIX_REGEX.matchEntire(trimmed)
        if (schemeMatch != null && ':' in authorityCandidate) {
            val prefix = schemeMatch.groupValues[1].lowercase(Locale.ROOT)
            val rest = schemeMatch.groupValues[2]
            val portMatch = PORT_PREFIX_REGEX.matchEntire(rest)
            if (prefix !in KNOWN_URI_SCHEMES && portMatch != null) {
                val candidateWithHttps = "https://$trimmed"
                normalizeHttpUrl(candidateWithHttps)?.let { return it }
            }
            // Mọi scheme khác (javascript:, data:, blob:, file:, content:, intent:, about:config...) bị chặn mở trực tiếp
            return searchEngine.buildSearchUrl(sanitizeSearchQuery(trimmed))
        }

        if (looksLikeBareDomainOrHost(trimmed)) {
            val candidateWithHttps = "https://$trimmed"
            normalizeHttpUrl(candidateWithHttps)?.let { return it }
        }

        return searchEngine.buildSearchUrl(sanitizeSearchQuery(trimmed))
    }

    /**
     * Ngữ cảnh 2: Kiểm tra liên kết ACTION_VIEW nhận từ ứng dụng bên ngoài (MainActivity).
     */
    fun sanitizeExternalIntentUri(
        action: String?,
        rawUri: String?,
        isLaunchedFromHistory: Boolean = false,
        isAlreadyConsumed: Boolean = false,
    ): String? {
        if (action != "android.intent.action.VIEW") return null
        if (isLaunchedFromHistory || isAlreadyConsumed) return null
        val candidate = rawUri?.trim() ?: return null
        if (candidate.isEmpty() || candidate.length > MAX_OMNIBOX_INPUT_LENGTH) return null
        if (containsAsciiControlChars(candidate)) return null
        return normalizeHttpUrl(candidate)
    }

    /**
     * Ngữ cảnh 3: Đánh giá yêu cầu điều hướng từ trang web trong NavigationDelegate.onLoadRequest.
     */
    fun evaluateWebLoadRequest(
        uri: String?,
        triggerUri: String? = null,
        targetWindow: Int = TARGET_WINDOW_CURRENT,
        isRedirect: Boolean = false,
        hasUserGesture: Boolean = false,
        isDirectNavigation: Boolean = false,
    ): Boolean {
        val candidate = uri?.trim() ?: return false
        if (candidate.isEmpty() || candidate.length > MAX_SAFE_DATA_URI_LENGTH) return false
        if (containsAsciiControlChars(candidate)) return false

        val scheme = extractScheme(candidate) ?: return false
        return when (scheme) {
            "http", "https" -> normalizeHttpUrl(candidate) != null
            "about" -> {
                // Không cho trang web tự ý chuyển hướng HTTP sang URI nội bộ about:*
                if (isRedirect && !isDirectNavigation) return false
                val baseAbout = candidate.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
                baseAbout in SAFE_ENGINE_ABOUT_BASES
            }
            "blob" -> {
                if (isRedirect) return false
                val triggerOrigin = extractHttpOrigin(triggerUri) ?: return false
                val blobInnerUrl = candidate.substring(5)
                val blobOrigin = extractHttpOrigin(blobInnerUrl) ?: return false
                blobOrigin == triggerOrigin
            }
            "data" -> {
                // Chặn tuyệt đối redirect sang data: và popup window.open('data:...') để chống giả mạo/XSS
                if (isRedirect || targetWindow == TARGET_WINDOW_NEW) return false
                if (!hasUserGesture && !isDirectNavigation && triggerUri != null) return false
                isSafePassiveDataUri(candidate)
            }
            else -> false
        }
    }

    /**
     * Ngữ cảnh 4: Kiểm tra URI đích khi trang web yêu cầu mở cửa sổ/tab popup mới (onNewSession).
     */
    fun sanitizePopupTargetUri(rawUri: String?, parentUrl: String?): String? {
        val trimmed = rawUri?.trim().orEmpty()
        // window.open("", "_blank") truyền chuỗi rỗng hoặc about:blank trước khi nạp tài liệu đích
        if (trimmed.isEmpty() || trimmed.equals(ABOUT_BLANK, ignoreCase = true)) {
            return ABOUT_BLANK
        }
        if (trimmed.length > MAX_OMNIBOX_INPUT_LENGTH || containsAsciiControlChars(trimmed)) {
            return null
        }
        return when (extractScheme(trimmed)) {
            "http", "https" -> normalizeHttpUrl(trimmed)
            "blob" -> {
                val parentOrigin = extractHttpOrigin(parentUrl) ?: return null
                val blobOrigin = extractHttpOrigin(trimmed.substring(5)) ?: return null
                if (parentOrigin == blobOrigin) trimmed else null
            }
            else -> null
        }
    }

    /**
     * Ngữ cảnh 5: Khử độc URL khi đọc lại phiên làm việc từ ổ đĩa (phòng trường hợp dữ liệu lưu bị hỏng).
     */
    fun sanitizeRestoredUrl(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || containsAsciiControlChars(trimmed) || trimmed.length > MAX_OMNIBOX_INPUT_LENGTH) {
            return TabSessionState.DEFAULT_HOME_URL
        }
        if (trimmed.equals(ABOUT_BLANK, ignoreCase = true)) {
            return ABOUT_BLANK
        }
        return normalizeHttpUrl(trimmed) ?: TabSessionState.DEFAULT_HOME_URL
    }

    /**
     * Chỉ cho phép lưu vào Room History/Bookmarks các URL http/https hợp lệ.
     */
    fun isRecordableHttpUrl(rawUrl: String?): Boolean {
        val trimmed = rawUrl?.trim() ?: return false
        if (trimmed.isEmpty() || containsAsciiControlChars(trimmed) || trimmed.length > MAX_OMNIBOX_INPUT_LENGTH) {
            return false
        }
        return normalizeHttpUrl(trimmed) != null
    }

    /**
     * Phân loại liên kết mở ứng dụng bên ngoài (mailto:, tel:, geo:, market:):
     * Bắt buộc có thao tác chạm trực tiếp của người dùng (hasUserGesture) và KHÔNG phải tự động redirect để chống mở nhầm app ngoài.
     */
    fun classifyExternalAppUri(
        uri: String?,
        isRedirect: Boolean,
        hasUserGesture: Boolean,
    ): Pair<String, String>? {
        if (!hasUserGesture || isRedirect) return null
        val candidate = uri?.trim() ?: return null
        if (candidate.isEmpty() || candidate.length > MAX_EXTERNAL_APP_URI_LENGTH) return null
        if (containsAsciiControlChars(candidate)) return null

        val scheme = extractScheme(candidate) ?: return null
        if (scheme !in SAFE_EXTERNAL_APP_SCHEMES) return null

        val payload = candidate.substring(scheme.length + 1).trim()
        if (payload.isEmpty()) return null
        return scheme to stripControlAndBidiChars(candidate)
    }

    /**
     * Trích xuất origin chuẩn hóa (scheme://host[:port]) cho kiểm tra quyền trang web và trạng thái bảo mật.
     */
    fun extractOrigin(rawUrl: String?): String? = extractHttpOrigin(rawUrl)

    /**
     * Trích xuất tên miền hiển thị an toàn trên thanh địa chỉ, loại bỏ phần userinfo@ để chống giả mạo tên miền (URL spoofing).
     */
    fun formatDisplayHost(rawUrl: String?): String {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals(ABOUT_BLANK, ignoreCase = true)) {
            return "Trang mới"
        }
        if (isInternalErrorDataUrl(trimmed)) {
            return "Không thể mở trang"
        }
        val normalized = normalizeHttpUrl(trimmed)
        if (normalized != null) {
            val afterScheme = normalized.substringAfter("://")
            val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            val hostPort = authority.substringAfterLast('@')
            return hostPort.removePrefix("www.").ifBlank { normalized }
        }
        return stripControlAndBidiChars(trimmed).take(120)
    }

    fun isNewTabBlankUrl(rawUrl: String?): Boolean {
        val trimmed = rawUrl?.trim().orEmpty()
        return trimmed.isEmpty() || trimmed.equals(ABOUT_BLANK, ignoreCase = true)
    }

    /**
     * Nhận diện trang lỗi nội bộ do Veko sinh ra để không ghi đè mất URL gốc của tab khi mất mạng.
     */
    fun isInternalErrorDataUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("data:text/html", ignoreCase = true) && url.endsWith(INTERNAL_ERROR_FRAGMENT)
    }

    /**
     * Sinh trang lỗi tĩnh có CSP khóa chặt script và escape toàn bộ ký tự đặc biệt/bidi spoofing.
     */
    fun buildSafeErrorPageDataUrl(failedUri: String?, errorCode: Int): String {
        val displayUri = failedUri
            ?.takeIf { it.isNotBlank() && !isInternalErrorDataUrl(it) }
            ?.let { stripControlAndBidiChars(it).take(MAX_ERROR_DISPLAY_URI_LENGTH) }
            ?: "unknown"
        val escapedUri = htmlEscapeForErrorPage(displayUri)
        val errorHtml = """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'none'; img-src 'none'; object-src 'none';">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;padding:32px 20px;background:#0F1424;color:#F2F5FF}
            .card{max-width:440px;margin:40px auto;padding:24px;border-radius:20px;background:#1A2138}
            h2{margin-top:0;color:#FF6B6B}code{word-break:break-all;color:#9BA7C7}</style></head>
            <body><div class="card"><h2>Không thể mở trang</h2>
            <p>Veko không kết nối được tới địa chỉ:</p><code>$escapedUri</code>
            <p style="margin-top:16px;font-size:13px;opacity:0.75">Mã lỗi Gecko: $errorCode</p>
            </div></body></html>
        """.trimIndent()
        val encodedHtml = URLEncoder.encode(errorHtml, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "data:text/html;charset=utf-8,$encodedHtml$INTERNAL_ERROR_FRAGMENT"
    }

    fun htmlEscapeForErrorPage(raw: String): String {
        val cleaned = stripControlAndBidiChars(raw)
        val sb = StringBuilder(cleaned.length + 16)
        for (ch in cleaned) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                '`' -> sb.append("&#96;")
                '/' -> sb.append("&#x2F;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun normalizeHttpUrl(candidate: String): String? {
        val colonSlashSlash = candidate.indexOf("://")
        if (colonSlashSlash <= 0) return null
        val scheme = candidate.substring(0, colonSlashSlash).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null

        val afterScheme = candidate.substring(colonSlashSlash + 3)
        if (afterScheme.isEmpty() || afterScheme.startsWith("/")) return null
        if (afterScheme.any { it.isWhitespace() }) return null

        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it == -1) afterScheme.length else it
        }
        val authority = afterScheme.substring(0, authorityEnd)
        val suffix = afterScheme.substring(authorityEnd)
        if (authority.isEmpty()) return null

        // Chặn userinfo chứa ký tự giả mạo hoặc rỗng (ví dụ https://@evil.com)
        val hostPort = if ('@' in authority) {
            val userInfo = authority.substringBeforeLast('@')
            if (userInfo.isEmpty()) return null
            authority.substringAfterLast('@')
        } else {
            authority
        }
        if (!isValidHostPort(hostPort, allowSingleLabelHost = true)) return null

        return "$scheme://$authority$suffix"
    }

    private fun extractHttpOrigin(rawUrl: String?): String? {
        val normalized = rawUrl?.trim()?.let { normalizeHttpUrl(it) } ?: return null
        val schemeEnd = normalized.indexOf("://")
        val afterScheme = normalized.substring(schemeEnd + 3)
        val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it == -1) afterScheme.length else it
        }
        val hostPort = afterScheme.substring(0, authorityEnd).substringAfterLast('@').lowercase(Locale.ROOT)
        val scheme = normalized.substring(0, schemeEnd)
        return "$scheme://$hostPort"
    }

    private fun looksLikeBareDomainOrHost(input: String): Boolean {
        val authorityEnd = input.indexOfAny(charArrayOf('/', '?', '#')).let {
            if (it == -1) input.length else it
        }
        val hostPort = input.substring(0, authorityEnd)
        if (hostPort.isEmpty() || '@' in hostPort) return false
        return isValidHostPort(hostPort, allowSingleLabelHost = false)
    }

    private fun isValidHostPort(hostPort: String, allowSingleLabelHost: Boolean): Boolean {
        if (hostPort.isEmpty()) return false
        val host: String
        val portStr: String?

        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket <= 1) return false
            host = hostPort.substring(0, closeBracket + 1)
            val remainder = hostPort.substring(closeBracket + 1)
            portStr = when {
                remainder.isEmpty() -> null
                remainder.startsWith(":") -> remainder.substring(1)
                else -> return false
            }
        } else {
            val colonCount = hostPort.count { it == ':' }
            if (colonCount > 1) return false
            if (colonCount == 1) {
                host = hostPort.substringBefore(':')
                portStr = hostPort.substringAfter(':')
            } else {
                host = hostPort
                portStr = null
            }
        }

        if (portStr != null) {
            val port = portStr.toIntOrNull() ?: return false
            if (port !in 1..65535) return false
        }

        if (host.startsWith("[") && host.endsWith("]")) {
            val ipv6Inner = host.substring(1, host.length - 1)
            return ipv6Inner.contains(':') && ipv6Inner.all {
                it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
            }
        }

        if (host.equals("localhost", ignoreCase = true)) return true

        val ipv4Match = IPV4_REGEX.matchEntire(host)
        if (ipv4Match != null) {
            return ipv4Match.groupValues.drop(1).all { octet ->
                val value = octet.toIntOrNull() ?: return@all false
                value in 0..255
            }
        }

        if (host.startsWith('.') || host.endsWith('.') || ".." in host) return false
        val labels = host.split('.')
        if (!allowSingleLabelHost && labels.size < 2) return false

        for (label in labels) {
            if (label.isEmpty() || label.length > 63) return false
            if (label.startsWith('-') || label.endsWith('-')) return false
            if (!label.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch.code > 127 }) {
                return false
            }
        }

        if (labels.size >= 2) {
            val tld = labels.last()
            val validTld = (tld.length >= 2 && tld.all { it.isLetter() || it.code > 127 }) ||
                tld.startsWith("xn--", ignoreCase = true)
            if (!validTld) return false
        }

        return true
    }

    private fun isSafePassiveDataUri(dataUri: String): Boolean {
        val commaIdx = dataUri.indexOf(',')
        if (commaIdx < 5) return false
        val meta = dataUri.substring(5, commaIdx).trim()
        val mediaType = meta.substringBefore(';').trim().lowercase(Locale.ROOT)
            .ifEmpty { "text/plain" }
        return mediaType in SAFE_PASSIVE_DATA_MIME_TYPES
    }

    private fun extractScheme(uri: String): String? {
        val match = SCHEME_PREFIX_REGEX.matchEntire(uri) ?: return null
        return match.groupValues[1].lowercase(Locale.ROOT)
    }

    private fun containsAsciiControlChars(text: String): Boolean =
        text.any { it.code in 0..0x1F || it.code == 0x7F }

    private fun stripControlAndBidiChars(text: String): String = buildString(text.length) {
        for (ch in text) {
            val code = ch.code
            val isControl = code in 0..0x1F || code == 0x7F
            val isBidiOverride = code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0x200E || code == 0x200F
            if (!isControl && !isBidiOverride) {
                append(ch)
            }
        }
    }

    private fun sanitizeSearchQuery(raw: String): String =
        raw.map { if (it.code in 0..0x1F || it.code == 0x7F) ' ' else it }
            .joinToString("")
            .trim()
            .take(MAX_SEARCH_FALLBACK_LENGTH)
            .ifEmpty { "veko" }
}
