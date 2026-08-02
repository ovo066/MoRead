package com.mozhi.reader.feature.settings

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.ai.client.PromptCacheTtl
import com.mozhi.reader.ai.client.ReasoningEffort
import com.mozhi.reader.core.database.entity.AiProviderType
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 设置主页与 Provider 详情页共用的标签/序列化助手。 */

internal fun AiProviderType.label(): String = when (this) {
    AiProviderType.CHAT -> "对话"
    AiProviderType.EMBEDDING -> "向量"
    AiProviderType.TTS -> "语音"
    AiProviderType.IMAGE -> "生图"
}

internal fun AiModelType.label(): String = when (this) {
    AiModelType.CHAT -> "对话"
    AiModelType.EMBEDDING -> "向量"
    AiModelType.TTS -> "语音"
    AiModelType.IMAGE -> "生图"
}

internal fun AiProviderAdapter.label(): String = when (this) {
    AiProviderAdapter.CUSTOM -> "自定义"
    AiProviderAdapter.OPENROUTER -> "OpenRouter"
    AiProviderAdapter.OPENAI -> "OpenAI"
    AiProviderAdapter.ANTHROPIC -> "Anthropic"
    AiProviderAdapter.GEMINI -> "Gemini"
    AiProviderAdapter.DEEPSEEK -> "DeepSeek"
    AiProviderAdapter.MINIMAX -> "MiniMax"
}

internal data class ProviderPreset(
    val adapter: AiProviderAdapter,
    val name: String,
    val baseUrl: String,
    val dialect: ApiDialect
)

internal val CommonProviderPresets = listOf(
    ProviderPreset(
        AiProviderAdapter.OPENROUTER,
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        ApiDialect.OPENAI
    ),
    ProviderPreset(
        AiProviderAdapter.OPENAI,
        "OpenAI",
        "https://api.openai.com/v1",
        ApiDialect.OPENAI
    ),
    ProviderPreset(
        AiProviderAdapter.ANTHROPIC,
        "Anthropic",
        "https://api.anthropic.com",
        ApiDialect.CLAUDE
    ),
    ProviderPreset(
        AiProviderAdapter.GEMINI,
        "Gemini",
        "https://generativelanguage.googleapis.com",
        ApiDialect.GEMINI
    ),
    ProviderPreset(
        AiProviderAdapter.DEEPSEEK,
        "DeepSeek",
        "https://api.deepseek.com",
        ApiDialect.OPENAI
    ),
    ProviderPreset(
        AiProviderAdapter.MINIMAX,
        "MiniMax（国内）",
        "https://api.minimaxi.com/v1",
        ApiDialect.OPENAI
    ),
    ProviderPreset(
        AiProviderAdapter.MINIMAX,
        "MiniMax（海外）",
        "https://api.minimax.io/v1",
        ApiDialect.OPENAI
    )
)

internal fun ApiDialect.label(): String = when (this) {
    ApiDialect.OPENAI -> "OpenAI 兼容"
    ApiDialect.OPENAI_RESPONSES -> "OpenAI Responses"
    ApiDialect.CLAUDE -> "Claude"
    ApiDialect.GEMINI -> "Gemini"
}

internal fun ApiDialect.defaultBaseUrl(): String = when (this) {
    ApiDialect.OPENAI -> "https://api.openai.com/v1"
    ApiDialect.OPENAI_RESPONSES -> "https://api.openai.com/v1"
    ApiDialect.CLAUDE -> "https://api.anthropic.com"
    ApiDialect.GEMINI -> "https://generativelanguage.googleapis.com"
}

internal fun ReasoningEffort.label(): String = when (this) {
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.MEDIUM -> "中"
    ReasoningEffort.HIGH -> "高"
}

/** Writes the chip choice into extraJson's `reasoning` key, leaving other keys untouched. */
internal fun String.withReasoningKey(reasoning: ReasoningEffort?): String {
    val root = runCatching { AiJson.parseToJsonElement(ifBlank { "{}" }).jsonObject }
        .getOrNull() ?: return this
    val updated = root.toMutableMap()
    if (reasoning == null) {
        updated.remove("reasoning")
    } else {
        updated["reasoning"] = JsonPrimitive(reasoning.wire)
    }
    return JsonObject(updated).toString()
}

/** Writes the cache choice into extraJson (`cache_prompt` + `cache_ttl`). */
internal fun String.withCacheKeys(cacheTtl: PromptCacheTtl?): String {
    val root = runCatching { AiJson.parseToJsonElement(ifBlank { "{}" }).jsonObject }
        .getOrNull() ?: return this
    val updated = root.toMutableMap()
    if (cacheTtl == null) {
        updated["cache_prompt"] = JsonPrimitive(false)
        updated.remove("cache_ttl")
    } else {
        updated.remove("cache_prompt")
        if (cacheTtl == PromptCacheTtl.FIVE_MINUTES) {
            updated.remove("cache_ttl")
        } else {
            updated["cache_ttl"] = JsonPrimitive(cacheTtl.wire)
        }
    }
    return JsonObject(updated).toString()
}
