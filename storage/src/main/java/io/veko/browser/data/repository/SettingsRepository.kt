package io.veko.browser.data.repository

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.vekoSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "veko_user_settings",
)

enum class CookieControlMode(val labelVi: String, val descriptionVi: String) {
    BLOCK_THIRD_PARTY(
        labelVi = "Chặn Cookie bên thứ ba",
        descriptionVi = "Cho phép đăng nhập trang chính, cô lập cookie theo dõi chéo trang (Khuyên dùng)",
    ),
    BLOCK_ALL(
        labelVi = "Chặn toàn bộ Cookie",
        descriptionVi = "Khóa mọi cookie HTTP/JS (một số trang có thể không giữ được phiên đăng nhập)",
    ),
    ALLOW_ALL(
        labelVi = "Cho phép Cookie tiêu chuẩn",
        descriptionVi = "Chỉ áp dụng cho tab thường; Tab riêng tư vẫn luôn cô lập cookie trên RAM",
    ),
}

enum class AppLanguage(
    val storageKey: String,
    val bcp47Tag: String,
    val nativeLabel: String,
) {
    SYSTEM("system", "", "System Default"),
    EN("en", "en-US", "English"),
    VI("vi", "vi-VN", "Tiếng Việt"),
    JA("ja", "ja-JP", "日本語"),
    KO("ko", "ko-KR", "한국어"),
    ZH_CN("zh-CN", "zh-CN", "简体中文"),
    FR("fr", "fr-FR", "Français"),
    DE("de", "de-DE", "Deutsch"),
    ES("es", "es-ES", "Español");

    fun resolveLanguageCode(systemLocale: Locale = Locale.getDefault()): String {
        if (this != SYSTEM) return storageKey
        val lang = systemLocale.language.lowercase(Locale.ROOT)
        return when (lang) {
            "vi" -> "vi"
            "ja" -> "ja"
            "ko" -> "ko"
            "zh" -> "zh-CN"
            "fr" -> "fr"
            "de" -> "de"
            "es" -> "es"
            else -> "en"
        }
    }

    companion object {
        fun fromStorageKey(raw: String?): AppLanguage =
            entries.firstOrNull { it.storageKey.equals(raw?.trim(), ignoreCase = true) } ?: SYSTEM
    }
}

enum class AutoplayPolicy(val storageKey: String) {
    BLOCK_AUDIO("block_audio"),
    BLOCK_ALL("block_all"),
    ALLOW_ALL("allow_all");

    companion object {
        fun fromStorageKey(raw: String?): AutoplayPolicy =
            entries.firstOrNull { it.storageKey.equals(raw?.trim(), ignoreCase = true) } ?: BLOCK_AUDIO
    }
}

@Immutable
data class VekoSettings(
    val dynamicColor: Boolean = false,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val autoFontSize: Boolean = true,
    val pageZoomPercent: Int = DEFAULT_PAGE_ZOOM_PERCENT,
    // Cấu hình quyền riêng tư & bảo mật mặc định an toàn (Privacy-by-Default)
    val adBlockEnabled: Boolean = true,
    val trackerBlockEnabled: Boolean = true,
    val strictAntiTracking: Boolean = true,
    val stripTrackingParamsEnabled: Boolean = true,
    val httpsUpgradeEnabled: Boolean = true,
    val blockMixedContent: Boolean = true,
    val cookieControlMode: CookieControlMode = CookieControlMode.BLOCK_THIRD_PARTY,
    val saveSearchHistory: Boolean = false,
    val clearDataOnExit: Boolean = false,
    val autoplayPolicy: AutoplayPolicy = AutoplayPolicy.BLOCK_AUDIO,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val trustedShieldHosts: Set<String> = emptySet(),
) {
    companion object {
        const val DEFAULT_PAGE_ZOOM_PERCENT = 100
        const val MIN_PAGE_ZOOM_PERCENT = 75
        const val MAX_PAGE_ZOOM_PERCENT = 150
        const val PAGE_ZOOM_STEP = 15

        fun normalizeHost(rawHostOrUrl: String): String {
            val trimmed = rawHostOrUrl.trim().lowercase(Locale.ROOT)
            if (trimmed.isEmpty()) return ""
            val withoutScheme = trimmed.substringAfter("://", missingDelimiterValue = trimmed)
            val authority = withoutScheme
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .substringAfterLast('@')
            val host = if (authority.startsWith("[")) {
                authority.substringBefore(']') + "]"
            } else {
                authority.substringBefore(':')
            }
            return host.trim().trim('.').takeIf { it.isNotEmpty() && !it.any(Char::isWhitespace) }.orEmpty()
        }
    }
}

