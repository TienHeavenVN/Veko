package io.veko.browser.engine

import androidx.compose.runtime.Immutable

/**
 * Định danh các nhân trình duyệt thực tế được kiến trúc Dual-Engine của Veko hỗ trợ.
 *
 * - [GECKO]: Nhân mặc định & khuyến nghị sử dụng SDK chính thức Mozilla GeckoView từ Mozilla Maven
 *   (Rendering: Gecko / WebRender, JavaScript: SpiderMonkey).
 * - [CHROMIUM]: Nhân thứ cấp sử dụng Chromium Content API `156.0.8078.85`
 *   (Rendering: Blink, JavaScript: V8); chỉ cho phép chọn khi gói SDK native đã được xác thực đầy đủ.
 *
 * Bất biến kiến trúc: Tuyệt đối không dùng Android System WebView hoặc chuyển hướng ngầm giữa hai nhân.
 */
@Immutable
enum class EngineType(
    val storageKey: String,
    val displayName: String,
    val shortBadge: String,
    val renderingEngineName: String,
    val javascriptEngineName: String,
    val isRecommendedDefault: Boolean,
) {
    GECKO(
        storageKey = "GECKO",
        displayName = "Mozilla GeckoView",
        shortBadge = "GeckoView",
        renderingEngineName = "Gecko (WebRender)",
        javascriptEngineName = "SpiderMonkey",
        isRecommendedDefault = true,
    ),
    CHROMIUM(
        storageKey = "CHROMIUM",
        displayName = "Chromium Content",
        shortBadge = "Chromium",
        renderingEngineName = "Blink",
        javascriptEngineName = "V8",
        isRecommendedDefault = false,
    );

    companion object {
        val DEFAULT: EngineType = GECKO

        fun fromStorageKey(raw: String?): EngineType {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.storageKey == normalized || it.name == normalized }
                ?: DEFAULT
        }
    }
}
