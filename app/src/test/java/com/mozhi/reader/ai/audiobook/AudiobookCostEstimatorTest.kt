package com.mozhi.reader.ai.audiobook

import org.junit.Assert.assertEquals
import org.junit.Test

class AudiobookCostEstimatorTest {
    @Test
    fun `只按 AI 段字数计费`() {
        val estimate = AudiobookCostEstimator.estimate(
            characterCounts = listOf(8_000, 2_000, 5_000),
            engines = listOf("SYSTEM", "AI", "AI"),
            pricePerTenThousandChars = 1.5
        )
        assertEquals(15_000, estimate.totalChars)
        assertEquals(2, estimate.aiSegmentCount)
        assertEquals(1, estimate.systemSegmentCount)
        assertEquals(1.05, estimate.estimatedCost, 0.0001)
    }
}