interface SettingsBackingStore {
    val flow: Flow<VekoSettings>
    suspend fun updateDynamicColor(enabled: Boolean)
    suspend fun updateSearchEngine(engine: SearchEngine)
    suspend fun updatePageZoom(autoFontSize: Boolean, zoomPercent: Int) {}
    suspend fun updateAdBlockEnabled(enabled: Boolean) {}
    suspend fun updateTrackerBlockEnabled(enabled: Boolean) {}
    suspend fun updateStrictAntiTracking(enabled: Boolean) {}
    suspend fun updateStripTrackingParamsEnabled(enabled: Boolean) {}
    suspend fun updateHttpsUpgradeEnabled(enabled: Boolean) {}
    suspend fun updateBlockMixedContent(enabled: Boolean) {}
    suspend fun updateCookieControlMode(mode: CookieControlMode) {}
    suspend fun updateSaveSearchHistory(enabled: Boolean) {}
    suspend fun updateClearDataOnExit(enabled: Boolean) {}
    suspend fun updateAutoplayPolicy(policy: AutoplayPolicy) {}
    suspend fun updateAppLanguage(language: AppLanguage) {}
    suspend fun updateTrustedShieldHost(host: String, trusted: Boolean) {}
}

private class DataStoreSettingsBackingStore(
    private val appContext: Context,
) : SettingsBackingStore {
    override val flow: Flow<VekoSettings> = appContext.vekoSettingsDataStore.data.map { prefs ->
        val engineName = prefs[KEY_SEARCH_ENGINE]
        val engine = SearchEngine.entries.firstOrNull { it.name == engineName } ?: SearchEngine.DUCKDUCKGO
        val zoom = (prefs[KEY_PAGE_ZOOM_PERCENT] ?: VekoSettings.DEFAULT_PAGE_ZOOM_PERCENT)
            .coerceIn(VekoSettings.MIN_PAGE_ZOOM_PERCENT, VekoSettings.MAX_PAGE_ZOOM_PERCENT)
        val cookieName = prefs[KEY_COOKIE_MODE]
        val cookieMode = CookieControlMode.entries.firstOrNull { it.name == cookieName }
            ?: CookieControlMode.BLOCK_THIRD_PARTY
        val appLanguage = AppLanguage.fromStorageKey(prefs[KEY_APP_LANGUAGE])
        val autoplayPolicy = AutoplayPolicy.fromStorageKey(prefs[KEY_AUTOPLAY_POLICY])
        val trustedHosts = prefs[KEY_TRUSTED_SHIELD_HOSTS]
            ?.mapNotNull { VekoSettings.normalizeHost(it).takeIf(String::isNotEmpty) }
            ?.toSet()
            ?: emptySet()

        VekoSettings(
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: false,
            searchEngine = engine,
            autoFontSize = prefs[KEY_AUTO_FONT_SIZE] ?: true,
            pageZoomPercent = zoom,
            adBlockEnabled = prefs[KEY_AD_BLOCK_ENABLED] ?: true,
            trackerBlockEnabled = prefs[KEY_TRACKER_BLOCK_ENABLED] ?: true,
            strictAntiTracking = prefs[KEY_STRICT_ANTI_TRACKING] ?: true,
            stripTrackingParamsEnabled = prefs[KEY_STRIP_TRACKING_PARAMS] ?: true,
            httpsUpgradeEnabled = prefs[KEY_HTTPS_UPGRADE] ?: true,
            blockMixedContent = prefs[KEY_BLOCK_MIXED_CONTENT] ?: true,
            cookieControlMode = cookieMode,
            saveSearchHistory = prefs[KEY_SAVE_SEARCH_HISTORY] ?: false,
            clearDataOnExit = prefs[KEY_CLEAR_DATA_ON_EXIT] ?: false,
            autoplayPolicy = autoplayPolicy,
            appLanguage = appLanguage,
            trustedShieldHosts = trustedHosts,
        )
    }

    override suspend fun updateDynamicColor(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun updateSearchEngine(engine: SearchEngine) {
        appContext.vekoSettingsDataStore.edit { it[KEY_SEARCH_ENGINE] = engine.name }
    }

    override suspend fun updatePageZoom(autoFontSize: Boolean, zoomPercent: Int) {
        val clamped = zoomPercent.coerceIn(VekoSettings.MIN_PAGE_ZOOM_PERCENT, VekoSettings.MAX_PAGE_ZOOM_PERCENT)
        appContext.vekoSettingsDataStore.edit { prefs ->
            prefs[KEY_AUTO_FONT_SIZE] = autoFontSize
            prefs[KEY_PAGE_ZOOM_PERCENT] = clamped
        }
    }

    override suspend fun updateAdBlockEnabled(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_AD_BLOCK_ENABLED] = enabled }
    }

    override suspend fun updateTrackerBlockEnabled(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_TRACKER_BLOCK_ENABLED] = enabled }
    }

    override suspend fun updateStrictAntiTracking(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_STRICT_ANTI_TRACKING] = enabled }
    }

    override suspend fun updateStripTrackingParamsEnabled(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_STRIP_TRACKING_PARAMS] = enabled }
    }

    override suspend fun updateHttpsUpgradeEnabled(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_HTTPS_UPGRADE] = enabled }
    }

    override suspend fun updateBlockMixedContent(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_BLOCK_MIXED_CONTENT] = enabled }
    }

    override suspend fun updateCookieControlMode(mode: CookieControlMode) {
        appContext.vekoSettingsDataStore.edit { it[KEY_COOKIE_MODE] = mode.name }
    }

    override suspend fun updateSaveSearchHistory(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_SAVE_SEARCH_HISTORY] = enabled }
    }

    override suspend fun updateClearDataOnExit(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_CLEAR_DATA_ON_EXIT] = enabled }
    }

    override suspend fun updateAutoplayPolicy(policy: AutoplayPolicy) {
        appContext.vekoSettingsDataStore.edit { it[KEY_AUTOPLAY_POLICY] = policy.storageKey }
    }

    override suspend fun updateAppLanguage(language: AppLanguage) {
        appContext.vekoSettingsDataStore.edit { it[KEY_APP_LANGUAGE] = language.storageKey }
    }

    override suspend fun updateTrustedShieldHost(host: String, trusted: Boolean) {
        val normalized = VekoSettings.normalizeHost(host)
        if (normalized.isEmpty()) return
        appContext.vekoSettingsDataStore.edit { prefs ->
            val current = prefs[KEY_TRUSTED_SHIELD_HOSTS]?.toMutableSet() ?: mutableSetOf()
            if (trusted) {
                current.add(normalized)
            } else {
                current.remove(normalized)
            }
            prefs[KEY_TRUSTED_SHIELD_HOSTS] = current
        }
    }

    private companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_SEARCH_ENGINE = stringPreferencesKey("default_search_engine")
        val KEY_AUTO_FONT_SIZE = booleanPreferencesKey("auto_font_size_enabled")
        val KEY_PAGE_ZOOM_PERCENT = intPreferencesKey("page_zoom_percent")
        val KEY_AD_BLOCK_ENABLED = booleanPreferencesKey("shield_ad_block_enabled")
        val KEY_TRACKER_BLOCK_ENABLED = booleanPreferencesKey("shield_tracker_block_enabled")
        val KEY_STRICT_ANTI_TRACKING = booleanPreferencesKey("shield_strict_anti_tracking")
        val KEY_STRIP_TRACKING_PARAMS = booleanPreferencesKey("shield_strip_tracking_params")
        val KEY_HTTPS_UPGRADE = booleanPreferencesKey("shield_https_upgrade_enabled")
        val KEY_BLOCK_MIXED_CONTENT = booleanPreferencesKey("shield_block_mixed_content")
        val KEY_COOKIE_MODE = stringPreferencesKey("shield_cookie_control_mode")
        val KEY_SAVE_SEARCH_HISTORY = booleanPreferencesKey("privacy_save_search_history")
        val KEY_CLEAR_DATA_ON_EXIT = booleanPreferencesKey("privacy_clear_data_on_exit")
        val KEY_AUTOPLAY_POLICY = stringPreferencesKey("media_autoplay_policy")
        val KEY_APP_LANGUAGE = stringPreferencesKey("ui_app_language")
        val KEY_TRUSTED_SHIELD_HOSTS = stringSetPreferencesKey("shield_trusted_hosts")
    }
}

