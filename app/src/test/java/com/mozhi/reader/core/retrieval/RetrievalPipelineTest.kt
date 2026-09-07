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
        // 字面确实写着查询实体，才配得上「救回」——只是 BM25 分数高但字面对不上的，
        // 会被相关性下限挡在门外（见 weakCandidatesAreNotConvertedToAClaimThatThePremiseIsFalse）。
        val weakVector = candidate(chapter = 1, index = 0, start = 0, end = 10, distance = 0.95)
            .copy(text = "他翻出那卷专有名词的注疏")
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

/**
 * 伴读实测反馈的四点（2026-09-05）：短实体查询漏字面精确匹配、top_k 欠交付不可见、
 * 同章片段挤占名额、负样本没有相关性下限。四条各自钉一个测试。
 */
class RetrievalQualityTest {

    @Test
    fun literalHitKeepsItsSlotEvenWhenSemanticRankingBuriesIt() = runTest {
        // 语义腿把三段「泛泛相似」排在前面，字面写着查询实体的第 18 章排最后。
        val semantic = (0 until 3).map { index ->
            chunk(chapter = 25 + index, index = 0, text = "秋宁站在祭坛前沉默不语", distance = 0.30)
        }
        val literal = chunk(
            chapter = 18,
            index = 4,
            text = "秋宁还没有得到全部的祭司传承",
            distance = 0.55
        )
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { semantic + literal },
            lexicalRecall = RetrievalRecall { listOf(literal.copy(lexicalScore = 6.0)) }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(
                bookId = 1,
                query = "秋宁 祭司传承",
                scope = ReadingScope.WholeBook,
                topK = 3,
                neighborRadius = 0
            )
        )

