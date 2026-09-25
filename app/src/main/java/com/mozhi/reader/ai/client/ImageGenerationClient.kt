package com.mozhi.reader.ai.client

/** 生图客户端抽象：OpenAI 兼容（images / chat 两种端点）与 NovelAI 各自实现。 */
interface ImageGenerationClient {
    val defaultImageSize: String? get() = null
    val capabilities: com.mozhi.reader.ai.media.ImageCapabilities
        get() = com.mozhi.reader.ai.media.ImageCapabilities()

    suspend fun generateImages(request: ImageRequest): List<GeneratedImage> =
        generateImages(request.prompt, request.count, request.size)

    suspend fun generateImages(
        prompt: String,
        count: Int = 1,
        size: String? = null
    ): List<GeneratedImage>

    /** URL 型结果立即取回字节，避免临时链接过期后插图丢失。 */
    suspend fun materializeImage(image: GeneratedImage): ByteArray
}

/** Bytes are normalized local assets, loaded only for an explicitly initiated generation. */
data class ImageReferenceInput(
    val spec: com.mozhi.reader.ai.media.ReferenceSpec,
    val bytes: ByteArray,
    val mediaType: String = "image/png",
    val encodedVibe: Boolean = false
)

data class ImageRequest(
    val prompt: String,
    val characterPrompts: List<String> = emptyList(),
    val negative: String = "",
    val references: List<ImageReferenceInput> = emptyList(),
    val seed: Long? = null,
    val size: String? = null,
    val count: Int = 1
)
