package com.mozhi.reader.ai.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookAttributionPlannerTest {
    @Test
    fun wholeChapterUsesOneBatchAndStableDialogueIds() {
        val body = "苏晚说：“别怕。”\n林舟问：“你还好吗？”"
        val segments = DialogueRuleSegmenter.segment(body)
        val targets = segments.indices
            .filter { segments[it].kind == AudiobookSegmentKind.DIALOGUE }
            .toSet()
        val firstDialogue = targets.min()

        val batches = buildAudiobookAttributionBatches(
            body = body,
            segments = segments,
            targetIndices = targets,
            lockedRoleNames = mapOf(firstDialogue to "苏晚")
        )

        assertEquals(1, batches.size)
        assertEquals(targets, batches.single().targetIndices)
        assertTrue(batches.single().markedContext.contains("苏晚说："))
        targets.forEach { index ->
            assertTrue(batches.single().markedContext.contains("<dialogue id=\"$index\""))
        }
        assertTrue(batches.single().markedContext.contains("locked_speaker=\"苏晚\""))
    }

    @Test
    fun oversizedChapterSplitsByContinuousScenes() {
        val prefix = "甲".repeat(20_000)
        val middle = "乙".repeat(21_000)
        val firstDialogue = "“第一句。”"
        val secondDialogue = "“第二句。”"
        val body = prefix + firstDialogue + middle + secondDialogue
        val firstStart = prefix.length
        val secondStart = prefix.length + firstDialogue.length + middle.length
        val segments = listOf(
            DraftAudiobookSegment(0, firstStart, "旁白", AudiobookSegmentKind.NARRATION, 1f),
            DraftAudiobookSegment(firstStart, firstStart + firstDialogue.length, "对白", AudiobookSegmentKind.DIALOGUE, 0.35f),
            DraftAudiobookSegment(firstStart + firstDialogue.length, secondStart, "旁白", AudiobookSegmentKind.NARRATION, 1f),
            DraftAudiobookSegment(secondStart, body.length, "对白", AudiobookSegmentKind.DIALOGUE, 0.35f)
        )

        val batches = buildAudiobookAttributionBatches(
            body = body,
            segments = segments,
            targetIndices = setOf(1, 3),
            lockedRoleNames = emptyMap()
        )

        assertEquals(2, batches.size)
        assertEquals(setOf(1), batches[0].targetIndices)
        assertEquals(setOf(3), batches[1].targetIndices)
        assertTrue(batches[0].markedContext.contains("第一句"))
        assertTrue(batches[1].markedContext.contains("第二句"))
    }
}