        assertTrue(
            "字面完整命中的片段必须在结果里",
            result.hits.any { it.chapterIndex == 18 }
        )
        assertEquals(1, result.diagnostics.literalReserved)
    }

    @Test
    fun chapterQuotaSpreadsSlotsAcrossChaptersInsteadOfOneChapter() = runTest {
        // 同一章四个不相邻片段排名最高，占满四个坑位——正是伴读看到的「四个坑位只覆盖两章」。
        val crowded = (0 until 4).map { index ->
            chunk(chapter = 5, index = index * 4, text = "流放地的惩罚条目 $index", distance = 0.20)
        }
        val others = listOf(
            chunk(chapter = 6, index = 0, text = "改造指南第一条", distance = 0.35),
            chunk(chapter = 9, index = 0, text = "监工的惩罚记录", distance = 0.40)
        )
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { crowded + others },
            lexicalRecall = RetrievalRecall { emptyList() }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(
                bookId = 1,
                query = "流放改造指南 惩罚",
                scope = ReadingScope.WholeBook,
                topK = 4,
                neighborRadius = 0
            )
        )

        val chapters = result.hits.map(RetrievalCandidate::chapterIndex)
        assertEquals("每章最多两段", 2, chapters.count { it == 5 })
        assertEquals("名额仍然填满", 4, result.hits.size)
        assertEquals("覆盖三章", setOf(5, 6, 9), chapters.toSet())
    }

    @Test
    fun weakCandidatesAreNotConvertedToAClaimThatThePremiseIsFalse() = runTest {
        // 候选相关性不是前提真伪；不能用未评测的整轮阈值伪装为无答案。
        val noise = listOf(
            chunk(chapter = 40, index = 0, text = "他抬头看了看天色", distance = 0.78),
            chunk(chapter = 53, index = 0, text = "舰队的传闻只是酒馆闲谈", distance = 0.81),
            chunk(chapter = 57, index = 0, text = "严默沉默地擦拭刀锋", distance = 0.79)
        )
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { noise },
            // 「严默」是主角名，词法腿必然给它分数——这条路以前是噪声的后门。
            lexicalRecall = RetrievalRecall { listOf(noise[2].copy(lexicalScore = 2.0)) }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(
                bookId = 1,
                query = "严默驾驶飞机与外星舰队交战",
                scope = ReadingScope.WholeBook,
                topK = 5,
                maxVectorDistance = 0.85
            )
        )

        assertEquals(3, result.hits.size)
    }

    @Test
    fun diagnosticsExplainWhyFewerThanTopKWereDelivered() = runTest {
        val only = listOf(
            chunk(chapter = 3, index = 0, text = "一段命中", distance = 0.25),
            chunk(chapter = 3, index = 9, text = "同章另一段", distance = 0.26),
            chunk(chapter = 3, index = 15, text = "同章第三段", distance = 0.27)
        )
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { only },
            lexicalRecall = RetrievalRecall { emptyList() }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(
                bookId = 1,
                query = "把脉 摸手腕",
                scope = ReadingScope.WholeBook,
                topK = 5,
                neighborRadius = 0
            )
        )

        // 只有一章可选、每章上限 2 段：宽放一格补到 3 段，仍少于请求的 5 段且原因可读。
        assertEquals(3, result.diagnostics.selectedCandidates)
        assertEquals(3, result.hits.size)
        assertTrue(result.diagnostics.selectedCandidates < 5)
        assertEquals(3, result.diagnostics.fusedCandidates)
    }

    @Test
    fun shortQueriesWeighTheLexicalLegHeavierThanLongOnes() {
        assertTrue(
            RetrievalQuery.lexicalWeightFor("秋宁") > RetrievalQuery.lexicalWeightFor("秋宁 祭司传承")
        )
        assertTrue(
            RetrievalQuery.lexicalWeightFor("秋宁 祭司传承") >
                RetrievalQuery.lexicalWeightFor("秋宁为什么迟迟没有继承祭司的全部传承与仪轨")
        )
        assertEquals(1.0, RetrievalQuery.lexicalWeightFor("秋宁为什么迟迟没有继承祭司的全部传承与仪轨"), 1e-9)
    }

    @Test
    fun literalSegmentsSplitOnSpacesAndPunctuationOnly() {
        assertEquals(listOf("秋宁", "祭司传承"), RetrievalQuery.segments("秋宁 祭司传承"))
        assertEquals(listOf("把脉", "摸手腕", "右腿阴雨天疼"), RetrievalQuery.segments("把脉、摸手腕，右腿阴雨天疼"))
        // 整句负样本切不出多段，因此字面匹配不会给它开门。
        assertEquals(
            listOf("严默驾驶飞机与外星舰队交战"),
            RetrievalQuery.segments("严默驾驶飞机与外星舰队交战")
        )
        assertTrue(
            RetrievalQuery.isLiteralHit("秋宁还没有得到全部的祭司传承", listOf("秋宁", "祭司传承"))
        )
        assertFalse(
            RetrievalQuery.isLiteralHit("秋宁站在祭坛前", listOf("秋宁", "祭司传承"))
        )
    }

    @Test
    fun lexicalOnlyFallbackIsNotSilencedByTheRelevanceFloor() = runTest {
        // 索引没建好或向量查询坏掉时只剩 BM25，一条距离都没有——此时下限无从判断，
        // 不能把回落结果一刀切成「书里没有」。
        val onlyLexical = chunk(chapter = 2, index = 0, text = "西市追查狼卫的线索")
            .copy(lexicalScore = 3.0)
        val pipeline = RetrievalPipeline(
            vectorRecall = RetrievalRecall { emptyList() },
            lexicalRecall = RetrievalRecall { listOf(onlyLexical) }
        )

        val result = pipeline.retrieve(
            RetrievalRequest(
                bookId = 1,
                query = "张小敬在哪里追查狼卫",
                scope = ReadingScope.WholeBook,
                topK = 5
            )
        )

        assertEquals(listOf(onlyLexical.key), result.hits.map { it.key })
    }


    @Test
    fun sortingChangesPresentationNotSelectionOrCoverage() = runTest {
        val hits = listOf(chunk(8, 0, "证据甲", 0.1), chunk(1, 0, "证据乙", 0.2))
        val pipeline = RetrievalPipeline(RetrievalRecall { hits }, RetrievalRecall { emptyList() })
        val request = RetrievalRequest(1, "语义描述", ReadingScope.WholeBook, topK = 2)
        val chapter = pipeline.retrieve(request)
        val relevance = pipeline.retrieve(request.copy(sort = RetrievalSort.RELEVANCE))
        assertEquals(listOf(1, 8), chapter.hits.map { it.chapterIndex })
        assertEquals(listOf(8, 1), relevance.hits.map { it.chapterIndex })
        assertEquals(chapter.hits.map { it.key }.toSet(), relevance.hits.map { it.key }.toSet())
        assertEquals(listOf(1, 2), relevance.hits.map { it.anchors.single().relevanceRank })
    }

    @Test
    fun unreadCandidatesNeverReachRerankerAndLexicalFailuresStayVisible() = runTest {
        val safe = chunk(0, 0, "已读证据", 0.1)
        val unread = chunk(1, 0, "未读真相", 0.01)
        var reranked = emptyList<RetrievalCandidate>()
        val pipeline = RetrievalPipeline(RetrievalRecall { listOf(unread, safe) },
            RetrievalRecall { error("正文不可读") },
            ChunkReranker { _, candidates -> reranked = candidates; candidates })
        val result = pipeline.retrieve(RetrievalRequest(1, "描述", ReadingScope.upto(0, 100)))
        assertEquals(listOf(safe.key), reranked.map { it.key })
        assertEquals(listOf(safe.key), result.hits.map { it.key })
        assertTrue(result.lexicalFailure != null)
    }

    private fun chunk(
        chapter: Int,
        index: Int,
        text: String,
        distance: Double? = null,
        lexicalScore: Double? = null
    ) = RetrievalCandidate(
        bookId = 1,
        chapterIndex = chapter,
        chunkIndex = index,
        text = text,
        startCharOffset = index * 100,
        endCharOffset = index * 100 + text.length,
        vectorDistance = distance,
        lexicalScore = lexicalScore
    )
}
