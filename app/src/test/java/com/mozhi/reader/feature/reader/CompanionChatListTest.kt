package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionChatListTest {

    private fun message(id: Long, role: String, content: String = "内容") = MessageEntity(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        createdAt = id
    )

    private fun entries(
        timeline: List<CompanionTimelineItem> = emptyList(),
        liveSteps: List<AgentExecutionStep> = emptyList(),
        streamingText: String? = null,
        isStreaming: Boolean = false,
        toolStatus: String? = null,
        error: String? = null,
        greeting: String? = null
    ) = buildCompanionChatEntries(
        timeline = timeline,
        liveSteps = liveSteps,
        streamingText = streamingText,
        isStreaming = isStreaming,
        toolStatus = toolStatus,
        thinkingLabel = "小助手正在思考…",
        error = error,
        greeting = greeting,
        embeddingProgress = null,
        sceneQuote = "第一章"
    )

    @Test
    fun `场景头永远是第一项`() {
        val result = entries()
        assertTrue(result.first() is ChatEntry.Scene)
        assertEquals(1, result.size)
    }

    @Test
    fun `流式期间的条目顺序是 历史-工具-正文-状态`() {
        val timeline = buildCompanionTimeline(
            listOf(message(1, "user"), message(2, "assistant"))
        )
        val result = entries(
            timeline = timeline,
            liveSteps = listOf(
                AgentExecutionStep("c1", "search_book", "检索书中原文", AgentStepState.RUNNING)
            ),
            streamingText = "正在生成的正文",
            isStreaming = true
        )
        val kinds = result.map { it::class.simpleName }
        assertEquals(
            listOf("Scene", "History", "History", "LiveTools", "LiveText", "LiveStatus"),
            kinds
        )
    }

    @Test
    fun `空白流式文本不产生正文项但保留状态行`() {
        val result = entries(streamingText = "", isStreaming = true)
        assertTrue(result.none { it is ChatEntry.LiveText })
        assertEquals("小助手正在思考…", (result.last() as ChatEntry.LiveStatus).text)
    }

    @Test
    fun `工具状态优先于思考占位`() {
        val result = entries(isStreaming = true, toolStatus = "正在检索书中原文…")
        assertEquals("正在检索书中原文…", (result.last() as ChatEntry.LiveStatus).text)
    }

    @Test
    fun `错误行钉在最末尾`() {
        val result = entries(streamingText = "残段", error = "请求失败")
        assertTrue(result.last() is ChatEntry.LiveError)
        assertTrue(result[result.size - 2] is ChatEntry.LiveText)
    }

    @Test
    fun `开场白只在时间线为空时出现`() {
        assertTrue(entries(greeting = "你好呀").any { it is ChatEntry.Greeting })
        val timeline = buildCompanionTimeline(listOf(message(1, "user")))
        assertFalse(entries(timeline = timeline, greeting = "你好呀").any { it is ChatEntry.Greeting })
    }

    @Test
    fun `key 稳定且互不冲突`() {
        val timeline = buildCompanionTimeline(
            listOf(message(1, "user"), message(2, "assistant"))
        )
        val result = entries(
            timeline = timeline,
            streamingText = "s",
            isStreaming = true,
            error = "e",
            greeting = null
        )
        val keys = result.map(ChatEntry::key)
        assertEquals(keys.size, keys.toSet().size)
        // 同输入重建后 key 完全一致（LazyColumn 依赖它保持滚动锚点）
        assertEquals(
            keys,
            entries(
                timeline = timeline,
                streamingText = "s2",
                isStreaming = true,
                error = "e",
                greeting = null
            ).map(ChatEntry::key)
        )
    }

    @Test
    fun `贴底判定 - 空列表视为在底部`() {
        assertTrue(isChatListAtBottom(0, -1, 0, 0))
    }

    @Test
    fun `贴底判定 - 最后一项在容差内露出才算在底部`() {
        assertTrue(isChatListAtBottom(10, 9, 1000, 1000))
        assertTrue(isChatListAtBottom(10, 9, 1000 + CHAT_BOTTOM_SLACK_PX, 1000))
        assertFalse(isChatListAtBottom(10, 9, 1000 + CHAT_BOTTOM_SLACK_PX + 1, 1000))
        assertFalse(isChatListAtBottom(10, 8, 1000, 1000))
    }
}
