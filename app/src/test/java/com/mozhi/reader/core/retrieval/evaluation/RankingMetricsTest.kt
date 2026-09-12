package com.mozhi.reader.core.retrieval.evaluation

import org.junit.Assert.*
import org.junit.Test

class RankingMetricsTest {
    @Test fun perfectRankingScoresOneAndTruncationReducesRecall() {
        val judgments = mapOf("a" to 3, "b" to 2)
        assertEquals(RankingMetrics(1.0, 1.0, 1.0, 2), rankingMetrics(listOf("a", "b"), judgments))
        val topOne = rankingMetrics(listOf("a", "b"), judgments, 1)
        assertEquals(.5, topOne.recall, 1e-9)
        assertEquals(1.0, topOne.ndcg, 1e-9)
    }
    @Test fun rankDiscountAndDuplicatesCannotInflateScores() {
        val judgments = mapOf("a" to 3, "b" to 1)
        val delayed = rankingMetrics(listOf("noise", "a", "b"), judgments)
        assertEquals(1.0, delayed.recall, 1e-9)
        assertEquals(.5, delayed.mrr, 1e-9)
        assertTrue(delayed.ndcg < 1)
        val duplicates = rankingMetrics(listOf("a", "a", "b"), judgments, 2)
        assertEquals(.5, duplicates.recall, 1e-9)
        assertTrue(duplicates.ndcg < 1)
    }
    @Test fun emptyJudgmentsAreExplicitAndNeverProduceNan() {
        assertEquals(RankingMetrics(0.0, 0.0, 0.0, 0), rankingMetrics(listOf("a"), emptyMap()))
        assertThrows(IllegalArgumentException::class.java) { rankingMetrics(emptyList(), emptyMap(), 0) }
    }
}
