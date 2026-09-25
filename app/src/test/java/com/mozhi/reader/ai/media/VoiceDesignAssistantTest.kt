package com.mozhi.reader.ai.media

import com.mozhi.reader.ai.agent.*
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.*
import io.mockk.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VoiceDesignAssistantTest {
    private val personas = mockk<PersonaRepository>()
    private class Actions : VoiceDesignActions {
        var generated = 0
        var request = GeminiVoiceDesignRequest("", "")
        override fun snapshot() = buildJsonObject { put("name", request.name); put("preview_ready", generated > 0) }
        override fun update(request: GeminiVoiceDesignRequest) { this.request = request }
        override suspend fun generatePreview(): JsonObject { generated++; return snapshot() }
        override suspend fun fetchPreview() = snapshot()
    }

    @Test fun agentChoosesToolsReadsTheirResultsAndGeneratesInsteadOfOnlyRewritingText() = runTest {
        val factory = mockk<AiClientFactory>()
        val client = mockk<ChatApiClient>()
        val provider = AiProviderEntity(id = 1, name = "chat", baseUrl = "https://example.test", apiKeyAlias = "test", type = AiProviderType.CHAT, createdAt = 0)
        coEvery { factory.forRole(any()) } returns ResolvedChatClient(client, ChatOptions.Default, provider, "chat")
        val persona = PersonaEntity(id = 7, name = "苏晚", personality = "温柔的书店店主", speakingStyle = "自然从容", isRoleplay = true,
            userProfile = "PRIVATE_USER_PROFILE", createdAt = 0)
        coEvery { personas.getPersonas() } returns listOf(persona)
        coEvery { personas.getPersona(7) } returns persona
        val loop = AgentLoop(mockk(relaxed = true), dagger.Lazy { factory }, dagger.Lazy { mockk() }, dagger.Lazy { mockk() }, AgentToolExecutor {})
        var round = 0
        val seen = mutableListOf<List<ChatMessage>>()
        every { client.chatStream(any(), any(), any()) } answers {
            seen += firstArg<List<ChatMessage>>().toList()
            round++
            when (round) {
                1 -> flowOf(ChatDelta.ToolCalls(listOf(ToolCall("1", "find_voice_personas", """{"query":"苏晚"}"""))))
                2 -> flowOf(ChatDelta.ToolCalls(listOf(ToolCall("2", "read_voice_persona", """{"persona_id":7}"""))))
                3 -> flowOf(ChatDelta.ToolCalls(listOf(ToolCall("3", "set_voice_design", """{"name":"苏晚","description":"温柔自然的普通话女声","gender":"female","language":"zh-CN"}"""))))
                4 -> flowOf(ChatDelta.ToolCalls(listOf(ToolCall("4", "generate_voice_preview", "{}"))))
                else -> flowOf(ChatDelta.Text("试听已生成，请听听是否满意。"))
            }
        }
        val actions = Actions()
        val events = VoiceDesignAssistant(loop, personas).run(listOf(ChatMessage(ChatRole.USER, "给苏晚设计声音")), actions).toList()
        assertEquals(1, actions.generated)
        assertEquals("苏晚", actions.request.name)
        assertEquals(4, events.filterIsInstance<AgentEvent.ToolRun>().size)
        assertTrue(seen[2].any { it.role == ChatRole.TOOL && it.content.contains("书店店主") })
        assertFalse(seen.flatten().any { it.content.contains("PRIVATE_USER_PROFILE") })
    }

    @Test fun noLibraryWriteToolExistsAndGenerationIsLimitedPerUserTurn() = runTest {
        val actions = Actions()
        val tools = VoiceDesignAssistant(mockk(), personas).tools(actions)
        assertFalse(tools.any { "save" in it.spec.name || "delete" in it.spec.name })
        val generate = tools.single { it.spec.name == "generate_voice_preview" }
        generate.execute(JsonObject(emptyMap()))
        assertTrue(runCatching { generate.execute(JsonObject(emptyMap())) }.exceptionOrNull() is IllegalStateException)
        assertEquals(1, actions.generated)
    }
}
