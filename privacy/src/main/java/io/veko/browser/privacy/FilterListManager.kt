package io.veko.browser.privacy

import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class FilterSubscriptionInfo(
    val id: String,
    val title: String,
    val category: String,
    val enabled: Boolean
)

/**
 * Quản lý tập danh sách lọc chuẩn cú pháp Adblock Plus / EasyList và nạp nóng xuống nhân Rust `adblock-rust`
 * thông qua cầu nối gốc `VekoChromiumNative`.
 */
class FilterListManager(
    private val customRulesStorageFile: File? = null
) {

    private val _subscriptions = MutableStateFlow(
        listOf(
            FilterSubscriptionInfo(
                id = "easylist-core",
                title = "Veko Ad Shield (EasyList Core)",
                category = "Quảng cáo",
                enabled = true
            ),
            FilterSubscriptionInfo(
                id = "easyprivacy-core",
                title = "Veko Anti-Tracking (EasyPrivacy Core)",
                category = "Trình theo dõi",
                enabled = true
            ),
            FilterSubscriptionInfo(
                id = "veko-clean-urls",
                title = "Veko URL Parameter Cleaner ($removeparam)",
                category = "Làm sạch URL",
                enabled = true
            )
        )
    )
    val subscriptions: StateFlow<List<FilterSubscriptionInfo>> = _subscriptions.asStateFlow()

    private val _customRulesText = MutableStateFlow(loadInitialCustomRules())
    val customRulesText: StateFlow<String> = _customRulesText.asStateFlow()

    init {
        val initial = _customRulesText.value
        if (initial.isNotBlank()) {
            runCatching { VekoChromiumNative.reloadFilterRules(initial, includeBuiltin = true) }
        }
    }

    fun updateCustomRules(newRules: String): Boolean {
        val normalized = newRules.trim()
        val applied = VekoChromiumNative.reloadFilterRules(normalized, includeBuiltin = true)
        if (applied) {
            _customRulesText.value = normalized
            customRulesStorageFile?.let { file ->
                file.parentFile?.mkdirs()
                file.writeText(normalized, Charsets.UTF_8)
            }
        }
        return applied
    }

    private fun loadInitialCustomRules(): String {
        val file = customRulesStorageFile ?: return ""
        return if (file.isFile) {
            runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        } else {
            ""
        }
    }
}
