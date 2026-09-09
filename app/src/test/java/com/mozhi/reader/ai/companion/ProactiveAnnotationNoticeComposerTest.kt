package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.datastore.CompanionAutonomySettings
import com.mozhi.reader.core.datastore.ProactiveAnnotationNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationNoticeComposerTest {
    private val persona = AnnotationReactionPersona("知墨", "好奇、温和", "像朋友一样轻声说话")

    @Test fun builtinKeepsCountWithoutCallingModel() = runTest {
        assertEquals("知墨 写了 3 条段评", composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.BUILT_IN) { error("not called") })
    }

    @Test fun fastModeUsesRoleSpeechNotCompletionStatistics() = runTest {
        val result = composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { role ->
            assertEquals(persona, role)
            "“还挺有意思，你怎么看？”"
        }
        assertEquals("还挺有意思，你怎么看？", result)
    }

    @Test fun failuresAndTimeoutKeepConversationalFallback() = runTest {
        val fallback = fallbackAnnotationReaction(0)
        assertEquals(fallback, composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { error("network") })
        assertEquals(fallback, composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { delay(5_000); "迟到" })
        assertEquals(fallback, composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { "长".repeat(25) })
        assertFalse(fallback.contains("段评"))
        assertFalse(fallback.contains("3"))
    }

    @Test fun statisticalRepliesAndMalformedOutputAreNotShown() = runTest {
        listOf("知墨 写了 3 条段评", "批注生成完成", "我写好了三条", "知墨：挺有意思", "你好\n你怎么看", "").forEach { bad ->
            assertNull(bad, cleanAnnotationReaction(bad))
            assertEquals(fallbackAnnotationReaction(0), composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { bad })
        }
    }

    @Test fun promptUsesOnlyBoundedRoleMetadataNotBookOrTaskData() {
        val messages = annotationReactionMessages(persona)
        val user = messages.last().content
        assertTrue(user.contains(persona.personality))
        assertTrue(user.contains(persona.speakingStyle))
        assertFalse(user.contains("完成条数"))
        assertFalse(user.contains("段评"))
        assertTrue(messages.first().content.contains("不要预告、暗示后文"))
        assertTrue(annotationReactionMessages(persona.copy(personality = "长".repeat(10_000))).last().content.length < 1_000)
    }

    @Test fun fallbackVariesWithoutRequestingAnotherModelCall() {
        val choices = (0..3).map(::fallbackAnnotationReaction)
        assertEquals(4, choices.distinct().size)
        choices.forEach { assertEquals(it, cleanAnnotationReaction(it)) }
        assertEquals(choices[3], fallbackAnnotationReaction(-1))
    }

    @Test fun cancellationIsNotConvertedToAVisibleNotice() = runTest {
        try {
            composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { throw CancellationException("left reader") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun masterModeForegroundAndCountAllGateNotice() {
        val enabled = CompanionAutonomySettings(proactiveAnnotationsEnabled = true)
        assertTrue(annotationNoticeEligible(enabled, 1, true))
        assertFalse(annotationNoticeEligible(enabled, 0, true))
        assertFalse(annotationNoticeEligible(enabled, 1, false))
        assertFalse(annotationNoticeEligible(CompanionAutonomySettings(), 1, true))
        assertFalse(annotationNoticeEligible(enabled.copy(annotationNotice = ProactiveAnnotationNotice.OFF), 1, true))
    }
}
