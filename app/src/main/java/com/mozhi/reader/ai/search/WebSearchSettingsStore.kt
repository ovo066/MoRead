package com.mozhi.reader.ai.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class WebSearchProvider(
    val label: String,
    val defaultSearchEndpoint: String,
    val defaultScrapeEndpoint: String
) {
    FIRECRAWL(
        "Firecrawl",
        "https://api.firecrawl.dev/v2/search",
        "https://api.firecrawl.dev/v2/scrape"
    ),
    EXA(
        "Exa",
        "https://api.exa.ai/search",
        "https://api.exa.ai/contents"
    ),
    TAVILY(
        "Tavily",
        "https://api.tavily.com/search",
        "https://api.tavily.com/extract"
    );

    /** Kept for source compatibility with the original search-only settings screen. */
    val defaultEndpoint: String get() = defaultSearchEndpoint
}

@Serializable
enum class TavilyDepth(val wireValue: String, val label: String) {
    BASIC("basic", "Basic"),
    ADVANCED("advanced", "Advanced")
}

@Serializable
data class WebSearchSettings(
    val enabled: Boolean = false,
    val provider: WebSearchProvider = WebSearchProvider.FIRECRAWL,
    // These original fields remain the search endpoints so existing DataStore JSON migrates intact.
    val firecrawlEndpoint: String = WebSearchProvider.FIRECRAWL.defaultSearchEndpoint,
    val exaEndpoint: String = WebSearchProvider.EXA.defaultSearchEndpoint,
    val tavilyEndpoint: String = WebSearchProvider.TAVILY.defaultSearchEndpoint,
    val firecrawlScrapeEndpoint: String = WebSearchProvider.FIRECRAWL.defaultScrapeEndpoint,
    val exaScrapeEndpoint: String = WebSearchProvider.EXA.defaultScrapeEndpoint,
    val tavilyScrapeEndpoint: String = WebSearchProvider.TAVILY.defaultScrapeEndpoint,
    val tavilySearchDepth: TavilyDepth = TavilyDepth.BASIC,
    val tavilyExtractDepth: TavilyDepth = TavilyDepth.BASIC
) {
    fun searchEndpoint(provider: WebSearchProvider = this.provider): String = when (provider) {
        WebSearchProvider.FIRECRAWL -> firecrawlEndpoint
        WebSearchProvider.EXA -> exaEndpoint
        WebSearchProvider.TAVILY -> tavilyEndpoint
    }.trim().ifBlank { provider.defaultSearchEndpoint }

    fun scrapeEndpoint(provider: WebSearchProvider = this.provider): String = when (provider) {
        WebSearchProvider.FIRECRAWL -> firecrawlScrapeEndpoint
        WebSearchProvider.EXA -> exaScrapeEndpoint
        WebSearchProvider.TAVILY -> tavilyScrapeEndpoint
    }.trim().ifBlank { provider.defaultScrapeEndpoint }

    fun endpoint(provider: WebSearchProvider = this.provider): String = searchEndpoint(provider)

    fun withEndpoint(provider: WebSearchProvider, endpoint: String): WebSearchSettings = when (provider) {
        WebSearchProvider.FIRECRAWL -> copy(firecrawlEndpoint = endpoint)
        WebSearchProvider.EXA -> copy(exaEndpoint = endpoint)
        WebSearchProvider.TAVILY -> copy(tavilyEndpoint = endpoint)
    }

    fun withScrapeEndpoint(provider: WebSearchProvider, endpoint: String): WebSearchSettings = when (provider) {
        WebSearchProvider.FIRECRAWL -> copy(firecrawlScrapeEndpoint = endpoint)
        WebSearchProvider.EXA -> copy(exaScrapeEndpoint = endpoint)
        WebSearchProvider.TAVILY -> copy(tavilyScrapeEndpoint = endpoint)
    }
}

object WebSearchCredentialAliases {
    const val FIRECRAWL = "web_search_firecrawl_api_key"
    const val EXA = "web_search_exa_api_key"
    const val TAVILY = "web_search_tavily_api_key"

    fun forProvider(provider: WebSearchProvider): String = when (provider) {
        WebSearchProvider.FIRECRAWL -> FIRECRAWL
        WebSearchProvider.EXA -> EXA
        WebSearchProvider.TAVILY -> TAVILY
    }
}

@Singleton
class WebSearchSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<WebSearchSettings> = dataStore.data.map { preferences ->
        decode(preferences[KEY])
    }

    suspend fun current(): WebSearchSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    suspend fun setProvider(provider: WebSearchProvider) = update { it.copy(provider = provider) }

    suspend fun setEndpoint(provider: WebSearchProvider, endpoint: String) = update {
        it.withEndpoint(provider, endpoint.trim())
    }

    suspend fun setScrapeEndpoint(provider: WebSearchProvider, endpoint: String) = update {
        it.withScrapeEndpoint(provider, endpoint.trim())
    }

    suspend fun setTavilySearchDepth(depth: TavilyDepth) = update {
        it.copy(tavilySearchDepth = depth)
    }

    suspend fun setTavilyExtractDepth(depth: TavilyDepth) = update {
        it.copy(tavilyExtractDepth = depth)
    }

    private suspend fun update(transform: (WebSearchSettings) -> WebSearchSettings) {
        dataStore.edit { preferences ->
            preferences[KEY] = json.encodeToString(transform(decode(preferences[KEY])))
        }
    }

    private fun decode(raw: String?): WebSearchSettings = raw
        ?.takeIf(String::isNotBlank)
        ?.let { encoded -> runCatching { json.decodeFromString<WebSearchSettings>(encoded) }.getOrNull() }
        ?: WebSearchSettings()

    private companion object {
        val KEY = stringPreferencesKey("agent_web_search_settings_v1")
        val json = Json { ignoreUnknownKeys = true }
    }
}
