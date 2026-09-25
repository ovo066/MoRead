package com.mozhi.reader.ai.audiobook

import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import org.junit.Assert.*
import org.junit.Test

class AudiobookAttributionReliabilityTest {
    private fun assignment(confidence: Float?, name: String? = "苏晚") =
        ParsedAudiobookAssignment(1, name, confidence, null, null, null)
    @Test fun missingLowOrInvalidConfidenceDoesNotCastACharacter() {
        listOf(null, 0.2f, 0.64f, Float.NaN).forEach {
            assertFalse(isReliableAudiobookAssignment(assignment(it)))
        }
        assertFalse(isReliableAudiobookAssignment(assignment(0.99f, null)))
        assertTrue(isReliableAudiobookAssignment(assignment(0.9f)))
    }
    @Test fun ambiguousAliasesAndNameFragmentsDoNotChooseTheFirstCharacter() {
        val roles = listOf(
            AudiobookRoleEntity(id = 1, bookId = 1, name = "苏晚", aliases = "苏小姐", kind = "CHARACTER"),
            AudiobookRoleEntity(id = 2, bookId = 1, name = "苏晚晴", aliases = "苏小姐", kind = "CHARACTER")
        )
        assertNull(resolveAudiobookRole(roles, "苏小姐"))
        assertNull(resolveAudiobookRole(roles, "晚晴"))
        assertEquals(1L, resolveAudiobookRole(roles, "苏晚（女主）")?.id)
    }
    @Test fun dialogueDenseShortChaptersRespectBatchLimits() {
        val body = (1..55).joinToString("\n") { "苏晚说：“第 "+it+" 句话。”" }
        val segments = DialogueRuleSegmenter.segment(body)
        val targets = segments.indices.filter { segments[it].kind == AudiobookSegmentKind.DIALOGUE }.toSet()
        val batches = buildAudiobookAttributionBatches(body, segments, targets, emptyMap())
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.targetIndices.size <= 18 })
        assertEquals(targets, batches.flatMap { it.targetIndices }.toSet())
        assertEquals(targets.size, batches.sumOf { it.targetIndices.size })
    }
    @Test fun actionFragmentsCannotBecomeLockedCharacters() {
        val role = AudiobookRoleEntity(id = 1, bookId = 1, name = "苏晚", kind = "CHARACTER")
        assertNull(resolveExplicitAudiobookRole(listOf(role), "苏晚没有回"))
        assertEquals(role, resolveExplicitAudiobookRole(listOf(role), "苏晚"))
        listOf("说", "问", "开口", "他又", "苏晚没有回", "这个").forEach { assertFalse(isPlausibleRuleSpeaker(it)) }
        assertTrue(isPlausibleRuleSpeaker("苏晚"))
    }
}
