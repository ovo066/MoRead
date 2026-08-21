package com.mozhi.reader.core.speech

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class TtsEngineMode { SYSTEM, AI }
enum class TtsSynthesisGranularity { SENTENCE, PARAGRAPH, CHAPTER }

/** 独立 TTS API 的服务商预设：MiniMax 国内/海外域名不同，OpenAI 兼容走 /audio/speech。 */
enum class TtsApiProvider { MINIMAX_CN, MINIMAX_INTL, OPENAI_COMPAT }

fun TtsApiProvider.defaultBaseUrl(): String = when (this) {
    TtsApiProvider.MINIMAX_CN -> "https://api.minimaxi.com/v1"
    TtsApiProvider.MINIMAX_INTL -> "https://api.minimax.io/v1"
    TtsApiProvider.OPENAI_COMPAT -> "https://api.openai.com/v1"
}

fun TtsApiProvider.defaultModel(): String = when (this) {
    TtsApiProvider.MINIMAX_CN, TtsApiProvider.MINIMAX_INTL -> "speech-2.8-hd"
    TtsApiProvider.OPENAI_COMPAT -> "gpt-4o-mini-tts"
}

/** 语音朗读配置：引擎切换 + 双引擎各自的参数，替代散落在 extraJson 里的手写字段。 */
data class TtsSettings(
    val engineMode: TtsEngineMode = TtsEngineMode.AI,
    /** 系统 TTS 引擎包名；空 = 系统默认引擎（如用户设为 Multi TTS 即生效）。 */
    val systemEnginePackage: String = "",
    /** BCP-47 语言标签；空 = 引擎默认。 */
    val systemLanguageTag: String = "",
    val systemRate: Float = 1f,
    val systemPitch: Float = 1f,
    val aiVoiceId: String = "",
    val aiSpeed: Float = 1f,
    val aiVolume: Float = 1f,
    val aiPitch: Int = 0,
    /** 以下为独立 TTS API 配置；填齐后优先于「模型分配」的 TTS 模型。 */
    val aiProvider: TtsApiProvider = TtsApiProvider.MINIMAX_CN,
    val aiBaseUrl: String = "",
    /** MiniMax GroupId，可选；OpenAI 兼容服务忽略。 */
    val aiGroupId: String = "",
    val aiModel: String = "",
    val allowAudioMixing: Boolean = false,
    val trimSilence: Boolean = true,
    val synthesisGranularity: TtsSynthesisGranularity = TtsSynthesisGranularity.PARAGRAPH,
    val maxSynthesisChars: Int = 400,
    val synthesisConcurrency: Int = 2,
    val retryCount: Int = 2,
    val prefetchCount: Int = 3,
    val systemVolumeCompensation: Float = 1f,
    val audiobookEnginePolicy: String = "NARRATOR_SYSTEM_CHARACTERS_AI"
) {
    /** Key 单独存 EncryptedSharedPreferences，可用性另行校验。 */
    val aiApiConfigured: Boolean get() = aiBaseUrl.isNotBlank() && aiModel.isNotBlank()

    /** 是否按 MiniMax t2a_v2 协议请求（自定义中转 URL 含 minimax 时也算）。 */
    val aiIsMiniMax: Boolean
        get() = aiProvider != TtsApiProvider.OPENAI_COMPAT ||
            aiBaseUrl.contains("minimax", ignoreCase = true)
}

