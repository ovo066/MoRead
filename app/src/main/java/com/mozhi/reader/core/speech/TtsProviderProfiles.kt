package com.mozhi.reader.core.speech

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Only non-secret settings belong in DataStore; keys have provider-scoped encrypted aliases. */
@Serializable
internal data class TtsProviderProfile(
    val baseUrl: String = "",
    val model: String = "",
    val groupId: String = "",
    val voiceId: String = "",
    val speed: Float = 1f,
    val volume: Float = 1f,
    val pitch: Int = 0,
    val granularity: String = "PARAGRAPH",
    val maxChars: Int = 400,
    val concurrency: Int = 2,
    val retries: Int = 2,
    val prefetch: Int = 3
) {
    fun applyTo(settings: TtsSettings) = settings.copy(
        aiBaseUrl = baseUrl, aiModel = model, aiGroupId = groupId, aiVoiceId = voiceId,
        aiSpeed = speed, aiVolume = volume, aiPitch = pitch,
        synthesisGranularity = TtsSynthesisGranularity.entries.firstOrNull { it.name == granularity }
            ?: TtsSynthesisGranularity.PARAGRAPH,
        maxSynthesisChars = maxChars, synthesisConcurrency = concurrency, retryCount = retries,
        prefetchCount = prefetch
    )

    companion object {
        fun from(settings: TtsSettings) = TtsProviderProfile(
            settings.aiBaseUrl, settings.aiModel, settings.aiGroupId, settings.aiVoiceId,
            settings.aiSpeed, settings.aiVolume, settings.aiPitch, settings.synthesisGranularity.name,
            settings.maxSynthesisChars, settings.synthesisConcurrency, settings.retryCount, settings.prefetchCount
        )
        fun defaults(provider: TtsApiProvider) = TtsProviderProfile(
            baseUrl = provider.defaultBaseUrl(), model = provider.defaultModel(),
            voiceId = if (provider == TtsApiProvider.GEMINI) "Sulafat" else ""
        )
    }
}

internal object TtsProviderProfiles {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(raw: String?): Map<String, TtsProviderProfile> = if (raw.isNullOrBlank()) emptyMap()
        else json.decodeFromString(raw)
    fun encode(profiles: Map<String, TtsProviderProfile>): String = json.encodeToString(profiles)
    fun decodeSystem(raw: String?): Map<String, TtsSystemProfile> = if (raw.isNullOrBlank()) emptyMap()
        else json.decodeFromString(raw)
    fun encodeSystem(profiles: Map<String, TtsSystemProfile>): String = json.encodeToString(profiles)
}

@Serializable
internal data class TtsSystemProfile(
    val voice: String = "", val language: String = "", val rate: Float = 1f, val pitch: Float = 1f
) {
    fun applyTo(settings: TtsSettings) = settings.copy(
        systemVoiceName = voice, systemLanguageTag = language, systemRate = rate, systemPitch = pitch
    )
    companion object {
        fun from(settings: TtsSettings) = TtsSystemProfile(
            settings.systemVoiceName, settings.systemLanguageTag, settings.systemRate, settings.systemPitch
        )
    }
}
