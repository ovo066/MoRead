package com.mozhi.reader.feature.listen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.speech.SpeechCacheBookStats
import com.mozhi.reader.core.speech.SpeechCacheStore
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.core.speech.TtsSettings
import com.mozhi.reader.core.speech.TtsSettingsStore
import com.mozhi.reader.core.speech.TtsSynthesisGranularity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TtsTuningUiState(
    val settings: TtsSettings = TtsSettings(),
    val bookCache: SpeechCacheBookStats? = null
)

@HiltViewModel
class TtsTuningViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val settingsStore: TtsSettingsStore,
    private val speechCacheStore: SpeechCacheStore
) : ViewModel() {
    private val bookId = savedStateHandle.get<String>("bookId")?.toLongOrNull() ?: 0L
    private val cache = MutableStateFlow<SpeechCacheBookStats?>(null)

    val uiState = combine(settingsStore.settings, cache) { settings, bookCache ->
        TtsTuningUiState(settings, bookCache)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsTuningUiState())

    init {
        refreshCache()
    }

    fun setEngineMode(value: TtsEngineMode) = update { it.copy(engineMode = value) }
    fun setAiVoice(value: String) = update { it.copy(aiVoiceId = value.trim()) }
    fun setSystemRate(value: Float) = update { it.copy(systemRate = value) }
    fun setSystemPitch(value: Float) = update { it.copy(systemPitch = value) }
    fun setAiSpeed(value: Float) = update { it.copy(aiSpeed = value) }
    fun setAiVolume(value: Float) = update { it.copy(aiVolume = value) }
    fun setAiPitch(value: Int) = update { it.copy(aiPitch = value) }
    fun setAllowAudioMixing(value: Boolean) = update { it.copy(allowAudioMixing = value) }
    fun setTrimSilence(value: Boolean) = update { it.copy(trimSilence = value) }
    fun setGranularity(value: TtsSynthesisGranularity) = update { it.copy(synthesisGranularity = value) }
    fun setMaxChars(value: Int) = update { it.copy(maxSynthesisChars = value) }
    fun setConcurrency(value: Int) = update { it.copy(synthesisConcurrency = value) }
    fun setRetryCount(value: Int) = update { it.copy(retryCount = value) }
    fun setPrefetchCount(value: Int) = update { it.copy(prefetchCount = value) }

    fun clearBookCache() {
        if (bookId <= 0) return
        viewModelScope.launch {
            speechCacheStore.clearBook(bookId)
            refreshCache()
        }
    }

    private fun update(transform: (TtsSettings) -> TtsSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    private fun refreshCache() {
        if (bookId <= 0) return
        viewModelScope.launch {
            cache.value = speechCacheStore.statsByBook().firstOrNull { it.bookId == bookId }
        }
    }
}
