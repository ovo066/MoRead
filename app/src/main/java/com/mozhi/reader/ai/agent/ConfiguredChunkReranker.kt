package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.retrieval.ChunkReranker
import com.mozhi.reader.core.retrieval.RetrievalCandidate
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class ConfiguredChunkReranker @Inject constructor(private val factory: dagger.Lazy<AiClientFactory>) : ChunkReranker {
    override suspend fun rerank(query: String, candidates: List<RetrievalCandidate>): List<RetrievalCandidate> {
        if (candidates.size < 2) return candidates
        val client = factory.get().rerankerOrNull() ?: return candidates
        return rerankWithBudget(query, candidates, client::rank)
    }
}

/** Only the bounded prefix is reordered. Untouched candidates retain their fused order. */
internal suspend fun rerankWithBudget(
    query: String,
    candidates: List<RetrievalCandidate>,
    rank: suspend (String, List<String>) -> List<Int>,
    timeoutMillis: Long = 5_000
): List<RetrievalCandidate> {
    val documents = mutableListOf<String>()
    var remaining = 12_000
    for (candidate in candidates.take(24)) {
        val text = candidate.text.takeForRerank(minOf(800, remaining))
        if (text.isBlank()) break
        documents += text
        remaining -= text.length
        if (remaining == 0) break
    }
    if (documents.size < 2) return candidates
    val indices = withTimeoutOrNull(timeoutMillis) { rank(query.takeForRerank(512), documents) }
        ?: throw IOException("重排超时")
    require(indices.size == documents.size && indices.toSet() == documents.indices.toSet()) { "重排候选编号无效" }
    return indices.map(candidates::get) + candidates.drop(documents.size)
}

private fun String.takeForRerank(limit: Int): String {
    var end = minOf(length, limit)
    if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
    return take(end)
}
