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

    override suspend fun generateImages(
        prompt: String,
        count: Int,
        size: String?
    ): List<GeneratedImage> {
        require(prompt.isNotBlank()) { "生图提示词不能为空" }
        val payload = buildPayload(prompt, size ?: defaultSize)
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
        return listOf(GeneratedImage(bytes = image, url = null, mediaType = "image/png"))
    }

    override suspend fun materializeImage(image: GeneratedImage): ByteArray =
        image.bytes?.takeIf(ByteArray::isNotEmpty) ?: throw AiClientException.Empty()

    /** 拆出来便于单测校验请求体结构。 */
    internal fun buildPayload(prompt: String, size: String): JsonObject {
        val (width, height) = parseSize(size)
        val effectivePrompt = mergePositivePrompt(positivePrompt, prompt)
        val negative = negativePrompt.trim().ifBlank { DEFAULT_NEGATIVE_PROMPT }
        val isV4 = model.startsWith("nai-diffusion-4")
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
        if (isV4) {
            parameters["params_version"] = JsonPrimitive(3)
            parameters["v4_prompt"] = JsonObject(
                mapOf(
                    "caption" to JsonObject(
                        mapOf(
                            "base_caption" to JsonPrimitive(effectivePrompt),
                            "char_captions" to JsonArray(emptyList())
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
