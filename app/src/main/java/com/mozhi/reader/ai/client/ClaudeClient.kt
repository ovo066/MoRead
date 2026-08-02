package com.mozhi.reader.ai.client

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Native Anthropic Messages dialect. `system` rides as a top-level block list, roles are only
 * user/assistant (tool results are `tool_result` blocks inside a user turn), and streaming
 * arrives as named events. With [ChatOptions.cacheTtl] the system prompt and the last tool
 * definition carry `cache_control` breakpoints (ephemeral prompt caching, 5m or 1h);
 * [ChatOptions.reasoning]
 * maps to extended thinking, which requires dropping temperature and widening max_tokens.
 */
class ClaudeClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: OkHttpClient,
    endpointPath: String = ""
) : ChatApiClient {

    private val base = normalizeBase(baseUrl, "/v1")
    private val messagesUrl = if (endpointPath.isBlank()) {
        "$base/v1/messages"
    } else {
        "${normalizeBase(baseUrl)}/${endpointPath.trimStart('/')}"
    }
    private val streamingClient = httpClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val plainClient = httpClient

    @Serializable
    private data class MessagesResponse(val content: List<Block> = emptyList()) {
        @Serializable
        data class Block(val type: String = "", val text: String? = null)
    }

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Flow<ChatDelta> =
        callbackFlow {
            val accumulator = ClaudeStreamAccumulator()
            fun finish() {
                val calls = accumulator.finish()
                if (calls.isNotEmpty()) trySend(ChatDelta.ToolCalls(calls))
                close()
            }
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
                            "message_stop" -> finish()
                            "error" -> close(
                                AiClientException.Http(200, extractErrorMessage(data))
                            )
                            else -> accumulator.onEvent(type, data)
                                ?.let { trySend(ChatDelta.Text(it)) }
                        }
                    }

                    override fun onClosed(eventSource: EventSource) {
                        finish()
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
        val response = AiJson.decodeFromString(MessagesResponse.serializer(), body)
        return response.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")
            .takeIf(String::isNotBlank)
            ?: throw AiClientException.Empty()
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        throw AiClientException.Unsupported("Claude 接口不提供 embedding，请为 Embedding 角色分配 OpenAI 兼容或 Gemini Provider")

    private fun buildRequest(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions,
        stream: Boolean
    ): Request {
        val system = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        val thinking = options.reasoning
        val maxTokens = if (thinking != null) {
            maxOf(options.maxTokens ?: DEFAULT_MAX_TOKENS, thinking.budgetTokens + THINKING_HEADROOM)
        } else {
            options.maxTokens ?: DEFAULT_MAX_TOKENS
        }
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", stream)
            system?.let {
                put(
                    "system",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", it)
                                options.cacheTtl?.let { ttl ->
                                    putJsonObject("cache_control") {
                                        put("type", "ephemeral")
                                        if (ttl != PromptCacheTtl.FIVE_MINUTES) put("ttl", ttl.wire)
                                    }
                                }
                            }
                        )
                    }
                )
            }
            put("messages", encodeTurns(messages))
            if (tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        tools.forEachIndexed { index, tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("input_schema", tool.parameters)
                                    if (options.cacheTtl != null && index == tools.lastIndex) {
                                        putJsonObject("cache_control") {
                                            put("type", "ephemeral")
                                            if (options.cacheTtl != PromptCacheTtl.FIVE_MINUTES) {
                                                put("ttl", options.cacheTtl.wire)
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                )
            }
            if (thinking != null) {
                // Extended thinking forbids temperature/top_p overrides and needs headroom.
                putJsonObject("thinking") {
                    put("type", "enabled")
                    put("budget_tokens", thinking.budgetTokens)
                }
            } else {
                options.temperature?.let { put("temperature", it) }
                options.topP?.let { put("top_p", it) }
            }
        }.mergeExtras(options.extraBody)
        return Request.Builder()
            .url(messagesUrl)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "application/json")
            .apply {
                // The 1h cache tier still rides behind a beta flag; harmless once it GAs.
                if (options.cacheTtl == PromptCacheTtl.ONE_HOUR) {
                    header("anthropic-beta", EXTENDED_CACHE_BETA)
                }
                options.extraHeaders.forEach { (key, value) -> header(key, value) }
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * Chat turns → Anthropic turns. Assistant tool calls become `tool_use` blocks; TOOL messages
     * become `tool_result` blocks inside a user turn, with consecutive results merged into one
     * turn (the API requires results to directly follow their `tool_use` in a single message).
     */
    internal fun encodeTurns(messages: List<ChatMessage>) = buildJsonArray {
        val turns = messages.filter { it.role != ChatRole.SYSTEM }
        var index = 0
        while (index < turns.size) {
            val message = turns[index]
            when (message.role) {
                ChatRole.TOOL -> {
                    val results = ArrayList<ChatMessage>()
                    while (index < turns.size && turns[index].role == ChatRole.TOOL) {
                        results.add(turns[index])
                        index++
                    }
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    results.forEach { result ->
                                        add(
                                            buildJsonObject {
                                                put("type", "tool_result")
                                                put("tool_use_id", result.toolCallId.orEmpty())
                                                put("content", result.content)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                }
                ChatRole.ASSISTANT -> {
                    add(
                        buildJsonObject {
                            put("role", "assistant")
                            put(
                                "content",
                                buildJsonArray {
                                    if (message.content.isNotBlank()) {
                                        add(
                                            buildJsonObject {
                                                put("type", "text")
                                                put("text", message.content)
                                            }
                                        )
                                    }
                                    message.toolCalls.forEach { call ->
                                        add(
                                            buildJsonObject {
                                                put("type", "tool_use")
                                                put("id", call.id)
                                                put("name", call.name)
                                                put("input", call.argumentsAsJson())
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    )
                    index++
                }
                else -> {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            if (message.parts.isEmpty()) {
                                put("content", message.content)
                            } else {
                                put(
                                    "content",
                                    buildJsonArray {
                                        // 图片放在文本之前（Anthropic 官方推荐顺序）
                                        message.parts.filterIsInstance<ChatPart.Image>().forEach { image ->
                                            add(
                                                buildJsonObject {
                                                    put("type", "image")
                                                    put(
                                                        "source",
                                                        buildJsonObject {
                                                            put("type", "base64")
                                                            put("media_type", image.mimeType)
                                                            put("data", image.base64)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                        message.parts.filterIsInstance<ChatPart.Text>().forEach { text ->
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", text.text)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    )
                    index++
                }
            }
        }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val EXTENDED_CACHE_BETA = "extended-cache-ttl-2025-04-11"
        const val DEFAULT_MAX_TOKENS = 2048
        const val THINKING_HEADROOM = 2048
    }
}

/** Parses a tool call's raw argument string back into the JSON object Anthropic expects. */
internal fun ToolCall.argumentsAsJson(): JsonObject =
    runCatching { AiJson.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
        ?: JsonObject(emptyMap())

/**
 * Assembles Anthropic stream events. Text deltas are returned per event; `tool_use` blocks are
 * accumulated (`content_block_start` opens a slot, `input_json_delta` fragments append) and
 * collected by [finish]. Thinking deltas are consumed silently.
 */
internal class ClaudeStreamAccumulator {

    @Serializable
    internal data class BlockStart(val index: Int = 0, @kotlinx.serialization.SerialName("content_block") val contentBlock: ContentBlock? = null) {
        @Serializable
        internal data class ContentBlock(
            val type: String = "",
            val id: String? = null,
            val name: String? = null
        )
    }

    @Serializable
    internal data class BlockDelta(val index: Int = 0, val delta: Delta? = null) {
        @Serializable
        internal data class Delta(
            val type: String? = null,
            val text: String? = null,
            @kotlinx.serialization.SerialName("partial_json") val partialJson: String? = null
        )
    }

    private class ToolSlot(val id: String, val name: String, val json: StringBuilder = StringBuilder())

    private val toolSlots = LinkedHashMap<Int, ToolSlot>()

    /** Feeds one SSE event; returns the text delta to emit, if any. */
    fun onEvent(type: String?, data: String): String? {
        when (type) {
            "content_block_start" -> {
                val start = decode(BlockStart.serializer(), data) ?: return null
                val block = start.contentBlock ?: return null
                if (block.type == "tool_use") {
                    toolSlots[start.index] = ToolSlot(block.id.orEmpty(), block.name.orEmpty())
                }
            }
            "content_block_delta" -> {
                val event = decode(BlockDelta.serializer(), data) ?: return null
                val delta = event.delta ?: return null
                when (delta.type) {
                    "text_delta" -> return delta.text?.takeIf(String::isNotEmpty)
                    "input_json_delta" -> delta.partialJson?.let {
                        toolSlots[event.index]?.json?.append(it)
                    }
                }
            }
        }
        return null
    }

    fun finish(): List<ToolCall> = toolSlots.values
        .filter { it.name.isNotBlank() }
        .mapIndexed { position, slot ->
            ToolCall(
                id = slot.id.ifBlank { "toolu_$position" },
                name = slot.name,
                arguments = slot.json.toString().ifBlank { "{}" }
            )
        }
        .also { toolSlots.clear() }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, data: String): T? =
        runCatching { AiJson.decodeFromString(serializer, data) }.getOrNull()
}
