package com.mozhi.reader.ai.memory

import com.mozhi.reader.ai.client.AiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 固化时对一条候选记忆做出的处置（Memory 2.0 批次 C）。
 * 硬规则写在提示词里：只有明确矛盾或被取代才 UPDATE/DELETE，拿不准一律 ADD——
 * 宁可留冗余，也不能替用户误删他说过的话。
 */
internal sealed interface MemoryOperation {
    data class Add(val summary: String) : MemoryOperation
    data class Update(val id: Long, val summary: String) : MemoryOperation
    data class Delete(val id: Long) : MemoryOperation
    data object NoOp : MemoryOperation
}

/**
 * 解析 CHEAP 返回的操作数组。容错风格对齐 [MemorySummaryParser]：模型爱加解释文字与
 * ```json 围栏，能挖出数组就挖，挖不出宁可返回空列表（= 这批不写入）也不抛异常。
 */
internal object MemoryOperationParser {

    const val MAX_OPERATIONS = 8
    const val MAX_SUMMARY_CHARS = 500

    fun parse(raw: String): List<MemoryOperation> {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        decode(trimmed).takeIf { it.isNotEmpty() }?.let { return it }
        if (trimmed == "[]") return emptyList()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        return if (start >= 0 && end > start) decode(trimmed.substring(start, end + 1)) else emptyList()
    }

    private fun decode(json: String): List<MemoryOperation> = runCatching {
        val array = when (val root = AiJson.parseToJsonElement(json)) {
            is JsonArray -> root
            is JsonObject -> root["operations"]?.jsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        array.mapNotNull { element -> element.toOperation() }
            .filterNot { it is MemoryOperation.NoOp }
            .take(MAX_OPERATIONS)
    }.getOrDefault(emptyList())

    private fun kotlinx.serialization.json.JsonElement.toOperation(): MemoryOperation? {
        val obj = runCatching { jsonObject }.getOrNull() ?: return null
        val action = obj["action"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: return null
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
            ?.trim()
            ?.take(MAX_SUMMARY_CHARS)
            .orEmpty()
        val id = obj["id"]?.jsonPrimitive?.longOrNull
        return when (action) {
            "ADD" -> summary.takeIf(String::isNotBlank)?.let(MemoryOperation::Add)
            // UPDATE 缺 id 无从下手，缺正文等于删除——都不是模型的本意，降级成新增更安全。
            "UPDATE" -> when {
                id == null || id <= 0L -> summary.takeIf(String::isNotBlank)?.let(MemoryOperation::Add)
                summary.isBlank() -> null
                else -> MemoryOperation.Update(id, summary)
            }
            "DELETE" -> id?.takeIf { it > 0L }?.let(MemoryOperation::Delete)
            "NOOP" -> MemoryOperation.NoOp
            else -> null
        }
    }
}
