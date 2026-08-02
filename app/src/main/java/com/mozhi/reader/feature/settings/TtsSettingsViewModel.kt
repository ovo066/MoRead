package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.security.ApiKeyStore
import com.mozhi.reader.core.speech.SystemTtsEngineInfo
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.core.speech.TtsApiProvider
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.core.speech.TtsSettings
import com.mozhi.reader.core.speech.TtsSettingsStore
import com.mozhi.reader.core.speech.defaultBaseUrl
import com.mozhi.reader.core.speech.defaultModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TtsSettingsUiState(
    val settings: TtsSettings = TtsSettings(),
    val systemEngines: List<SystemTtsEngineInfo> = emptyList(),
    val hasApiKey: Boolean = false,
    val isPreviewing: Boolean = false,
    val message: String? = null
)

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val settingsStore: TtsSettingsStore,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val mediaService: AiMediaGenerationService,
    private val apiKeyStore: ApiKeyStore
) : ViewModel() {

    private val engines = MutableStateFlow<List<SystemTtsEngineInfo>>(emptyList())
    private val hasKey = MutableStateFlow(
        !apiKeyStore.get(TtsSettingsStore.API_KEY_ALIAS).isNullOrBlank()
    )
    private val preview = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        settingsStore.settings,
        engines,
        hasKey,
        preview,
        message
    ) { settings, engines, hasApiKey, previewing, message ->
        TtsSettingsUiState(settings, engines, hasApiKey, previewing, message)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TtsSettingsUiState()
    )

    init {
        viewModelScope.launch {
            engines.value = runCatching { systemTtsSpeaker.engines() }.getOrDefault(emptyList())
        }
    }

    fun setEngineMode(mode: TtsEngineMode) = update { it.copy(engineMode = mode) }
    fun setSystemEngine(packageName: String) = update { it.copy(systemEnginePackage = packageName) }
    fun setSystemLanguage(tag: String) = update { it.copy(systemLanguageTag = tag.trim()) }
    fun setSystemRate(rate: Float) = update { it.copy(systemRate = rate) }
    fun setSystemPitch(pitch: Float) = update { it.copy(systemPitch = pitch) }
    fun setAiVoice(voice: String) = update { it.copy(aiVoiceId = voice.trim()) }
    fun setAiSpeed(speed: Float) = update { it.copy(aiSpeed = speed) }
    fun setAiVolume(volume: Float) = update { it.copy(aiVolume = volume) }
    fun setAiPitch(pitch: Int) = update { it.copy(aiPitch = pitch) }

    /** 切服务商预设时把 Base URL / 模型重置为该预设默认值（可再手改）。 */
    fun setAiProvider(provider: TtsApiProvider) = update {
        it.copy(
            aiProvider = provider,
            aiBaseUrl = provider.defaultBaseUrl(),
            aiModel = provider.defaultModel()
        )
    }

    fun setAiBaseUrl(value: String) = update { it.copy(aiBaseUrl = value.trim()) }
    fun setAiGroupId(value: String) = update { it.copy(aiGroupId = value.trim()) }
    fun setAiModel(value: String) = update { it.copy(aiModel = value.trim()) }

    fun saveApiKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) return
        apiKeyStore.put(TtsSettingsStore.API_KEY_ALIAS, key)
        hasKey.value = true
        message.value = "API Key 已保存"
    }

    fun clearApiKey() {
        apiKeyStore.remove(TtsSettingsStore.API_KEY_ALIAS)
        hasKey.value = false
        message.value = "已删除 API Key"
    }

    fun preview() {
        viewModelScope.launch {
            preview.value = true
            message.value = null
            try {
                val settings = settingsStore.current()
                if (settings.engineMode == TtsEngineMode.SYSTEM) {
                    val ok = systemTtsSpeaker.speak(PREVIEW_TEXT, settings)
                    if (!ok) message.value = "系统引擎初始化失败，请换一个引擎试试"
                } else {
                    val speech = mediaService.synthesizeSpeech(
                        bookId = 0,
                        text = PREVIEW_TEXT,
                        voiceId = settings.aiVoiceId.takeIf(String::isNotBlank),
                        speed = settings.aiSpeed.takeIf { it != 1f },
                        volume = settings.aiVolume.takeIf { it != 1f },
                        pitch = settings.aiPitch.takeIf { it != 0 }
                    )
                    playPreview(speech.path)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                message.value = error.message ?: "试听失败"
            } finally {
                preview.value = false
            }
        }
    }

    private fun playPreview(path: String) {
        val player = android.media.MediaPlayer()
        player.setDataSource(path)
        player.setOnPreparedListener { it.start() }
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ ->
            mp.release()
            true
        }
        player.prepareAsync()
    }

    private fun update(transform: (TtsSettings) -> TtsSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    override fun onCleared() {
        systemTtsSpeaker.stop()
    }

    private companion object {
        const val PREVIEW_TEXT = "你好，这是墨知的语音朗读试听。夜色温柔，愿你读到喜欢的故事。"
    }
}
