package com.mozhi.reader.ai.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 从 chat/completions 风格响应里挖出生成图。gpt-image 系模型经各家中转时，
 * 图片可能出现在 message.images[]（OpenRouter）、message.content 的 markdown/
 * data URI 文本里、content 多模态分段里，或干脆按 images 端点的 data[] 返回。
 * 统一产出字符串引用：https URL 或 `data:image/...;base64,...`。
 */
internal object ChatImageExtractor {

    private val MARKDOWN_IMAGE = Regex("""!\[[^\]]*\]\((https?://[^)\s]+|data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+)\)""")
    private val DATA_URI = Regex("""data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+""")
    private val BARE_IMAGE_URL = Regex(
        """https?://[^\s"'()<>]+\.(?:png|jpe?g|webp|gif)(?:\?[^\s"'()<>]*)?""",
        RegexOption.IGNORE_CASE
    )

    fun extract(body: String): List<String> {
        val root = runCatching { AiJson.parseToJsonElement(body) }.getOrNull()?.asObject()
            ?: return emptyList()
        val found = LinkedHashSet<String>()
        (root["choices"] as? JsonArray)?.forEach { choice ->
            val message = choice.asObject()?.get("message")?.asObject() ?: return@forEach
            (message["images"] as? JsonArray)?.forEach { image ->
                imageRefOf(image)?.let(found::add)
            }
            when (val content = message["content"]) {
                is JsonPrimitive -> content.contentOrNull?.let { found += extractFromText(it) }
                is JsonArray -> content.forEach { part ->
                    val obj = part.asObject() ?: return@forEach
                    imageRefOf(obj)?.let(found::add)
                    (obj["text"] as? JsonPrimitive)?.contentOrNull?.let { found += extractFromText(it) }
                }
                else -> Unit
            }
        }
        (root["data"] as? JsonArray)?.forEach { item ->
            imageRefOf(item)?.let(found::add)
        }
        return found.toList()
    }

    private fun imageRefOf(element: JsonElement): String? {
        val obj = element.asObject() ?: return null
        (obj["b64_json"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?.let { return "data:image/png;base64,$it" }
        val imageUrl = when (val direct = obj["image_url"]) {
            is JsonPrimitive -> direct.contentOrNull
            is JsonObject -> (direct["url"] as? JsonPrimitive)?.contentOrNull
            else -> null
        }
        return (imageUrl ?: (obj["url"] as? JsonPrimitive)?.contentOrNull)
            ?.takeIf(String::isNotBlank)
    }

    private fun extractFromText(text: String): List<String> {
        val results = ArrayList<String>()
        MARKDOWN_IMAGE.findAll(text).forEach { results += it.groupValues[1] }
        DATA_URI.findAll(text).forEach { results += it.value }
        if (results.isEmpty()) {
            BARE_IMAGE_URL.findAll(text).forEach { results += it.value }
        }
        return results
    }

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
}
