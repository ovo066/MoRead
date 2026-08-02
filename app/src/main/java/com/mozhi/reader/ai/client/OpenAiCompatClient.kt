package com.mozhi.reader.ai.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI-compatible dialect (`/chat/completions`, `/embeddings`), the lingua franca of DeepSeek,
 * SiliconFlow, OpenRouter and most aggregators.
 */
class OpenAiCompatClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: OkHttpClient,
    private val chatEndpointPath: String = "",
    private val embeddingEndpointPath: String = "",
    extraJson: String = "{}"
) : ChatApiClient {

    private val base = normalizeBase(baseUrl)
    private val streamingClient = httpClient.newBuilder()
        .readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
        .build()
    private val plainClient = httpClient
    private val requestOverrides = RequestOverrides.parse(extraJson)

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Flow<ChatDelta> =
        callbackFlow {
            val request = buildChatRequest(messages, tools, options, stream = true)
            val toolCalls = OpenAiToolCallAccumulator()
            fun finish() {
                if (!toolCalls.isEmpty()) trySend(ChatDelta.ToolCalls(toolCalls.build()))
                close()
            }
            val source = EventSources.createFactory(streamingClient).newEventSource(
                request,
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        if (data == DONE_MARKER) {
                            finish()
                            return
                        }
                        val chunk = runCatching {
                            AiJson.decodeFromString(ChatCompletionChunk.serializer(), data)
                        }.getOrNull() ?: return
                        val choice = chunk.choices.firstOrNull() ?: return
                        choice.delta.content
                            ?.takeIf(String::isNotEmpty)
                            ?.let { trySend(ChatDelta.Text(it)) }
                        toolCalls.accept(choice.delta.toolCalls)
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
        val body = execute(plainClient, buildChatRequest(messages, emptyList(), options, stream = false))
        val response = AiJson.decodeFromString(ChatCompletionResponse.serializer(), body)
        return response.choices.firstOrNull()?.message?.content
            ?.takeIf(String::isNotBlank)
            ?: throw AiClientException.Empty()
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val payload = AiJson.encodeToJsonElement(EmbeddingRequest(model, texts))
            .jsonObject
            .mergeExtras(requestOverrides.body)
        val request = jsonRequest(
            endpointUrl(embeddingEndpointPath, "/embeddings"),
            payload,
            requestOverrides.headers
        )
        val body = execute(plainClient, request)
        val response = AiJson.decodeFromString(EmbeddingResponse.serializer(), body)
        if (response.data.size != texts.size) {
            throw AiClientException.Malformed("embedding 数量与输入不一致")
        }
        return response.data.sortedBy { it.index }.map { it.embedding.toFloatArray() }
    }

    private fun buildChatRequest(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions,
        stream: Boolean
    ): Request {
        val payload = AiJson.encodeToJsonElement(
            ChatCompletionRequest(
                model = model,
                messages = messages.map { message ->
                    WireRequestMessage(
                        role = message.role.wire,
                        content = message.openAiContentElement(),
                        toolCalls = message.toolCalls.takeIf { it.isNotEmpty() }?.map { call ->
                            WireToolCall(
                                id = call.id,
                                type = "function",
                                function = WireToolFunction(name = call.name, arguments = call.arguments)
                            )
                        },
                        toolCallId = message.toolCallId
                    )
                },
                temperature = options.temperature,
                maxTokens = options.maxTokens,
                reasoningEffort = options.reasoning?.wire,
                tools = tools.takeIf { it.isNotEmpty() }?.map { tool ->
                    WireToolCall(
                        type = "function",
                        function = WireToolFunction(
                            name = tool.name,
                            description = tool.description,
                            parameters = tool.parameters
                        )
                    )
                },
                stream = stream
            )
        ).jsonObject.let { encoded ->
            val withTopP = options.topP?.let {
                encoded.mergeExtras(
                    JsonObject(mapOf("top_p" to kotlinx.serialization.json.JsonPrimitive(it)))
                )
            } ?: encoded
            withTopP.mergeExtras(options.extraBody)
        }
        return jsonRequest(
            endpointUrl(chatEndpointPath, "/chat/completions"),
            payload,
            options.extraHeaders
        )
    }

    private fun endpointUrl(configured: String, fallback: String): String =
        "$base/${configured.ifBlank { fallback }.trimStart('/')}"

    private fun jsonRequest(
        url: String,
        payload: JsonObject,
        extraHeaders: Map<String, String>
    ): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $apiKey")
        .header("Accept", "application/json")
        .apply { extraHeaders.forEach { (key, value) -> header(key, value) } }
        .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        .build()

    private companion object {
        const val DONE_MARKER = "[DONE]"
        const val STREAM_READ_TIMEOUT_S = 120L
    }
}

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/** Reads a successful body or throws the mapped dialect-agnostic error. */
internal suspend fun execute(client: OkHttpClient, request: Request): String =
    withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (error: Throwable) {
            throw mapTransportError(error)
        }
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw httpError(it.code, extractErrorMessage(body))
            if (body.isBlank()) throw AiClientException.Empty()
            body
        }
    }

internal fun sseFailure(t: Throwable?, response: Response?): AiClientException {
    if (response != null) {
        val detail = runCatching { response.body?.string() }.getOrNull()
        if (!response.isSuccessful) return httpError(response.code, extractErrorMessage(detail))
    }
    return when (t) {
        null -> AiClientException.Network()
        else -> mapTransportError(t)
    }
}

/** Pulls a human message out of any dialect's error envelope. */
internal fun extractErrorMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    return runCatching {
        AiJson.decodeFromString(ErrorEnvelope.serializer(), body).error?.message
    }.getOrNull() ?: body.take(200)
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
    continuation.invokeOnCancellation { runCatching(::cancel) }
}
