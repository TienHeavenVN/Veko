package io.veko.browser.tab

import io.veko.browser.data.repository.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class UrlNavigationPolicyTest {

    @Test
    fun `normalizeUserInput handles blank, valid http-https, bare domains, ports and IPv6`() {
        assertEquals(
            TabSessionState.DEFAULT_HOME_URL,
            UrlNavigationPolicy.normalizeUserInput("   ", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "https://mozilla.org/en-US/",
            UrlNavigationPolicy.normalizeUserInput("HTTPS://mozilla.org/en-US/", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "https://example.com/path?a=1|2",
            UrlNavigationPolicy.normalizeUserInput("example.com/path?a=1|2", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "https://localhost:8080/api/health",
            UrlNavigationPolicy.normalizeUserInput("localhost:8080/api/health", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "https://127.0.0.1:3000/test",
            UrlNavigationPolicy.normalizeUserInput("127.0.0.1:3000/test", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "https://[::1]:8080/index.html",
            UrlNavigationPolicy.normalizeUserInput("[::1]:8080/index.html", SearchEngine.DUCKDUCKGO),
        )
        assertEquals(
            "about:blank",
            UrlNavigationPolicy.normalizeUserInput("ABOUT:BLANK", SearchEngine.DUCKDUCKGO),
        )
    }

    @Test
    fun `normalizeUserInput routes search queries and blocks dangerous schemes from omnibox`() {
        assertEquals(
            "https://duckduckgo.com/?q=kotlin%20geckoview%20android",
            UrlNavigationPolicy.normalizeUserInput("kotlin geckoview android", SearchEngine.DUCKDUCKGO),
        )

        val dangerousInputs = listOf(
            "javascript:alert(1)",
            "JAVASCRIPT://example.com/%0aalert(1)",
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.com/1234",
            "file:///etc/hosts",
            "content://com.android.contacts/data",
            "intent://scan/#Intent;scheme=zxing;end",
            "about:config",
            "about:crashparent",
            "https://",
            "http:///missing-host",
            "https://evil.com:99999/test",
            "https://evil.com\r\njavascript:alert(1)",
        )

        for (input in dangerousInputs) {
            val normalized = UrlNavigationPolicy.normalizeUserInput(input, SearchEngine.BRAVE)
            assertTrue(
                "Dangerous or malformed omnibox input '$input' must route to search URL, got: $normalized",
                normalized.startsWith("https://search.brave.com/search?q="),
            )
        }
    }

    @Test
    fun `sanitizeExternalIntentUri enforces VIEW action, http-https only, and single consumption`() {
        assertEquals(
            "https://developer.android.com/jetpack",
            UrlNavigationPolicy.sanitizeExternalIntentUri(
                action = "android.intent.action.VIEW",
                rawUri = "https://developer.android.com/jetpack",
                isLaunchedFromHistory = false,
                isAlreadyConsumed = false,
            ),
        )

        // Chặn mở lặp khi tái tạo Activity hoặc mở lại từ Recents
        assertNull(
            UrlNavigationPolicy.sanitizeExternalIntentUri(
                action = "android.intent.action.VIEW",
                rawUri = "https://developer.android.com",
                isLaunchedFromHistory = true,
                isAlreadyConsumed = false,
            ),
        )
        assertNull(
            UrlNavigationPolicy.sanitizeExternalIntentUri(
                action = "android.intent.action.VIEW",
                rawUri = "https://developer.android.com",
                isLaunchedFromHistory = false,
                isAlreadyConsumed = true,
            ),
        )

        // Chặn các scheme nguy hiểm hoặc sai định dạng từ app ngoài
        for (badUri in listOf(
            "javascript:alert(1)",
            "data:text/html,<h1>spoof</h1>",
            "file:///sdcard/secret.txt",
            "content://media/external/images",
            "about:config",
            "http:///nohost",
            "https://bad\u0000host.com",
        )) {
            assertNull(
                "External URI '$badUri' must be rejected",
                UrlNavigationPolicy.sanitizeExternalIntentUri(
                    action = "android.intent.action.VIEW",
                    rawUri = badUri,
                ),
            )
        }
    }

    @Test
    fun `evaluateWebLoadRequest enforces context-sensitive scheme policy for redirects, data and blob`() {
        // Điều hướng web chuẩn
        assertTrue(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "https://vi.wikipedia.org/wiki/Tiếng_Việt",
                triggerUri = "https://duckduckgo.com",
            ),
        )

        // about: chỉ cho phép các trang nội bộ an toàn và không qua HTTP redirect từ web
        assertTrue(UrlNavigationPolicy.evaluateWebLoadRequest(uri = "about:blank"))
        assertTrue(UrlNavigationPolicy.evaluateWebLoadRequest(uri = "about:neterror?e=dnsNotFound"))
        assertFalse(UrlNavigationPolicy.evaluateWebLoadRequest(uri = "about:config"))
        assertFalse(UrlNavigationPolicy.evaluateWebLoadRequest(uri = "about:crashparent"))
        assertFalse(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "about:blank",
                triggerUri = "https://evil.example",
                isRedirect = true,
                isDirectNavigation = false,
            ),
        )

        // data: chặn tuyệt đối text/html, image/svg+xml, redirect và cửa sổ mới TARGET_WINDOW_NEW
        assertFalse(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
                triggerUri = "https://example.com",
                hasUserGesture = true,
            ),
        )
        assertFalse(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "data:image/svg+xml,<svg onload='alert(1)'/>",
                triggerUri = "https://example.com",
                hasUserGesture = true,
            ),
        )
        assertFalse(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "data:image/png;base64,iVBORw0KGgo=",
                triggerUri = "https://example.com",
                isRedirect = true,
                hasUserGesture = true,
            ),
        )
        assertFalse(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "data:image/png;base64,iVBORw0KGgo=",
                triggerUri = "https://example.com",
                targetWindow = UrlNavigationPolicy.TARGET_WINDOW_NEW,
                hasUserGesture = true,
            ),
        )
        assertTrue(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "data:image/png;base64,iVBORw0KGgo=",
                triggerUri = "https://example.com",
                targetWindow = UrlNavigationPolicy.TARGET_WINDOW_CURRENT,
                isRedirect = false,
                hasUserGesture = true,
            ),
        )

        // blob: chỉ cho phép cùng origin http/https với triggerUri và không qua redirect
        assertTrue(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "blob:https://docs.example.com:8443/550e8400-e29b-41d4-a716-446655440000",
                triggerUri = "https://docs.example.com:8443/viewer",
                isRedirect = false,
            ),
        )
        assertFalse(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "blob:https://docs.example.com/550e8400",
                triggerUri = "https://attacker.example.com/",
            ),
        )
        assertFalse(
            UrlNavigationPolicy.evaluateWebLoadRequest(
                uri = "blob:null/550e8400",
                triggerUri = null,
            ),
        )

        // Chặn tuyệt đối file:, content:, javascript:, intent:
        for (blocked in listOf(
            "javascript:alert(document.cookie)",
            "file:///data/data/io.veko.browser/databases/veko_browser.db",
            "content://settings/secure",
            "intent://example.com#Intent;scheme=http;end",
        )) {
            assertFalse(
                "Web load request must block '$blocked'",
                UrlNavigationPolicy.evaluateWebLoadRequest(uri = blocked, hasUserGesture = true),
            )
        }
    }

    @Test
    fun `sanitizePopupTargetUri allows blank and valid web popups while rejecting script or data popups`() {
        assertEquals(
            "about:blank",
            UrlNavigationPolicy.sanitizePopupTargetUri("", "https://example.com"),
        )
        assertEquals(
            "https://sub.example.com/login",
            UrlNavigationPolicy.sanitizePopupTargetUri("https://sub.example.com/login", "https://example.com"),
        )
        assertEquals(
            "blob:https://example.com/abc-123",
            UrlNavigationPolicy.sanitizePopupTargetUri("blob:https://example.com/abc-123", "https://example.com/app"),
        )
        assertNull(
            UrlNavigationPolicy.sanitizePopupTargetUri("javascript:alert(1)", "https://example.com"),
        )
        assertNull(
            UrlNavigationPolicy.sanitizePopupTargetUri("data:text/html,<h1>phish</h1>", "https://example.com"),
        )
        assertNull(
            UrlNavigationPolicy.sanitizePopupTargetUri("file:///etc/passwd", "https://example.com"),
        )
    }

    @Test
    fun `buildSafeErrorPageDataUrl escapes HTML quotes, strips bidi spoofing and enforces CSP`() {
        val maliciousUri = "https://good.com/\u202E<script>alert('xss'+\"1\"+`2`)</script>"
        val dataUrl = UrlNavigationPolicy.buildSafeErrorPageDataUrl(maliciousUri, -42)

        assertTrue(UrlNavigationPolicy.isInternalErrorDataUrl(dataUrl))
        val encodedBody = dataUrl
            .removePrefix("data:text/html;charset=utf-8,")
            .removeSuffix("#veko-internal-error")
        val decodedHtml = URLDecoder.decode(encodedBody, StandardCharsets.UTF_8.name())

        assertTrue(decodedHtml.contains("Content-Security-Policy"))
        assertTrue(decodedHtml.contains("script-src 'none'"))
        assertFalse("Raw <script> tag must never appear in error HTML", decodedHtml.contains("<script>"))
        assertFalse("Bidi override char must be stripped", decodedHtml.contains("\u202E"))
        assertTrue(decodedHtml.contains("&lt;script&gt;"))
        assertTrue(decodedHtml.contains("&#39;xss&#39;"))
        assertTrue(decodedHtml.contains("&quot;1&quot;"))
        assertTrue(decodedHtml.contains("&#96;2&#96;"))
    }
}
