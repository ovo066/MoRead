package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** ElevenLabs selects the voice in the URL and returns audio directly. */
internal class ElevenLabsTtsClient(
    provider: AiProviderEntity,
    private val model: AiModelEntity,
    private val apiKey: String,
    httpClient: OkHttpClient
) {
    private val base = normalizeBase(provider.baseUrl)
    private val overrides = RequestOverrides.parse(mergeExtraJson(provider.extraJson, model.extraJson))
    private val transport = TtsHttpTransport(httpClient, apiKey)

    suspend fun synthesizeSpeech(text: String, voice: String?, format: String?, speed: Float?): SynthesizedSpeech {
        val fields = overrides.body.toMutableMap()
        val voiceId = (voice ?: (fields["voice_id"] as? JsonPrimitive)?.contentOrNull)?.trim().orEmpty()
        require(voiceId.isNotEmpty() && voiceId != "." && voiceId != "..") { "请填写 ElevenLabs 音色 ID（voice_id），可从 ElevenLabs 音色库复制" }
        val outputFormat = resolveFormat(format ?: (fields["output_format"] as? JsonPrimitive)?.contentOrNull ?: "mp3")
        fields.remove("voice_id")
        fields.remove("output_format")
        fields["text"] = JsonPrimitive(text)
        fields["model_id"] = JsonPrimitive(model.modelName)
        val voiceSettings = (fields["voice_settings"] as? JsonObject).orEmpty().toMutableMap()
        if (model.modelName.trim().equals("eleven_v3", ignoreCase = true)) {
            voiceSettings.remove("speed")
        } else {
            speed?.let { voiceSettings["speed"] = JsonPrimitive(it.coerceIn(0.7f, 1.2f)) }
        }
        fields.remove("voice_settings")
        if (voiceSettings.isNotEmpty()) fields["voice_settings"] = JsonObject(voiceSettings)
        val path = model.endpointPath.ifBlank { "/text-to-speech" }.trimStart('/')
        val url = "$base/$path".toHttpUrl().newBuilder()
            .addPathSegment(voiceId).setQueryParameter("output_format", outputFormat).build()
        val request = Request.Builder().url(url)
            .header("Accept", "audio/*")
            .apply { overrides.headers.forEach { (key, value) -> header(key, value) } }
            .header("xi-api-key", apiKey)
            .post(JsonObject(fields).toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val result = transport.read(request)
        val mime = result.mediaType?.substringBefore(';')?.trim()?.lowercase()
        if (mime != null && !mime.startsWith("audio/") && mime != "application/octet-stream") {
            throw AiClientException.Malformed("ElevenLabs 未返回音频，请检查模型和音色 ID")
        }
        return result.copy(mediaType = when {
            outputFormat.startsWith("wav_") -> "audio/wav"
            outputFormat.startsWith("opus_") -> "audio/ogg"
            else -> "audio/mpeg"
        })
    }

    private fun resolveFormat(format: String): String {
        val resolved = when (val value = format.lowercase()) {
            "mp3" -> "mp3_44100_128"
            "wav" -> "wav_24000"
            "opus" -> "opus_48000_128"
            else -> value
        }
        require(resolved in OUTPUT_FORMATS) { "ElevenLabs 朗读支持 MP3、WAV 或 Opus 格式" }
        return resolved
    }

    private companion object {
        val OUTPUT_FORMATS = setOf(
            "mp3_22050_32", "mp3_24000_48", "mp3_44100_32", "mp3_44100_64", "mp3_44100_96", "mp3_44100_128", "mp3_44100_192",
            "wav_8000", "wav_16000", "wav_22050", "wav_24000", "wav_32000", "wav_44100", "wav_48000",
            "opus_48000_32", "opus_48000_64", "opus_48000_96", "opus_48000_128", "opus_48000_192"
        )
    }
}
