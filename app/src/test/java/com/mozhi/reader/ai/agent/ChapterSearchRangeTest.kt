package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.retrieval.*
import com.mozhi.reader.core.vector.BookChunk
import com.mozhi.reader.core.vector.VectorDb
import com.mozhi.reader.core.vector.VectorQueries
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChapterSearchRangeTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun typedBoundsNeverWidenProgressAndRejectMalformedInput() {
        val range = parseChapterSearchBounds(buildJsonObject { put("from_chapter", 3); put("to_chapter", 8) })
            .resolve(10, ReadingScope.upto(4, 17))
        assertEquals(2, range.firstIndex)
        assertEquals(4, range.lastIndex)
        assertEquals(17, range.scope.maxCharOffset)
        assertEquals(ReadingScope.upto(2, Int.MAX_VALUE), ChapterSearchBounds(2, 3).resolve(10, ReadingScope.upto(4, 17)).scope)
        listOf("""{"from_chapter":0}""", """{"to_chapter":"3"}""", """{"from_chapter":5,"to_chapter":2}""", """{"from_chapter":null}""").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { parseChapterSearchBounds(Json.parseToJsonElement(raw).jsonObject) }
        }
        assertThrows(IllegalArgumentException::class.java) { ChapterSearchBounds(9).resolve(10, ReadingScope.upto(4, 17)) }
    }

    @Test fun narrowedSearchLoadsOnlyRequestedReadableChapters() = runTest {
        val loaded = mutableListOf<Int>()
        val book = BookEntity(id = 1, title = "范围测试", author = "", coverPath = null,
            epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 6)
        val tool = SearchBookTool(1, { book }, { "章节$it" }, { error("must stay local") },
            { error("index disabled") }, ReadingScope.upto(3, 4),
            loadChapter = { index ->
                loaded += index
                ChapterDocument(index, "", if (index == 3) "灯塔线索未读尾部" else "灯塔线索在此")
            }, indexingEnabled = { false })
        val result = tool.execute(buildJsonObject {
            put("query", "灯塔线索"); put("from_chapter", 3); put("to_chapter", 6)
        })
        assertEquals(listOf(2, 3), loaded)
        assertTrue(result, result.contains("第 3 至 4 章"))
        assertFalse(result, result.contains("未读尾部"))
    }

    @Test fun nativeVectorQueryUsesLowerAndUpperBoundsBeforeRanking() {
        val store = VectorDb.openAt(temporary.newFolder("vectors"))
        try {
            val query = FloatArray(VectorDb.EMBEDDING_DIMENSIONS).also { it[0] = 1f }
            fun put(chapter: Int, value: Float) {
                store.boxFor(BookChunk::class.java).put(BookChunk().also {
                    it.bookId = 1; it.chapterIndex = chapter; it.chunkIndex = 0; it.text = "章$chapter"
                    it.embedding = query.copyOf().also { v -> v[0] = value; v[1] = 1f - value }
                })
            }
            repeat(100) { put(0, 1f); put(9, 1f) }
            put(4, .9f)
            val hits = VectorQueries.searchChunks(store, 1, query, 5, 4, 4)
            assertEquals(listOf(4), hits.map { it.get().chapterIndex })
        } finally { store.close() }
    }

    @Test fun explicitSingleChapterSearchDoesNotLoseSlotsToWholeBookDiversityQuota() = runTest {
        val body = (1..5).joinToString("\n") { "灯塔线索$it。" + "叙述".repeat(180) }
        val book = BookEntity(id = 1, title = "单章", author = "", coverPath = null, epubPath = "",
            sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1)
        val tool = SearchBookTool(1, { book }, { "单章" }, { error("local only") }, { error("index disabled") },
            ReadingScope.WholeBook, loadChapter = { ChapterDocument(0, "单章", body) }, indexingEnabled = { false })
        val result = tool.execute(buildJsonObject {
            put("query", "灯塔"); put("from_chapter", 1); put("to_chapter", 1); put("top_k", 5)
        })
        assertTrue(result, result.contains("本轮选择 5 个候选"))
    }

    @Test fun corpusSkipsPrefixBulkLoaderForANarrowedRange() = runTest {
        val visited = mutableListOf<Int>()
        val corpus = loadReadableCorpus(1, 5, ReadingScope.upto(3, Int.MAX_VALUE),
            { visited += it; ChapterDocument(it, "", "第 $it 章") },
            { error("a prefix loader would read unrequested chapters") }, firstChapterIndex = 2)
        assertEquals(listOf(2, 3), visited)
        assertEquals(setOf(2, 3), corpus.candidates.map { it.chapterIndex }.toSet())
        assertTrue(corpus.complete)
    }

    @Test fun pipelineEnforcesLowerBoundBeforeRerankAndAfterExpansion() = runTest {
        fun candidate(chapter: Int) = RetrievalCandidate(1, chapter, 0, "灯塔", 0, 2, .1)
        val before = candidate(0)
        val inside = candidate(2)
        var seen = emptyList<RetrievalCandidate>()
        val pipeline = RetrievalPipeline(RetrievalRecall { listOf(before, inside) }, RetrievalRecall { emptyList() },
            ChunkReranker { _, items -> seen = items; items }, NeighborExpander { items, _, _ -> items + before })
        val result = pipeline.retrieve(RetrievalRequest(1, "灯塔", ReadingScope.upto(3, 50), firstChapterIndex = 2))
        assertEquals(listOf(inside), seen)
        assertEquals(listOf(2), result.hits.map { it.chapterIndex })
    }

    @Test fun scopeOrRevisionChangesBeforeRerankCannotUploadOrReturnOldEvidence() = runTest {
        val body = (1..3).joinToString("\n") { "灯塔线索$it。" + "叙述".repeat(180) }
        val book = BookEntity(id = 1, title = "来源变化", author = "", coverPath = null, epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1)
        for (shrinkScope in listOf(true, false)) {
            var scope = ReadingScope.upto(0, body.length)
            var revision = "initial"
            var ranked = false
            val tool = SearchBookTool(1, { book }, { "本章" }, { error("No embeddings") }, { error("No index") }, scope,
                loadChapter = {
                    if (shrinkScope) scope = ReadingScope.upto(0, 0) else revision = "changed"
                    ChapterDocument(0, "本章", body)
                }, currentScope = { scope }, indexingEnabled = { false },
                reranker = ChunkReranker { _, candidates -> ranked = true; candidates }, sourceRevision = { revision })
            val result = tool.execute(buildJsonObject { put("query", "灯塔线索") })
            assertFalse(ranked)
            assertFalse(result, result.contains("灯塔线索1"))
            assertTrue(result, result.contains("已丢弃"))
        }
    }
}
