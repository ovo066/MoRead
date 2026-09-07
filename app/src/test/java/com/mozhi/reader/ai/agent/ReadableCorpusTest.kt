package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.retrieval.*
import com.mozhi.reader.core.vector.BookChunk
import com.mozhi.reader.core.vector.ChapterChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ReadableCorpusTest {
    @Test fun clippedChunkIsLexicalOnlyAndDoesNotConsumeFullEmbedding() = runTest {
        val prefix = "已读线索青铜钥匙。"
        val body = prefix + "未读凶手揭晓。".repeat(100)
        val corpus = loadReadableCorpus(1, 1, ReadingScope.upto(0, prefix.length),
            { ChapterDocument(it, "", body) }, { emptyList() })
        assertTrue(corpus.complete)
        assertEquals(prefix, corpus.candidates.single().text)
        assertTrue(corpus.indexedText.isEmpty())
        val chunk = ChapterChunker.chunkWithOffsets(body).first()
        val indexed = BookChunk().also {
            it.bookId = 1; it.chapterIndex = 0; it.chunkIndex = 0; it.text = chunk.text
            it.startCharOffset = chunk.startCharOffset; it.endCharOffset = chunk.endCharOffset
        }
        assertNull(corpus.vectorCandidate(indexed))
        assertFalse(corpus.candidates.single().key == RetrievalCandidate(1, 0, 0, chunk.text,
            chunk.startCharOffset, chunk.endCharOffset).key)
    }

    @Test fun completeCanonicalChunksCanAttachButStaleEmbeddingsCannot() = runTest {
        val body = "  第一段原文。\n\n   第二段原文。  "
        val corpus = loadReadableCorpus(1, 1, ReadingScope.WholeBook,
            { ChapterDocument(it, "", body) }, { emptyList() })
        val chunk = ChapterChunker.chunkWithOffsets(body).single()
        val indexed = BookChunk().also {
            it.bookId = 1; it.chapterIndex = 0; it.chunkIndex = 0; it.text = chunk.text
            it.startCharOffset = chunk.startCharOffset; it.endCharOffset = chunk.endCharOffset
        }
        assertEquals(body.substring(chunk.startCharOffset, chunk.endCharOffset), corpus.vectorCandidate(indexed)!!.text)
        indexed.text = "这是旧版本正文"
        assertNull(corpus.vectorCandidate(indexed))
        indexed.text = chunk.text
        indexed.startCharOffset++
        assertNull(corpus.vectorCandidate(indexed))
    }

    @Test fun bulkFailureRetriesIndividualChaptersWithoutHidingMissingCoverage() = runTest {
        val corpus = loadReadableCorpus(1, 3, ReadingScope.WholeBook,
            { if (it == 1) null else ChapterDocument(it, "", "可读正文") }, { error("bulk failure") })
        assertEquals(setOf(0, 2), corpus.bodies.keys)
        assertEquals(listOf(1), corpus.failures)
        assertFalse(corpus.complete)
    }

    @Test fun zeroLengthBoundaryNeedsNoChapterLoadAndCancellationIsNotAReadFailure() = runTest {
        val empty = loadReadableCorpus(1, 1, ReadingScope.upto(0, 0),
            { error("must not load") }, { emptyList() })
        assertTrue(empty.complete)
        assertTrue(empty.candidates.isEmpty())
        try {
            loadReadableCorpus(1, 1, ReadingScope.WholeBook,
                { throw CancellationException("stop") }, { emptyList() })
            fail("must cancel")
        } catch (_: CancellationException) { }
    }

    @Test fun independentNeighborWindowsKeepTheirOwnScoresAndHitAnchors() {
        val body = "甲乙丙丁戊己庚辛壬癸"
        val corpus = body.mapIndexed { index, c -> RetrievalCandidate(1, 0, index, c.toString(), index, index + 1) }
        val first = corpus[1].copy(lexicalScore = 100.0, vectorDistance = 0.1,
            anchors = listOf(RetrievalAnchor(1, 1, 2, 1)))
        val second = corpus[8].copy(lexicalScore = 2.0, vectorDistance = 0.5,
            anchors = listOf(RetrievalAnchor(8, 8, 9, 2)))
        val windows = expandNeighborWindows(listOf(first, second), corpus, 1, ReadingScope.WholeBook, mapOf(0 to body))
        assertEquals(listOf("甲乙丙", "辛壬癸"), windows.map { it.text })
        assertEquals(listOf(100.0, 2.0), windows.map { it.lexicalScore })
        assertEquals(listOf(0.1, 0.5), windows.map { it.vectorDistance })
        assertEquals(listOf(1, 8), windows.map { it.anchors.single().startCharOffset })
    }
}
