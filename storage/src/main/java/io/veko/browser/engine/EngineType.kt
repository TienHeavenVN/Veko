package io.veko.browser.engine

import androidx.compose.runtime.Immutable

/**
 * Định danh browser engine duy nhất của Veko Browser: Mozilla GeckoView
 * (Rendering: Gecko / WebRender, JavaScript: SpiderMonkey).
 *
 * Bất biến kiến trúc: GeckoView là browser engine duy nhất; tuyệt đối không dùng Android System WebView.
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
    );

    companion object {
        val DEFAULT: EngineType = GECKO

        /**
         * Chuẩn hóa khóa lưu trữ từ phiên cũ: mọi giá trị không xác định từ bản dựng cũ đều hội tụ an toàn về [GECKO].
         */
        fun fromStorageKey(raw: String?): EngineType {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return entries.firstOrNull { it.storageKey == normalized || it.name == normalized }
                ?: DEFAULT
        }
    }
}
