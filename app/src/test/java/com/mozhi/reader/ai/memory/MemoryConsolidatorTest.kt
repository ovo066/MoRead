package com.mozhi.reader.ai.memory

import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryConsolidatorTest {
    private fun messages(count: Int, role: ChatRole = ChatRole.USER): List<MessageEntity> =
        (1..count).map { id ->
            MessageEntity(
                id = id.toLong(),
                conversationId = 7,
                role = role.wire,
                content = "消息$id",
                createdAt = id.toLong()
            )
        }

    @Test
    fun `normal consolidation waits for thirty useful messages`() {
        assertNull(MemoryBatchPlanner.plan(messages(29), consolidatedThrough = 0, forceOnClose = false))

        val batch = MemoryBatchPlanner.plan(messages(30), consolidatedThrough = 0, forceOnClose = false)

        assertEquals(30, batch?.messages?.size)
        assertEquals(30L, batch?.throughMessageId)
    }

    @Test
    fun `closing a conversation flushes ten remaining messages`() {
        assertNull(MemoryBatchPlanner.plan(messages(9), consolidatedThrough = 0, forceOnClose = true))
        assertEquals(
            10L,
            MemoryBatchPlanner.plan(messages(10), consolidatedThrough = 0, forceOnClose = true)
                ?.throughMessageId
        )
    }

    @Test
    fun `planner skips watermark system tool and blank messages`() {
        val ignored = listOf(
            MessageEntity(31, 7, ChatRole.SYSTEM.wire, "system", createdAt = 31),
            MessageEntity(32, 7, ChatRole.TOOL.wire, "tool", createdAt = 32),
            MessageEntity(33, 7, ChatRole.USER.wire, "   ", createdAt = 33)
        )
        val batch = MemoryBatchPlanner.plan(
            messages(35) + ignored,
            consolidatedThrough = 5,
            forceOnClose = false
        )

        assertEquals((6L..35L).toList(), batch?.messages?.map(MessageEntity::id))
        assertEquals(35L, batch?.throughMessageId)
    }

    @Test
    fun `summary parser accepts fenced arrays and object fallback`() {
        assertEquals(
            listOf("用户喜欢科幻", "约好下次聊第二章"),
            MemorySummaryParser.parse(
                """
                ```json
                ["用户喜欢科幻", "约好下次聊第二章", "用户喜欢科幻"]
                ```
                """.trimIndent()
            )
        )
        assertEquals(
            listOf("共同经历"),
            MemorySummaryParser.parse("前缀 {\"memories\":[\"共同经历\"]} 后缀")
        )
        assertTrue(MemorySummaryParser.parse("not json").isEmpty())
    }
}
