package com.mozhi.reader.ai.audiobook

data class AudiobookCostEstimate(
    val totalChars: Int,
    val segmentCount: Int,
    val aiSegmentCount: Int,
    val systemSegmentCount: Int,
    val estimatedCost: Double
)

object AudiobookCostEstimator {
    fun estimate(
        characterCounts: List<Int>,
        engines: List<String>,
        pricePerTenThousandChars: Double
    ): AudiobookCostEstimate {
        val size = minOf(characterCounts.size, engines.size)
        val aiChars = (0 until size)
            .filter { engines[it].equals("AI", ignoreCase = true) }
            .sumOf { characterCounts[it].coerceAtLeast(0) }
        val aiSegments = (0 until size).count { engines[it].equals("AI", ignoreCase = true) }
        return AudiobookCostEstimate(
            totalChars = characterCounts.take(size).sumOf { it.coerceAtLeast(0) },
            segmentCount = size,
            aiSegmentCount = aiSegments,
            systemSegmentCount = size - aiSegments,
            estimatedCost = aiChars / 10_000.0 * pricePerTenThousandChars.coerceAtLeast(0.0)
        )
    }
}
