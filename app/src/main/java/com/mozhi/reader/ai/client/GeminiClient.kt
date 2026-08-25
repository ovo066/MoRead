package com.mozhi.reader.ai.client

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Native Google Gemini dialect (`generateContent` / `streamGenerateContent?alt=sse`). Roles are
 * user/model, the system prompt is `systemInstruction`, tuning lives in `generationConfig`
 * (including `thinkingConfig` for [ChatOptions.reasoning]). Tool calls arrive as whole
 * `functionCall` parts (no argument streaming); results go back as `functionResponse` parts.
 * Gemini has no call ids — they are synthesized locally and matched by function name on replay.
 */
class GeminiClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: OkHttpClient
) : ChatApiClient {

    private val base = normalizeBase(baseUrl, "/v1beta", "/v1")
    private val streamingClient = httpClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val plainClient = httpClient

    @Serializable
    internal data class FunctionCall(val name: String = "", val args: JsonObject? = null)

    @Serializable
    internal data class FunctionResponse(val name: String, val response: JsonObject)

    @Serializable
    internal data class InlineData(
        @SerialName("mime_type") val mimeType: String,
        val data: String
    )

    @Serializable
    internal data class Part(
        val text: String? = null,
        /** true = 这段 text 是思维链摘要而不是回答正文（Gemini 2.5 起的 thought summary）。 */
        val thought: Boolean? = null,
        @SerialName("inline_data") val inlineData: InlineData? = null,
        @SerialName("functionCall") val functionCall: FunctionCall? = null,
        @SerialName("functionResponse") val functionResponse: FunctionResponse? = null
    )

    @Serializable
    internal data class Content(val role: String? = null, val parts: List<Part>)

    @Serializable
    internal data class ThinkingConfig(@SerialName("thinkingBudget") val thinkingBudget: Int)

    @Serializable
    internal data class GenerationConfig(
        val temperature: Float? = null,
        @SerialName("topP") val topP: Float? = null,
        @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
        @SerialName("thinkingConfig") val thinkingConfig: ThinkingConfig? = null
    )

    @Serializable
    internal data class FunctionDeclaration(
        val name: String,
        val description: String,
        val parameters: JsonObject? = null
    )

    @Serializable
    internal data class WireTool(
        @SerialName("functionDeclarations") val functionDeclarations: List<FunctionDeclaration>
    )

    @Serializable
    internal data class GenerateRequest(
        val contents: List<Content>,
        @SerialName("systemInstruction") val systemInstruction: Content? = null,
        val tools: List<WireTool>? = null,
        @SerialName("generationConfig") val generationConfig: GenerationConfig? = null
    )

    @Serializable
    internal data class GenerateResponse(val candidates: List<Candidate> = emptyList()) {
        @Serializable
        data class Candidate(val content: Content? = null)
    }

    @Serializable
    private data class EmbedRequest(val content: Content)

    @Serializable
    private data class EmbedResponse(val embedding: Embedding? = null) {
        @Serializable
        data class Embedding(val values: List<Float> = emptyList())
    }

    override fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Flow<ChatDelta> =
        callbackFlow {
            val url = "$base/v1beta/models/$model:streamGenerateContent?alt=sse"
            val pendingCalls = ArrayList<ToolCall>()
            fun finish() {
                if (pendingCalls.isNotEmpty()) trySend(ChatDelta.ToolCalls(pendingCalls.toList()))
                close()
            }
            val source = EventSources.createFactory(streamingClient).newEventSource(
                buildRequest(url, messages, tools, options),
                object : EventSourceListener() {
                    override fun onEvent(
                        eventSource: EventSource,
                        id: String?,
                        type: String?,
                        data: String
                    ) {
                        val chunk = runCatching {
                            AiJson.decodeFromString(GenerateResponse.serializer(), data)
                        }.getOrNull() ?: return
                        val parts = chunk.candidates.firstOrNull()?.content?.parts.orEmpty()
                        parts.forEach { part ->
                            part.text?.takeIf(String::isNotEmpty)?.let { text ->
                                trySend(
                                    if (part.thought == true) {
                                        ChatDelta.Reasoning(text)
                                    } else {
                                        ChatDelta.Text(text)
                                    }
                                )
                            }
                            part.functionCall?.takeIf { it.name.isNotBlank() }?.let {
                                pendingCalls.add(
                                    ToolCall(
                                        // "g<index>_<name>": the name (which may itself contain
                                        // underscores) is everything after the first separator.
                                        id = "g${pendingCalls.size}_${it.name}",
                                        name = it.name,
                                        arguments = (it.args ?: JsonObject(emptyMap())).toString()
                                    )
                                )
                            }
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
        val url = "$base/v1beta/models/$model:generateContent"
        val body = execute(plainClient, buildRequest(url, messages, emptyList(), options))
        val response = AiJson.decodeFromString(GenerateResponse.serializer(), body)
        return response.candidates.firstOrNull()?.content?.parts
            // 思维链摘要不算回答：非流式调用（建议回复、批量总结）拿到的必须是干净正文。
            ?.filter { it.thought != true }
            ?.mapNotNull { it.text }
            ?.joinToString("")
            ?.takeIf(String::isNotBlank)
            ?: throw AiClientException.Empty()
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
        val url = "$base/v1beta/models/$model:embedContent"
        val payload = AiJson.encodeToJsonElement(
            EmbedRequest(Content(parts = listOf(Part(text = text))))
        ).jsonObject
        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = execute(plainClient, request)
        val response = AiJson.decodeFromString(EmbedResponse.serializer(), body)
        response.embedding?.values?.toFloatArray()
            ?: throw AiClientException.Malformed("embedContent 无返回向量")
    }

    private fun buildRequest(
        url: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        options: ChatOptions
    ): Request {
        val system = messages.filter { it.role == ChatRole.SYSTEM }
            .joinToString("\n\n") { it.content }
            .ifBlank { null }
        val contents = messages.filter { it.role != ChatRole.SYSTEM }.map { message ->
            when (message.role) {
                ChatRole.TOOL -> Content(
                    role = "user",
                    parts = listOf(
                        Part(
                            functionResponse = FunctionResponse(
                                name = message.toolCallId?.substringAfter('_').orEmpty(),
                                response = buildJsonObject { put("result", message.content) }
                            )
                        )
                    )
                )
                ChatRole.ASSISTANT -> Content(
                    role = "model",
                    parts = buildList {
                        if (message.content.isNotBlank()) add(Part(text = message.content))
                        message.toolCalls.forEach { call ->
                            add(Part(functionCall = FunctionCall(call.name, call.argumentsAsJson())))
                        }
                    }.ifEmpty { listOf(Part(text = "")) }
                )
                else -> Content(role = "user", parts = geminiUserParts(message))
            }
        }
        val config = GenerationConfig(
            temperature = options.temperature,
            topP = options.topP,
            maxOutputTokens = options.maxTokens,
            thinkingConfig = options.reasoning?.let { ThinkingConfig(it.budgetTokens) }
        ).takeIf {
            it.temperature != null || it.topP != null ||
                it.maxOutputTokens != null || it.thinkingConfig != null
        }
        val payload = AiJson.encodeToJsonElement(
            GenerateRequest(
                contents = contents,
                systemInstruction = system?.let { Content(parts = listOf(Part(text = it))) },
                tools = tools.takeIf { it.isNotEmpty() }?.let { specs ->
                    listOf(
                        WireTool(
                            specs.map {
                                FunctionDeclaration(it.name, it.description, it.parameters)
                            }
                        )
                    )
                },
                generationConfig = config
            )
        ).jsonObject.mergeExtras(options.extraBody)
        return Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .header("Accept", "application/json")
            .apply { options.extraHeaders.forEach { (key, value) -> header(key, value) } }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }
}

/** Gemini user/system 消息 parts：纯文本单 text Part；带图逐 part 映射为 text / inline_data。 */
internal fun geminiUserParts(message: ChatMessage): List<GeminiClient.Part> =
    if (message.parts.isEmpty()) {
        listOf(GeminiClient.Part(text = message.content))
    } else {
        message.parts.map { part ->
            when (part) {
                is ChatPart.Text -> GeminiClient.Part(text = part.text)
                is ChatPart.Image -> GeminiClient.Part(
                    inlineData = GeminiClient.InlineData(part.mimeType, part.base64)
                )
            }
        }
    }
