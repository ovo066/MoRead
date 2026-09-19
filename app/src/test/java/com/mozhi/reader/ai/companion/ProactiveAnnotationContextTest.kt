package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.knowledge.ChapterKnowledge
import com.mozhi.reader.ai.knowledge.ChapterKnowledgeCodec
import com.mozhi.reader.ai.knowledge.KnowledgeFact
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import com.mozhi.reader.core.datastore.ProactiveAnnotationContextSettings
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationContextTest {
    private val promise = "顾衡在雪岭立誓，要将玉佩交还给沈岚，绝不食言。"
    private val target = "顾衡取出玉佩，想起在雪岭对沈岚许下的承诺，终于决定兑现誓言。".repeat(3)

    @Test fun chapterOneHundredCanRecallChapterThreeInsteadOfOnlyRecentChapters() = runTest {
        val candidates = (0..98).map { index ->
            val text = if (index == 2) promise else "当地人忙着耕种庄稼，讨论今日天气。"
            RetrievalCandidate(1, index, 0, text, 0, text.length)
        }
        val prepared = PreparedAnnotationContext("r", candidates,
            listOf(AnnotationOutline(2, "顾衡对沈岚立下玉佩与雪岭的承诺。")), mapOf(2 to "雪岭之誓"))
        val result = prepared.forParagraph(target, ProactiveAnnotationParagraph(0, target.length), 8_000)
        assertTrue(result.background.contains(promise))
        assertTrue(result.background.contains("第 3 章"))
        assertTrue(result.background.contains("梗概"))
        assertFalse(result.background.contains("第 99 章"))
        assertEquals(target, result.target)
    }

    @Test fun everyBudgetKeepsTargetAndClipsCurrentChapterAtTargetEnd() = runTest {
        val before = "很早的本章正文😀".repeat(8_000)
        val after = "未来真相不得泄露".repeat(300)
        val body = before + target + after
        val paragraph = ProactiveAnnotationParagraph(before.length, before.length + target.length)
        val prepared = PreparedAnnotationContext("r", listOf(RetrievalCandidate(1, 2, 0, promise, 0, promise.length)))
        for (budget in listOf(4_000, 8_000, 16_000, 32_000, 64_000)) {
            val result = prepared.forParagraph(body, paragraph, budget)
            assertTrue("budget=$budget actual=" + result.chars, result.chars <= budget)
            assertEquals(target, result.target)
            assertTrue(result.prefix.endsWith(target))
            assertFalse(result.prefix.first().isLowSurrogate())
            assertFalse((result.prefix + result.background).contains("未来真相"))
            assertTrue(result.background.contains(promise))
        }
    }

    @Test fun minimumBudgetStillRetainsAnEntireMaximumLengthTarget() = runTest {
        val text = "😀".repeat(ProactiveAnnotationParagraphs.MAX_TARGET_CHARS / 2)
        val result = PreparedAnnotationContext("r").forParagraph(text, ProactiveAnnotationParagraph(0, text.length), -1)
        assertEquals(text, result.target)
        assertEquals(text, result.prefix)
        assertTrue(result.chars <= ProactiveAnnotationContextSettings.MIN_CHARS)
    }

    @Test fun incompleteCorpusIsDisclosedAndNeverTreatedAsProofOfAbsence() = runTest {
        val result = PreparedAnnotationContext("r", partial = true)
            .forParagraph(target, ProactiveAnnotationParagraph(0, target.length), 4_000)
        assertTrue(result.background.contains("只覆盖部分正文"))
    }

    @Test fun summariesMustMatchTheirSourceAndStayBeforeTheSpoilerBoundary() {
        val entry = entry(2, promise)
        val scope = ReadingScope.upto(98, Int.MAX_VALUE)
        assertNotNull(validatedAnnotationOutline(entry, scope, "r", promise))
        assertNull(validatedAnnotationOutline(entry.copy(sourceRevision = "old"), scope, "r", promise))
        assertNull(validatedAnnotationOutline(entry, scope, "r", promise.replace("顾衡", "别人")))
        assertNull(validatedAnnotationOutline(entry, scope, "r", null))
        assertNull(validatedAnnotationOutline(entry.copy(chapterIndex = 99), scope, "r", promise))
        assertNull(validatedAnnotationOutline(entry, ReadingScope.upto(2, 5), "r", promise))
    }

    private fun entry(chapter: Int, text: String) = ChapterKnowledgeEntity(
        bookId = 1, chapterIndex = chapter, sourceRevision = "r", sourceEnd = text.length,
        sourceHash = ChapterKnowledgeCodec.hash(text), modelKey = "m", modelLabel = "m",
        promptVersion = ChapterKnowledgeCodec.PROMPT_VERSION,
        contentJson = ChapterKnowledgeCodec.encode(ChapterKnowledge(listOf(KnowledgeFact("顾衡的承诺", text, 0, text.length)))), createdAt = 0
    )
}
