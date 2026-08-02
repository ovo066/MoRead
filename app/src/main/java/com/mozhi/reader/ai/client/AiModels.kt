package com.mozhi.reader.ai.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Wire models for OpenAI-compatible endpoints. Field shapes follow the official API; the data
 * classes are deliberately tolerant (`ignoreUnknownKeys` in [AiJson]) because third-party
 * compatible services (DeepSeek, SiliconFlow, …) add their own fields freely.
 */
enum class ChatRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(value: String): ChatRole =
            entries.firstOrNull { it.wire == value } ?: ASSISTANT
    }
}

/**
 * One tool invocation requested by the assistant. [id] is the provider's call id (OpenAI/Claude/
 * Responses); Gemini has none, so there it is synthesized from the function name and ignored on
 * replay. [arguments] is the raw JSON object string exactly as the model produced it.
 */
@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

/** A tool declared to the model. [parameters] is a JSON Schema object. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/**
 * A chat turn. [toolCalls] is only meaningful on ASSISTANT messages (the calls it requested);
 * [toolCallId] only on TOOL messages (which call this result answers).
 *
 * [parts] 非空时该消息按多模态编码（图片 + 文本），[content] 仍保留纯文本视图（历史窗口、
 * 持久化、token 估算都用它）；为空时走原纯文本路径，旧调用零改动。
 */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val parts: List<ChatPart> = emptyList()
)

/** 多模态消息片段；Image 携带 base64 数据与 MIME（如 image/jpeg）。 */
sealed interface ChatPart {
    data class Text(val text: String) : ChatPart
    data class Image(val base64: String, val mimeType: String) : ChatPart
}

/** Streaming increments. A stream is a sequence of [Text] optionally terminated by [ToolCalls]. */
sealed interface ChatDelta {
    data class Text(val text: String) : ChatDelta

    /** Terminal event: the assistant wants these tools run; arguments are fully assembled. */
    data class ToolCalls(val calls: List<ToolCall>) : ChatDelta
}

// ---- OpenAI chat/completions wire ----

@Serializable
internal data class WireToolFunction(
    val name: String? = null,
    val arguments: String? = null,
    val description: String? = null,
    val parameters: JsonObject? = null
)

@Serializable
internal data class WireToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: WireToolFunction? = null
)

@Serializable
internal data class WireChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

/**
 * 请求侧消息：content 允许是字符串或多模态数组（`[{type:text},{type:image_url}]`）。
 * 与响应解析用的 [WireChatMessage] 刻意分离——响应永远是纯字符串 content。
 */
@Serializable
internal data class WireRequestMessage(
    val role: String,
    val content: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
internal data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireRequestMessage>,
    val temperature: Float? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val tools: List<WireToolCall>? = null,
    val stream: Boolean = false
)

@Serializable
internal data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null
) {
    @Serializable
    data class Choice(
        val message: WireChatMessage? = null,
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Usage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0
    )
}

@Serializable
internal data class ChatCompletionChunk(
    val choices: List<StreamChoice> = emptyList()
) {
    @Serializable
    data class StreamChoice(
        val delta: Delta = Delta(),
        @SerialName("finish_reason") val finishReason: String? = null
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null
    )
}

/**
 * Assembles OpenAI streaming `delta.tool_calls` fragments into complete [ToolCall]s. Fragments
 * carry an `index` slot; the first fragment of a slot has id/name, later ones append argument
 * text. Providers that omit `index` (rare) fall back to appending to the last slot.
 */
internal class OpenAiToolCallAccumulator {
    private class Slot(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    private val slots = LinkedHashMap<Int, Slot>()

    fun accept(fragments: List<WireToolCall>?) {
        fragments?.forEach { fragment ->
            val index = fragment.index ?: (slots.keys.maxOrNull() ?: 0)
            val slot = slots.getOrPut(index) { Slot() }
            fragment.id?.let { slot.id = it }
            fragment.function?.name?.let { slot.name = it }
            fragment.function?.arguments?.let { slot.arguments.append(it) }
        }
    }

    fun isEmpty(): Boolean = slots.isEmpty()

    fun build(): List<ToolCall> = slots.values
        .filter { it.name.isNotBlank() }
        .mapIndexed { position, slot ->
            ToolCall(
                id = slot.id.ifBlank { "call_$position" },
                name = slot.name,
                arguments = slot.arguments.toString().ifBlank { "{}" }
            )
        }
}

/** OpenAI chat/completions 请求侧 content：纯文本走字符串，带图走多模态数组。 */
internal fun ChatMessage.openAiContentElement(): JsonElement =
    if (parts.isEmpty()) {
        JsonPrimitive(content)
    } else {
        buildJsonArray {
            parts.forEach { part ->
                when (part) {
                    is ChatPart.Text -> add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        }
                    )
                    is ChatPart.Image -> add(
                        buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", "data:${part.mimeType};base64,${part.base64}")
                            }
                        }
                    )
                }
            }
        }
    }

@Serializable
internal data class EmbeddingRequest(
    val model: String,
    val input: List<String>
)

@Serializable
internal data class EmbeddingResponse(
    val data: List<Datum> = emptyList()
) {
    @Serializable
    data class Datum(
        val index: Int = 0,
        val embedding: List<Float> = emptyList()
    )
}

@Serializable
internal data class ErrorEnvelope(
    val error: ErrorBody? = null
) {
    @Serializable
    data class ErrorBody(
        val message: String? = null,
        val code: String? = null,
        val type: String? = null
    )
}
