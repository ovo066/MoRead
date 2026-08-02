package com.mozhi.reader.ai.chat

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.ModelRole
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 模型输出 → 建议回复列表；容忍代码块围栏、对象包裹与前后杂讯。 */
internal object ReplySuggestionParser {
    fun parse(raw: String): List<String> {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val direct = decode(trimmed)
        if (direct.isNotEmpty() || trimmed == "[]") return direct
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        return if (start >= 0 && end > start) decode(trimmed.substring(start, end + 1)) else emptyList()
    }

    private fun decode(json: String): List<String> = runCatching {
        when (val root = AiJson.parseToJsonElement(json)) {
            is JsonArray -> root
            is JsonObject -> root["suggestions"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }.mapNotNull { it.jsonPrimitive.contentOrNull }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(MAX_SUGGESTIONS)
            .map { it.take(MAX_CHARS) }
    }.getOrDefault(emptyList())

    const val MAX_SUGGESTIONS = 3
    private const val MAX_CHARS = 40
}

/**
 * 伴读输入区的 AI 建议回复：AI 回合结束后，用最近几轮对话替用户拟三条可一键
 * 发送的短回复。模型取 SUGGESTION 分配，未分配时回落 CHEAP → CHAT；三者都
 * 没配则静默返回空（建议是锦上添花，不该弹配置错误）。
 */
@Singleton
class ReplySuggestionService @Inject constructor(
    private val clientFactory: AiClientFactory
) {
    suspend fun suggest(
        personaName: String,
        bookTitle: String,
        history: List<MessageEntity>
    ): List<String> {
        val recent = history
            .filter { it.role == ChatRole.USER.wire || it.role == ChatRole.ASSISTANT.wire }
            .filter { it.content.isNotBlank() }
            .takeLast(HISTORY_WINDOW)
        if (recent.isEmpty() || recent.last().role != ChatRole.ASSISTANT.wire) return emptyList()

        val resolved = resolveClient() ?: return emptyList()
        val transcript = buildString {
            recent.forEach { message ->
                append(if (message.role == ChatRole.USER.wire) "用户" else personaName.ifBlank { "AI" })
                append("：")
                append(message.content.take(MAX_MESSAGE_CHARS))
                append('\n')
            }
        }
        val response = resolved.client.chat(
            messages = listOf(
                ChatMessage(
                    ChatRole.SYSTEM,
                    buildString {
                        append("你是阅读应用「墨知」伴读聊天的输入联想助手。")
                        if (bookTitle.isNotBlank()) {
                            append("用户正在阅读《").append(bookTitle).append("》，")
                        }
                        append("对话对象是角色「").append(personaName.ifBlank { "伴读" }).append("」。")
                        append(
                            "根据对话记录，替用户拟 ${ReplySuggestionParser.MAX_SUGGESTIONS} 条 TA 接下来" +
                                "可能想发送的回复：口语化简体中文，每条不超过 16 个字，" +
                                "彼此角度不同（追问、回应感受、换话题皆可），" +
                                "不要剧透用户还没读到的内容，不要重复对话里已有的话。" +
                                "只输出 JSON 字符串数组，不要 Markdown，不要解释。"
                        )
                    }
                ),
                ChatMessage(ChatRole.USER, transcript.take(MAX_TRANSCRIPT_CHARS))
            ),
            options = resolved.options
        )
        return ReplySuggestionParser.parse(response)
    }

    /** SUGGESTION → CHEAP → CHAT 逐级回落；仅在「未配置」时继续尝试下一级。 */
    private suspend fun resolveClient() = FALLBACK_ROLES.firstNotNullOfOrNull { role ->
        try {
            clientFactory.forRole(role)
        } catch (error: AiClientException.NotConfigured) {
            null
        } catch (error: AiClientException.MissingKey) {
            null
        }
    }

    private companion object {
        val FALLBACK_ROLES = listOf(ModelRole.SUGGESTION, ModelRole.CHEAP, ModelRole.CHAT)
        const val HISTORY_WINDOW = 8
        const val MAX_MESSAGE_CHARS = 600
        const val MAX_TRANSCRIPT_CHARS = 6_000
    }
}
