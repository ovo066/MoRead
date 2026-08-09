package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.search.WebSearchCredentialAliases
import com.mozhi.reader.ai.search.WebSearchProvider
import com.mozhi.reader.ai.search.WebSearchSettings
import com.mozhi.reader.ai.search.WebSearchSettingsStore
import com.mozhi.reader.ai.search.TavilyDepth
import com.mozhi.reader.core.security.ApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WebSearchSettingsUiState(
    val settings: WebSearchSettings = WebSearchSettings(),
    val hasKeys: Map<WebSearchProvider, Boolean> = emptyMap()
)

@HiltViewModel
class WebSearchSettingsViewModel @Inject constructor(
    private val store: WebSearchSettingsStore,
    private val apiKeyStore: ApiKeyStore
) : ViewModel() {
    private val hasKeys = MutableStateFlow(readKeyState())

    val uiState = combine(store.settings, hasKeys) { settings, keys ->
        WebSearchSettingsUiState(settings, keys)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WebSearchSettingsUiState(hasKeys = hasKeys.value)
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setEnabled(enabled) }
    }

    fun setProvider(provider: WebSearchProvider) {
        viewModelScope.launch { store.setProvider(provider) }
    }

    fun saveEndpoint(provider: WebSearchProvider, endpoint: String) {
        viewModelScope.launch { store.setEndpoint(provider, endpoint) }
    }

    fun resetEndpoint(provider: WebSearchProvider) {
        saveEndpoint(provider, provider.defaultSearchEndpoint)
    }

    fun saveScrapeEndpoint(provider: WebSearchProvider, endpoint: String) {
        viewModelScope.launch { store.setScrapeEndpoint(provider, endpoint) }
    }

    fun resetScrapeEndpoint(provider: WebSearchProvider) {
        saveScrapeEndpoint(provider, provider.defaultScrapeEndpoint)
    }

    fun setTavilySearchDepth(depth: TavilyDepth) {
        viewModelScope.launch { store.setTavilySearchDepth(depth) }
    }

    fun setTavilyExtractDepth(depth: TavilyDepth) {
        viewModelScope.launch { store.setTavilyExtractDepth(depth) }
    }

    fun saveApiKey(provider: WebSearchProvider, apiKey: String) {
        val clean = apiKey.trim()
        if (clean.isEmpty()) return
        apiKeyStore.put(WebSearchCredentialAliases.forProvider(provider), clean)
        hasKeys.value = readKeyState()
    }

    fun clearApiKey(provider: WebSearchProvider) {
        apiKeyStore.remove(WebSearchCredentialAliases.forProvider(provider))
        hasKeys.value = readKeyState()
    }

    private fun readKeyState(): Map<WebSearchProvider, Boolean> =
        WebSearchProvider.entries.associateWith { provider ->
            !apiKeyStore.get(WebSearchCredentialAliases.forProvider(provider)).isNullOrBlank()
        }
}
