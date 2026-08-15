package com.mozhi.reader.ai.memory

import com.mozhi.reader.core.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingSummaryPlannerTest {

    @Test
    fun doesNothingWhileEverythingStillFitsInTheWindow() {
        val messages = conversation(count = RollingSummaryPlanner.WINDOW_MESSAGES)

        assertNull(RollingSummaryPlanner.plan(messages, consolidatedThrough = 0, summarizedThrough = 0))
    }

    @Test
    fun summarisesOnlyWhatHasSlidOutOfTheWindow() {
        // 30 条里最后 20 条还在上下文里，前 10 条才是「滑出去」的部分。
        val messages = conversation(count = 30)

        val work = RollingSummaryPlanner.plan(messages, 0, 0)

        assertNotNull(work)
        assertEquals(10, work!!.messages.size)
        assertEquals(10L, work.throughMessageId)
        assertTrue("窗口内的消息不得进入摘要", work.messages.all { it.id <= 10 })
    }

    @Test
    fun waitsUntilEnoughMessagesAccumulate() {
        // 只滑出 3 条，不值得为它花一次 CHEAP 调用。
        val messages = conversation(count = RollingSummaryPlanner.WINDOW_MESSAGES + 3)

        assertNull(RollingSummaryPlanner.plan(messages, 0, 0))
    }

    @Test
    fun skipsMessagesAlreadyCoveredBySummary() {
        val messages = conversation(count = 40)

        val work = RollingSummaryPlanner.plan(messages, consolidatedThrough = 0, summarizedThrough = 12)

        assertNotNull(work)
        assertEquals(13L, work!!.messages.first().id)
        assertEquals(20L, work.throughMessageId)
    }

    /** 固化水位之后的消息才在上下文里；之前的已是长期记忆，不该再进提要。 */
    @Test
    fun consolidatedMessagesAreNotSummarisedAgain() {
        val messages = conversation(count = 40)

        val work = RollingSummaryPlanner.plan(messages, consolidatedThrough = 30, summarizedThrough = 0)

        assertNull("固化后只剩 10 条，全都在窗口内", work)
    }

    @Test
    fun advancesSummaryWatermarkToConsolidationWatermark() {
        assertEquals(
            30L,
            RollingSummaryPlanner.watermarkAfterConsolidation(
                consolidatedThrough = 30,
                summarizedThrough = 12
            )
        )
        assertNull(
            "摘要已经走在前面时不必回退",
            RollingSummaryPlanner.watermarkAfterConsolidation(
                consolidatedThrough = 10,
                summarizedThrough = 12
            )
        )
    }

    @Test
    fun capsOneBatchSoTheFirstSummaryDoesNotExplode() {
        val messages = conversation(count = 200)

        val work = RollingSummaryPlanner.plan(messages, 0, 0)!!

        assertEquals(RollingSummaryPlanner.MAX_BATCH, work.messages.size)
    }

    @Test
    fun transcriptLabelsSpeakersAndSkipsToolNoise() {
        val work = RollingSummaryWork(
            messages = listOf(
                message(1, "user", "帮我理一下人物关系"),
                message(2, "assistant", "好的，主要有三个人"),
                message(3, "tool", "{\"result\":\"…\"}")
            ),
            throughMessageId = 3
        )

        val transcript = RollingSummaryPlanner.transcript(work)

        assertTrue(transcript.contains("用户：帮我理一下人物关系"))
        assertTrue(transcript.contains("我：好的，主要有三个人"))
    }

    @Test
    fun blankAndToolMessagesNeverCountTowardsTheThreshold() {
        val messages = (1..40).map { index ->
            when {
                index > 20 -> message(index.toLong(), "user", "窗口内 $index")
                index % 2 == 0 -> message(index.toLong(), "tool", "工具输出")
                else -> message(index.toLong(), "user", "")
            }
        }

        assertNull("滑出窗口的 20 条全是工具与空消息，没有可摘的内容", RollingSummaryPlanner.plan(messages, 0, 0))
    }

    private fun conversation(count: Int): List<MessageEntity> = (1..count).map { index ->
        message(
            id = index.toLong(),
            role = if (index % 2 == 1) "user" else "assistant",
            content = "第 $index 条"
        )
    }

    private fun message(id: Long, role: String, content: String) = MessageEntity(
        id = id,
        conversationId = 1,
        role = role,
        content = content,
        createdAt = id
    )
}
