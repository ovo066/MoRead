package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.log10

/** Fish selects its speech model in a header and its voice via reference_id. */
internal class FishTtsClient(
    provider: AiProviderEntity,
    private val model: AiModelEntity,
    private val apiKey: String,
    httpClient: OkHttpClient
) {
    private val base = normalizeBase(provider.baseUrl)
    private val overrides = RequestOverrides.parse(mergeExtraJson(provider.extraJson, model.extraJson))
    private val transport = TtsHttpTransport(httpClient, apiKey)

    suspend fun synthesizeSpeech(
        text: String, voice: String?, format: String?, speed: Float?, volume: Float?,
        emotion: String?, instruction: String?
    ): SynthesizedSpeech {
        val fields = overrides.body.toMutableMap()
        val outputFormat = (format ?: (fields["format"] as? JsonPrimitive)?.contentOrNull ?: "mp3").lowercase()
        require(outputFormat in setOf("mp3", "wav", "opus")) { "Fish 朗读支持 MP3、WAV 或 Opus 格式" }
        fields["text"] = JsonPrimitive(styledText(text, emotion, instruction))
        fields["format"] = JsonPrimitive(outputFormat)
        if (voice != null) {
            fields.remove("references")
            if (voice.isBlank()) fields.remove("reference_id")
            else fields["reference_id"] = JsonPrimitive(voice.trim())
        }
        val prosody = (fields["prosody"] as? JsonObject).orEmpty().toMutableMap()
        speed?.let { prosody["speed"] = JsonPrimitive(it.coerceIn(0.5f, 2f)) }
        // App volume is an amplitude multiplier; Fish expects a gain in dB (1x = 0 dB).
        volume?.let { prosody["volume"] = JsonPrimitive((20 * log10(it.coerceAtLeast(0.01f))).coerceIn(-20f, 20f)) }
        if (prosody.isNotEmpty()) fields["prosody"] = JsonObject(prosody)
        fields.remove("model")
        val request = Request.Builder()
            .url("$base/${model.endpointPath.ifBlank { "/tts" }.trimStart('/')}")
            .header("Authorization", "Bearer $apiKey").header("Accept", "audio/*")
            .apply { overrides.headers.forEach { (key, value) -> header(key, value) } }
            .header("model", model.modelName)
            .post(JsonObject(fields).toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val result = transport.read(request)
        val mime = result.mediaType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && !mime.startsWith("audio/") && mime != "application/octet-stream") {
            throw AiClientException.Malformed("Fish 未返回音频，请检查模型和音色 ID")
        }
        return result.copy(mediaType = when (outputFormat) {
            "wav" -> "audio/wav"
            "opus" -> "audio/ogg"
            else -> "audio/mpeg"
        })
    }

    private fun styledText(text: String, emotion: String?, instruction: String?): String {
        val tags = buildList {
            emotion?.trim()?.takeIf(String::isNotBlank)?.let { add(EMOTIONS[it] ?: it) }
            instruction?.trim()?.takeIf(String::isNotBlank)?.let(::add)
        }.distinct()
        if (tags.isEmpty()) return text
        val legacy = model.modelName.equals("s1", true) || model.modelName.startsWith("speech-", true)
        // S1 understands only its fixed emotion vocabulary; do not read free-form direction aloud.
        val supported = if (legacy) tags.filter { it in EMOTIONS.values } else tags
        return supported.joinToString("") { tag ->
            val clean = tag.replace(Regex("[\\[\\]()]"), " ").trim()
            if (legacy) "($clean)" else "[$clean]"
        } + text
    }

    private companion object {
        val EMOTIONS = mapOf("开心" to "happy", "悲伤" to "sad", "愤怒" to "angry",
            "恐惧" to "scared", "厌恶" to "disgusted", "惊讶" to "surprised",
            "中性" to "neutral", "低语" to "whispering")
    }
}
