package com.mozhi.reader.ai.provider

import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity

/**
 * 模型厂商、请求协议和模型能力是三个维度。尤其 OpenRouter 的 Claude 对话可以走
 * Anthropic Messages，但 embedding / image / TTS 仍必须走 OpenRouter 自己的专用端点。
 */
sealed interface ModelProtocolRoute {
    data class Chat(val dialect: ApiDialect) : ModelProtocolRoute
    data class Embedding(val dialect: ApiDialect) : ModelProtocolRoute
    data object Media : ModelProtocolRoute
    data class Unsupported(val reason: String) : ModelProtocolRoute
}

object ProviderProtocolPolicy {
    fun supportedChatDialects(adapter: AiProviderAdapter): List<ApiDialect> = when (adapter) {
        AiProviderAdapter.OPENROUTER -> listOf(
            ApiDialect.OPENAI,
            ApiDialect.OPENAI_RESPONSES,
            ApiDialect.CLAUDE
        )
        AiProviderAdapter.OPENAI -> listOf(ApiDialect.OPENAI, ApiDialect.OPENAI_RESPONSES)
        AiProviderAdapter.ANTHROPIC -> listOf(ApiDialect.CLAUDE)
        AiProviderAdapter.GEMINI -> listOf(ApiDialect.GEMINI)
        AiProviderAdapter.DEEPSEEK,
        AiProviderAdapter.MINIMAX -> listOf(ApiDialect.OPENAI)
        AiProviderAdapter.CUSTOM -> ApiDialect.entries
    }

    fun defaultChatDialect(adapter: AiProviderAdapter): ApiDialect = when (adapter) {
        AiProviderAdapter.OPENROUTER,
        AiProviderAdapter.OPENAI,
        AiProviderAdapter.DEEPSEEK,
        AiProviderAdapter.MINIMAX,
        AiProviderAdapter.CUSTOM -> ApiDialect.OPENAI
        AiProviderAdapter.ANTHROPIC -> ApiDialect.CLAUDE
        AiProviderAdapter.GEMINI -> ApiDialect.GEMINI
    }

    fun normalizeChatDialect(
        adapter: AiProviderAdapter,
        requested: ApiDialect
    ): ApiDialect = requested.takeIf { it in supportedChatDialects(adapter) }
        ?: defaultChatDialect(adapter)

    fun providerChatDialect(provider: AiProviderEntity): ApiDialect = normalizeChatDialect(
        provider.adapter,
        ApiDialect.fromWire(provider.apiFormat)
    )

    fun modelChatDialect(provider: AiProviderEntity, model: AiModelEntity): ApiDialect {
        val requested = model.chatApiFormat
            .takeIf(String::isNotBlank)
            ?.let(ApiDialect::fromWire)
            ?: providerChatDialect(provider)
        return normalizeChatDialect(provider.adapter, requested)
    }

    fun route(provider: AiProviderEntity, model: AiModelEntity): ModelProtocolRoute =
        when (model.type) {
            AiModelType.CHAT -> ModelProtocolRoute.Chat(modelChatDialect(provider, model))
            AiModelType.EMBEDDING -> embeddingRoute(provider)
            AiModelType.TTS, AiModelType.IMAGE -> mediaRoute(provider)
        }

    fun isSupported(provider: AiProviderEntity, model: AiModelEntity): Boolean =
        route(provider, model) !is ModelProtocolRoute.Unsupported

    private fun embeddingRoute(provider: AiProviderEntity): ModelProtocolRoute =
        when (provider.adapter) {
            AiProviderAdapter.OPENROUTER,
            AiProviderAdapter.OPENAI -> ModelProtocolRoute.Embedding(ApiDialect.OPENAI)
            AiProviderAdapter.GEMINI -> ModelProtocolRoute.Embedding(ApiDialect.GEMINI)
            AiProviderAdapter.ANTHROPIC -> ModelProtocolRoute.Unsupported(
                "Anthropic Messages 不提供 embedding，请分配其他向量模型"
            )
            AiProviderAdapter.DEEPSEEK -> ModelProtocolRoute.Unsupported(
                "DeepSeek 内置适配当前不提供 embedding"
            )
            AiProviderAdapter.MINIMAX -> ModelProtocolRoute.Unsupported(
                "MiniMax 内置适配当前不提供 embedding，请分配其他向量模型"
            )
            AiProviderAdapter.CUSTOM -> when (providerChatDialect(provider)) {
                ApiDialect.OPENAI,
                ApiDialect.OPENAI_RESPONSES -> ModelProtocolRoute.Embedding(ApiDialect.OPENAI)
                ApiDialect.GEMINI -> ModelProtocolRoute.Embedding(ApiDialect.GEMINI)
                ApiDialect.CLAUDE -> ModelProtocolRoute.Unsupported(
                    "Anthropic Messages 不提供 embedding，请改用 OpenAI 兼容或 Gemini 协议"
                )
            }
        }

    private fun mediaRoute(provider: AiProviderEntity): ModelProtocolRoute =
        when (provider.adapter) {
            AiProviderAdapter.OPENROUTER,
            AiProviderAdapter.OPENAI,
            AiProviderAdapter.MINIMAX -> ModelProtocolRoute.Media
            AiProviderAdapter.CUSTOM -> when (providerChatDialect(provider)) {
                ApiDialect.OPENAI,
                ApiDialect.OPENAI_RESPONSES -> ModelProtocolRoute.Media
                else -> ModelProtocolRoute.Unsupported("当前自定义协议不支持 OpenAI 兼容媒体端点")
            }
            else -> ModelProtocolRoute.Unsupported("${provider.name} 的内置适配当前不支持该媒体能力")
        }
}
