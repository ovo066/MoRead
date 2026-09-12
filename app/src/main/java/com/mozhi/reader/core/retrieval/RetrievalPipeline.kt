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
    val lexicalScore: Double? = null,
    val anchors: List<RetrievalAnchor> = emptyList()
) {
    // A clipped prefix must never fuse with the embedding of the complete chunk.
    val key: String get() = "$bookId:$chapterIndex:$chunkIndex:$startCharOffset:$endCharOffset"
}

data class RetrievalAnchor(
    val chunkIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val relevanceRank: Int
)

enum class RetrievalSort { CHAPTER, RELEVANCE }

data class RetrievalRequest(
    val bookId: Long,
    val query: String,
    val scope: ReadingScope,
    val topK: Int = 8,
    val recallDepth: Int = 60,
    val maxVectorDistance: Double = 0.80,
    val neighborRadius: Int = 1,
    /** 每章最多占几个名额；同章相邻片段本就会被邻域扩展合并，这里管的是同章的远距离重复。 */
    val maxChunksPerChapter: Int = 2,
    /** 为「字面完整命中查询实体」的片段预留的名额，纯语义排序永远压不过它。 */
    val literalReserve: Int = 2,
    /** 词法救回下限：BM25 分数不到本轮最高分的这个比例，就不能靠词法绕过向量距离闸。 */
    val minLexicalRescueRatio: Double = 0.35,
    val sort: RetrievalSort = RetrievalSort.CHAPTER,
    /** User-selected chapter lower bound, independent of the spoiler upper boundary. */
    val firstChapterIndex: Int = 0
)

/**
 * 一轮检索为什么只给了这么多条。调用方要把它讲给模型听，否则「返回 2 条」会被误读成
 * 「书里只有 2 处」，模型转头就拿噪声硬凑答案。
 */
data class RetrievalDiagnostics(
    val vectorCandidates: Int = 0,
    val lexicalCandidates: Int = 0,
    val fusedCandidates: Int = 0,
    /** 真正拿到名额的片段数；邻域合并会把相邻片段并成一条，所以它 ≥ 最终输出条数。 */
    val selectedCandidates: Int = 0,
    val droppedByRelevance: Int = 0,
    val droppedByScope: Int = 0,
    val droppedByChapterQuota: Int = 0,
    val literalReserved: Int = 0,
    val bestVectorDistance: Double? = null
)

