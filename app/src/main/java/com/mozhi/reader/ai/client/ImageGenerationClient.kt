package com.mozhi.reader.ai.client

/** 生图客户端抽象：OpenAI 兼容（images / chat 两种端点）与 NovelAI 各自实现。 */
interface ImageGenerationClient {
    suspend fun generateImages(
        prompt: String,
        count: Int = 1,
        size: String? = null
    ): List<GeneratedImage>

    /** URL 型结果立即取回字节，避免临时链接过期后插图丢失。 */
    suspend fun materializeImage(image: GeneratedImage): ByteArray
}
