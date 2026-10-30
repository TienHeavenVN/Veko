package io.veko.browser.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun buildSearchUrl(query: String): String = queryTemplate.replace("%s", Uri.encode(query))
}

data class VekoSettings(
    val dynamicColor: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    val settingsFlow: Flow<VekoSettings> = appContext.vekoSettingsDataStore.data.map { prefs ->
        val engineName = prefs[KEY_SEARCH_ENGINE]
        val engine = SearchEngine.entries.firstOrNull { it.name == engineName } ?: SearchEngine.DUCKDUCKGO
        VekoSettings(
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            searchEngine = engine,
        )
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        appContext.vekoSettingsDataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setSearchEngine(engine: SearchEngine) {
        appContext.vekoSettingsDataStore.edit { it[KEY_SEARCH_ENGINE] = engine.name }
    }

    private companion object {
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val KEY_SEARCH_ENGINE = stringPreferencesKey("default_search_engine")
    }
}