data class RetrievalResult(
    val hits: List<RetrievalCandidate>,
    val vectorFailure: Throwable? = null,
    val lexicalFailure: Throwable? = null,
    val rerankFailure: Throwable? = null,
    val diagnostics: RetrievalDiagnostics = RetrievalDiagnostics()
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
 * Hybrid retrieval in a fixed order: parallel recall -> weighted RRF -> literal boost ->
 * relevance gate -> scope gate -> optional rerank -> chapter-quota selection with a literal
 * reserve -> neighbour expansion -> source order. Scope is deliberately before reranking so
 * excluded text can neither leak to a remote reranker nor occupy its top-N budget.
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
        val vectorResult = vectorDeferred.await()
        val lexicalResult = lexicalDeferred.await()
        val rawVector = vectorResult.getOrDefault(emptyList())
        val rawLexical = lexicalResult.getOrDefault(emptyList())
        fun allowed(candidate: RetrievalCandidate) = candidate.bookId == request.bookId &&
            candidate.chapterIndex >= request.firstChapterIndex.coerceAtLeast(0) &&
            request.scope.allowsChapter(candidate.chapterIndex) && request.scope.allowsChunk(
                candidate.chapterIndex, candidate.startCharOffset, candidate.endCharOffset)
        val vector = rawVector.filter(::allowed)
        val lexical = rawLexical.filter(::allowed)

        val segments = RetrievalQuery.segments(request.query)
        val fused = RrfFusion.fuse(
            vector = vector,
            lexical = lexical,
            lexicalWeight = RetrievalQuery.lexicalWeightFor(request.query),
            literalBonus = { candidate -> RetrievalQuery.literalRatio(candidate.text, segments) }
        )
        val literalKeys = fused.filter { RetrievalQuery.isLiteralHit(it.text, segments) }
            .mapTo(HashSet(), RetrievalCandidate::key)
        // 词法救回要有下限：只沾到一个高频字就能绕过向量距离闸，等于给噪声开后门。
        val lexicalRescueFloor = (lexical.mapNotNull(RetrievalCandidate::lexicalScore).maxOrNull() ?: 0.0) *
            request.minLexicalRescueRatio
        val relevant = fused.filter { candidate ->
            candidate.vectorDistance == null ||
                candidate.vectorDistance <= request.maxVectorDistance ||
                candidate.key in literalKeys ||
                (lexicalRescueFloor > 0.0 && (candidate.lexicalScore ?: 0.0) >= lexicalRescueFloor)
        }
        val inScope = relevant.filter { candidate ->
            request.scope.allowsChunk(
                candidate.chapterIndex,
                candidate.startCharOffset,
                candidate.endCharOffset
            )
        }
        val bestDistance = inScope.mapNotNull(RetrievalCandidate::vectorDistance).minOrNull()
        val baseDiagnostics = RetrievalDiagnostics(
            vectorCandidates = vector.size,
            lexicalCandidates = lexical.size,
            fusedCandidates = inScope.size,
            droppedByRelevance = fused.size - relevant.size,
            droppedByScope = rawVector.size + rawLexical.size - vector.size - lexical.size,
            bestVectorDistance = bestDistance
        )

        // RRF ranks evidence, not truth. No uncalibrated whole-query "fact absent" threshold.
        var rerankFailure: Throwable? = null
        val ranked = if (reranker == null || inScope.isEmpty()) {
            inScope
        } else {
            try {
                val originals = inScope.associateBy(RetrievalCandidate::key)
                val reordered = reranker.rerank(request.query, inScope)
                require(reordered.size == inScope.size && reordered.map { it.key }.toSet() == originals.keys) {
                    "重排必须保留全部原始候选"
                }
                // A ranker cannot alter evidence, offsets or scope through its returned objects.
                reordered.map { originals.getValue(it.key) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                rerankFailure = error
                inScope
            }
        }
        val selection = RetrievalSelection.select(
            ranked = ranked,
            topK = request.topK.coerceAtLeast(1),
            maxPerChapter = request.maxChunksPerChapter,
            literalKeys = literalKeys,
            literalReserve = request.literalReserve
        )
        val ranks = ranked.mapIndexed { index, candidate -> candidate.key to index + 1 }.toMap()
        val selected = selection.selected.map { hit ->
            hit.copy(anchors = listOf(RetrievalAnchor(
                hit.chunkIndex, hit.startCharOffset, hit.endCharOffset, ranks.getValue(hit.key)
            )))
        }
        val expanded = expander.expand(
            selected,
            request.neighborRadius.coerceAtLeast(0),
            request.scope
        ).filter(::allowed)
            .distinctBy(RetrievalCandidate::key)
            .sortedWith(if (request.sort == RetrievalSort.RELEVANCE) {
                compareBy<RetrievalCandidate> { it.anchors.minOfOrNull(RetrievalAnchor::relevanceRank) ?: Int.MAX_VALUE }
                    .thenBy(RetrievalCandidate::chapterIndex).thenBy(RetrievalCandidate::startCharOffset)
            } else {
                compareBy(RetrievalCandidate::chapterIndex, RetrievalCandidate::startCharOffset)
            })

        RetrievalResult(
            hits = expanded,
            vectorFailure = vectorResult.exceptionOrNull(),
            lexicalFailure = lexicalResult.exceptionOrNull(),
            rerankFailure = rerankFailure,
            diagnostics = baseDiagnostics.copy(
                selectedCandidates = selection.selected.size,
                droppedByChapterQuota = selection.droppedByChapterQuota,
                literalReserved = selection.literalReserved
            )
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

    /**
     * Reciprocal-rank fusion，两处偏离教科书版本：
     *
     * - [lexicalWeight] 让短查询（两三个字的人名、术语）把词法腿放大。短查询的向量表示信息量
     *   本来就少，等权融合会让「字面明明写着」的片段输给语义上泛泛相似的段落。
     * - [literalBonus] 给字面命中查询实体的片段一份额外权重，量纲取 rank 1 的倒数，
     *   相当于「字面命中」自己也算一条腿。
     */
    fun fuse(
        vector: List<RetrievalCandidate>,
        lexical: List<RetrievalCandidate>,
        lexicalWeight: Double = 1.0,
        literalBonus: (RetrievalCandidate) -> Double = { 0.0 }
    ): List<RetrievalCandidate> {
        data class Acc(var candidate: RetrievalCandidate, var score: Double = 0.0)
        val scores = LinkedHashMap<String, Acc>()
        fun add(items: List<RetrievalCandidate>, weight: Double) {
            items.forEachIndexed { index, item ->
                val acc = scores.getOrPut(item.key) { Acc(item) }
                acc.score += weight / (K + index + 1)
                acc.candidate = merge(acc.candidate, item)
            }
        }
        add(vector, 1.0)
        add(lexical, lexicalWeight)
        val unit = 1.0 / (K + 1.0)
        scores.values.forEach { acc ->
            acc.score += literalBonus(acc.candidate).coerceIn(0.0, 1.0) * unit * LITERAL_WEIGHT
        }
        return scores.values.sortedByDescending(Acc::score).map(Acc::candidate)
    }

    /** 字面全命中最多值一条 rank-1 腿的 1.5 倍；再高就压过双腿共识了。 */
    private const val LITERAL_WEIGHT = 1.5

    private fun merge(a: RetrievalCandidate, b: RetrievalCandidate): RetrievalCandidate = a.copy(
        text = a.text.ifBlank { b.text },
        startCharOffset = listOf(a.startCharOffset, b.startCharOffset).firstOrNull { it >= 0 } ?: -1,
        endCharOffset = maxOf(a.endCharOffset, b.endCharOffset),
        vectorDistance = a.vectorDistance ?: b.vectorDistance,
        lexicalScore = a.lexicalScore ?: b.lexicalScore
    )
}

/**
 * 查询串的形态分析。全是纯函数：切实体段、按长度定词法权重、判字面命中。
 *
 * 「实体段」= 用空白与标点切开后长度 ≥2 的片段。中文查询常写成「秋宁 祭司传承」这种
 * 空格分隔的实体串，切开后逐段做字面匹配，比把整句当一个短语靠谱得多；反过来，
 * 「严默驾驶飞机与外星舰队交战」这类整句负样本切不出多段，字面匹配自然不会命中。
 */
object RetrievalQuery {

    private val SEPARATORS = Regex("[\\s，,、。;；:：!！?？\"“”'‘’()（）\\[\\]【】/\\\\|]+")

    fun segments(query: String): List<String> = query.split(SEPARATORS)
        .map(String::trim)
        .filter { it.length >= 2 }

    /**
     * 词法腿权重：查询越短越重。两三个字的人名/术语几乎只能靠字面对齐，
     * 长句提问才轮到向量的语义泛化发挥。
     */
    fun lexicalWeightFor(query: String): Double {
        val meaningful = query.count(Char::isLetterOrDigit)
        return when {
            meaningful <= 4 -> 2.0
            meaningful <= 8 -> 1.6
            meaningful <= 16 -> 1.3
            else -> 1.0
        }
    }

    /** 命中的实体段占比；用于融合加成。 */
    fun literalRatio(text: String, segments: List<String>): Double {
        if (segments.isEmpty() || text.isBlank()) return 0.0
        val hits = segments.count { text.contains(it, ignoreCase = true) }
        return hits.toDouble() / segments.size
    }

    /** 字面完整命中：查询的每个实体段都在这段原文里出现过。 */
    fun isLiteralHit(text: String, segments: List<String>): Boolean =
        segments.isNotEmpty() && text.isNotBlank() &&
            segments.all { text.contains(it, ignoreCase = true) }
}

/** 名额分配结果。 */
data class RetrievalSelectionOutcome(
    val selected: List<RetrievalCandidate>,
    val droppedByChapterQuota: Int = 0,
    val literalReserved: Int = 0
)

/**
 * 名额分配：先给字面完整命中留位，再按融合排名填满，全程遵守每章上限。
 *
 * 两个问题合并在这里解决——纯排序会让同一章的几个片段吃掉全部名额（四个坑位只覆盖两章），
 * 也会让「字面明明写着」的片段被语义相似度挤出去。
 */
object RetrievalSelection {

    fun select(
        ranked: List<RetrievalCandidate>,
        topK: Int,
        maxPerChapter: Int,
        literalKeys: Set<String>,
        literalReserve: Int
    ): RetrievalSelectionOutcome {
        if (ranked.isEmpty() || topK <= 0) return RetrievalSelectionOutcome(emptyList())
        val quota = maxPerChapter.coerceAtLeast(1)
        val perChapter = HashMap<Int, Int>()
        val picked = LinkedHashMap<String, RetrievalCandidate>()
        var quotaDrops = 0

        fun tryPick(candidate: RetrievalCandidate): Boolean {
            if (picked.size >= topK || picked.containsKey(candidate.key)) return false
            val used = perChapter.getOrDefault(candidate.chapterIndex, 0)
            if (used >= quota) {
                quotaDrops++
                return false
            }
            perChapter[candidate.chapterIndex] = used + 1
            picked[candidate.key] = candidate
            return true
        }

        val reserve = literalReserve.coerceIn(0, topK)
        var reserved = 0
        if (reserve > 0 && literalKeys.isNotEmpty()) {
            for (candidate in ranked) {
                if (reserved >= reserve) break
                if (candidate.key !in literalKeys) continue
                if (tryPick(candidate)) reserved++
            }
        }
        ranked.forEach { candidate -> tryPick(candidate) }

        // 名额没填满而候选还有剩：多半是每章上限挡的，这时放宽一格补齐，别白交欠交付的答卷。
        if (picked.size < topK && quotaDrops > 0) {
            val relaxed = quota + 1
            ranked.forEach { candidate ->
                if (picked.size >= topK || picked.containsKey(candidate.key)) return@forEach
                val used = perChapter.getOrDefault(candidate.chapterIndex, 0)
                if (used >= relaxed) return@forEach
                perChapter[candidate.chapterIndex] = used + 1
                picked[candidate.key] = candidate
                quotaDrops--
            }
        }

        return RetrievalSelectionOutcome(
            selected = picked.values.toList(),
            droppedByChapterQuota = quotaDrops.coerceAtLeast(0),
            literalReserved = reserved
        )
    }
}

/** Small, deterministic BM25 implementation used over the persisted chunk corpus. */
object Bm25LexicalRecall {
    fun rank(
        candidates: List<RetrievalCandidate>,
        query: String,
        limit: Int,
        checkActive: () -> Unit = {}
    ): List<RetrievalCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return emptyList()
        data class Stats(val length: Int, val frequencies: Map<String, Int>)
        val terms = queryTerms.toHashSet()
        // Retain only query-term counts, not every n-gram of the entire novel.
        val docs = candidates.map {
            checkActive()
            val tokens = tokenize(it.text)
            Stats(tokens.size, tokens.asSequence().filter(terms::contains).groupingBy { it }.eachCount())
        }
        val averageLength = docs.map(Stats::length).average().coerceAtLeast(1.0)
        val documentFrequency = queryTerms.associateWith { term -> docs.count { term in it.frequencies } }
        return candidates.indices.mapNotNull { index ->
            checkActive()
            val stats = docs[index]
            if (stats.length == 0) return@mapNotNull null
            val frequencies = stats.frequencies
            var score = 0.0
            queryTerms.forEach { term ->
                val frequency = frequencies[term] ?: 0
                if (frequency == 0) return@forEach
                val df = documentFrequency.getValue(term)
                val idf = ln(1.0 + (candidates.size - df + 0.5) / (df + 0.5))
                val normalized = frequency * (K1 + 1.0) /
                    (frequency + K1 * (1.0 - B + B * stats.length / averageLength))
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
