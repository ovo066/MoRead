package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ChatApiClient
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ProactiveAnnotationAllowance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationServiceTest {
    private val factory = mockk<AiClientFactory>()
    private val client = mockk<ChatApiClient>()
    private val media = mockk<AiMediaGenerationService>()
    private val service = ProactiveAnnotationService(factory, media)
    private val persona = PersonaEntity(id = 2, name = "知墨", personality = "", isRoleplay = false, createdAt = 0)
    private val first = "首段原文".repeat(15)
    private val second = "末段秘密".repeat(15)
    private val body = "$first\n$second"
    private val request = ProactiveAnnotationRequest(1, 3, body, persona, 2, emptySet())
    private val permit = ProactiveAnnotationAllowance(true, maxAnnotations = 2)

    init { coEvery { factory.forRole(ModelRole.CHEAP) } returns ResolvedChatClient(client, ChatOptions(), mockk(), "mock") }

    @Test fun eachRequestUsesOnlyTargetPrefixAndStoresExactProvenance() = runTest {
        val requests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(requests), any()) } answers {
            val text = requests.last().last().content.orEmpty()
            val quote = if (second in text) second else first
            """{"quote":"$quote","note":"评论"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        val ends = mutableListOf<Int>()
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { row, end, _ -> rows += row; ends += end; true })
        assertFalse(result.failed)
        assertEquals(2, requests.size)
        assertFalse(requests.first().any { it.content.orEmpty().contains(second) })
        assertTrue(requests.last().last().content.orEmpty().endsWith(second))
        assertEquals(listOf(first.length, body.length), ends)
        assertEquals(ends, rows.map { it.sourceScopeCharOffset })
        assertTrue(rows.all { it.sourceScopeChapterIndex == 3 })
    }

    @Test fun allThreeModelSelectedStylesReachTheCommitSink() = runTest {
        val styles = listOf("HIGHLIGHT", "underline", "WAVY")
        coEvery { client.chat(any(), any()) } returnsMany styles.map { style ->
            """{"quote":"$first","note":"评论","style":"$style"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        styles.forEach {
            val result = service.generateForChapter(request.copy(candidateLimit = 1),
                { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
            assertFalse(result.failed)
        }
        assertEquals(listOf("HIGHLIGHT", "UNDERLINE", "WAVY"), rows.map { it.style })
        coVerify(exactly = 3) { client.chat(any(), any()) }
    }

    @Test fun promptExplainsSemanticStylesInsteadOfAFixedHighlightExample() = runTest {
        val messages = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(messages), any()) } returns """{"quote":"$first","note":"评论","style":"WAVY"}"""
        service.generateForChapter(request.copy(candidateLimit = 1), { permit }, { _, _ -> }, { _, _, _ -> true })
        val prompt = messages.single().first().content.orEmpty()
        assertTrue(prompt.contains("HIGHLIGHT（荧光）：金句"))
        assertTrue(prompt.contains("WAVY（波浪线）："))
        assertTrue(prompt.contains("UNDERLINE（直线）：知识点"))
        assertTrue(prompt.contains("不要随机轮换"))
        assertTrue(prompt.contains("不能据此断言未读剧情"))
        assertFalse(prompt.contains("\"style\":\"HIGHLIGHT\""))
    }

    @Test fun badQuoteAndFailedParagraphDoNotPreventNextParagraph() = runTest {
        var calls = 0
        coEvery { client.chat(any(), any()) } answers {
            if (++calls == 1) error("failed")
            """{"quote":"$second","note":"评论"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertTrue(result.failed)
        assertEquals(1, rows.size)
        assertEquals(second, rows.single().selectedText)
    }

    @Test fun quoteOutsideTargetIsRejectedEvenWhenItExistsElsewhereInChapter() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertEquals(1, rows.size)
        assertTrue(result.failed)
    }

    @Test fun successfulParagraphsAreSkippedOnRetryAndRevokedPermitStopsRequests() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$second","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        service.generateForChapter(request.copy(doneParagraphEnds = setOf(first.length)), { permit }, { _, _ -> },
            { row, _, _ -> rows += row; true })
        assertEquals(1, rows.size)
        val stopped = service.generateForChapter(request, { null }, { _, _ -> }, { _, _, _ -> error("no commit") })
        assertTrue(stopped.stopped)
        coVerify(exactly = 1) { client.chat(any(), any()) }
    }

    @Test fun mediaDisabledSkipsBothPaidCalls() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论","voice":true,"image_prompt":"配图"}"""
        service.generateForChapter(request.copy(candidateLimit = 1, persona = persona.copy(voiceId = "voice")),
            { permit }, { _, _ -> error("no media charge") }, { _, _, _ -> true })
        coVerify(exactly = 0) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { media.synthesizeSpeech(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun failedVoiceIsChargedBeforeCallAndPlainAnnotationsContinueWithinCap() = runTest {
        var voiceCharges = 0
        var calls = 0
        var commits = 0
        coEvery { client.chat(any(), any()) } answers {
            val quote = if (++calls == 1) first else second
            """{"quote":"$quote","note":"评论","voice":true}"""
        }
        coEvery { media.synthesizeSpeech(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            assertEquals(1, voiceCharges)
            error("tts unavailable")
        }
        val outcome = service.generateForChapter(request.copy(persona = persona.copy(voiceId = "voice")),
            { permit.copy(maxVoice = 1 - voiceCharges) }, { voices, _ -> voiceCharges += voices },
            { row, _, _ ->
                assertNull(com.mozhi.reader.core.library.AnnotationMedia.decode(row.mediaJson).audioPath)
                commits++; true
            })
        assertFalse(outcome.failed)
        assertEquals(2, commits)
        assertEquals(1, voiceCharges)
        coVerify(exactly = 1) { media.synthesizeSpeech(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun imageIsChargedBeforeRequestAndRollbackDeletesDetachedAsset() = runTest {
        val file = java.io.File.createTempFile("annotation-image", ".png")
        var imageCharges = 0
        try {
            coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论","image_prompt":"配图"}"""
            coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals(1, imageCharges)
                assertFalse(arg<Boolean>(7)) // detached, never published by the media service
                com.mozhi.reader.core.database.entity.IllustrationEntity(bookId = 1, prompt = "配图", imagePath = file.path, createdAt = 0)
            }
            val outcome = service.generateForChapter(request.copy(candidateLimit = 1),
                { permit.copy(maxImages = 1 - imageCharges) }, { _, images -> imageCharges += images },
                { _, _, _ -> error("transaction rolled back") })
            assertTrue(outcome.failed)
            assertEquals(1, imageCharges) // paid attempt is not refunded by rollback
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun committedImageRemainsAndImageQuotaStopsFollowingRequests() = runTest {
        val file = java.io.File.createTempFile("annotation-image", ".png")
        var imageCharges = 0
        var calls = 0
        try {
            coEvery { client.chat(any(), any()) } answers {
                val quote = if (++calls == 1) first else second
                """{"quote":"$quote","note":"评论","image_prompt":"配图"}"""
            }
            coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals(1, imageCharges)
                com.mozhi.reader.core.database.entity.IllustrationEntity(bookId = 1, prompt = "配图", imagePath = file.path, createdAt = 0)
            }
            val outcome = service.generateForChapter(request,
                { permit.copy(maxImages = 1 - imageCharges) }, { _, images -> imageCharges += images }, { _, _, _ -> true })
            assertFalse(outcome.failed)
            assertEquals(1, imageCharges)
            coVerify(exactly = 1) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertTrue(file.exists())
        } finally { file.delete() }
    }

    @Test fun lateResultIsDroppedByTransactionalSink() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论"}"""
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { _, _, _ -> false })
        assertTrue(result.stopped)
        coVerify(exactly = 1) { client.chat(any(), any()) }
    }
}
