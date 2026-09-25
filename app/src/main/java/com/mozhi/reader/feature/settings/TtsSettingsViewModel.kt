package com.mozhi.reader.feature.settings

import com.mozhi.reader.core.di.ApplicationScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.security.ApiKeyStore
import com.mozhi.reader.core.speech.SystemTtsVoiceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.mozhi.reader.core.speech.SystemTtsEngineInfo
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.core.speech.TtsApiProvider
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.core.speech.TtsSettings
import com.mozhi.reader.core.speech.TtsSettingsStore
import com.mozhi.reader.core.speech.TtsSynthesisGranularity
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
    val message: String? = null,
    val systemVoices: List<SystemTtsVoiceInfo> = emptyList(),
    val loadingVoices: Boolean = false
)

@HiltViewModel
class TtsSettingsViewModel @Inject constructor(
    private val settingsStore: TtsSettingsStore,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val mediaService: AiMediaGenerationService,
    private val apiKeyStore: ApiKeyStore,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {

    private val voiceState = MutableStateFlow(emptyList<SystemTtsVoiceInfo>() to false)
    private var voiceJob: Job? = null
    private val engines = MutableStateFlow<List<SystemTtsEngineInfo>>(emptyList())
    private val keyRevision = MutableStateFlow(0)
    private val preview = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val writes = SettingsWriteQueue(
        CoroutineScope(applicationScope.coroutineContext + Dispatchers.Main.immediate)
    ) { error -> message.value = "保存失败：${error.message ?: "请重试"}" }

    suspend fun flushPendingWrites(retry: Boolean = false): Boolean {
        if (retry) writes.retryFailed()
        val saved = writes.flush()
        if (saved && message.value?.startsWith("保存失败") == true) message.value = null
        return saved
    }

    fun discardFailedWrites() { writes.discardFailures() }

    val uiState = combine(
        settingsStore.settings,
        combine(engines, voiceState) { installed, voices -> installed to voices },
        keyRevision,
        preview,
        message
    ) { settings, engines, _, previewing, message ->
        val providerKey = apiKeyStore.migrateAlias(TtsSettingsStore.API_KEY_ALIAS, TtsSettingsStore.apiKeyAlias(settings.aiProvider))
        TtsSettingsUiState(settings, engines.first, !providerKey.isNullOrBlank(), previewing, message, engines.second.first, engines.second.second)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TtsSettingsUiState()
    )

    init {
        viewModelScope.launch {
            settingsStore.settings.map { it.systemEnginePackage }.distinctUntilChanged().collectLatest {
                refreshSystemVoices()
            }
        }
        viewModelScope.launch {
            engines.value = runCatching { systemTtsSpeaker.engines() }.getOrDefault(emptyList())
        }
    }

    fun refreshSystemVoices() {
        voiceJob?.cancel()
        voiceJob = viewModelScope.launch {
            if (!writes.flush()) return@launch
            val engine = settingsStore.current().systemEnginePackage
            voiceState.value = voiceState.value.first to true
            try {
                engines.value = systemTtsSpeaker.engines()
                val voices = systemTtsSpeaker.voices(engine)
                voiceState.value = voices to false
                message.value = if (voices.isEmpty()) "引擎未公开音色，请在引擎应用中配置后刷新" else "已读取 "+voices.size+" 个音色"
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                voiceState.value = emptyList<SystemTtsVoiceInfo>() to false
                message.value = error.message ?: "读取音色失败"
            }
        }
    }

    fun setSystemVoice(name: String) = update("setSystemVoice") { it.copy(systemVoiceName = name) }

    fun setEngineMode(mode: TtsEngineMode) = update("setEngineMode") { it.copy(engineMode = mode) }
    fun setSystemEngine(packageName: String) {
        voiceJob?.cancel()
        voiceState.value = emptyList<SystemTtsVoiceInfo>() to true
        update("setSystemEngine") { it.copy(systemEnginePackage = packageName) }
        refreshSystemVoices()
    }
    fun setSystemLanguage(tag: String) = update("setSystemLanguage") { it.copy(systemLanguageTag = tag) }
    fun setSystemRate(rate: Float) = update("setSystemRate") { it.copy(systemRate = rate) }
    fun setSystemPitch(pitch: Float) = update("setSystemPitch") { it.copy(systemPitch = pitch) }
    fun setAiVoice(voice: String) = update("setAiVoice") { it.copy(aiVoiceId = voice) }
    fun setAiSpeed(speed: Float) = update("setAiSpeed") { it.copy(aiSpeed = speed) }
    fun setAiVolume(volume: Float) = update("setAiVolume") { it.copy(aiVolume = volume) }
    fun setAiPitch(pitch: Int) = update("setAiPitch") { it.copy(aiPitch = pitch) }
    fun setAllowAudioMixing(value: Boolean) = update("setAllowAudioMixing") { it.copy(allowAudioMixing = value) }
    fun setTrimSilence(value: Boolean) = update("setTrimSilence") { it.copy(trimSilence = value) }
    fun setSynthesisGranularity(value: TtsSynthesisGranularity) =
        update("setSynthesisGranularity") { it.copy(synthesisGranularity = value) }
    fun setMaxSynthesisChars(value: Int) = update("setMaxSynthesisChars") { it.copy(maxSynthesisChars = value) }
    fun setSynthesisConcurrency(value: Int) = update("setSynthesisConcurrency") { it.copy(synthesisConcurrency = value) }
    fun setRetryCount(value: Int) = update("setRetryCount") { it.copy(retryCount = value) }
    fun setPrefetchCount(value: Int) = update("setPrefetchCount") { it.copy(prefetchCount = value) }

    /** The write queue commits the outgoing form before restoring the target provider profile. */
    fun setAiProvider(provider: TtsApiProvider) = writes.enqueue("setAiProvider") {
        val previous = settingsStore.current().aiProvider
        apiKeyStore.migrateAlias(TtsSettingsStore.API_KEY_ALIAS, TtsSettingsStore.apiKeyAlias(previous))
        settingsStore.update { it.copy(aiProvider = provider) }
        keyRevision.value += 1
    }

    fun setAiBaseUrl(value: String) = update("setAiBaseUrl") { it.copy(aiBaseUrl = value) }
    fun setAiGroupId(value: String) = update("setAiGroupId") { it.copy(aiGroupId = value) }
    fun setAiModel(value: String) = update("setAiModel") { it.copy(aiModel = value) }

    fun saveApiKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) return
        writes.enqueue("saveApiKey") {
            val provider = settingsStore.current().aiProvider
            apiKeyStore.migrateAlias(TtsSettingsStore.API_KEY_ALIAS, TtsSettingsStore.apiKeyAlias(provider))
            apiKeyStore.put(TtsSettingsStore.apiKeyAlias(provider), key)
            keyRevision.value += 1
            message.value = "API Key 已保存"
        }
    }

    fun clearApiKey() {
        writes.enqueue("clearApiKey") {
            val provider = settingsStore.current().aiProvider
            apiKeyStore.migrateAlias(TtsSettingsStore.API_KEY_ALIAS, TtsSettingsStore.apiKeyAlias(provider))
            apiKeyStore.remove(TtsSettingsStore.apiKeyAlias(provider))
            keyRevision.value += 1
            message.value = "已删除 API Key"
        }
    }

    fun preview() {
        viewModelScope.launch {
            if (!writes.flush() || preview.value) return@launch
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

    private fun update(key: String, transform: (TtsSettings) -> TtsSettings) =
        writes.enqueue(key) { settingsStore.update(transform) }

    override fun onCleared() {
        systemTtsSpeaker.stop()
    }

    private companion object {
        const val PREVIEW_TEXT = "你好，这是墨知的语音朗读试听。夜色温柔，愿你读到喜欢的故事。"
    }
}
