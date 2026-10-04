package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.client.AiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject

/** Accept complete structured output in Markdown/prose; never repair truncated JSON or evidence. */
internal object KnowledgeResponseJson {
    fun clean(raw: String): String {
        require(raw.length <= 64_000) { "整理结果过长" }
        val trimmed = raw.trim().removePrefix("\uFEFF").trim()
        if (isStructured(trimmed)) return trimmed
        val start = trimmed.indexOfFirst { it == '{' || it == '[' }
        val end = if (start >= 0) trimmed.lastIndexOf(if (trimmed[start] == '{') '}' else ']') else -1
        if (start >= 0 && end > start) {
            val candidate = trimmed.substring(start, end + 1)
            if (isStructured(candidate)) return candidate
        }
        return trimmed
    }

    fun characters(raw: String): String {
        val clean = clean(raw)
        val value = runCatching { AiJson.parseToJsonElement(clean) }.getOrNull()
        return if (value is JsonArray) buildJsonObject { put("characters", value) }.toString() else clean
    }

    private fun isStructured(value: String): Boolean = runCatching {
        val parsed = AiJson.parseToJsonElement(value)
        parsed is JsonObject || parsed is JsonArray
    }.getOrDefault(false)
}
