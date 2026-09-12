package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.retrieval.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConfiguredChunkRerankerTest {
    @Test fun boundedPayloadKeepsFullEvidenceAndTheUnrankedTail() = runTest {
        val candidates = (0..29).map { index -> RetrievalCandidate(1, index, 0, "原".repeat(799) + "😀" + "后".repeat(500), 0, 1301) }
        var sent = emptyList<String>()
        val ranked = rerankWithBudget("检索问题", candidates, { _, docs -> sent = docs; docs.indices.reversed().toList() })
        assertTrue(sent.size <= 24)
        assertTrue(sent.sumOf(String::length) <= 12_000)
        assertTrue(sent.all { it.length <= 800 && !it.last().isHighSurrogate() })
        assertEquals(candidates.take(sent.size).reversed() + candidates.drop(sent.size), ranked)
        assertTrue(ranked.all { it.text.length > 800 })
    }

    @Test fun ownTimeoutFallsBackButUserCancellationPropagates() = runTest {
        val candidates = listOf(hit(0), hit(1))
        val timeoutRanker = ChunkReranker { query, rows -> rerankWithBudget(query, rows, { _, _ -> awaitCancellation() }, 50) }
        val result = pipeline(candidates, timeoutRanker).retrieve(request())
        assertTrue(result.rerankFailure is IOException)
        assertEquals(candidates.map { it.key }, result.hits.map { it.key })
        val cancelled = runCatching { pipeline(candidates, ChunkReranker { _, _ -> throw CancellationException("user stopped") }).retrieve(request()) }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    @Test fun scopeGatePrecedesNetworkAndRankerCannotForgeSourceText() = runTest {
        val safe = listOf(hit(0), hit(1))
        var seen = emptyList<RetrievalCandidate>()
        val malicious = ChunkReranker { _, items -> seen = items; items.reversed().map { it.copy(text = "伪造原文") } }
        val result = pipeline(safe + hit(2), malicious).retrieve(request())
        assertEquals(safe, seen)
        assertEquals(safe.reversed().map { it.text }, result.hits.map { it.text })
        assertNull(result.rerankFailure)
        val missing = pipeline(safe, ChunkReranker { _, _ -> listOf(safe[0]) }).retrieve(request())
        assertNotNull(missing.rerankFailure)
        assertEquals(safe.map { it.key }, missing.hits.map { it.key })
    }

    private fun hit(index: Int) = RetrievalCandidate(1, index, 0, "证据$index", 0, 3, vectorDistance = 0.1)
    private fun pipeline(rows: List<RetrievalCandidate>, ranker: ChunkReranker) = RetrievalPipeline(RetrievalRecall { rows }, RetrievalRecall { emptyList() }, ranker)
    private fun request() = RetrievalRequest(1, "问题", ReadingScope.upto(1, 3), topK = 2, literalReserve = 0, neighborRadius = 0, sort = RetrievalSort.RELEVANCE)
}
