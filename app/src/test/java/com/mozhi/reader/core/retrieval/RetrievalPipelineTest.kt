package com.mozhi.reader.core.retrieval

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalPipelineTest {
    @Test
    fun strictScopeFiltersBeforeRerankAndAgainAfterExpansion() = runTest {
        val seenByReranker = mutableListOf<RetrievalCandidate>()
        val safe = candidate(chapter = 1, index = 0, start = 0, end = 80, distance = 0.1)
        val boundarySafe = candidate(chapter = 2, index = 0, start = 0, end = 50, distance = 0.2)
        val boundarySpoiler = candidate(chapter = 2, index = 1, start = 50, end = 120, distance = 0.01)
        val laterSpoiler = candidate(chapter = 3, index = 0, start = 0, end = 20, distance = 0.01)
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { listOf(laterSpoiler, boundarySpoiler, safe, boundarySafe) },
            lexicalRecall = RetrievalRecall { emptyList() },
            reranker = ChunkReranker { _, candidates ->
                seenByReranker += candidates
                candidates.reversed()
            },
            expander = NeighborExpander { hits, _, _ -> hits + laterSpoiler + boundarySpoiler }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(bookId = 1, query = "线索", scope = ReadingScope.upto(2, 50))
        )

        assertEquals(listOf(safe.key, boundarySafe.key), seenByReranker.map { it.key }.sorted())
        assertEquals(listOf(safe.key, boundarySafe.key), result.hits.map { it.key })
        assertFalse(result.hits.any { it.chapterIndex > 2 || it.endCharOffset > 50 && it.chapterIndex == 2 })
    }

    @Test
    fun wholeBookUsesSamePipelineWithoutFiltering() = runTest {
        val candidates = listOf(
            candidate(chapter = 2, index = 0, start = 0, end = 50, distance = 0.2),
            candidate(chapter = 90, index = 0, start = -1, end = -1, distance = 0.1)
        )
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { candidates },
            lexicalRecall = RetrievalRecall { emptyList() }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(bookId = 1, query = "结局", scope = ReadingScope.WholeBook)
        )

        assertEquals(setOf(2, 90), result.hits.map { it.chapterIndex }.toSet())
    }

    @Test
    fun distanceThresholdMayReturnEmptyInsteadOfInjectingNoise() = runTest {
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall {
                listOf(candidate(chapter = 0, index = 0, start = 0, end = 10, distance = 0.95))
            },
            lexicalRecall = RetrievalRecall { emptyList() }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(
                bookId = 1,
                query = "书中不存在的问题",
                scope = ReadingScope.WholeBook,
                maxVectorDistance = 0.8
            )
        )

        assertTrue(result.hits.isEmpty())
    }

    @Test
    fun lexicalEvidenceCanRescueAWeakVectorCandidate() = runTest {
        val weakVector = candidate(chapter = 1, index = 0, start = 0, end = 10, distance = 0.95)
        val lexical = weakVector.copy(vectorDistance = null, lexicalScore = 4.0)
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { listOf(weakVector) },
            lexicalRecall = RetrievalRecall { listOf(lexical) }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(bookId = 1, query = "专有名词", scope = ReadingScope.WholeBook)
        )

        assertEquals(listOf(weakVector.key), result.hits.map { it.key })
    }

    @Test
    fun neighborWindowsMergeOverlappingIntervalsInSourceOrder() {
        val corpus = (0..5).map { index ->
            candidate(chapter = 4, index = index, start = index * 10, end = index * 10 + 10)
        }
        val windows = com.mozhi.reader.ai.agent.expandNeighborWindows(
            hits = listOf(corpus[1], corpus[3]),
            corpus = corpus,
            radius = 1,
            scope = ReadingScope.WholeBook
        )

        assertEquals(1, windows.size)
        assertEquals((0..4).joinToString("\n") { "片段$it" }, windows.single().text)
        assertEquals(0, windows.single().startCharOffset)
        assertEquals(50, windows.single().endCharOffset)
    }

    private fun candidate(
        chapter: Int,
        index: Int,
        start: Int,
        end: Int,
        distance: Double? = null
    ) = RetrievalCandidate(
        bookId = 1,
        chapterIndex = chapter,
        chunkIndex = index,
        text = "片段$index",
        startCharOffset = start,
        endCharOffset = end,
        vectorDistance = distance
    )
}
