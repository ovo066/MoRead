package com.mozhi.reader.ai.client

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull

/** Shared lenient JSON for every dialect. */
val AiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * The API dialect a provider speaks. Following RikkaHub-style clients: OpenAI-compatible covers
 * most aggregators (DeepSeek, SiliconFlow, OpenRouter…), OpenAI's newer Responses API, plus
 * native Anthropic and Gemini.
 */
enum class ApiDialect {
    OPENAI,
    OPENAI_RESPONSES,
    CLAUDE,
    GEMINI;

    companion object {
        fun fromWire(value: String?): ApiDialect =
            entries.firstOrNull { it.name == value } ?: OPENAI
    }
}

/**
 * Reasoning/thinking effort, mapped per dialect: OpenAI `reasoning_effort`, Responses
 * `reasoning.effort`, Claude `thinking.budget_tokens`, Gemini `thinkingConfig.thinkingBudget`.
 * OFF sends nothing and leaves the provider default untouched.
 */
enum class ReasoningEffort(val wire: String, val budgetTokens: Int) {
    LOW("low", 2048),
    MEDIUM("medium", 8192),
    HIGH("high", 16384);

    companion object {
        fun fromWire(value: String?): ReasoningEffort? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** Anthropic prompt-cache lifetime. 5m is the API default; 1h costs a higher cache-write rate. */
enum class PromptCacheTtl(val wire: String) {
    FIVE_MINUTES("5m"),
    ONE_HOUR("1h");

    companion object {
        fun fromWire(value: String?): PromptCacheTtl? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/**
 * Per-request tuning, merged from the provider's `extraJson` and call-site needs.
 *
 * `extraJson` schema (all optional, unknown keys ignored):
 * ```json
 * {
 *   "temperature": 0.7, "top_p": 0.9, "max_tokens": 2048,
 *   "reasoning": "low" | "medium" | "high",
 *   "cache_prompt": true, "cache_ttl": "5m" | "1h",
 *   "headers": { "X-Custom": "v" },
 *   "body":    { "enable_thinking": false }
 * }
 * ```
 * `headers` are added to the HTTP request; `body` keys are merged verbatim into the JSON payload
 * (dialect-specific), which is how provider-specific switches stay usable without code changes.
 * `cache_prompt` (default true) marks the system prompt and tool list with Anthropic
 * `cache_control` breakpoints, `cache_ttl` picks their lifetime; other dialects cache
 * automatically and ignore both.
 */
data class ChatOptions(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val reasoning: ReasoningEffort? = null,
    /** Anthropic prompt-cache lifetime; null disables the cache breakpoints. */
    val cacheTtl: PromptCacheTtl? = PromptCacheTtl.FIVE_MINUTES,
    val extraHeaders: Map<String, String> = emptyMap(),
    val extraBody: JsonObject? = null
) {
    companion object {
        val Default = ChatOptions()

        fun fromExtraJson(extraJson: String?): ChatOptions {
            if (extraJson.isNullOrBlank() || extraJson.trim() == "{}") return Default
            val root = runCatching { AiJson.parseToJsonElement(extraJson).jsonObject }
                .getOrNull() ?: return Default
            val headers = (root["headers"] as? JsonObject)
                ?.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.content?.let { key to it }
                }
                ?.toMap()
                .orEmpty()
            val cacheEnabled = (root["cache_prompt"] as? JsonPrimitive)?.booleanOrNull ?: true
            return ChatOptions(
                temperature = (root["temperature"] as? JsonPrimitive)?.floatOrNull,
                topP = (root["top_p"] as? JsonPrimitive)?.floatOrNull,
                maxTokens = (root["max_tokens"] as? JsonPrimitive)?.intOrNull,
                reasoning = ReasoningEffort.fromWire((root["reasoning"] as? JsonPrimitive)?.content),
                cacheTtl = if (!cacheEnabled) {
                    null
                } else {
                    PromptCacheTtl.fromWire((root["cache_ttl"] as? JsonPrimitive)?.content)
                        ?: PromptCacheTtl.FIVE_MINUTES
                },
                extraHeaders = headers,
                extraBody = root["body"] as? JsonObject
            )
        }
    }
}

/** One provider+model bound to a concrete dialect implementation. */
interface ChatApiClient {
    /**
     * Streams the assistant turn: [ChatDelta.Text] increments, optionally terminated by one
     * [ChatDelta.ToolCalls] when the model wants [tools] run. Terminates normally at end of
     * message.
     */
    fun chatStream(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        options: ChatOptions = ChatOptions.Default
    ): Flow<ChatDelta>

    /** Non-streaming completion for background tasks. */
    suspend fun chat(messages: List<ChatMessage>, options: ChatOptions = ChatOptions.Default): String

    /** Batch embeddings; dialects without an embedding endpoint throw [AiClientException.Unsupported]. */
    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** Merges `body` extras into an already-encoded payload object. */
internal fun JsonObject.mergeExtras(extras: JsonObject?): JsonObject {
    if (extras == null || extras.isEmpty()) return this
    val merged = toMutableMap()
    extras.forEach { (key, value) -> merged[key] = value }
    return JsonObject(merged)
}

/** `https://host/v1` and `https://host/v1/` and `https://host` all normalize to `https://host`. */
internal fun normalizeBase(baseUrl: String, vararg stripSuffixes: String): String {
    var base = baseUrl.trim().trimEnd('/')
    for (suffix in stripSuffixes) {
        if (base.endsWith(suffix)) {
            base = base.removeSuffix(suffix).trimEnd('/')
            break
        }
    }
    return base
}
