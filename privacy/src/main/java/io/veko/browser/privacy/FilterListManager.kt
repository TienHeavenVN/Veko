package io.veko.browser.privacy

import io.veko.browser.chromium.nativebridge.VekoChromiumNative
import io.veko.browser.data.repository.VekoSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class FilterSubscriptionInfo(
    val id: String,
    val title: String,
    val category: String,
    val enabled: Boolean,
)

/**
 * Quản lý các gói danh sách lọc chuẩn cú pháp Adblock Plus / EasyList (Quốc tế, Khu vực VN, Chống Pop-under,
 * Chống Fingerprinting/Session-Replay, Làm sạch URL) và nạp nóng xuống nhân Rust `adblock-rust`
 * thông qua cầu nối gốc `VekoChromiumNative`.
 */
class FilterListManager(
    private val customRulesStorageFile: File? = null,
) {

    private val _subscriptions = MutableStateFlow(
        listOf(
            FilterSubscriptionInfo(
                id = ID_EASYLIST_CORE,
                title = "Veko Ad Shield (EasyList Core)",
                category = "Ads",
                enabled = true,
            ),
            FilterSubscriptionInfo(
                id = ID_EASYPRIVACY_CORE,
                title = "Veko Anti-Tracking & Anti-Fingerprinting",
                category = "Trackers",
                enabled = true,
            ),
            FilterSubscriptionInfo(
                id = ID_CLEAN_URLS,
                title = "Veko Clean URLs (\$removeparam 44+ tokens)",
                category = "URL Hygiene",
                enabled = true,
            ),
            FilterSubscriptionInfo(
                id = ID_VN_REGIONAL,
                title = "Vietnam Regional Ad Shield (Admicro / eClick / Adtima)",
                category = "Regional",
                enabled = true,
            ),
            FilterSubscriptionInfo(
                id = ID_ANTI_POPUNDER,
                title = "Pop-under, Video Ad & Malvertising Guard",
                category = "Annoyances",
                enabled = true,
            ),
        ),
    )
    val subscriptions: StateFlow<List<FilterSubscriptionInfo>> = _subscriptions.asStateFlow()

    private val _customRulesText = MutableStateFlow(loadInitialCustomRules())
    val customRulesText: StateFlow<String> = _customRulesText.asStateFlow()

    private var trustedHostsSnapshot: Set<String> = emptySet()

    init {
        val initial = _customRulesText.value
        if (initial.isNotBlank()) {
            runCatching { reloadCombinedIntoRust(initial, _subscriptions.value, trustedHostsSnapshot) }
        }
    }

    fun updateCustomRules(newRules: String): Boolean {
        val normalized = newRules.trim()
        val applied = reloadCombinedIntoRust(normalized, _subscriptions.value, trustedHostsSnapshot)
        if (applied) {
            _customRulesText.value = normalized
            customRulesStorageFile?.let { file ->
                file.parentFile?.mkdirs()
                file.writeText(normalized, Charsets.UTF_8)
            }
        }
        return applied
    }

    fun setSubscriptionEnabled(subscriptionId: String, enabled: Boolean): Boolean {
        val current = _subscriptions.value
        if (current.none { it.id == subscriptionId }) return false
        val updated = current.map { item ->
            if (item.id == subscriptionId) item.copy(enabled = enabled) else item
        }
        val applied = reloadCombinedIntoRust(_customRulesText.value, updated, trustedHostsSnapshot)
        if (applied) {
            _subscriptions.value = updated
        }
        return applied
    }

    fun syncTrustedHosts(hosts: Set<String>): Boolean {
        val normalized = hosts.mapNotNull { VekoSettings.normalizeHost(it).takeIf(String::isNotEmpty) }.toSet()
        if (normalized == trustedHostsSnapshot) return true
        trustedHostsSnapshot = normalized
        return reloadCombinedIntoRust(_customRulesText.value, _subscriptions.value, normalized)
    }

    private fun reloadCombinedIntoRust(
        customRules: String,
        subs: List<FilterSubscriptionInfo>,
        trustedHosts: Set<String>,
    ): Boolean {
        val enabledIds = subs.filter { it.enabled }.map { it.id }.toSet()
        val combined = buildString {
            if (ID_VN_REGIONAL in enabledIds) {
                appendLine(VN_REGIONAL_EXTRA_RULES)
            }
            if (ID_ANTI_POPUNDER in enabledIds) {
                appendLine(ANTI_POPUNDER_EXTRA_RULES)
            }
            for (host in trustedHosts.sorted()) {
                appendLine("@@||$host^\$document,generichide")
            }
            if (customRules.isNotBlank()) {
                appendLine(customRules)
            }
        }.trim()
        val includeBuiltin = ID_EASYLIST_CORE in enabledIds ||
            ID_EASYPRIVACY_CORE in enabledIds ||
            ID_CLEAN_URLS in enabledIds
        return runCatching {
            VekoChromiumNative.reloadFilterRules(combined, includeBuiltin = includeBuiltin)
        }.getOrDefault(false)
    }

    private fun loadInitialCustomRules(): String {
        val file = customRulesStorageFile ?: return ""
        return if (file.isFile) {
            runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
        } else {
            ""
        }
    }

    companion object {
        const val ID_EASYLIST_CORE = "easylist-core"
        const val ID_EASYPRIVACY_CORE = "easyprivacy-core"
        const val ID_CLEAN_URLS = "veko-clean-urls"
        const val ID_VN_REGIONAL = "vn-regional-ads"
        const val ID_ANTI_POPUNDER = "veko-anti-popunder"

        private val VN_REGIONAL_EXTRA_RULES = """
||logging.admicro.vn^
||lg1.logging.admicro.vn^
||media1.admicro.vn^
||adi.admicro.vn^
||ads.eclick.vn^
||static.eclick.vn^
||stc.adtima.vn^
||delivery.yomedia.vn^
||ad.ants.vn^
||api.dable.io^
||static.dable.io^
||novaon.net/ads/${'$'}third-party
||cleverads.vn/ads/${'$'}third-party
"""

        private const val ANTI_POPUNDER_EXTRA_RULES = """
||pushhouse.com^
||onclickads.net^
||onclkds.com^
||admaven.com^
||bidvertiser.com^
||zeroredirect1.com^
||tsyndicate.com^
||realsrv.com^
||twinrdsyn.com^
"""
    }
}
