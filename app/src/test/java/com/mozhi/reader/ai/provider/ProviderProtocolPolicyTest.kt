package com.mozhi.reader.ai.provider

import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderProtocolPolicyTest {
    @Test
    fun `openRouter Claude default affects chat but embedding stays OpenAI compatible`() {
        val provider = provider(AiProviderAdapter.OPENROUTER, ApiDialect.CLAUDE)

        val chat = ProviderProtocolPolicy.route(provider, model(AiModelType.CHAT))
        val embedding = ProviderProtocolPolicy.route(provider, model(AiModelType.EMBEDDING))
        val image = ProviderProtocolPolicy.route(provider, model(AiModelType.IMAGE))

        assertEquals(ApiDialect.CLAUDE, (chat as ModelProtocolRoute.Chat).dialect)
        assertEquals(ApiDialect.OPENAI, (embedding as ModelProtocolRoute.Embedding).dialect)
        assertTrue(image is ModelProtocolRoute.Media)
    }

    @Test
    fun `chat model can override its provider default protocol`() {
        val provider = provider(AiProviderAdapter.OPENROUTER, ApiDialect.CLAUDE)
        val model = model(AiModelType.CHAT, chatApiFormat = ApiDialect.OPENAI.name)

        val route = ProviderProtocolPolicy.route(provider, model)

        assertEquals(ApiDialect.OPENAI, (route as ModelProtocolRoute.Chat).dialect)
    }

    @Test
    fun `native Anthropic does not advertise embedding`() {
        val route = ProviderProtocolPolicy.route(
            provider(AiProviderAdapter.ANTHROPIC, ApiDialect.CLAUDE),
            model(AiModelType.EMBEDDING)
        )

        assertTrue(route is ModelProtocolRoute.Unsupported)
    }

    @Test
    fun `built in adapter rejects an unrelated chat protocol`() {
        assertEquals(
            ApiDialect.CLAUDE,
            ProviderProtocolPolicy.normalizeChatDialect(
                AiProviderAdapter.ANTHROPIC,
                ApiDialect.GEMINI
            )
        )
    }

    private fun provider(adapter: AiProviderAdapter, dialect: ApiDialect) = AiProviderEntity(
        id = 1,
        name = adapter.name,
        baseUrl = "https://example.test/api/v1",
        apiKeyAlias = "alias",
        type = AiProviderType.CHAT,
        apiFormat = dialect.name,
        adapter = adapter,
        createdAt = 1
    )

    private fun model(type: AiModelType, chatApiFormat: String = "") = AiModelEntity(
        id = 1,
        providerId = 1,
        modelName = "test/model",
        type = type,
        chatApiFormat = chatApiFormat,
        createdAt = 1
    )
}
