package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.client.ChatDelta
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import com.mozhi.reader.ai.memory.RollingSummarizer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    private class FakeChatDao(seed: List<MessageEntity>) : ChatDao {
        val messages = seed.toMutableList()
        private var nextId = seed.size + 1L

        override suspend fun insertConversation(conversation: ConversationEntity): Long = 1
        override suspend fun getConversation(conversationId: Long): ConversationEntity? = null
        override suspend fun getLatestConversation(
            bookId: Long,
            personaId: Long,
            type: String
        ): ConversationEntity? = null
        override fun observeConversations(bookId: Long): Flow<List<ConversationEntity>> =
            flowOf(emptyList())
        override fun observeConversations(
            bookId: Long,
            personaId: Long,
            type: String
        ): Flow<List<ConversationEntity>> = flowOf(emptyList())
        override suspend fun updateConversationTitle(
            conversationId: Long,
            title: String,
            updatedAt: Long
        ) = Unit
        override suspend fun touchConversation(conversationId: Long, updatedAt: Long) = Unit
        override suspend fun updateRollingSummary(
            conversationId: Long,
            summary: String,
            messageId: Long
        ) = Unit
        override suspend fun clearRollingSummary(conversationId: Long) = Unit

        override suspend fun deleteConversation(conversationId: Long) = Unit
        override suspend fun insertMessage(message: MessageEntity): Long {
            messages.add(message.copy(id = nextId))
            return nextId++
        }

        override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
            flowOf(messages)

        override fun observeUserMessageCount(): Flow<Int> =
            flowOf(messages.count { it.role == "user" })

        override suspend fun getMessages(conversationId: Long): List<MessageEntity> = messages
        override suspend fun getMessage(messageId: Long): MessageEntity? =
            messages.firstOrNull { it.id == messageId }
        override suspend fun updateMessageContent(
            messageId: Long,
            content: String,
            editedAt: Long
        ) = Unit
        override suspend fun deleteMessage(messageId: Long) = Unit
        override suspend fun deleteMessagesAfter(conversationId: Long, messageId: Long) = Unit
        override suspend fun deleteMessageRange(
            conversationId: Long,
            fromMessageId: Long,
            untilMessageId: Long
        ) = Unit
        override suspend fun resetMemoryConsolidationWatermark(
            conversationId: Long,
            updatedAt: Long
        ) = Unit

        override suspend fun advanceMemoryConsolidationWatermark(
            conversationId: Long,
            messageId: Long
        ) = Unit
    }

    private class EchoTool(private val reply: String) : AgentTool {
        var invocations = 0
            private set
        override val displayName: String = "查询测试"
        override val spec: ToolSpec = ToolSpec(
            name = "echo_tool",
            description = "test",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {}
            }
        )

        override suspend fun execute(arguments: JsonObject): String {
            invocations++
            return reply
        }
    }

    private fun seed(vararg turns: Pair<ChatRole, String>): List<MessageEntity> =
        turns.mapIndexed { index, (role, content) ->
            MessageEntity(
                id = index + 1L,
                conversationId = 1,
                role = role.wire,
                content = content,
                createdAt = index.toLong()
            )
        }

    private fun loop(dao: ChatDao) =
        AgentLoop(
            dao,
            clientFactory = dagger.Lazy { error("runWith never resolves the factory") },
            attachmentStore = dagger.Lazy { error("no attachments in these tests") },
            // 这些用例的会话都没有前情提要，摘要器只会被问一次 block("")。
            rollingSummarizer = dagger.Lazy {
                RollingSummarizer(dao, dagger.Lazy { error("summariser never calls the factory here") })
            }
        )

    @Test
    fun `plain reply streams text and persists once`() = runTest {
        val dao = FakeChatDao(seed(ChatRole.SYSTEM to "s", ChatRole.USER to "你好"))
        val events = loop(dao).runWith(1, emptyList()) {
            AgentLoop.Streamer { _, _ -> flowOf(ChatDelta.Text("你"), ChatDelta.Text("好")) }
        }.toList()
        assertEquals(AgentEvent.Text("你"), events[0])
        assertEquals(AgentEvent.Text("好"), events[1])
        assertTrue(events[2] is AgentEvent.RoundCommitted)
        val persisted = dao.messages.last()
        assertEquals(ChatRole.ASSISTANT.wire, persisted.role)
        assertEquals("你好", persisted.content)
    }

    @Test
    fun `tool round executes, persists plumbing, and feeds the next round`() = runTest {
        val dao = FakeChatDao(seed(ChatRole.USER to "进度如何"))
        val tool = EchoTool("已读到第 3 章")
        var round = 0
        val seenHistories = ArrayList<List<ChatMessage>>()
        val events = loop(dao).runWith(1, listOf(tool)) {
            AgentLoop.Streamer { messages, _ ->
                seenHistories.add(messages)
                round++
                if (round == 1) {
                    flowOf(ChatDelta.ToolCalls(listOf(ToolCall("call_1", "echo_tool", "{}"))))
                } else {
                    flowOf(ChatDelta.Text("你读到第 3 章了"))
                }
            }
        }.toList()

        assertEquals(1, tool.invocations)
        assertTrue(
            events.contains(
                AgentEvent.ToolRun("call_1", "echo_tool", "查询测试")
            )
        )
        assertTrue(
            events.contains(
                AgentEvent.ToolFinished(
                    "call_1",
                    "echo_tool",
                    "查询测试",
                    succeeded = true,
                    detail = "已完成",
                    // 「过程」卡展开后要能看到工具到底返回了什么，成功时也给预览。
                    resultPreview = "已读到第 3 章"
                )
            )
        )
        assertTrue(events.contains(AgentEvent.Text("你读到第 3 章了")))
        // Round 2 history replays the tool call and its result.
        val secondHistory = seenHistories[1]
        assertEquals(ChatRole.ASSISTANT, secondHistory[1].role)
        assertEquals("echo_tool", secondHistory[1].toolCalls.single().name)
        assertEquals(ChatRole.TOOL, secondHistory[2].role)
        assertEquals("已读到第 3 章", secondHistory[2].content)
        assertEquals("call_1", secondHistory[2].toolCallId)
        // Persisted rows mirror that plumbing.
        val roles = dao.messages.map { it.role }
        assertEquals(listOf("user", "assistant", "tool", "assistant"), roles)
    }

    @Test
    fun `tool preface commits before tool activity and next round starts separately`() = runTest {
        val dao = FakeChatDao(seed(ChatRole.USER to "搜搜今天新闻"))
        val tool = EchoTool("搜索结果")
        var round = 0

        val events = loop(dao).runWith(1, listOf(tool)) {
            AgentLoop.Streamer { _, _ ->
                round++
                if (round == 1) {
                    flowOf(
                        ChatDelta.Text("我来查一下。"),
                        ChatDelta.ToolCalls(listOf(ToolCall("call_1", "echo_tool", "{}")))
                    )
                } else {
                    flowOf(ChatDelta.Text("今天的新闻如下。"))
                }
            }
        }.toList()

        assertEquals(AgentEvent.Text("我来查一下。"), events[0])
        assertTrue(events[1] is AgentEvent.RoundCommitted)
        assertTrue(events[2] is AgentEvent.ToolRun)
        assertTrue(events[3] is AgentEvent.ToolFinished)
        assertEquals(AgentEvent.Text("今天的新闻如下。"), events[4])
        assertEquals(
            listOf("我来查一下。", "今天的新闻如下。"),
            dao.messages.filter { it.role == "assistant" }.map { it.content }
        )
    }

    @Test
    fun `unknown tool returns an error result instead of crashing`() = runTest {
        val dao = FakeChatDao(seed(ChatRole.USER to "hi"))
        var round = 0
        loop(dao).runWith(1, emptyList()) {
            AgentLoop.Streamer { _, _ ->
                round++
                if (round == 1) {
                    flowOf(ChatDelta.ToolCalls(listOf(ToolCall("c", "missing_tool", "{}"))))
                } else {
                    flowOf(ChatDelta.Text("好的"))
                }
            }
        }.toList()
        val toolRow = dao.messages.first { it.role == ChatRole.TOOL.wire }
        assertTrue(toolRow.content.contains("未知工具"))
    }

    @Test
    fun `empty first round throws Empty`() = runTest {
        val dao = FakeChatDao(seed(ChatRole.USER to "hi"))
        val result = runCatching {
            loop(dao).runWith(1, emptyList()) {
                AgentLoop.Streamer { _, _ -> flowOf() }
            }.toList()
        }
        assertTrue(result.exceptionOrNull() is AiClientException.Empty)
    }

    @Test
    fun `system override replaces persisted system message for the run only`() = runTest {
        val dao = FakeChatDao(seed(ChatRole.SYSTEM to "旧提示词", ChatRole.USER to "你好"))
        var seen: List<ChatMessage> = emptyList()
        loop(dao).runWith(1, emptyList(), systemPrompt = "新提示词") {
            AgentLoop.Streamer { messages, _ ->
                seen = messages
                flowOf(ChatDelta.Text("好"))
            }
        }.toList()

        assertEquals(ChatRole.SYSTEM, seen.first().role)
        assertEquals("新提示词", seen.first().content)
        assertEquals(1, seen.count { it.role == ChatRole.SYSTEM })
        // 落库的系统消息保持原快照。
        assertEquals("旧提示词", dao.messages.first { it.role == ChatRole.SYSTEM.wire }.content)
    }

    @Test
    fun `long history is windowed starting at a user message`() = runTest {
        // system + 30 条 user/assistant 交替（user 在偶数位）。
        val turns = buildList {
            add(ChatRole.SYSTEM to "s")
            repeat(15) { i ->
                add(ChatRole.USER to "问${i}")
                add(ChatRole.ASSISTANT to "答${i}")
            }
        }
        val dao = FakeChatDao(seed(*turns.toTypedArray()))
        var seen: List<ChatMessage> = emptyList()
        loop(dao).runWith(1, emptyList()) {
            AgentLoop.Streamer { messages, _ ->
                seen = messages
                flowOf(ChatDelta.Text("ok"))
            }
        }.toList()

        // 30 条对话取尾窗 20 条，起点恰好是 user，system 恒在。
        assertEquals(ChatRole.SYSTEM, seen.first().role)
        assertEquals(21, seen.size)
        assertEquals(ChatRole.USER, seen[1].role)
        assertEquals("问5", seen[1].content)
        assertEquals("答14", seen.last().content)
    }

    @Test
    fun `window never strands tool results from their call`() = runTest {
        // 构造 user 之后跟一长串 assistant(toolCalls)+tool 的历史，窗口起点若切进中段必然产生孤儿 tool。
        val entities = buildList {
            add(MessageEntity(id = 1, conversationId = 1, role = ChatRole.SYSTEM.wire, content = "s", createdAt = 0))
            add(MessageEntity(id = 2, conversationId = 1, role = ChatRole.USER.wire, content = "开始", createdAt = 1))
            var id = 3L
            repeat(12) { i ->
                add(
                    MessageEntity(
                        id = id++, conversationId = 1, role = ChatRole.ASSISTANT.wire, content = "",
                        toolCallsJson = """[{"id":"c$i","name":"echo_tool","arguments":"{}"}]""",
                        createdAt = 2L + i * 2
                    )
                )
                add(
                    MessageEntity(
                        id = id++, conversationId = 1, role = ChatRole.TOOL.wire, content = "r$i",
                        toolCallId = "c$i", createdAt = 3L + i * 2
                    )
                )
            }
        }
        val dao = FakeChatDao(entities)
        var seen: List<ChatMessage> = emptyList()
        loop(dao).runWith(1, emptyList()) {
            AgentLoop.Streamer { messages, _ ->
                seen = messages
                flowOf(ChatDelta.Text("ok"))
            }
        }.toList()

        // 尾部 20 条内没有 user，向前扩到唯一的 user，全量 24 条都带上。
        assertEquals(ChatRole.USER, seen[1].role)
        val toolIds = seen.filter { it.role == ChatRole.TOOL }.map { it.toolCallId }
        val callIds = seen.filter { it.role == ChatRole.ASSISTANT }
            .flatMap { it.toolCalls }
            .map { it.id }
        assertEquals(toolIds.toSet(), callIds.toSet())
    }

    // ---- runDetached：段评讨论串等轻量场景的不落库循环 ----

    @Test
    fun `detached run streams text and writes nothing to the dao`() = runTest {
        val dao = FakeChatDao(emptyList())
        val history = listOf(
            ChatMessage(ChatRole.SYSTEM, "讨论区规则"),
            ChatMessage(ChatRole.USER, "你怎么看这段")
        )
        val events = loop(dao).runDetachedWith(history, emptyList(), maxRounds = 3) {
            AgentLoop.Streamer { _, _ -> flowOf(ChatDelta.Text("我认"), ChatDelta.Text("为很妙")) }
        }.toList()

        assertEquals(listOf<AgentEvent>(AgentEvent.Text("我认"), AgentEvent.Text("为很妙")), events)
        assertTrue("讨论内容绝不落聊天消息表", dao.messages.isEmpty())
    }

    @Test
    fun `detached run executes tools and feeds results to the next round`() = runTest {
        val dao = FakeChatDao(emptyList())
        val tool = EchoTool("第 2 章的雪是白色的")
        var round = 0
        val events = loop(dao).runDetachedWith(
            listOf(ChatMessage(ChatRole.USER, "前文的雪什么颜色")),
            listOf(tool),
            maxRounds = 3
        ) {
            AgentLoop.Streamer { messages, _ ->
                round++
                if (round == 1) {
                    flowOf(ChatDelta.ToolCalls(listOf(ToolCall("call_1", "echo_tool", "{}"))))
                } else {
                    assertEquals(ChatRole.TOOL, messages.last().role)
                    flowOf(ChatDelta.Text("是白色的"))
                }
            }
        }.toList()

        assertEquals(1, tool.invocations)
        assertTrue(events.any { it is AgentEvent.ToolRun })
        assertTrue(events.any { it is AgentEvent.ToolFinished && it.succeeded })
        assertEquals("是白色的", events.filterIsInstance<AgentEvent.Text>().joinToString("") { it.text })
        assertTrue(dao.messages.isEmpty())
    }

    @Test
    fun `detached run requires a user message`() = runTest {
        val dao = FakeChatDao(emptyList())
        val result = runCatching {
            loop(dao).runDetachedWith(
                listOf(ChatMessage(ChatRole.SYSTEM, "只有系统")),
                emptyList(),
                maxRounds = 3
            ) {
                AgentLoop.Streamer { _, _ -> flowOf(ChatDelta.Text("不该到这")) }
            }.toList()
        }
        assertTrue(result.exceptionOrNull() is AiClientException.Empty)
    }
}
