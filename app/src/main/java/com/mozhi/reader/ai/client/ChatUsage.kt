package com.mozhi.reader.ai.client

import kotlinx.serialization.json.*

/** Read the usage-only SSE events too: they frequently contain no text or choices. */
internal fun parseChatUsage(data: String, dialect: ApiDialect): ChatDelta.Usage? = runCatching {
    val root = AiJson.parseToJsonElement(data) as? JsonObject ?: return null
    val usage = when (dialect) {
        ApiDialect.OPENAI -> root["usage"]
        ApiDialect.OPENAI_RESPONSES -> (root["response"] as? JsonObject)?.get("usage")
        ApiDialect.CLAUDE -> (root["message"] as? JsonObject)?.get("usage") ?: root["usage"]
        ApiDialect.GEMINI -> root["usageMetadata"]
    } as? JsonObject ?: return null
    fun count(key: String) = (usage[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    fun sum(vararg values: Long?): Long? = values.filterNotNull().takeIf { it.isNotEmpty() }
        ?.fold(0L) { total, value -> Math.addExact(total, value) }
    val input = when (dialect) {
        ApiDialect.OPENAI -> count("prompt_tokens")
        ApiDialect.GEMINI -> count("promptTokenCount")
        ApiDialect.CLAUDE -> sum(count("input_tokens"), count("cache_read_input_tokens"), count("cache_creation_input_tokens"))
        else -> count("input_tokens")
    }
    val output = when (dialect) {
        ApiDialect.OPENAI -> count("completion_tokens")
        ApiDialect.GEMINI -> sum(count("candidatesTokenCount"), count("thoughtsTokenCount"))
        else -> count("output_tokens")
    }
    val total = if (dialect == ApiDialect.GEMINI) count("totalTokenCount") else count("total_tokens")
    ChatDelta.Usage(input, output, total).takeIf { input != null || output != null || total != null }
}.getOrNull()

internal class ChatUsageAccumulator(private val nanoTime: () -> Long = System::nanoTime) {
    private val started = nanoTime()
    var inputTokens: Long? = null; private set
    var outputTokens: Long? = null; private set
    private var reportedTotal: Long? = null
    fun accept(usage: ChatDelta.Usage) {
        usage.inputTokens?.let { inputTokens = it }
        usage.outputTokens?.let { outputTokens = it }
        usage.totalTokens?.let { reportedTotal = it }
    }
    val totalTokens: Int? get() = (reportedTotal ?: inputTokens?.let { input -> outputTokens?.let { input + it } })
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    fun elapsedMillis(): Long = ((nanoTime() - started) / 1_000_000).coerceAtLeast(1)
}
