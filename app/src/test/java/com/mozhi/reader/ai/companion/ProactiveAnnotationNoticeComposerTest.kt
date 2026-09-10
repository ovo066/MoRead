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
        val reasons = mutableListOf<String>()
        assertEquals(fallback, composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL, onFallback = reasons::add) { error("network") })
        assertEquals(fallback, composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL, onFallback = reasons::add) {
            delay(ANNOTATION_REACTION_TIMEOUT_MS + 1_000); "迟到"
        })
        assertEquals(fallback, composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL, onFallback = reasons::add) { "长".repeat(25) })
        assertFalse(fallback.contains("段评"))
        assertFalse(fallback.contains("3"))
        // 每次回落都留下原因，排查「AI 选项像内置」时不用猜。
        assertEquals(3, reasons.size)
        assertTrue(reasons[0].contains("request failed"))
        assertTrue(reasons[1].contains("timeout"))
        assertTrue(reasons[2].contains("rejected output"))
    }

    @Test fun fallbackDiagnosticsNeverIncludeModelOutputOrExceptionMessages() = runTest {
        val privateOutput = "private persona details ".repeat(4)
        val privateError = "request to https://private.example/token?key=secret failed"
        val reasons = mutableListOf<String>()
        composeAnnotationNotice(persona, 1, ProactiveAnnotationNotice.FAST_MODEL, onFallback = reasons::add) { privateOutput }
        composeAnnotationNotice(persona, 1, ProactiveAnnotationNotice.FAST_MODEL, onFallback = reasons::add) {
            throw IllegalStateException(privateError)
        }

        assertEquals(listOf("rejected output (length=${privateOutput.length})", "request failed: IllegalStateException"), reasons)
        assertFalse(reasons.any { it.contains("private") || it.contains("secret") || it.contains("https://") })
    }

    @Test fun slowButHealthyModelsAreWaitedForInsteadOfFallingBack() = runTest {
        // 3 秒对多数供应商的首 token 与推理型模型都不够；时限内的正常回复必须被采用。
        val result = composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { delay(5_000); "真没想到会这样。" }
        assertEquals("真没想到会这样。", result)
        assertTrue(ANNOTATION_REACTION_TIMEOUT_MS >= 8_000L)
    }

    @Test fun statisticalRepliesAndMalformedOutputAreNotShown() = runTest {
        listOf("知墨 写了 3 条段评", "批注生成完成", "我写好了三条", "第一句：第二句：第三句", "", "长".repeat(25)).forEach { bad ->
            assertNull(bad, cleanAnnotationReaction(bad))
            assertEquals(fallbackAnnotationReaction(0), composeAnnotationNotice(persona, 3, ProactiveAnnotationNotice.FAST_MODEL) { bad })
        }
    }

    @Test fun reasoningBlocksSpeakerPrefixesAndMultilineOutputAreCleanedNotRejected() {
        // 推理型/小模型常见的「脏」输出不应整句作废——那会让 AI 弹幕永远等于内置短句。
        assertEquals("这段写得真好，你觉得呢？", cleanAnnotationReaction("<think>用户在读书，我要轻声说一句。</think>\n\n知墨：「这段写得真好，你觉得呢？」"))
        assertEquals("真没想到会这样。", cleanAnnotationReaction("```\n真没想到会这样。\n```"))
        assertEquals("你怎么看", cleanAnnotationReaction("你好\n你怎么看"))
        assertEquals("挺有意思", cleanAnnotationReaction("知墨：挺有意思"))
        assertNull(cleanAnnotationReaction("<think>没想好</think>"))
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
