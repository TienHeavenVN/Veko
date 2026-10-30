package io.veko.browser.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
)

internal interface SettingsBackingStore {
    val flow: Flow<VekoSettings>
    suspend fun updateDynamicColor(enabled: Boolean)
    suspend fun updateSearchEngine(engine: SearchEngine)
}

private class DataStoreSettingsBackingStore(
    private val appContext: Context,
) : SettingsBackingStore {
    override val flow: Flow<VekoSettings> = appContext.vekoSettingsDataStore.data.map { prefs ->
        val engineName = prefs[KEY_SEARCH_ENGINE]
        val engine = SearchEngine.entries.firstOrNull { it.name == engineName } ?: SearchEngine.DUCKDUCKGO
        VekoSettings(
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            searchEngine = engine,
        )
    }

    override suspend fun updateDynamicColor(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    override suspend fun updateSearchEngine(engine: SearchEngine) {
        appContext.vekoSettingsDataStore.edit { it[KEY_SEARCH_ENGINE] = engine.name }
    }

    private companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_SEARCH_ENGINE = stringPreferencesKey("default_search_engine")
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
}