@Singleton
class TtsSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val settings: Flow<TtsSettings> = dataStore.data.map(::readFrom)

    suspend fun current(): TtsSettings = settings.first()

    suspend fun update(transform: (TtsSettings) -> TtsSettings) {
        dataStore.edit { prefs ->
            val next = transform(readFrom(prefs))
            prefs[KEY_ENGINE_MODE] = next.engineMode.name
            prefs[KEY_SYSTEM_ENGINE] = next.systemEnginePackage
            prefs[KEY_SYSTEM_LANGUAGE] = next.systemLanguageTag
            prefs[KEY_SYSTEM_RATE] = next.systemRate
            prefs[KEY_SYSTEM_PITCH] = next.systemPitch
            prefs[KEY_AI_VOICE] = next.aiVoiceId
            prefs[KEY_AI_SPEED] = next.aiSpeed
            prefs[KEY_AI_VOLUME] = next.aiVolume
            prefs[KEY_AI_PITCH] = next.aiPitch
            prefs[KEY_AI_PROVIDER] = next.aiProvider.name
            prefs[KEY_AI_BASE_URL] = next.aiBaseUrl
            prefs[KEY_AI_GROUP_ID] = next.aiGroupId
            prefs[KEY_AI_MODEL] = next.aiModel
            prefs[KEY_ALLOW_AUDIO_MIXING] = next.allowAudioMixing
            prefs[KEY_TRIM_SILENCE] = next.trimSilence
            prefs[KEY_SYNTHESIS_GRANULARITY] = next.synthesisGranularity.name
            prefs[KEY_MAX_SYNTHESIS_CHARS] = next.maxSynthesisChars.coerceIn(80, 2_000)
            prefs[KEY_SYNTHESIS_CONCURRENCY] = next.synthesisConcurrency.coerceIn(1, 4)
            prefs[KEY_RETRY_COUNT] = next.retryCount.coerceIn(0, 5)
            prefs[KEY_PREFETCH_COUNT] = next.prefetchCount.coerceIn(0, 10)
            prefs[KEY_SYSTEM_VOLUME_COMPENSATION] =
                next.systemVolumeCompensation.coerceIn(0.25f, 2f)
            prefs[KEY_AUDIOBOOK_ENGINE_POLICY] = next.audiobookEnginePolicy
        }
    }

    private fun readFrom(prefs: Preferences): TtsSettings = TtsSettings(
        engineMode = prefs[KEY_ENGINE_MODE]
            ?.let { raw -> TtsEngineMode.entries.firstOrNull { it.name == raw } }
            ?: TtsEngineMode.AI,
        systemEnginePackage = prefs[KEY_SYSTEM_ENGINE].orEmpty(),
        systemLanguageTag = prefs[KEY_SYSTEM_LANGUAGE].orEmpty(),
        systemRate = prefs[KEY_SYSTEM_RATE] ?: 1f,
        systemPitch = prefs[KEY_SYSTEM_PITCH] ?: 1f,
        aiVoiceId = prefs[KEY_AI_VOICE].orEmpty(),
        aiSpeed = prefs[KEY_AI_SPEED] ?: 1f,
        aiVolume = prefs[KEY_AI_VOLUME] ?: 1f,
        aiPitch = prefs[KEY_AI_PITCH] ?: 0,
        aiProvider = prefs[KEY_AI_PROVIDER]
            ?.let { raw -> TtsApiProvider.entries.firstOrNull { it.name == raw } }
            ?: TtsApiProvider.MINIMAX_CN,
        aiBaseUrl = prefs[KEY_AI_BASE_URL].orEmpty(),
        aiGroupId = prefs[KEY_AI_GROUP_ID].orEmpty(),
        aiModel = prefs[KEY_AI_MODEL].orEmpty(),
        allowAudioMixing = prefs[KEY_ALLOW_AUDIO_MIXING] ?: false,
        trimSilence = prefs[KEY_TRIM_SILENCE] ?: true,
        synthesisGranularity = prefs[KEY_SYNTHESIS_GRANULARITY]
            ?.let { raw -> TtsSynthesisGranularity.entries.firstOrNull { it.name == raw } }
            ?: TtsSynthesisGranularity.PARAGRAPH,
        maxSynthesisChars = (prefs[KEY_MAX_SYNTHESIS_CHARS] ?: 400).coerceIn(80, 2_000),
        synthesisConcurrency = (prefs[KEY_SYNTHESIS_CONCURRENCY] ?: 2).coerceIn(1, 4),
        retryCount = (prefs[KEY_RETRY_COUNT] ?: 2).coerceIn(0, 5),
        prefetchCount = (prefs[KEY_PREFETCH_COUNT] ?: 3).coerceIn(0, 10),
        systemVolumeCompensation =
            (prefs[KEY_SYSTEM_VOLUME_COMPENSATION] ?: 1f).coerceIn(0.25f, 2f),
        audiobookEnginePolicy = prefs[KEY_AUDIOBOOK_ENGINE_POLICY]
            ?: "NARRATOR_SYSTEM_CHARACTERS_AI"
    )

    companion object {
        /** 独立 TTS API Key 在 EncryptedSharedPreferences 里的别名。 */
        const val API_KEY_ALIAS = "standalone-tts-api"

        private val KEY_ENGINE_MODE = stringPreferencesKey("tts_engine_mode")
        private val KEY_SYSTEM_ENGINE = stringPreferencesKey("tts_system_engine")
        private val KEY_SYSTEM_LANGUAGE = stringPreferencesKey("tts_system_language")
        private val KEY_SYSTEM_RATE = floatPreferencesKey("tts_system_rate")
        private val KEY_SYSTEM_PITCH = floatPreferencesKey("tts_system_pitch")
        private val KEY_AI_VOICE = stringPreferencesKey("tts_ai_voice")
        private val KEY_AI_SPEED = floatPreferencesKey("tts_ai_speed")
        private val KEY_AI_VOLUME = floatPreferencesKey("tts_ai_volume")
        private val KEY_AI_PITCH = intPreferencesKey("tts_ai_pitch")
        private val KEY_AI_PROVIDER = stringPreferencesKey("tts_ai_provider")
        private val KEY_AI_BASE_URL = stringPreferencesKey("tts_ai_base_url")
        private val KEY_AI_GROUP_ID = stringPreferencesKey("tts_ai_group_id")
        private val KEY_AI_MODEL = stringPreferencesKey("tts_ai_model")
        private val KEY_ALLOW_AUDIO_MIXING = booleanPreferencesKey("tts_allow_audio_mixing")
        private val KEY_TRIM_SILENCE = booleanPreferencesKey("tts_trim_silence")
        private val KEY_SYNTHESIS_GRANULARITY = stringPreferencesKey("tts_synthesis_granularity")
        private val KEY_MAX_SYNTHESIS_CHARS = intPreferencesKey("tts_max_synthesis_chars")
        private val KEY_SYNTHESIS_CONCURRENCY = intPreferencesKey("tts_synthesis_concurrency")
        private val KEY_RETRY_COUNT = intPreferencesKey("tts_retry_count")
        private val KEY_PREFETCH_COUNT = intPreferencesKey("tts_prefetch_count")
        private val KEY_SYSTEM_VOLUME_COMPENSATION =
            floatPreferencesKey("tts_system_volume_compensation")
        private val KEY_AUDIOBOOK_ENGINE_POLICY = stringPreferencesKey("tts_audiobook_engine_policy")
    }
}
