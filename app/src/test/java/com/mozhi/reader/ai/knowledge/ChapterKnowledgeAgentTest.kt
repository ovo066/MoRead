package com.mozhi.reader.ai.knowledge

import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.AgentToolExecutor
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChapterKnowledgeAgentTest {
    private val part = KnowledgePart(30, "林舟在灯塔等候。小满带来一封信。")
    private val valid = """{"characters":[{"name":"林舟","facts":[{"text":"在灯塔等候","quote":"林舟在灯塔等候。"}]}]}"""
    private val client = ScriptedClient()
    private val model = ResolvedChatClient(client, ChatOptions.Default,
        AiProviderEntity(id = 1, name = "test", baseUrl = "https://example.test", apiKeyAlias = "alias",
            type = AiProviderType.CHAT, createdAt = 1), "extractor")
    private val agent = ChapterKnowledgeAgent(
        AgentLoop(mockk(), dagger.Lazy { error("unused") }, dagger.Lazy { error("unused") },
            dagger.Lazy { error("unused") }, AgentToolExecutor { }), KnowledgeRequestLimiter())

    @Test fun proseAndMarkdownWrappedTextIsVerifiedWithoutASecondRequest() = runBlocking {
        client.responses += listOf(ChatDelta.Text("整理如下：\n```JSON\n"), ChatDelta.Text(valid + "\n```"))
        val result = agent.extractCharacters(model, "灯塔", "第十一章", part) {}
        assertEquals("林舟", result.single().name)
        assertEquals(30, result.single().facts.single().start)
        assertEquals(1, client.requests.size)
    }

    @Test fun characterArraysIncludingEmptyChaptersAreAcceptedAndStillVerifyQuotes() = runBlocking {
        client.responses += listOf(ChatDelta.Text("[]"))
        assertTrue(agent.extractCharacters(model, "灯塔", "扉页", part) {}.isEmpty())
        val array = valid.removePrefix("{\"characters\":").dropLast(1)
        client.responses += listOf(ChatDelta.Text("结果如下：\n```json\n$array\n```"))
        assertEquals("林舟", agent.extractCharacters(model, "灯塔", "第十一章", part) {}.single().name)
        assertEquals(2, client.requests.size)
        assertTrue(runCatching {
            ChapterKnowledgeCodec.parseCharacters(array.replace("林舟在灯塔等候。", "原文并没有这句话。"), part)
        }.isFailure)
    }

    @Test fun malformedTextGetsOneCorrectionAndDoesNotContaminateTheNextReply() = runBlocking {
        client.responses += listOf(ChatDelta.Text("人物包括林舟。"))
        client.responses += listOf(ChatDelta.Text(valid))
        assertEquals(1, agent.extractCharacters(model, "灯塔", "第十一章", part) {}.size)
        assertEquals(2, client.requests.size)
        assertTrue(client.requests.last().any { it.role == ChatRole.USER && "有效的 JSON 对象" in it.content })
        assertTrue(client.requests.last().none { it.role == ChatRole.TOOL || it.toolCalls.isNotEmpty() })
    }

    @Test fun truncatedToolArgumentsCanBeCorrectedWithText() = runBlocking {
        client.responses += listOf(ChatDelta.ToolCalls(listOf(ToolCall("first", "save_book_characters", valid.dropLast(8)))))
        client.responses += listOf(ChatDelta.Text(valid))
        assertEquals(1, agent.extractCharacters(model, "灯塔", "第十一章", part) {}.size)
        assertEquals(2, client.requests.size)
    }

    @Test fun exhaustedQuoteValidationReportsTheEvidenceErrorInsteadOfParsingTheLoopNotice() = runBlocking {
        repeat(2) {
            client.responses += listOf(ChatDelta.ToolCalls(listOf(ToolCall("call-$it", "save_book_characters",
                valid.replace("林舟在灯塔等候。", "林舟去了不存在的地方。")))))
        }
        val error = runCatching { agent.extractCharacters(model, "灯塔", "第十一章", part) {} }.exceptionOrNull()
        assertNotNull(error)
        assertEquals("部分引文无法唯一核对，请重新整理", error!!.message)
        assertEquals(2, client.requests.size)
    }

    @Test fun emptyAndTruncatedResponsesFailClearlyWithinTwoRequests() = runBlocking {
        client.responses += emptyList<ChatDelta>()
        client.responses += listOf(ChatDelta.Text(valid.dropLast(1)))
        val error = runCatching { agent.extractCharacters(model, "灯塔", "第十一章", part) {} }.exceptionOrNull()
        assertTrue(error!!.message.orEmpty().contains("不是完整的 JSON 对象"))
        assertFalse(error.message.orEmpty().contains("Unexpected JSON"))
        assertEquals(2, client.requests.size)
    }

    @Test fun emptyCharactersIsAValidSubmissionAndCancellationIsNeverRetried() = runBlocking {
        client.responses += listOf(ChatDelta.Text("""{"characters":[]}"""))
        assertTrue(agent.extractCharacters(model, "灯塔", "第十一章", part) {}.isEmpty())
        client.failure = CancellationException("stopped")
        val error = runCatching { agent.extractCharacters(model, "灯塔", "第十一章", part) {} }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(2, client.requests.size)
    }

    private class ScriptedClient : ChatApiClient {
        val responses = ArrayDeque<List<ChatDelta>>()
        val requests = mutableListOf<List<ChatMessage>>()
        var failure: Exception? = null
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>, options: ChatOptions): Flow<ChatDelta> = flow {
            requests += messages.toList()
            failure?.let { throw it }
            responses.removeFirst().forEach { emit(it) }
        }
        override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String = error("unused")
        override suspend fun embed(texts: List<String>): List<FloatArray> = error("unused")
    }
}
