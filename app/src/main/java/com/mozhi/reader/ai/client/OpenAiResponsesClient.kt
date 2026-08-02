package com.mozhi.reader.ai.client

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * OpenAI Responses API dialect (`/responses`), the successor to chat/completions. The system
 * prompt rides as top-level `instructions`, tool calls and their outputs are input items, text
 * streams as `response.output_text.delta` events, and completed tool calls are lifted from the
 * final `response.completed` payload — no argument-fragment stitching needed. `store: false`
 * keeps BYOK conversations off the provider's servers.
 */
class OpenAiResponsesClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: OkHttpClient,
    private val responsesEndpointPath: String = "",
    private val embeddingEndpointPath: String = "",
    extraJson: String = "{}"
) : ChatApiClient {

    private val base = normalizeBase(baseUrl)
    private val streamingClient = httpClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val plainClient = httpClient
    private val requestOverrides = RequestOverrides.parse(extraJson)

    @Serializable
    internal data class InputItem(
        val type: String? = null,
        val role: String? = null,
        // 字符串或多模态数组（[{type:input_text},{type:input_image}]）
        val content: kotlinx.serialization.json.JsonElement? = null,
        @SerialName("call_id") val callId: String? = null,
        val name: String? = null,
        val arguments: String? = null,
        val output: String? = null
    )

    @Serializable
    internal data class WireTool(
        // 必填且不能给默认值：AiJson 关闭 encodeDefaults，默认值会被静默省略，
        // OpenAI 官方 Responses API 随即报 tools[0].type 缺失。
        val type: String,
        val name: String,
        val description: String? = null,
        val parameters: JsonObject? = null
    )

    @Serializable
    internal data class Reasoning(val effort: String)

    @Serializable
    internal data class ResponsesRequest(
        val model: String,
        val input: List<InputItem>,
        val instructions: String? = null,
        val tools: List<WireTool>? = null,
        val temperature: Float? = null,
        @SerialName("top_p") val topP: Float? = null,
        @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
        val reasoning: Reasoning? = null,
        val stream: Boolean = false,
        // Responses 默认可能持久化；必须显式发送 false，不能被 encodeDefaults 省掉。
        val store: Boolean
    )

    @Serializable
    internal data class OutputContent(val type: String = "", val text: String? = null)

    @Serializable
    internal data class OutputItem(
        val type: String = "",
        val content: List<OutputContent>? = null,
        @SerialName("call_id") val callId: String? = null,
        val name: String? = null,
        val arguments: String? = null
    )

    @Serializable
    internal data class ResponsesResponse(val output: List<OutputItem> = emptyList())

    /** Payload shapes vary per event type; every field is optional and read per event. */
    @Serializable
    internal data class StreamEvent(
        val delta: String? = null,
        val response: ResponsesResponse? = null,
        val message: String? = null
    )

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Flow<ChatDelta> = callbackFlow {
        val source = EventSources.createFactory(streamingClient).newEventSource(
            buildRequest(messages, tools, options, stream = true),
            object : EventSourceListener() {
                override fun onEvent(
                    eventSource: EventSource,
                    id: String?,
                    type: String?,
                    data: String
                ) {
                    when (type) {
                        "response.output_text.delta" -> {
                            val event = decodeEvent(data) ?: return
                            event.delta?.takeIf(String::isNotEmpty)
                                ?.let { trySend(ChatDelta.Text(it)) }
                        }
                        "response.completed" -> {
                            val event = decodeEvent(data)
                            val calls = event?.response?.let(::extractToolCalls).orEmpty()
                            if (calls.isNotEmpty()) trySend(ChatDelta.ToolCalls(calls))
                            close()
                        }
                        "response.failed", "response.incomplete", "error" -> {
                            val event = decodeEvent(data)
                            close(
                                AiClientException.Http(
                                    200,
                                    event?.message ?: extractErrorMessage(data)
                                )
                            )
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(
                    eventSource: EventSource,
                    t: Throwable?,
                    response: Response?
                ) {
                    close(sseFailure(t, response))
                }
            }
        )
        awaitClose { source.cancel() }
    }

    override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String {
        val body = execute(plainClient, buildRequest(messages, emptyList(), options, stream = false))
        val response = AiJson.decodeFromString(ResponsesResponse.serializer(), body)
        return response.output
            .filter { it.type == "message" }
            .flatMap { it.content.orEmpty() }
            .filter { it.type == "output_text" }
            .mapNotNull { it.text }
            .joinToString("")
            .takeIf(String::isNotBlank)
            ?: throw AiClientException.Empty()
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val payload = AiJson.encodeToJsonElement(EmbeddingRequest(model, texts))
            .jsonObject
            .mergeExtras(requestOverrides.body)
        val request = Request.Builder()
            .url(endpointUrl(embeddingEndpointPath, "/embeddings"))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .apply {
                requestOverrides.headers.forEach { (key, value) -> header(key, value) }
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = execute(plainClient, request)
        val response = AiJson.decodeFromString(EmbeddingResponse.serializer(), body)
        if (response.data.size != texts.size) {
            throw AiClientException.Malformed("embedding 数量与输入不一致")
        }
        return response.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
    }

    private fun buildRequest(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions,
        stream: Boolean
    ): Request {
        val instructions = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        val payload = AiJson.encodeToJsonElement(
            ResponsesRequest(
                model = model,
                input = messages.filter { it.role != ChatRole.SYSTEM }.flatMap(::encodeItem),
                instructions = instructions,
                tools = tools.takeIf { it.isNotEmpty() }?.map {
                    WireTool(
                        type = "function",
                        name = it.name,
                        description = it.description,
                        parameters = it.parameters
                    )
                },
                temperature = options.temperature,
                topP = options.topP,
                maxOutputTokens = options.maxTokens,
                reasoning = options.reasoning?.let { Reasoning(it.wire) },
                stream = stream,
                store = false
            )
        ).jsonObject.mergeExtras(options.extraBody)
        return Request.Builder()
            .url(endpointUrl(responsesEndpointPath, "/responses"))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .apply { options.extraHeaders.forEach { (key, value) -> header(key, value) } }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun endpointUrl(configured: String, fallback: String): String =
        "$base/${configured.ifBlank { fallback }.trimStart('/')}"

    /** One chat turn → input items: text message, then each tool call / tool output as its own item. */
    private fun encodeItem(message: ChatMessage): List<InputItem> = when (message.role) {
        ChatRole.TOOL -> listOf(
            InputItem(
                type = "function_call_output",
                callId = message.toolCallId,
                output = message.content
            )
        )
        ChatRole.ASSISTANT -> buildList {
            if (message.content.isNotBlank()) {
                add(InputItem(role = "assistant", content = JsonPrimitive(message.content)))
            }
            message.toolCalls.forEach { call ->
                add(
                    InputItem(
                        type = "function_call",
                        callId = call.id,
                        name = call.name,
                        arguments = call.arguments
                    )
                )
            }
        }
        else -> listOf(
            InputItem(role = message.role.wire, content = responsesContentElement(message))
        )
    }

    private fun decodeEvent(data: String): StreamEvent? =
        runCatching { AiJson.decodeFromString(StreamEvent.serializer(), data) }.getOrNull()

    internal companion object {
        fun extractToolCalls(response: ResponsesResponse): List<ToolCall> = response.output
            .filter { it.type == "function_call" && !it.name.isNullOrBlank() }
            .map {
                ToolCall(
                    id = it.callId.orEmpty().ifBlank { "call_${it.name}" },
                    name = it.name.orEmpty(),
                    arguments = it.arguments?.takeIf(String::isNotBlank) ?: "{}"
                )
            }
    }
}

/** Responses user/system 消息 content：纯文本发字符串；带图发 input_text/input_image 数组。 */
internal fun responsesContentElement(message: ChatMessage): kotlinx.serialization.json.JsonElement =
    if (message.parts.isEmpty()) {
        JsonPrimitive(message.content)
    } else {
        buildJsonArray {
            message.parts.forEach { part ->
                when (part) {
                    is ChatPart.Text -> add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", part.text)
                        }
                    )
                    is ChatPart.Image -> add(
                        buildJsonObject {
                            put("type", "input_image")
                            put("image_url", "data:${part.mimeType};base64,${part.base64}")
                        }
                    )
                }
            }
        }
    }
