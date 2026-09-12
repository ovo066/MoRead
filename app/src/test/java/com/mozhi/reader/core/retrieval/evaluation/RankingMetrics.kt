package com.mozhi.reader.core.retrieval.evaluation

import kotlin.math.ln
import kotlin.math.pow

internal data class RankingMetrics(val recall: Double, val ndcg: Double, val mrr: Double, val relevantDocuments: Int)

/** Standard document-ranking metrics. Duplicate result IDs consume slots but never earn gain twice. */
internal fun rankingMetrics(rankedIds: List<String>, judgments: Map<String, Int>, k: Int = 8): RankingMetrics {
    require(k > 0)
    require(judgments.values.all { it in 0..3 })
    val relevant = judgments.filterValues { it > 0 }
    if (relevant.isEmpty()) return RankingMetrics(0.0, 0.0, 0.0, 0)
    fun gain(grade: Int, rank: Int) = (2.0.pow(grade) - 1.0) / (ln(rank + 1.0) / ln(2.0))
    val seen = hashSetOf<String>()
    var hits = 0
    var dcg = 0.0
    var first = 0
    rankedIds.take(k).forEachIndexed { index, id ->
        val grade = if (seen.add(id)) relevant[id] ?: 0 else 0
        if (grade > 0) {
            hits++
            dcg += gain(grade, index + 1)
            if (first == 0) first = index + 1
        }
    }
    val ideal = relevant.values.sortedDescending().take(k).mapIndexed { index, grade -> gain(grade, index + 1) }.sum()
    return RankingMetrics(hits.toDouble() / relevant.size, dcg / ideal, if (first == 0) 0.0 else 1.0 / first, relevant.size)
}
