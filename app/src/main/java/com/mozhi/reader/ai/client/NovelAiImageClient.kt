package com.mozhi.reader.ai.client

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.mozhi.reader.ai.media.ImageCapabilities
import com.mozhi.reader.ai.media.ReferenceKind
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * NovelAI 生图客户端：`/ai/generate-image` 返回 zip（内含 PNG）。
 * v4 系模型走 v4_prompt 结构化提示词；v3 及更早用平铺 negative_prompt。
 */
class NovelAiImageClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val defaultSize: String,
    private val positivePrompt: String = "",
    private val negativePrompt: String,
    private val sampler: String = "",
    private val steps: Int = 28,
    private val scale: Float = 5f,
    private val httpClient: OkHttpClient
) : ImageGenerationClient {

    private val base = normalizeBase(baseUrl.ifBlank { DEFAULT_BASE_URL })
    override val defaultImageSize: String get() = defaultSize
    override val capabilities: ImageCapabilities get() {
        val v45 = model.startsWith("nai-diffusion-4-5")
        val v4 = model.startsWith("nai-diffusion-4")
        val v5 = model.startsWith("nai-diffusion-5")
        return ImageCapabilities(maxReferences = if (v4 || v5) 3 else 0,
            maxCharacterReferences = if (v45) 1 else 0, maxCharacters = if (v5) 22 else if (v4) 6 else Int.MAX_VALUE,
            perCharacterPrompt = v4 || v5, seed = true, vibe = v4 || v5,
            exclusiveCharacterAndStyle = true, characterReferenceCost = 5, tags = true, multilingual = v5)
    }

    override suspend fun generateImages(
        prompt: String,
        count: Int,
        size: String?
    ): List<GeneratedImage> {
        return generateImages(ImageRequest(prompt = prompt, count = count, size = size))
    }

    override suspend fun generateImages(request: ImageRequest): List<GeneratedImage> {
        require(request.count in 1..4)
        return (0 until request.count).map { index ->
            currentCoroutineContext().ensureActive()
            generateOne(request.copy(count = 1, seed = request.seed?.let { (it + index) and 0xffffffffL }))
        }
    }

    private suspend fun generateOne(input: ImageRequest): GeneratedImage {
        require(input.prompt.isNotBlank()) { "生图提示词不能为空" }
        val payload = buildPayload(input, input.size ?: defaultSize)
        val request = Request.Builder()
            .url("$base/ai/generate-image")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "*/*")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val zipBytes = withContext(Dispatchers.IO) {
            val response = try {
                httpClient.newCall(request).execute()
            } catch (error: Throwable) {
                throw mapTransportError(error)
            }
            response.use {
                if (!it.isSuccessful) {
                    throw httpError(it.code, extractErrorMessage(it.body.string()))
                }
                val contentType = it.header("Content-Type").orEmpty()
                if (contentType.contains("json", ignoreCase = true)) {
                    throw AiClientException.Malformed(
                        extractErrorMessage(it.body.string()) ?: "NovelAI 返回了非图片响应"
                    )
                }
                val declaredLength = it.body.contentLength()
                require(declaredLength < 0 || declaredLength <= MAX_ARCHIVE_BYTES) {
                    "生成图片超过 30 MB，已取消保存"
                }
                it.body.bytes().also { raw ->
                    require(raw.size <= MAX_ARCHIVE_BYTES) { "生成图片超过 30 MB，已取消保存" }
                }
            }
        }
        val image = NovelAiZipParser.firstImage(zipBytes)
            ?: throw AiClientException.Malformed("NovelAI 响应里没有图片文件")
        if (image.isEmpty()) throw AiClientException.Empty()
        return GeneratedImage(bytes = image, url = null, mediaType = "image/png")
    }

    override suspend fun materializeImage(image: GeneratedImage): ByteArray =
        image.bytes?.takeIf(ByteArray::isNotEmpty) ?: throw AiClientException.Empty()

    /** 拆出来便于单测校验请求体结构。 */
    internal fun buildPayload(prompt: String, size: String): JsonObject {
        return buildPayload(ImageRequest(prompt), size)
    }

    internal fun buildPayload(request: ImageRequest, size: String): JsonObject {
        val (width, height) = parseSize(size)
        val effectivePrompt = mergePositivePrompt(positivePrompt, request.prompt)
        val negative = mergePositivePrompt(negativePrompt.trim().ifBlank { DEFAULT_NEGATIVE_PROMPT }, request.negative)
        val isV4 = capabilities.perCharacterPrompt
        require(request.characterPrompts.size <= capabilities.maxCharacters) { "当前 NovelAI 模型的出场人物数量已达上限" }
        val parameters = linkedMapOf<String, JsonElement>(
            "width" to JsonPrimitive(width),
            "height" to JsonPrimitive(height),
            "scale" to JsonPrimitive(scale.coerceIn(0f, 10f)),
            "sampler" to JsonPrimitive(sampler.trim().ifBlank { DEFAULT_SAMPLER }),
            "steps" to JsonPrimitive(steps.coerceIn(1, 50)),
            "n_samples" to JsonPrimitive(1),
            "ucPreset" to JsonPrimitive(0),
            "qualityToggle" to JsonPrimitive(true),
            "negative_prompt" to JsonPrimitive(negative)
        )
        request.seed?.let { parameters["seed"] = JsonPrimitive(it and 0xffffffffL) }
        val characterRefs = request.references.filter { it.spec.kind == ReferenceKind.CHARACTER }
        val styleRefs = request.references.filter { it.spec.kind == ReferenceKind.STYLE }
        require(characterRefs.size <= capabilities.maxCharacterReferences)
        require(characterRefs.isEmpty() || styleRefs.isEmpty()) { "角色参考不能与 Vibe 同时使用" }
        if (characterRefs.isNotEmpty()) {
            parameters["director_reference_images"] = JsonArray(characterRefs.map { JsonPrimitive(Base64.getEncoder().encodeToString(it.bytes)) })
            parameters["director_reference_descriptions"] = JsonArray(characterRefs.map {
                JsonObject(mapOf("caption" to JsonObject(mapOf("base_caption" to JsonPrimitive("character"), "char_captions" to JsonArray(emptyList())))))
            })
            parameters["director_reference_information_extracted"] = JsonArray(characterRefs.map { JsonPrimitive(1f) })
            parameters["director_reference_strength_values"] = JsonArray(characterRefs.map { JsonPrimitive(it.spec.strength.coerceIn(0f, 1f)) })
            parameters["director_reference_secondary_strength_values"] = JsonArray(characterRefs.map { JsonPrimitive(it.spec.fidelity.coerceIn(0f, 1f)) })
        }
        if (styleRefs.isNotEmpty()) {
            require(capabilities.vibe && styleRefs.all { it.encodedVibe })
            parameters["reference_image_multiple"] = JsonArray(styleRefs.map { JsonPrimitive(Base64.getEncoder().encodeToString(it.bytes)) })
            parameters["reference_information_extracted_multiple"] = JsonArray(styleRefs.map { JsonPrimitive(1f) })
            parameters["reference_strength_multiple"] = JsonArray(styleRefs.map { JsonPrimitive(it.spec.strength.coerceIn(0f, 1f)) })
        }
        if (isV4) {
            parameters["params_version"] = JsonPrimitive(3)
            parameters["v4_prompt"] = JsonObject(
                mapOf(
                    "caption" to JsonObject(
                        mapOf(
                            "base_caption" to JsonPrimitive(effectivePrompt),
                            "char_captions" to JsonArray(request.characterPrompts.map { character ->
                                JsonObject(mapOf("char_caption" to JsonPrimitive(character), "centers" to JsonArray(emptyList())))
                            })
                        )
                    ),
                    "use_coords" to JsonPrimitive(false),
                    "use_order" to JsonPrimitive(true)
                )
            )
            parameters["v4_negative_prompt"] = JsonObject(
                mapOf(
                    "caption" to JsonObject(
                        mapOf(
                            "base_caption" to JsonPrimitive(negative),
                            "char_captions" to JsonArray(emptyList())
                        )
                    )
                )
            )
        }
        return JsonObject(
            mapOf(
                "input" to JsonPrimitive(effectivePrompt),
                "model" to JsonPrimitive(model),
                "action" to JsonPrimitive("generate"),
                "parameters" to JsonObject(parameters)
            )
        )
    }

    /** Official /ai/encode-vibe returns binary. Caller caches by image hash + model + extraction. */
    suspend fun encodeVibe(bytes: ByteArray, information: Float): ByteArray = withContext(Dispatchers.IO) {
        val payload = JsonObject(mapOf("model" to JsonPrimitive(model),
            "image" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes)),
            "information_extracted" to JsonPrimitive(information.coerceIn(0f, 1f))))
        val request = Request.Builder().url("$base/ai/encode-vibe").header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response.code, extractErrorMessage(response.body.string()))
            val data = response.body.bytes()
            require(data.isNotEmpty() && data.size <= MAX_ARCHIVE_BYTES) { "Vibe 编码响应无效" }
            data
        }
    }

    /** NovelAI 要求宽高为 64 的倍数；非法输入回落竖版 832x1216。 */
    private fun parseSize(size: String): Pair<Int, Int> {
        val parts = size.lowercase().split('x', '×')
        val width = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val height = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return 832 to 1216
        fun align(value: Int): Int = (value / 64 * 64).coerceIn(64, 2048)
        return align(width) to align(height)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://image.novelai.net"
        const val DEFAULT_SAMPLER = "k_euler_ancestral"
        const val DEFAULT_NEGATIVE_PROMPT =
            "lowres, jpeg artifacts, worst quality, bad quality, watermark, blurry, very displeasing"
        private const val MAX_ARCHIVE_BYTES = 30 * 1024 * 1024

        internal fun mergePositivePrompt(fixed: String, dynamic: String): String =
            listOf(fixed, dynamic)
                .map { it.trim().trim(',') }
                .filter(String::isNotBlank)
                .joinToString(", ")
    }
}

/** NovelAI zip 响应解包：取第一张图片文件，读取时限流防 zip 炸弹。 */
internal object NovelAiZipParser {
    private const val MAX_ENTRY_BYTES = 30 * 1024 * 1024
    private val IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp")

    fun firstImage(bytes: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.lowercase()
                    if (IMAGE_SUFFIXES.any(name::endsWith)) {
                        return readLimited(zip)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun readLimited(input: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_ENTRY_BYTES) { "生成图片超过 30 MB，已取消保存" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }
}
