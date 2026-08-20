package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody

data class GeneratedImage(
    val bytes: ByteArray?,
    val url: String?,
    val mediaType: String?
)

data class SynthesizedSpeech(
    val bytes: ByteArray,
    val mediaType: String?,
    val generationId: String?
)

/** OpenAI-compatible media endpoints；OpenRouter 的生图默认路径单独适配为 `/images`。 */
class OpenAiMediaClient(
    private val provider: AiProviderEntity,
    private val model: AiModelEntity,
    private val apiKey: String,
    private val httpClient: OkHttpClient
) : ImageGenerationClient {
    private val base = normalizeBase(provider.baseUrl)
    private val mergedExtraJson = mergeExtraJson(provider.extraJson, model.extraJson)
    private val overrides = RequestOverrides.parse(mergedExtraJson)
    private val isMiniMax = provider.adapter == AiProviderAdapter.MINIMAX ||
        provider.baseUrl.contains("minimax", ignoreCase = true) ||
        provider.baseUrl.contains("minimaxi", ignoreCase = true)

    override suspend fun generateImages(
        prompt: String,
        count: Int,
        size: String?
    ): List<GeneratedImage> {
        require(prompt.isNotBlank()) { "生图提示词不能为空" }
        val path = model.endpointPath.ifBlank {
            if (provider.adapter == AiProviderAdapter.OPENROUTER) {
                "/images"
            } else {
                "/images/generations"
            }
        }
        // gpt-image 系经中转常暴露在 chat/completions；按端点路径切换请求与解析方式。
        if (path.contains("chat/completion", ignoreCase = true)) {
            return generateImagesViaChat(prompt, path)
        }
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "model" to JsonPrimitive(model.modelName),
            "prompt" to JsonPrimitive(prompt)
        )
        if (count > 1) fields["n"] = JsonPrimitive(count)
        size?.takeIf(String::isNotBlank)?.let { fields["size"] = JsonPrimitive(it) }
        fields.putAll(overrides.body)
        val body = execute(httpClient, request(path, JsonObject(fields)))
        val response = runCatching {
            AiJson.decodeFromString(ImageResponse.serializer(), body)
        }.getOrElse { throw AiClientException.Malformed("无法解析生图响应") }
        val images = response.data.mapNotNull { item ->
            val bytes = item.base64?.let { encoded ->
                require(encoded.length <= MAX_IMAGE_BASE64_CHARS) {
                    "生成图片超过 30 MB，已取消保存"
                }
                runCatching { Base64.getDecoder().decode(encoded) }
                    .getOrNull()
                    ?.also {
                        require(it.size <= MAX_IMAGE_BYTES) { "生成图片超过 30 MB，已取消保存" }
                    }
            }
            if (bytes == null && item.url.isNullOrBlank()) null else {
                GeneratedImage(bytes, item.url, item.mediaType)
            }
        }
        if (images.isEmpty()) throw AiClientException.Empty()
        return images
    }

    /** chat/completions 出图：发一轮用户消息，从响应各处（images[] / markdown / data URI）捞图。 */
    private suspend fun generateImagesViaChat(prompt: String, path: String): List<GeneratedImage> {
        val message = JsonObject(
            mapOf(
                "role" to JsonPrimitive("user"),
                "content" to JsonPrimitive(prompt)
            )
        )
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "model" to JsonPrimitive(model.modelName),
            "messages" to kotlinx.serialization.json.JsonArray(listOf(message)),
            "stream" to JsonPrimitive(false),
            "modalities" to kotlinx.serialization.json.JsonArray(
                listOf(JsonPrimitive("image"), JsonPrimitive("text"))
            )
        )
        overrides.body.forEach { (key, value) ->
            if (key != "size") fields[key] = value
        }
        val body = execute(httpClient, request(path, JsonObject(fields)))
        val images = ChatImageExtractor.extract(body).mapNotNull(::imageFromRef)
        if (images.isEmpty()) {
            throw AiClientException.Malformed("聊天补全响应中没有图片，请确认模型支持生图")
        }
        return images
    }

    private fun imageFromRef(ref: String): GeneratedImage? {
        if (!ref.startsWith("data:", ignoreCase = true)) {
            return GeneratedImage(bytes = null, url = ref, mediaType = null)
        }
        val comma = ref.indexOf(',')
        if (comma <= 5) return null
        val header = ref.substring(5, comma)
        val mediaType = header.substringBefore(';').trim().ifBlank { "image/png" }
        val encoded = ref.substring(comma + 1)
        require(encoded.length <= MAX_IMAGE_BASE64_CHARS) { "生成图片超过 30 MB，已取消保存" }
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        require(bytes.size <= MAX_IMAGE_BYTES) { "生成图片超过 30 MB，已取消保存" }
        return GeneratedImage(bytes = bytes, url = null, mediaType = mediaType)
    }

    /** 生图服务返回临时 URL 时立即下载，避免链接过期后插图丢失。 */
    override suspend fun materializeImage(image: GeneratedImage): ByteArray {
        image.bytes?.takeIf { it.isNotEmpty() }?.let { return it }
        val url = image.url?.trim().orEmpty().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("生图服务返回了无效的图片地址")
        val isLocalHttp = url.scheme == "http" && url.host in LOCAL_IMAGE_HOSTS
        require(url.isHttps || isLocalHttp) { "生图服务返回了不安全的图片地址" }
        val request = Request.Builder().url(url).header("Accept", "image/*").get().build()
        return withContext(Dispatchers.IO) {
            val response = try {
                httpClient.newCall(request).execute()
            } catch (error: Throwable) {
                throw mapTransportError(error)
            }
            response.use {
                if (!it.isSuccessful) throw httpError(it.code, "下载生成图片失败")
                val body = it.body
                val declaredLength = body.contentLength()
                require(declaredLength < 0 || declaredLength <= MAX_IMAGE_BYTES) {
                    "生成图片超过 30 MB，已取消保存"
                }
                val bytes = body.byteStream().use(::readImageBytesLimited)
                if (bytes.isEmpty()) throw AiClientException.Empty()
                bytes
            }
        }
    }

    /**
     * OpenAI `/audio/speech` 与 MiniMax `/t2a_v2` 共用入口。参数为 null 时读取模型
     * `extraJson.body` 的默认值；非 null 表示本次 Agent/用户调用显式覆盖。
     */
    suspend fun synthesizeSpeech(
        text: String,
        voice: String? = null,
        responseFormat: String? = null,
        speed: Float? = null,
        volume: Float? = null,
        pitch: Int? = null,
        emotion: String? = null,
        instruction: String? = null
    ): SynthesizedSpeech {
        require(text.isNotBlank()) { "朗读文本不能为空" }
        return if (isMiniMax) {
            synthesizeMiniMax(text, voice, responseFormat, speed, volume, pitch, emotion, instruction)
        } else {
            val fields = overrides.body.toMutableMap()
            fields["model"] = JsonPrimitive(model.modelName)
            fields["input"] = JsonPrimitive(text)
            if (voice != null || "voice" !in fields) {
                fields["voice"] = JsonPrimitive(voice?.trim()?.ifBlank { "alloy" } ?: "alloy")
            }
            if (responseFormat != null || "response_format" !in fields) {
                fields["response_format"] = JsonPrimitive(responseFormat ?: "mp3")
            }
            if (speed != null || "speed" !in fields) {
                fields["speed"] = JsonPrimitive((speed ?: 1f).coerceIn(0.25f, 4f))
            }
            EmotionDialectMapper.map(emotion, instruction, TtsEmotionDialect.OPENAI)
                ?.let { mapped -> fields[mapped.field] = JsonPrimitive(mapped.value) }
            val path = model.endpointPath.ifBlank { "/audio/speech" }
            executeBytes(httpClient, request(path, JsonObject(fields), accept = "*/*"))
        }
    }

    private suspend fun synthesizeMiniMax(
        text: String,
        voice: String?,
        responseFormat: String?,
        speed: Float?,
        volume: Float?,
        pitch: Int?,
        emotion: String?,
        instruction: String?
    ): SynthesizedSpeech {
        val defaults = overrides.body
        val defaultVoice = defaults["voice_setting"] as? JsonObject ?: JsonObject(emptyMap())
        val defaultAudio = defaults["audio_setting"] as? JsonObject ?: JsonObject(emptyMap())
        val voiceFields = defaultVoice.toMutableMap().apply {
            put(
                "voice_id",
                JsonPrimitive(
                    voice?.trim()?.takeIf(String::isNotEmpty)
                        ?: get("voice_id")?.jsonPrimitive?.contentOrNull
                        ?: defaults["voice_id"]?.jsonPrimitive?.contentOrNull
                        ?: "male-qn-qingse"
                )
            )
            put("speed", JsonPrimitive((speed ?: get("speed")?.jsonPrimitive?.floatOrNull ?: 1f).coerceIn(0.5f, 2f)))
            put("vol", JsonPrimitive((volume ?: get("vol")?.jsonPrimitive?.floatOrNull ?: 1f).coerceIn(0f, 10f)))
            put("pitch", JsonPrimitive((pitch ?: get("pitch")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0).coerceIn(-12, 12)))
            if (!emotion.isNullOrBlank() || !instruction.isNullOrBlank()) {
                EmotionDialectMapper.map(emotion, instruction, TtsEmotionDialect.MINIMAX)
                    ?.let { mapped -> put(mapped.field, JsonPrimitive(mapped.value)) }
            }
        }
        val format = responseFormat
            ?: defaultAudio["format"]?.jsonPrimitive?.contentOrNull
            ?: defaults["response_format"]?.jsonPrimitive?.contentOrNull
            ?: "mp3"
        val audioFields = defaultAudio.toMutableMap().apply { put("format", JsonPrimitive(format)) }
        val fields = defaults.toMutableMap().apply {
            listOf(
                "group_id", "GroupId", "voice_id", "voice", "speed", "vol", "pitch",
                "response_format"
            ).forEach(::remove)
            put("model", JsonPrimitive(model.modelName))
            put("text", JsonPrimitive(text))
            put("stream", JsonPrimitive(false))
            put("voice_setting", JsonObject(voiceFields))
            put("audio_setting", JsonObject(audioFields))
        }
        val groupId = defaults["group_id"]?.jsonPrimitive?.contentOrNull
            ?: defaults["GroupId"]?.jsonPrimitive?.contentOrNull
        val path = model.endpointPath.ifBlank { "/t2a_v2" }
        val body = execute(
            httpClient,
            request(
                path = path,
                payload = JsonObject(fields),
                query = groupId?.let { mapOf("GroupId" to it) }.orEmpty()
            )
        )
        val response = runCatching {
            AiJson.decodeFromString(MiniMaxSpeechResponse.serializer(), body)
        }.getOrElse { throw AiClientException.Malformed("无法解析 MiniMax 语音响应") }
        if ((response.baseResponse?.statusCode ?: 0) != 0) {
            throw AiClientException.Malformed(
                response.baseResponse?.statusMessage ?: "MiniMax 语音合成失败"
            )
        }
        val encoded = response.data?.audio?.trim().orEmpty()
        if (encoded.isEmpty()) throw AiClientException.Empty()
        require(encoded.length <= MAX_AUDIO_ENCODED_CHARS) { "生成语音超过 30 MB，已取消缓存" }
        val bytes = decodeMiniMaxAudio(encoded)
        require(bytes.size <= MAX_AUDIO_BYTES) { "生成语音超过 30 MB，已取消缓存" }
        return SynthesizedSpeech(
            bytes = bytes,
            mediaType = audioMediaType(format),
            generationId = response.traceId
        )
    }

    private fun request(
        path: String,
        payload: JsonObject,
        accept: String = "application/json",
        query: Map<String, String> = emptyMap()
    ): Request {
        val rawUrl = "$base/${path.trimStart('/')}"
        val url = rawUrl.toHttpUrlOrNull()?.newBuilder()?.apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: throw IllegalArgumentException("媒体端点地址无效")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", accept)
            .apply { overrides.headers.forEach { (key, value) -> header(key, value) } }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private suspend fun executeBytes(
        client: OkHttpClient,
        request: Request
    ): SynthesizedSpeech = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (error: Throwable) {
            throw mapTransportError(error)
        }
        response.use {
            if (!it.isSuccessful) {
                val errorBody = it.body.string()
                throw httpError(it.code, extractErrorMessage(errorBody))
            }
            val declaredLength = it.body.contentLength()
            require(declaredLength < 0 || declaredLength <= MAX_AUDIO_BYTES) {
                "生成语音超过 30 MB，已取消缓存"
            }
            val bytes = it.body.byteStream().use(::readAudioBytesLimited)
            if (bytes.isEmpty()) throw AiClientException.Empty()
            SynthesizedSpeech(
                bytes = bytes,
                mediaType = it.header("Content-Type"),
                generationId = it.header("X-Generation-Id")
            )
        }
    }

    private fun readAudioBytesLimited(input: java.io.InputStream): ByteArray =
        readBytesLimited(input, MAX_AUDIO_BYTES, "生成语音超过 30 MB，已取消缓存")

    private fun readImageBytesLimited(input: java.io.InputStream): ByteArray =
        readBytesLimited(input, MAX_IMAGE_BYTES, "生成图片超过 30 MB，已取消保存")

    private fun readBytesLimited(
        input: java.io.InputStream,
        maxBytes: Int,
        overflowMessage: String
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { overflowMessage }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun decodeMiniMaxAudio(encoded: String): ByteArray {
        val isHex = encoded.length % 2 == 0 && encoded.all { it in "0123456789abcdefABCDEF" }
        return runCatching {
            if (isHex) {
                ByteArray(encoded.length / 2) { index ->
                    encoded.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            } else {
                Base64.getDecoder().decode(encoded)
            }
        }.getOrElse { throw AiClientException.Malformed("MiniMax 返回了无效的音频数据") }
    }

    private fun audioMediaType(format: String): String = when (format.lowercase()) {
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "pcm" -> "audio/L16"
        else -> "audio/mpeg"
    }

    @Serializable
    private data class ImageResponse(val data: List<ImageData> = emptyList())

    @Serializable
    private data class ImageData(
        @SerialName("b64_json") val base64: String? = null,
        val url: String? = null,
        @SerialName("media_type") val mediaType: String? = null
    )

    @Serializable
    private data class MiniMaxSpeechResponse(
        val data: MiniMaxSpeechData? = null,
        @SerialName("trace_id") val traceId: String? = null,
        @SerialName("base_resp") val baseResponse: MiniMaxBaseResponse? = null
    )

    @Serializable
    private data class MiniMaxSpeechData(val audio: String? = null)

    @Serializable
    private data class MiniMaxBaseResponse(
        @SerialName("status_code") val statusCode: Int = 0,
        @SerialName("status_msg") val statusMessage: String? = null
    )

    private companion object {
        const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
        const val MAX_AUDIO_BYTES = 30 * 1024 * 1024
        const val MAX_AUDIO_ENCODED_CHARS = MAX_AUDIO_BYTES * 2 + 8
        const val MAX_IMAGE_BASE64_CHARS = MAX_IMAGE_BYTES * 4 / 3 + 8
        val LOCAL_IMAGE_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
