package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionChatListTest {

    private fun message(
        id: Long,
        role: String,
        content: String = "内容",
        reasoning: String? = null,
        toolCallsJson: String? = null
    ) = MessageEntity(
        id = id,
        conversationId = 1L,
        role = role,
        content = content,
        reasoningContent = reasoning,
        toolCallsJson = toolCallsJson,
        createdAt = id
    )

    private fun entries(
        timeline: List<CompanionTimelineItem> = emptyList(),
        liveSteps: List<AgentExecutionStep> = emptyList(),
        liveReasoning: String? = null,
        streamingText: String? = null,
        isStreaming: Boolean = false,
        toolStatus: String? = null,
        error: String? = null,
        greeting: String? = null,
        multiBubble: Boolean = false,
        lastAssistantMessageId: Long? = null
    ) = buildCompanionChatEntries(
        timeline = timeline,
        liveSteps = liveSteps,
        liveReasoning = liveReasoning,
        streamingText = streamingText,
        isStreaming = isStreaming,
        toolStatus = toolStatus,
        thinkingLabel = "小助手正在思考…",
        error = error,
        greeting = greeting,
        embeddingProgress = null,
        sceneQuote = "第一章",
        multiBubble = multiBubble,
        lastAssistantMessageId = lastAssistantMessageId
    )

    @Test
    fun `场景头永远是第一项`() {
        val result = entries()
        assertTrue(result.first() is ChatEntry.Scene)
        assertEquals(1, result.size)
    }

    @Test
    fun `流式期间的条目顺序是 历史-过程-正文-状态`() {
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
            listOf("Scene", "Bubble", "Bubble", "Process", "Bubble", "Status"),
            kinds
        )
    }

    @Test
    fun `思维链没有工具调用时也会产生过程卡`() {
        val timeline = buildCompanionTimeline(
            listOf(message(1, "user"), message(2, "assistant", reasoning = "先看看进度"))
        )
        val process = timeline.filterIsInstance<CompanionTimelineItem.Process>().single()
        assertEquals("先看看进度", process.reasoning)
        assertTrue(process.steps.isEmpty())
        // 过程在它所属消息的气泡之前：先看到想了什么，再看到答案。
        assertTrue(timeline.indexOf(process) < timeline.indexOfLast { it is CompanionTimelineItem.Bubble })
    }

    @Test
    fun `没有思维链也没有工具调用时不产生过程卡`() {
        val timeline = buildCompanionTimeline(listOf(message(1, "assistant")))
        assertTrue(timeline.none { it is CompanionTimelineItem.Process })
        assertFalse(entries(timeline = timeline).any { it is ChatEntry.Process })
    }

    @Test
    fun `空白流式文本不产生正文项但保留状态行`() {
        val result = entries(streamingText = "", isStreaming = true)
        assertTrue(result.none { it is ChatEntry.Bubble })
        assertEquals("小助手正在思考…", (result.last() as ChatEntry.Status).text)
    }

    @Test
    fun `落库回复与流式快照相同时只显示历史气泡`() {
        val reply = "〔原文 第2章〕「这是可以跳转的原文引用」"
        val timeline = buildCompanionTimeline(
            listOf(message(1, "user"), message(2, "assistant", reply))
        )

        val result = entries(
            timeline = timeline,
            streamingText = reply,
            isStreaming = true
        )

        assertEquals(2, result.count { it is ChatEntry.Bubble })
        assertTrue(result.filterIsInstance<ChatEntry.Bubble>().none { it.streaming })
    }

    @Test
    fun `落库完整回复先到时隐藏尚未追平的多气泡快照`() {
        val timeline = buildCompanionTimeline(
            listOf(message(1, "user", "继续"), message(2, "assistant", "111。\n222。"))
        )

        val bubbles = entries(
            timeline = timeline,
            streamingText = "111。\n22",
            isStreaming = true,
            multiBubble = true
        ).filterIsInstance<ChatEntry.Bubble>()

        assertEquals(listOf("继续", "111。", "222。"), bubbles.map { it.part.text })
        assertTrue(bubbles.none { it.streaming })
    }

    @Test
    fun `下一条用户消息阻止旧回复吞掉新一轮相同开头`() {
        val timeline = buildCompanionTimeline(
            listOf(
                message(1, "assistant", "好的，我继续说。"),
                message(2, "user", "继续")
            )
        )

        val bubbles = entries(
            timeline = timeline,
            streamingText = "好的",
            isStreaming = true,
            multiBubble = true
        ).filterIsInstance<ChatEntry.Bubble>()

        assertEquals("好的", bubbles.last().part.text)
        assertTrue(bubbles.last().streaming)
    }

    @Test
    fun `工具状态优先于思考占位`() {
        val result = entries(isStreaming = true, toolStatus = "正在检索书中原文…")
        assertEquals("正在检索书中原文…", (result.last() as ChatEntry.Status).text)
    }

    @Test
    fun `错误行钉在最末尾`() {
        val result = entries(streamingText = "残段", error = "请求失败")
        assertTrue(result.last() is ChatEntry.ErrorLine)
        assertTrue(result[result.size - 2] is ChatEntry.Bubble)
    }

    @Test
    fun `开场白只在时间线为空时出现`() {
        assertTrue(entries(greeting = "你好呀").any { it is ChatEntry.Bubble })
        val timeline = buildCompanionTimeline(listOf(message(1, "user")))
        val withHistory = entries(timeline = timeline, greeting = "你好呀")
        assertTrue(withHistory.filterIsInstance<ChatEntry.Bubble>().none { it.message == null })
    }

    // ---- 多气泡与气泡组 ----

    @Test
    fun `多气泡把一条回复拆成多个气泡`() {
        val timeline = buildCompanionTimeline(
            listOf(message(2, "assistant", "第一句\n第二句\n第三句"))
        )
        val single = entries(timeline = timeline, multiBubble = false)
            .filterIsInstance<ChatEntry.Bubble>()
        assertEquals(1, single.size)

        val split = entries(timeline = timeline, multiBubble = true)
            .filterIsInstance<ChatEntry.Bubble>()
        assertEquals(3, split.size)
        assertEquals(listOf("第一句", "第二句", "第三句"), split.map { it.part.text })
    }

    @Test
    fun `用户消息不参与多气泡拆分`() {
        val timeline = buildCompanionTimeline(listOf(message(1, "user", "我\n换\n行")))
        val bubbles = entries(timeline = timeline, multiBubble = true)
            .filterIsInstance<ChatEntry.Bubble>()
        assertEquals(1, bubbles.size)
        assertEquals("我\n换\n行", bubbles.single().part.text)
    }

    @Test
    fun `气泡组只有组首带头像 组尾带尖角与时间`() {
        val timeline = buildCompanionTimeline(
            listOf(message(2, "assistant", "甲\n乙\n丙"))
        )
        val bubbles = entries(timeline = timeline, multiBubble = true)
            .filterIsInstance<ChatEntry.Bubble>()
        assertEquals(listOf(true, false, false), bubbles.map { it.showAvatar })
        assertEquals(listOf(false, false, true), bubbles.map { it.isTail })
        assertNull(bubbles[0].timestamp)
        assertNull(bubbles[1].timestamp)
        assertEquals(2L, bubbles[2].timestamp)
    }

    @Test
    fun `换方向即断组 两边各自成组`() {
        val timeline = buildCompanionTimeline(
            listOf(message(1, "user", "问"), message(2, "assistant", "甲\n乙"))
        )
        val bubbles = entries(timeline = timeline, multiBubble = true)
            .filterIsInstance<ChatEntry.Bubble>()
        assertEquals(3, bubbles.size)
        // 用户那条自己一组，所以它既是组首也是组尾。
        assertTrue(bubbles[0].isTail && bubbles[0].showAvatar)
        assertTrue(bubbles[1].showAvatar && !bubbles[1].isTail)
        assertTrue(bubbles[2].isTail && !bubbles[2].showAvatar)
    }

    @Test
    fun `重新生成只挂在最后一条 AI 消息的最后一个气泡上`() {
        val timeline = buildCompanionTimeline(
            listOf(message(2, "assistant", "甲\n乙"), message(4, "assistant", "丙\n丁"))
        )
        val bubbles = entries(
            timeline = timeline,
            multiBubble = true,
            lastAssistantMessageId = 4L
        ).filterIsInstance<ChatEntry.Bubble>()
        assertEquals(listOf(false, false, false, true), bubbles.map { it.canReroll })
    }

    @Test
    fun `关闭多气泡时流式换行始终保持一个气泡`() {
        val before = entries(
            streamingText = "第一段",
            isStreaming = true,
            multiBubble = false
        ).filterIsInstance<ChatEntry.Bubble>()
        val after = entries(
            streamingText = "第一段\n第二段\n第三段",
            isStreaming = true,
            multiBubble = false
        ).filterIsInstance<ChatEntry.Bubble>()

        assertEquals(1, before.size)
        assertEquals(1, after.size)
        assertEquals(before.single().key, after.single().key)
        assertEquals("第一段\n第二段\n第三段", after.single().part.text)
        assertTrue(after.single().streaming)
        assertTrue(after.single().isTail)

        val committed = buildCompanionChatEntries(
            timeline = buildCompanionTimeline(
                listOf(message(9, "assistant", "第一段\n第二段\n第三段"))
            ),
            liveSteps = emptyList(),
            liveReasoning = null,
            streamingText = null,
            isStreaming = false,
            toolStatus = null,
            thinkingLabel = "小助手正在思考…",
            error = null,
            greeting = null,
            embeddingProgress = null,
            sceneQuote = "第一章",
            multiBubble = false,
            liveEntryId = "live",
            messageKeyAliases = mapOf(9L to "live")
        ).filterIsInstance<ChatEntry.Bubble>()
        assertEquals(1, committed.size)
        assertEquals(after.single().key, committed.single().key)
    }

    @Test
    fun `流式期间只有尾段是流式气泡`() {
        val result = entries(
            streamingText = "已经落定的一行\n正在长的这行",
            isStreaming = true,
            multiBubble = true
        ).filterIsInstance<ChatEntry.Bubble>()
        assertEquals(2, result.size)
        assertFalse(result[0].streaming)
        assertTrue(result[1].streaming)
        assertEquals("正在长的这行", result[1].part.text)
    }

    @Test
    fun `流式换行后已有气泡 key 与形状保持稳定`() {
        val before = entries(
            streamingText = "第一行",
            isStreaming = true,
            multiBubble = true
        ).filterIsInstance<ChatEntry.Bubble>()
        val after = entries(
            streamingText = "第一行\n第二行",
            isStreaming = true,
            multiBubble = true
        ).filterIsInstance<ChatEntry.Bubble>()

        assertEquals(before.single().key, after.first().key)
        assertTrue(after.none { it.isTail })
        assertTrue(after.none { it.timestamp != null })
    }

    @Test
    fun `流式回复落库后沿用同一组 LazyColumn key`() {
        val live = entries(
            streamingText = "第一行\n第二行",
            isStreaming = true,
            multiBubble = true,
            lastAssistantMessageId = null
        ).filterIsInstance<ChatEntry.Bubble>().map(ChatEntry::key)

        val timeline = buildCompanionTimeline(
            listOf(message(9, "assistant", "第一行\n第二行"))
        )
        val committed = buildCompanionChatEntries(
            timeline = timeline,
            liveSteps = emptyList(),
            liveReasoning = null,
            streamingText = null,
            isStreaming = false,
            toolStatus = null,
            thinkingLabel = "小助手正在思考…",
            error = null,
            greeting = null,
            embeddingProgress = null,
            sceneQuote = "第一章",
            multiBubble = true,
            liveEntryId = "live",
            messageKeyAliases = mapOf(9L to "live")
        ).filterIsInstance<ChatEntry.Bubble>().map(ChatEntry::key)

        assertEquals(live, committed)
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
    fun `突发流式文本会分多帧平滑吐出`() {
        val target = "一".repeat(80)
        val firstFrame = nextStreamingFrame("", target)
        val secondFrame = nextStreamingFrame(firstFrame, target)

        assertEquals(10, firstFrame.length)
        assertTrue(secondFrame.length > firstFrame.length)
        assertTrue(secondFrame.length < target.length)
    }

    @Test
    fun `流式目标回退时直接重置快照`() {
        assertEquals("新回复", nextStreamingFrame("旧回复内容", "新回复"))
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
