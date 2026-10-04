package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import java.util.Base64
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** MiMo puts the spoken text in an assistant message and style in a user message. */
internal class MimoTtsClient(
    provider: AiProviderEntity,
    private val model: AiModelEntity,
    private val apiKey: String,
    httpClient: OkHttpClient
) {
    private val base = normalizeBase(provider.baseUrl)
    private val overrides = RequestOverrides.parse(mergeExtraJson(provider.extraJson, model.extraJson))
    private val transport = TtsHttpTransport(httpClient, apiKey)

    suspend fun synthesizeSpeech(
        text: String, voice: String?, format: String?, speed: Float?, volume: Float?, pitch: Int?,
        emotion: String?, instruction: String?
    ): SynthesizedSpeech {
        val audio = (overrides.body["audio"] as? JsonObject).orEmpty().toMutableMap()
        val outputFormat = (format ?: (audio["format"] as? JsonPrimitive)?.contentOrNull ?: "wav").lowercase()
        require(outputFormat in setOf("wav", "mp3")) { "MiMo 朗读支持 WAV 或 MP3 格式" }
        audio["format"] = JsonPrimitive(outputFormat)
        if (model.modelName.endsWith("-voicedesign", true)) {
            audio.remove("voice")
        } else if (voice != null || "voice" !in audio) {
            audio["voice"] = JsonPrimitive(voice?.trim()?.takeIf(String::isNotEmpty) ?: "mimo_default")
        }
        // Reading must preserve the book's text, even if an override requests rewriting.
        audio.remove("optimize_text_preview")
        val style = buildList {
            instruction?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            emotion?.trim()?.takeIf(String::isNotEmpty)?.let { add("请用${it}的语气朗读。") }
            speed?.takeIf { it != 1f }?.let { add("语速为正常语速的 ${it.coerceIn(0.5f, 2f)} 倍。") }
            volume?.takeIf { it != 1f }?.let { add(if (it < 1f) "声音轻一些。" else "声音响亮一些。") }
            pitch?.takeIf { it != 0 }?.let { add(if (it < 0) "音调低一些。" else "音调高一些。") }
        }.joinToString("\n")
        val messages = buildJsonArray {
            if (style.isNotBlank()) add(buildJsonObject { put("role", "user"); put("content", style) })
            add(buildJsonObject { put("role", "assistant"); put("content", text) })
        }
        val body = JsonObject(overrides.body.toMutableMap().apply {
            put("model", JsonPrimitive(model.modelName))
            put("messages", messages)
            put("audio", JsonObject(audio))
            put("stream", JsonPrimitive(false))
        })
        val request = Request.Builder()
            .url("$base/${model.endpointPath.ifBlank { "/chat/completions" }.trimStart('/')}")
            .header("Authorization", "Bearer $apiKey").header("Accept", "application/json")
            .apply { overrides.headers.forEach { (key, value) -> header(key, value) } }
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        val response = transport.read(request, TtsHttpTransport.MAX_JSON_BYTES)
        val root = try { AiJson.parseToJsonElement(response.bytes.decodeToString()) as? JsonObject }
            catch (_: Exception) { null } ?: throw AiClientException.Malformed("无法解析 MiMo 语音响应")
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        val finishReason = (choice?.get("finish_reason") as? JsonPrimitive)?.contentOrNull
        if (finishReason == "length" || finishReason == "content_filter") {
            throw AiClientException.Malformed("MiMo 未生成完整语音，请缩短合成文本或检查内容")
        }
        val result = ((choice?.get("message") as? JsonObject)?.get("audio") as? JsonObject)
        val encoded = (result?.get("data") as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: throw AiClientException.Malformed("MiMo 未返回音频数据")
        require(encoded.length <= TtsHttpTransport.MAX_AUDIO_BYTES * 4 / 3 + 8) { "生成语音超过 30 MB，已取消缓存" }
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            throw AiClientException.Malformed("MiMo 返回了无效音频数据")
        }
        if (bytes.isEmpty()) throw AiClientException.Empty()
        require(bytes.size <= TtsHttpTransport.MAX_AUDIO_BYTES) { "生成语音超过 30 MB，已取消缓存" }
        return SynthesizedSpeech(bytes, if (outputFormat == "wav") "audio/wav" else "audio/mpeg",
            (result?.get("id") as? JsonPrimitive)?.contentOrNull ?: (root["id"] as? JsonPrimitive)?.contentOrNull)
    }
}
