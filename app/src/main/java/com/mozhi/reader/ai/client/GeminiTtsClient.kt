package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

data class GeminiVoiceDesignRequest(
    val name: String, val description: String, val gender: String = "female", val language: String = "zh-CN"
)

data class GeminiDesignedVoice(val id: String, val preview: SynthesizedSpeech?)

/** Gemini 3.8 native Interactions TTS. Transcript and acting directions remain separate. */
class GeminiTtsClient(
    private val provider: AiProviderEntity,
    private val model: AiModelEntity,
    private val apiKey: String,
    private val httpClient: OkHttpClient
) {
    private val base = normalizeBase(provider.baseUrl, "/v1beta", "/v1") + "/v1beta"
    val sourceBaseUrl: String get() = base

    suspend fun designVoice(design: GeminiVoiceDesignRequest): GeminiDesignedVoice {
        require(design.name.isNotBlank() && design.name.length <= 80) { "请填写 80 字以内的音色名称" }
        require(design.description.isNotBlank() && design.description.length <= 2000) { "请填写 2000 字以内的声音描述" }
        require(design.gender in setOf("male", "female", "neutral")) { "请选择声音类型" }
        require(design.language.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) { "语言代码无效" }
        val body = buildJsonObject {
            put("store", true)
            putJsonObject("voice") {
                put("model", model.modelName.removePrefix("models/"))
                put("type", "prompted")
                put("display_name", design.name.trim())
                put("gender", design.gender)
                put("language_code", design.language)
                putJsonObject("prompted") { put("input", design.description.trim()) }
            }
        }
        val response = requestJson(request("$base/voices").post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
        val id = response.string("id")
        requireDesignedId(id)
        // Keep the returned identity even when the preview is absent/corrupt; GetVoice can retry it without creating another voice.
        val preview = (response["sample_audio"] as? JsonObject)?.let { runCatching { decodeAudio(it, id) }.getOrNull() }
        return GeminiDesignedVoice(id, preview)
    }

    suspend fun designedVoicePreview(id: String): SynthesizedSpeech {
        requireDesignedId(id)
        val response = requestJson(request("$base/voices/$id").get().build())
        val audio = response["sample_audio"] as? JsonObject
            ?: throw AiClientException.Malformed("音色已生成，但服务暂未提供试听，请稍后重试获取试听")
        return decodeAudio(audio, id)
    }

    suspend fun deleteDesignedVoice(id: String) {
        requireDesignedId(id)
        requestJson(request("$base/voices/$id").delete().build(), allowEmpty = true)
    }

    private fun requireDesignedId(id: String) {
        require(id.matches(Regex("voice_[A-Za-z0-9_-]+"))) { "服务未返回有效的自定义音色 ID" }
    }

    suspend fun synthesizeSpeech(
        text: String,
        voice: String? = null,
        speed: Float? = null,
        volume: Float? = null,
        pitch: Int? = null,
        emotion: String? = null,
        instruction: String? = null
    ): SynthesizedSpeech {
        require(text.isNotBlank()) { "朗读文本不能为空" }
        val style = buildList {
            instruction?.trim()?.takeIf(String::isNotBlank)?.let(::add)
            emotion?.trim()?.takeIf { it.isNotBlank() && it != "auto" }?.let { add("Emotion: $it") }
            speed?.takeIf { it != 1f }?.let { add("Speak at ${it.coerceIn(0.5f, 2f)} times normal speed") }
            volume?.takeIf { it != 1f }?.let { add(if (it < 1f) "Speak softly" else "Speak with a louder delivery") }
            pitch?.takeIf { it != 0 }?.let { add(if (it < 0) "Use a lower vocal pitch" else "Use a higher vocal pitch") }
        }.joinToString("; ")
        val body = buildJsonObject {
            put("model", model.modelName.removePrefix("models/"))
            // Stateless synthesis: do not persist book excerpts as server-side interactions.
            put("store", false)
            putJsonArray("input") {
                add(buildJsonObject {
                    put("type", "user_input")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", text)
                            if (style.isNotBlank()) putJsonArray("annotations") {
                                add(buildJsonObject {
                                    put("type", "speech_metadata")
                                    put("style", style)
                                })
                            }
                        })
                    }
                })
            }
            putJsonObject("response_format") { put("type", "audio") }
            putJsonObject("generation_config") {
                putJsonArray("speech_config") {
                    add(buildJsonObject { put("voice", voice?.trim()?.takeIf(String::isNotBlank) ?: "Sulafat") })
                }
            }
        }
        val response = requestJson(request("$base/interactions").post(body.toString().toRequestBody(JSON_MEDIA_TYPE)).build())
        if (response.string("status") != "completed") throw AiClientException.Malformed("Gemini 语音合成未完成")
        // output_audio is an SDK convenience property; REST returns steps[].content[].
        val parts = (response["steps"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.filter { it.string("type") == "model_output" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }.filter { it.string("type") == "audio" }
        if (parts.size != 1) throw AiClientException.Malformed("Gemini 未返回单段语音")
        return decodeAudio(parts.single(), response.string("id").takeIf(String::isNotBlank))
    }

    private fun decodeAudio(part: JsonObject, generationId: String?): SynthesizedSpeech {
        val encoded = part.string("data")
        require(encoded.length <= MAX_AUDIO_BYTES / 3 * 4 + 4) { "生成语音超过 30 MB，已取消缓存" }
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            throw AiClientException.Malformed("Gemini 返回了无效音频")
        }
        require(bytes.size <= MAX_AUDIO_BYTES) { "生成语音超过 30 MB，已取消缓存" }
        if (bytes.isEmpty()) throw AiClientException.Empty()
        val mime = part.string("mime_type").lowercase()
        val wav = when (mime.substringBefore(';').trim()) {
            "audio/wav", "audio/wave", "audio/x-wav" -> {
                if (bytes.size < 44 || bytes.copyOfRange(0, 4).decodeToString() != "RIFF" ||
                    bytes.copyOfRange(8, 12).decodeToString() != "WAVE") {
                    throw AiClientException.Malformed("Gemini 返回的 WAV 文件无效")
                }
                bytes
            }
            "audio/l16", "audio/pcm" -> pcmToWav(bytes, mime)
            else -> throw AiClientException.Malformed("Gemini 返回了不支持的音频格式")
        }
        return SynthesizedSpeech(wav, "audio/wav", generationId)
    }

    /** Catalog language labels differ from synthesis languages; import the server catalog as-is. */
    suspend fun listVoices(): List<TtsVoiceEntity> {
        val voices = linkedMapOf<String, TtsVoiceEntity>()
        var token = ""
        val seen = mutableSetOf<String>()
        do {
            check(seen.add(token) && seen.size <= 100) { "Gemini 音色分页异常，请稍后重试" }
            val url = "$base/voices".toHttpUrl().newBuilder()
                .addQueryParameter("page_size", "1000")
                .apply { if (token.isNotBlank()) addQueryParameter("page_token", token) }.build()
            val response = requestJson(request(url.toString()).get().build(), 2 * 1024 * 1024)
            (response["voices"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { item ->
                val id = item.string("id")
                if (id.isNotBlank()) voices[id] = TtsVoiceEntity(
                    voiceId = id,
                    displayName = item.string("display_name").ifBlank { id },
                    tags = listOf("Gemini", item.string("language_code"), item.string("persona"), item.string("pitch"))
                        .filter(String::isNotBlank).joinToString(","),
                    gender = when (item.string("gender")) { "female" -> "FEMALE"; "male" -> "MALE"; else -> "UNSPECIFIED" },
                    providerHint = "GEMINI"
                )
            }
            token = response.string("next_page_token")
        } while (token.isNotBlank())
        return voices.values.toList()
    }

    private fun request(url: String) = Request.Builder().url(url).header("x-goog-api-key", apiKey)

    private suspend fun requestJson(request: Request, limit: Int = 42 * 1024 * 1024, allowEmpty: Boolean = false): JsonObject = withContext(Dispatchers.IO) {
        val call = httpClient.newCall(request)
        val response = try {
            suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IOException) { throw mapTransportError(error) }
        response.use {
            if (allowEmpty && it.code == 404) return@withContext JsonObject(emptyMap())
            if (!it.isSuccessful) {
                val detail = it.peekBody(8192).string()
                throw httpError(it.code, extractErrorMessage(detail)?.replace(apiKey, "[redacted]"))
            }
            require(it.body.contentLength() <= limit) { "Gemini 响应过大" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            it.body.byteStream().use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit) { "Gemini 响应过大" }
                    output.write(buffer, 0, count)
                }
            }
            if (allowEmpty && output.size() == 0) return@withContext JsonObject(emptyMap())
            try { AiJson.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject }
            catch (_: Exception) { throw AiClientException.Malformed("无法解析 Gemini 语音响应") }
        }
    }

    private fun pcmToWav(bytes: ByteArray, mime: String): ByteArray {
        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        require(rate in 8000..96000 && bytes.size % 2 == 0 && bytes.size <= MAX_AUDIO_BYTES - 44) { "Gemini PCM 音频无效" }
        return ByteBuffer.allocate(44 + bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(bytes.size + 36); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes.size); put(bytes)
        }.array()
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private companion object { const val MAX_AUDIO_BYTES = 30 * 1024 * 1024 }
}