class SettingsRepository(
    private val store: SettingsBackingStore,
) {
    constructor(
        appContext: Context,
    ) : this(DataStoreSettingsBackingStore(appContext.applicationContext))

    val settingsFlow: Flow<VekoSettings> = store.flow

    suspend fun setDynamicColor(enabled: Boolean) = store.updateDynamicColor(enabled)

    suspend fun setSearchEngine(engine: SearchEngine) = store.updateSearchEngine(engine)

    suspend fun setPageZoom(autoFontSize: Boolean, zoomPercent: Int) =
        store.updatePageZoom(
            autoFontSize = autoFontSize,
            zoomPercent = zoomPercent.coerceIn(VekoSettings.MIN_PAGE_ZOOM_PERCENT, VekoSettings.MAX_PAGE_ZOOM_PERCENT),
        )

    suspend fun setAdBlockEnabled(enabled: Boolean) = store.updateAdBlockEnabled(enabled)

    suspend fun setTrackerBlockEnabled(enabled: Boolean) = store.updateTrackerBlockEnabled(enabled)

    suspend fun setStrictAntiTracking(enabled: Boolean) = store.updateStrictAntiTracking(enabled)

    suspend fun setStripTrackingParamsEnabled(enabled: Boolean) = store.updateStripTrackingParamsEnabled(enabled)

    suspend fun setHttpsUpgradeEnabled(enabled: Boolean) = store.updateHttpsUpgradeEnabled(enabled)

    suspend fun setBlockMixedContent(enabled: Boolean) = store.updateBlockMixedContent(enabled)

    suspend fun setCookieControlMode(mode: CookieControlMode) = store.updateCookieControlMode(mode)

    suspend fun setSaveSearchHistory(enabled: Boolean) = store.updateSaveSearchHistory(enabled)

    suspend fun setClearDataOnExit(enabled: Boolean) = store.updateClearDataOnExit(enabled)

    suspend fun setAutoplayPolicy(policy: AutoplayPolicy) = store.updateAutoplayPolicy(policy)

    suspend fun setAppLanguage(language: AppLanguage) = store.updateAppLanguage(language)

    suspend fun setTrustedShieldHost(host: String, trusted: Boolean) =
        store.updateTrustedShieldHost(host, trusted)
}
