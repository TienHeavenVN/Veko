package io.veko.browser.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

private val Context.vekoSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "veko_user_settings",
)

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

data class VekoSettings(
    val dynamicColor: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val autoFontSize: Boolean = true,
    val pageZoomPercent: Int = DEFAULT_PAGE_ZOOM_PERCENT,
) {
    companion object {
        const val DEFAULT_PAGE_ZOOM_PERCENT = 100
        const val MIN_PAGE_ZOOM_PERCENT = 75
        const val MAX_PAGE_ZOOM_PERCENT = 150
        const val PAGE_ZOOM_STEP = 15
    }
}

internal interface SettingsBackingStore {
    val flow: Flow<VekoSettings>
    suspend fun updateDynamicColor(enabled: Boolean)
    suspend fun updateSearchEngine(engine: SearchEngine)
    suspend fun updatePageZoom(autoFontSize: Boolean, zoomPercent: Int) {}
}

private class DataStoreSettingsBackingStore(
    private val appContext: Context,
) : SettingsBackingStore {
    override val flow: Flow<VekoSettings> = appContext.vekoSettingsDataStore.data.map { prefs ->
        val engineName = prefs[KEY_SEARCH_ENGINE]
        val engine = SearchEngine.entries.firstOrNull { it.name == engineName } ?: SearchEngine.DUCKDUCKGO
        val zoom = (prefs[KEY_PAGE_ZOOM_PERCENT] ?: VekoSettings.DEFAULT_PAGE_ZOOM_PERCENT)
            .coerceIn(VekoSettings.MIN_PAGE_ZOOM_PERCENT, VekoSettings.MAX_PAGE_ZOOM_PERCENT)
        VekoSettings(
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            searchEngine = engine,
            autoFontSize = prefs[KEY_AUTO_FONT_SIZE] ?: true,
            pageZoomPercent = zoom,
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

    private companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_SEARCH_ENGINE = stringPreferencesKey("default_search_engine")
        val KEY_AUTO_FONT_SIZE = booleanPreferencesKey("auto_font_size_enabled")
        val KEY_PAGE_ZOOM_PERCENT = intPreferencesKey("page_zoom_percent")
    }
}

@Singleton
class SettingsRepository internal constructor(
    private val store: SettingsBackingStore,
) {
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
    ) : this(DataStoreSettingsBackingStore(appContext))

    val settingsFlow: Flow<VekoSettings> = store.flow

    suspend fun setDynamicColor(enabled: Boolean) = store.updateDynamicColor(enabled)

    suspend fun setSearchEngine(engine: SearchEngine) = store.updateSearchEngine(engine)

    suspend fun setPageZoom(autoFontSize: Boolean, zoomPercent: Int) =
        store.updatePageZoom(
            autoFontSize = autoFontSize,
            zoomPercent = zoomPercent.coerceIn(VekoSettings.MIN_PAGE_ZOOM_PERCENT, VekoSettings.MAX_PAGE_ZOOM_PERCENT),
        )
}
