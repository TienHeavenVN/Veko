package io.veko.browser.data.repository

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class SearchEngine(val label: String, val homeUrl: String, private val queryTemplate: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com", "https://duckduckgo.com/?q=%s"),
    BRAVE("Brave Search", "https://search.brave.com", "https://search.brave.com/search?q=%s"),
    GOOGLE("Google", "https://www.google.com", "https://www.google.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com", "https://www.startpage.com/do/dsearch?query=%s");

    // Dùng URLEncoder chuẩn JDK để chạy đồng nhất trên cả Android Runtime lẫn JVM Unit Test
    fun buildSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")
        return queryTemplate.replace("%s", encoded)
    }
}
