package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import java.io.ByteArrayOutputStream
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    private val isGmiRequestQueue = provider.baseUrl.contains("gmicloud.ai", ignoreCase = true) ||
        model.endpointPath.contains("requestqueue", ignoreCase = true)
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
        val baseUrl = base.toHttpUrlOrNull()
        val sameOrigin = baseUrl != null && baseUrl.scheme == url.scheme &&
            baseUrl.host == url.host && baseUrl.port == url.port
        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/*")
            .apply {
                if (sameOrigin) {
                    header("Authorization", "Bearer $apiKey")
                    overrides.headers.forEach { (key, value) -> header(key, value) }
                }
            }
            .get()
            .build()
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
                require(looksLikeImage(bytes)) {
                    "生图 API 返回了结果，但下载内容不是可识别的图片"
                }
                bytes
            }
        }
    }

    /**
     * OpenAI `/audio/speech`、MiniMax `/t2a_v2` 与 GMI Request Queue 共用入口。
     * 参数为 null 时读取模型 `extraJson.body` 的默认值；非 null 表示本次调用显式覆盖。
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
        return if (isGmiRequestQueue) {
            synthesizeGmiCloud(text, voice, responseFormat, speed, volume, pitch, emotion, instruction)
        } else if (isMiniMax) {
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

    private suspend fun synthesizeGmiCloud(
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
        val defaultPayload = defaults["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val performance = MiniMaxSpeechPerformanceMapper.map(emotion, instruction)
        val format = responseFormat
            ?: defaultPayload["format"]?.jsonPrimitive?.contentOrNull
            ?: "mp3"
        val payloadFields = defaultPayload.toMutableMap().apply {
            put("text", JsonPrimitive(performance.applyToText(text)))
            if (voice != null || "voice_id" !in this) {
                put(
                    "voice_id",
                    JsonPrimitive(
                        voice?.trim()?.takeIf(String::isNotEmpty)
                            ?: "English_expressive_narrator"
                    )
                )
            }
            val baseSpeed = speed ?: get("speed")?.jsonPrimitive?.floatOrNull ?: 1f
            val baseVolume = volume ?: get("vol")?.jsonPrimitive?.floatOrNull ?: 1f
            val basePitch = pitch ?: get("pitch")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            put(
                "speed",
                JsonPrimitive((baseSpeed * performance.speedMultiplier).coerceIn(0.5f, 2f).toString())
            )
            put(
                "vol",
                JsonPrimitive((baseVolume * performance.volumeMultiplier).coerceIn(0f, 10f).toString())
            )
            put(
                "pitch",
                JsonPrimitive((basePitch + performance.pitchOffset).coerceIn(-12, 12).toString())
            )
            if (!emotion.isNullOrBlank() || !instruction.isNullOrBlank() || "emotion" !in this) {
                EmotionDialectMapper.map(emotion, instruction, TtsEmotionDialect.GMI)
                    ?.let { mapped -> put(mapped.field, JsonPrimitive(mapped.value)) }
            }
            put("format", JsonPrimitive(format))
            if ("language_boost" !in this) put("language_boost", JsonPrimitive("auto"))
            if ("audio_sample_rate" !in this) put("audio_sample_rate", JsonPrimitive("32000"))
            if ("bitrate" !in this) put("bitrate", JsonPrimitive("128000"))
            if ("channel" !in this) put("channel", JsonPrimitive("2"))
        }
        val fields = defaults.toMutableMap().apply {
            put("model", JsonPrimitive(model.modelName))
            put("payload", JsonObject(payloadFields))
        }
        val path = model.endpointPath.ifBlank { GMI_REQUEST_PATH }
        var responseJson = parseGmiResponse(
            execute(httpClient, request(path, JsonObject(fields)))
        )
        var requestId = gmiRequestId(responseJson)
        repeat(GMI_MAX_POLL_ATTEMPTS) { attempt ->
            val status = gmiStatus(responseJson)
            if (status in GMI_FAILURE_STATES) {
                throw AiClientException.Malformed(
                    gmiErrorMessage(responseJson) ?: "GMI 语音合成失败"
                )
            }
            gmiSpeechFrom(responseJson, format, requestId)?.let { return it }
            if (status in GMI_SUCCESS_STATES) {
                throw AiClientException.Malformed("GMI 任务已完成，但响应中没有音频")
            }
            if (requestId.isNullOrBlank()) {
                throw AiClientException.Malformed("GMI 响应中没有任务 ID 或音频结果")
            }
            if (attempt > 0) delay(GMI_POLL_INTERVAL_MS)
            responseJson = parseGmiResponse(execute(httpClient, gmiStatusRequest(path, requestId)))
            requestId = gmiRequestId(responseJson) ?: requestId
        }
        throw AiClientException.Malformed("GMI 语音合成等待超时")
    }

    private fun parseGmiResponse(body: String): JsonElement = runCatching {
        AiJson.parseToJsonElement(body)
    }.getOrElse { throw AiClientException.Malformed("无法解析 GMI 语音响应") }

    private fun gmiRequestId(element: JsonElement): String? {
        val root = element as? JsonObject
        listOf("request_id", "requestId", "id").forEach { key ->
            (root?.get(key) as? JsonPrimitive)?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        return findJsonString(element, setOf("request_id", "requestId"))
    }

    private fun gmiStatus(element: JsonElement): String =
        findJsonString(element, setOf("status", "state"))?.trim()?.lowercase().orEmpty()

    private fun gmiErrorMessage(element: JsonElement): String? =
        findJsonString(element, setOf("message", "error_message", "errorMessage", "detail"))

    private suspend fun gmiSpeechFrom(
        element: JsonElement,
        format: String,
        requestId: String?
    ): SynthesizedSpeech? {
        val reference = findJsonString(
            element,
            setOf(
                "audio_url", "audioUrl", "output_url", "outputUrl", "file_url", "fileUrl",
                "url", "output", "result"
            )
        )
        if (!reference.isNullOrBlank()) {
            if (reference.startsWith("data:audio/", ignoreCase = true)) {
                val comma = reference.indexOf(',')
                if (comma > 0) {
                    val mediaType = reference.substringAfter("data:").substringBefore(';')
                    val bytes = decodeGmiAudio(reference.substring(comma + 1))
                    return SynthesizedSpeech(bytes, mediaType, requestId)
                }
            }
            if (reference.startsWith("https://", ignoreCase = true) ||
                reference.startsWith("http://", ignoreCase = true)
            ) {
                return downloadSpeech(reference, format, requestId)
            }
        }
        val encoded = findJsonString(
            element,
            setOf("audio_base64", "audioBase64", "base64", "audio")
        )?.takeIf { value ->
            !value.startsWith("http://", ignoreCase = true) &&
                !value.startsWith("https://", ignoreCase = true)
        } ?: return null
        val bytes = decodeGmiAudio(encoded)
        return SynthesizedSpeech(bytes, audioMediaType(format), requestId)
    }

    private fun findJsonString(element: JsonElement, keys: Set<String>): String? = when (element) {
        is JsonObject -> {
            keys.firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            } ?: element.values.firstNotNullOfOrNull { child -> findJsonString(child, keys) }
        }
        is JsonArray -> element.firstNotNullOfOrNull { child -> findJsonString(child, keys) }
        else -> null
    }

    private fun decodeGmiAudio(encoded: String): ByteArray {
        val clean = encoded.filterNot(Char::isWhitespace)
        require(clean.length <= MAX_AUDIO_BASE64_CHARS) { "生成语音超过 30 MB，已取消缓存" }
        val bytes = runCatching { Base64.getDecoder().decode(clean) }
            .getOrElse { throw AiClientException.Malformed("GMI 返回了无效的音频数据") }
        if (bytes.isEmpty()) throw AiClientException.Empty()
        require(bytes.size <= MAX_AUDIO_BYTES) { "生成语音超过 30 MB，已取消缓存" }
        return bytes
    }

    private fun gmiStatusRequest(path: String, requestId: String): Request = Request.Builder()
        .url("$base/${path.trimStart('/').trimEnd('/')}/$requestId")
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "application/json")
        .apply { overrides.headers.forEach { (key, value) -> header(key, value) } }
        .get()
        .build()

    private suspend fun downloadSpeech(
        rawUrl: String,
        format: String,
        requestId: String?
    ): SynthesizedSpeech = withContext(Dispatchers.IO) {
        val url = rawUrl.toHttpUrlOrNull()
            ?: throw AiClientException.Malformed("GMI 返回了无效的音频地址")
        val isLocalHttp = url.scheme == "http" && url.host in LOCAL_IMAGE_HOSTS
        require(url.isHttps || isLocalHttp) { "GMI 返回了不安全的音频地址" }
        val baseUrl = base.toHttpUrlOrNull()
        val sameOrigin = baseUrl != null && baseUrl.scheme == url.scheme &&
            baseUrl.host == url.host && baseUrl.port == url.port
        val request = Request.Builder()
            .url(url)
            .header("Accept", "audio/*")
            .apply {
                if (sameOrigin) {
                    header("Authorization", "Bearer $apiKey")
                    overrides.headers.forEach { (key, value) -> header(key, value) }
                }
            }
            .get()
            .build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (error: Throwable) {
            throw mapTransportError(error)
        }
        response.use {
            if (!it.isSuccessful) throw httpError(it.code, "下载 GMI 语音失败")
            val declaredLength = it.body.contentLength()
            require(declaredLength < 0 || declaredLength <= MAX_AUDIO_BYTES) {
                "生成语音超过 30 MB，已取消缓存"
            }
            val bytes = it.body.byteStream().use(::readAudioBytesLimited)
            if (bytes.isEmpty()) throw AiClientException.Empty()
            SynthesizedSpeech(
                bytes = bytes,
                mediaType = it.header("Content-Type") ?: audioMediaType(format),
                generationId = requestId
            )
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
        val performance = MiniMaxSpeechPerformanceMapper.map(emotion, instruction)
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
            val baseSpeed = speed ?: get("speed")?.jsonPrimitive?.floatOrNull ?: 1f
            val baseVolume = volume ?: get("vol")?.jsonPrimitive?.floatOrNull ?: 1f
            val basePitch = pitch ?: get("pitch")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            put("speed", JsonPrimitive((baseSpeed * performance.speedMultiplier).coerceIn(0.5f, 2f)))
            put("vol", JsonPrimitive((baseVolume * performance.volumeMultiplier).coerceIn(0f, 10f)))
            put("pitch", JsonPrimitive((basePitch + performance.pitchOffset).coerceIn(-12, 12)))
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
            put("text", JsonPrimitive(performance.applyToText(text)))
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

    private fun looksLikeImage(bytes: ByteArray): Boolean {
        val png = bytes.size >= 4 && bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
        val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val gif = bytes.size >= 6 && bytes.copyOfRange(0, 6).decodeToString() in setOf("GIF87a", "GIF89a")
        val webp = bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP"
        val bmp = bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte()
        val avif = bytes.size >= 12 && bytes.copyOfRange(4, 12).decodeToString().startsWith("ftyp")
        return png || jpeg || gif || webp || bmp || avif
    }

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
        const val MAX_AUDIO_BASE64_CHARS = MAX_AUDIO_BYTES * 4 / 3 + 8
        const val MAX_IMAGE_BASE64_CHARS = MAX_IMAGE_BYTES * 4 / 3 + 8
        const val GMI_REQUEST_PATH = "/api/v1/ie/requestqueue/apikey/requests"
        const val GMI_POLL_INTERVAL_MS = 1_500L
        const val GMI_MAX_POLL_ATTEMPTS = 80
        val GMI_SUCCESS_STATES = setOf("success", "succeeded", "completed", "finished", "done")
        val GMI_FAILURE_STATES = setOf("failed", "failure", "error", "cancelled", "canceled")
        val LOCAL_IMAGE_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
