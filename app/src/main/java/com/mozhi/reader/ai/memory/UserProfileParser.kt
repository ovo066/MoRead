package com.mozhi.reader.ai.memory

import com.mozhi.reader.ai.client.AiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一次固化的完整产出：记忆条目的处置 + 可选的用户画像改写。
 * 两者搭同一次 CHEAP 调用的便车——固化本来就要把这批对话读一遍，
 * 再单独请求一次只为写画像不值当。
 */
internal data class MemoryConsolidationDraft(
    val operations: List<MemoryOperation>,
    /** null = 模型认为画像无需变化；非 null 一律是整段覆盖式改写。 */
    val userProfile: String?
)

/**
 * 解析固化产出（Memory 2.0 批次 B + C）。
 *
 * 画像是「常驻小抄」而不是记忆流水：称呼、偏好雷点、阅读口味、关系进展、共读书目。
 * 它与人设同级注入且永不裁，所以必须限长，也必须只记本人——面具内的扮演身份不进这里。
 */
internal object UserProfileParser {

    const val MAX_PROFILE_CHARS = 800

    fun parse(raw: String): MemoryConsolidationDraft {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        // 只吐出了操作数组也认：画像视为无变化，记忆照样能写进去。
        // 这一步必须先判，否则下面的花括号兜底会从数组里挖出第一个元素当成整个对象。
        if (trimmed.startsWith("[")) {
            return MemoryConsolidationDraft(MemoryOperationParser.parse(trimmed), null)
        }
        val root = runCatching { AiJson.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: runCatching {
                val start = trimmed.indexOf('{')
                val end = trimmed.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    AiJson.parseToJsonElement(trimmed.substring(start, end + 1)).jsonObject
                } else {
                    null
                }
            }.getOrNull()
            ?: return MemoryConsolidationDraft(MemoryOperationParser.parse(trimmed), null)

        return MemoryConsolidationDraft(
            operations = root["operations"]?.let { MemoryOperationParser.parse(it.toString()) }
                .orEmpty(),
            userProfile = root.readProfile()
        )
    }

    private fun JsonObject.readProfile(): String? {
        val value = this["user_profile"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        // 模型常用 "无变化"/"null"/"" 表达「不用改」，一律当作没有更新。
        if (value.isEmpty() || value in NO_CHANGE_MARKERS) return null
        return value.take(MAX_PROFILE_CHARS)
    }

    private val NO_CHANGE_MARKERS = setOf(
        "null", "NULL", "none", "None", "无", "无变化", "不变", "没有变化", "-"
    )
}
