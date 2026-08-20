package com.mozhi.reader.ai.audiobook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ParsedAudiobookSegment(
    val startCharOffset: Int,
    val endCharOffset: Int,
    val roleName: String?,
    val emotion: String?,
    val instruction: String?
)

data class ParsedAudiobookAssignment(
    val segmentIndex: Int,
    val roleName: String?,
    val emotion: String?,
    val instruction: String?
)

object AudiobookScriptParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析“稳定分段 ID -> 角色”的 AI 标注。分段坐标由本地规则产生，AI 只做分类，
     * 避免模型计算 UTF-16 偏移错误后整章回退成旁白。
     */
    fun parseAssignments(raw: String, validIndices: Set<Int>): List<ParsedAudiobookAssignment> {
        if (validIndices.isEmpty()) return emptyList()
        val root = runCatching { json.parseToJsonElement(extractJson(raw)) }.getOrNull()
            ?: return emptyList()
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["assignments"] ?: root["segments"]) as? JsonArray
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        val seen = mutableSetOf<Int>()
        return items.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val index = obj.int("segment_id", "segmentIndex", "segment_index", "index", "id")
                ?: return@mapNotNull null
            if (index !in validIndices || !seen.add(index)) return@mapNotNull null
            ParsedAudiobookAssignment(
                segmentIndex = index,
                roleName = obj.string("role", "roleName", "speaker"),
                emotion = obj.string("emotion"),
                instruction = obj.string("instruction")
            )
        }.sortedBy(ParsedAudiobookAssignment::segmentIndex)
    }

    fun parse(raw: String, textLength: Int): List<ParsedAudiobookSegment> {
        if (textLength <= 0) return emptyList()
        val root = runCatching { json.parseToJsonElement(extractJson(raw)) }.getOrNull()
            ?: return emptyList()
        val items = when (root) {
            is JsonArray -> root
            is JsonObject -> root["segments"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return items.mapNotNull { parseItem(it, textLength) }
            .sortedBy(ParsedAudiobookSegment::startCharOffset)
            .filterNonOverlapping()
    }

    private fun parseItem(item: JsonElement, textLength: Int): ParsedAudiobookSegment? {
        val obj = item as? JsonObject ?: return null
        val start = obj.int("start", "startCharOffset")?.coerceIn(0, textLength) ?: return null
        val end = obj.int("end", "endCharOffset")?.coerceIn(0, textLength) ?: return null
        if (start >= end) return null
        return ParsedAudiobookSegment(
            startCharOffset = start,
            endCharOffset = end,
            roleName = obj.string("role", "roleName"),
            emotion = obj.string("emotion"),
            instruction = obj.string("instruction")
        )
    }

    private fun JsonObject.int(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
        get(key)?.jsonPrimitive?.intOrNull
    }

    private fun JsonObject.string(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        get(key)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun List<ParsedAudiobookSegment>.filterNonOverlapping(): List<ParsedAudiobookSegment> {
        var previousEnd = -1
        return filter { segment ->
            val valid = segment.startCharOffset >= previousEnd
            if (valid) previousEnd = segment.endCharOffset
            valid
        }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed.substringAfter('\n').substringBeforeLast("```").trim()
    }
}
