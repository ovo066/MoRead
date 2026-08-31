package com.mozhi.reader.core.retrieval

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.ln

/** One retrievable chunk. Offsets are UTF-16 and [endCharOffset] is exclusive. */
data class RetrievalCandidate(
    val bookId: Long,
    val chapterIndex: Int,
    val chunkIndex: Int,
    val text: String,
    val startCharOffset: Int = -1,
    val endCharOffset: Int = -1,
    val vectorDistance: Double? = null,
    val lexicalScore: Double? = null
) {
    val key: String get() = "$bookId:$chapterIndex:$chunkIndex"
}

data class RetrievalRequest(
    val bookId: Long,
    val query: String,
    val scope: ReadingScope,
    val topK: Int = 8,
    val recallDepth: Int = 60,
    val maxVectorDistance: Double = 0.80,
    val neighborRadius: Int = 1
)

data class RetrievalResult(
    val hits: List<RetrievalCandidate>,
    val vectorFailure: Throwable? = null,
    val lexicalFailure: Throwable? = null,
    val rerankFailure: Throwable? = null
)

fun interface RetrievalRecall {
    suspend fun recall(request: RetrievalRequest): List<RetrievalCandidate>
}

fun interface ChunkReranker {
    suspend fun rerank(query: String, candidates: List<RetrievalCandidate>): List<RetrievalCandidate>
}

fun interface NeighborExpander {
    suspend fun expand(
        hits: List<RetrievalCandidate>,
        radius: Int,
        scope: ReadingScope
    ): List<RetrievalCandidate>
}

/**
 * Hybrid retrieval in a fixed order: parallel recall -> RRF -> distance gate -> scope gate ->
 * optional rerank -> top K -> neighbour expansion -> source order. Scope is deliberately before
 * reranking so excluded text can neither leak to a remote reranker nor occupy its top-N budget.
 */
class RetrievalPipeline(
    private val vectorRecall: RetrievalRecall,
    private val lexicalRecall: RetrievalRecall,
    private val reranker: ChunkReranker? = null,
    private val expander: NeighborExpander = NeighborExpander { hits, _, _ -> hits }
) {
    suspend fun retrieve(request: RetrievalRequest): RetrievalResult = coroutineScope {
        val vectorDeferred = async { captureRecall { vectorRecall.recall(request) } }
        val lexicalDeferred = async { captureRecall { lexicalRecall.recall(request) } }
        val vector = vectorDeferred.await()
        val lexical = lexicalDeferred.await()

        val fused = RrfFusion.fuse(
            vector.getOrDefault(emptyList()),
            lexical.getOrDefault(emptyList())
        ).asSequence()
            .filter { candidate ->
                candidate.vectorDistance == null ||
                    candidate.vectorDistance <= request.maxVectorDistance ||
                    candidate.lexicalScore != null
            }
            .filter { candidate ->
                request.scope.allowsChunk(
                    candidate.chapterIndex,
                    candidate.startCharOffset,
                    candidate.endCharOffset
                )
            }
            .toList()

        var rerankFailure: Throwable? = null
        val ranked = if (reranker == null || fused.isEmpty()) {
            fused
        } else {
            try {
                reranker.rerank(request.query, fused)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                rerankFailure = error
                fused
            }
        }
        val selected = ranked.take(request.topK.coerceAtLeast(1))
        val expanded = expander.expand(
            selected,
            request.neighborRadius.coerceAtLeast(0),
            request.scope
        ).filter { request.scope.allowsChunk(it.chapterIndex, it.startCharOffset, it.endCharOffset) }
            .distinctBy(RetrievalCandidate::key)
            .sortedWith(compareBy(RetrievalCandidate::chapterIndex, RetrievalCandidate::chunkIndex))

        RetrievalResult(
            hits = expanded,
            vectorFailure = vector.exceptionOrNull(),
            lexicalFailure = lexical.exceptionOrNull(),
            rerankFailure = rerankFailure
        )
    }

    private suspend fun captureRecall(block: suspend () -> List<RetrievalCandidate>): Result<List<RetrievalCandidate>> =
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        }
}

object RrfFusion {
    private const val K = 60.0

    fun fuse(
        vector: List<RetrievalCandidate>,
        lexical: List<RetrievalCandidate>
    ): List<RetrievalCandidate> {
        data class Acc(var candidate: RetrievalCandidate, var score: Double = 0.0)
        val scores = LinkedHashMap<String, Acc>()
        fun add(items: List<RetrievalCandidate>) {
            items.forEachIndexed { index, item ->
                val acc = scores.getOrPut(item.key) { Acc(item) }
                acc.score += 1.0 / (K + index + 1)
                acc.candidate = merge(acc.candidate, item)
            }
        }
        add(vector)
        add(lexical)
        return scores.values.sortedByDescending(Acc::score).map(Acc::candidate)
    }

    private fun merge(a: RetrievalCandidate, b: RetrievalCandidate): RetrievalCandidate = a.copy(
        text = a.text.ifBlank { b.text },
        startCharOffset = listOf(a.startCharOffset, b.startCharOffset).firstOrNull { it >= 0 } ?: -1,
        endCharOffset = maxOf(a.endCharOffset, b.endCharOffset),
        vectorDistance = a.vectorDistance ?: b.vectorDistance,
        lexicalScore = a.lexicalScore ?: b.lexicalScore
    )
}

/** Small, deterministic BM25 implementation used over the persisted chunk corpus. */
object Bm25LexicalRecall {
    fun rank(
        candidates: List<RetrievalCandidate>,
        query: String,
        limit: Int
    ): List<RetrievalCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return emptyList()
        val docs = candidates.map { tokenize(it.text) }
        val averageLength = docs.map(List<String>::size).average().coerceAtLeast(1.0)
        val documentFrequency = queryTerms.associateWith { term -> docs.count { term in it } }
        return candidates.indices.mapNotNull { index ->
            val tokens = docs[index]
            if (tokens.isEmpty()) return@mapNotNull null
            val frequencies = tokens.groupingBy { it }.eachCount()
            var score = 0.0
            queryTerms.forEach { term ->
                val frequency = frequencies[term] ?: 0
                if (frequency == 0) return@forEach
                val df = documentFrequency.getValue(term)
                val idf = ln(1.0 + (candidates.size - df + 0.5) / (df + 0.5))
                val normalized = frequency * (K1 + 1.0) /
                    (frequency + K1 * (1.0 - B + B * tokens.size / averageLength))
                score += idf * normalized
            }
            if (score > 0.0) candidates[index].copy(lexicalScore = score) else null
        }.sortedWith(
            compareByDescending<RetrievalCandidate> { it.lexicalScore ?: 0.0 }
                .thenBy(RetrievalCandidate::chapterIndex)
                .thenBy(RetrievalCandidate::chunkIndex)
        ).take(limit.coerceAtLeast(1))
    }

    internal fun tokenize(text: String): List<String> {
        val normalized = text.lowercase()
        val terms = mutableListOf<String>()
        Regex("[a-z0-9]+", RegexOption.IGNORE_CASE).findAll(normalized).forEach { match ->
            terms += match.value
        }
        val hanRuns = Regex("[\\p{IsHan}]+").findAll(normalized).map { it.value }
        hanRuns.forEach { run ->
            if (run.length == 1) terms += run
            for (size in 2..minOf(4, run.length)) {
                for (start in 0..run.length - size) terms += run.substring(start, start + size)
            }
        }
        return terms
    }

    private const val K1 = 1.2
    private const val B = 0.75
}
