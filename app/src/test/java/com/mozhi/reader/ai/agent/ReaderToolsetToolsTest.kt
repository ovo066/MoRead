package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.vector.BookChunk
import com.mozhi.reader.core.vector.MemoryEntry
import com.mozhi.reader.core.vector.VectorDb
import io.objectbox.BoxStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** search_book / recall_memory 的执行语义，重点是查询层防剧透。桌面 ObjectBox 真跑。 */
class ReaderToolsetToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: BoxStore

    @Before
    fun setUp() {
        store = VectorDb.openAt(tempFolder.newFolder("objectbox"))
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun searchBookNeverReturnsChunksBeyondProgress() = runTest {
        val box = store.boxFor(BookChunk::class.java)
        // 第 2 章与第 9 章语义都相关，但用户只读到第 4 章（index 3）。
        box.put(chunk(chapterIndex = 2, text = "张小敬在西市追查狼卫。"))
        box.put(chunk(chapterIndex = 9, text = "张小敬在花萼楼见到幕后主使。"))

        val result = searchTool(lastReadChapterIndex = 3).execute(
            buildJsonObject { put("query", "张小敬在哪里") }
        )

        assertTrue(result.contains("已读范围（第 1 至 4 章）"))
        assertTrue(result.contains("西市追查狼卫"))
        assertFalse(result.contains("花萼楼"))
        assertTrue(result, result.contains("【第 3 章「章题2」】"))
    }

    @Test
    fun searchBookDistinguishesMissingIndexFromNoHits() = runTest {
        // 已读范围（≤4 章）完全没有切片，但后文有——应提示索引未就绪而不是“没找到”。
        store.boxFor(BookChunk::class.java).put(chunk(chapterIndex = 9, text = "后文内容"))

        val result = searchTool(lastReadChapterIndex = 3).execute(
            buildJsonObject { put("query", "任意问题") }
        )

        assertTrue(result.contains("向量索引正在后台建立"))
        assertTrue(result.contains("已自动尝试本地关键词检索"))
    }

    @Test
    fun searchBookSurvivesCrowdingByOutOfRangeChunks() = runTest {
        val box = store.boxFor(BookChunk::class.java)
        // 100 条后文切片都比唯一的已读切片更贴近查询向量（方向差远小于已读块的偏角）——
        // 「先近邻后过滤」的检索必须自适应扩大候选集，不能让后文把已读结果挤出去。
        // 向量各不相同：完全相同的向量会把 HNSW 图折叠成病态结构，与真实 embedding 不符。
        repeat(100) { i ->
            box.put(chunk(chapterIndex = 50 + i, text = "后文${i}", x = 1f, y = 0.0001f * (i + 1)))
        }
        box.put(chunk(chapterIndex = 1, text = "已读线索", x = 0.9f, y = 0.1f))

        val result = searchTool(lastReadChapterIndex = 3).execute(
            buildJsonObject {
                put("query", "线索")
                put("top_k", 2)
            }
        )

        assertTrue(result, result.contains("已读线索"))
        assertFalse(result, result.contains("后文"))
    }

    @Test
    fun searchBookRequiresQuery() = runTest {
        val result = searchTool(lastReadChapterIndex = 3)
            .execute(buildJsonObject { })
        assertEquals("缺少检索词 query", result)
    }

    @Test
    fun searchBookFallsBackToLocalChineseKeywordSearchWhenEmbeddingFails() = runTest {
        val tool = SearchBookTool(
            bookId = 1,
            getBook = { book(lastReadChapterIndex = 3) },
            chapterTitle = { "章题$it" },
            embedQuery = { error("未配置 Embedding 模型") },
            store = { store },
            loadChaptersThrough = {
                listOf(
                    ChapterDocument(0, "开端", "长安城里风声鹤唳。"),
                    ChapterDocument(2, "追查", "张小敬在西市追查狼卫，并找到了关键线索。")
                )
            }
        )

        val result = tool.execute(buildJsonObject { put("query", "张小敬在哪里追查狼卫") })

        assertTrue(result, result.contains("已自动切换到本地关键词检索"))
        assertTrue(result, result.contains("西市追查狼卫"))
    }

    @Test
    fun searchBookNeverReturnsUnreadTailOfCurrentChapter() = runTest {
        val box = store.boxFor(BookChunk::class.java)
        box.put(chunk(chapterIndex = 3, text = "凶手在本章结尾现身。"))
        box.put(chunk(chapterIndex = 1, text = "前文只知道凶手留下了信件。", x = 0.9f, y = 0.1f))
        val currentBody = "用户目前只读到这里。凶手在本章结尾现身。"
        val tool = SearchBookTool(
            bookId = 1,
            getBook = { book(lastReadChapterIndex = 3, lastReadCharOffset = 10) },
            chapterTitle = { "章题$it" },
            embedQuery = { vector(1f, 0f) },
            store = { store },
            loadChapter = { ChapterDocument(3, "当前章", currentBody) }
        )

        val result = tool.execute(buildJsonObject { put("query", "凶手") })

        assertFalse(result, result.contains("本章结尾现身"))
        assertTrue(result, result.contains("前文只知道"))
    }

    @Test
    fun searchBookSurfacesEmbeddingFailureAsReadableText() = runTest {
        val tool = SearchBookTool(
            bookId = 1,
            getBook = { book(lastReadChapterIndex = 3) },
            chapterTitle = { null },
            embedQuery = { error("未配置 Embedding 模型") },
            store = { store }
        )
        val result = tool.execute(buildJsonObject { put("query", "任意") })
        assertTrue(result.startsWith("向量检索不可用"))
    }

    @Test
    fun searchBookRequestsLazyIndexWhenBookHasNoChunks() = runTest {
        var requested = 0
        val tool = SearchBookTool(
            bookId = 99,
            getBook = { book(lastReadChapterIndex = 3) },
            chapterTitle = { null },
            embedQuery = { error("索引缺失时也可能没有模型") },
            store = { store },
            requestIndex = { requested++ }
        )

        tool.execute(buildJsonObject { put("query", "任意") })
        tool.execute(buildJsonObject { put("query", "再来一次") })

        // 每次调用都可请求（Worker 侧 KEEP 幂等），关键是无索引时确实发出了请求
        assertEquals(2, requested)
    }

    @Test
    fun bookSectionSupportsChapterRangeAndLongChapterContinuation() {
        val result = formatBookSection(
            chapters = listOf(
                ChapterDocument(1, "第二章", "甲".repeat(1_500)),
                ChapterDocument(2, "第三章", "这是当前章已读前缀")
            ),
            fromChapter = 2,
            toChapter = 3,
            startChar = 0,
            maxChars = 1_000
        )

        assertTrue(result.contains("【第 2 章「第二章」】"))
        assertTrue(result.contains("内容未完"))
        assertTrue(result.contains("from_chapter=2"))
        assertTrue(result.contains("start_char="))
        assertFalse(result.contains("【第 3 章"))
    }

    @Test
    fun bookSectionContinuationStartsAtRequestedUtf16Offset() {
        val body = "开头😀中段和结尾"
        val start = body.indexOf("中段")
        val result = formatBookSection(
            chapters = listOf(ChapterDocument(0, "首章", body)),
            fromChapter = 1,
            toChapter = 1,
            startChar = start,
            maxChars = 1_000
        )

        assertTrue(result.contains("中段和结尾"))
        assertFalse(result.contains("开头😀"))
    }

    @Test
    fun exactQuoteLocatorReturnsUtf16OffsetsAndDetectsAmbiguity() {
        val chapters = listOf(
            ChapterDocument(0, "第一章", "甲说：你好。乙回答。"),
            ChapterDocument(1, "第二章", "再次出现你好，也有 emoji😀。")
        )

        val unique = locateExactQuote(chapters, "emoji😀")
        val ambiguous = locateExactQuote(chapters, "你好")

        assertEquals(1, unique.single().chapterIndex)
        assertEquals(chapters[1].body.indexOf("emoji😀"), unique.single().startCharOffset)
        assertEquals("emoji😀".length, unique.single().endCharOffset - unique.single().startCharOffset)
        assertEquals(2, ambiguous.size)
    }

    @Test
    fun recallMemoryIsScopedToPersonaAndFormatsBullets() = runTest {
        val box = store.boxFor(MemoryEntry::class.java)
        box.put(
            memory(personaId = 7, summary = "用户最喜欢张小敬"),
            memory(personaId = 8, summary = "别的角色的记忆")
        )

        val tool = RecallMemoryTool(
            personaId = 7,
            embedQuery = { vector(1f, 0f) },
            store = { store }
        )
        val result = tool.execute(buildJsonObject { put("query", "用户喜欢谁") })

        assertTrue(result.contains("- 用户最喜欢张小敬"))
        assertFalse(result.contains("别的角色的记忆"))
    }

    @Test
    fun recallMemoryWithEmptyStoreSaysSo() = runTest {
        val tool = RecallMemoryTool(
            personaId = 7,
            embedQuery = { vector(1f, 0f) },
            store = { store }
        )
        assertEquals(
            "还没有与此相关的长期记忆。",
            tool.execute(buildJsonObject { put("query", "任意") })
        )
    }

    private fun searchTool(lastReadChapterIndex: Int) = SearchBookTool(
        bookId = 1,
        getBook = { book(lastReadChapterIndex) },
        chapterTitle = { index -> "章题$index" },
        embedQuery = { vector(1f, 0f) },
        store = { store }
    )

    private fun book(
        lastReadChapterIndex: Int,
        lastReadCharOffset: Int = 0
    ) = BookEntity(
        id = 1,
        title = "测试书",
        author = "作者",
        coverPath = null,
        epubPath = "/tmp/a.epub",
        sourceType = BookSourceType.TXT,
        importedAt = 0,
        totalChapters = 12,
        lastReadChapterIndex = lastReadChapterIndex,
        lastReadCharOffset = lastReadCharOffset
    )

    private fun vector(x: Float, y: Float): FloatArray =
        FloatArray(VectorDb.EMBEDDING_DIMENSIONS).also {
            it[0] = x
            it[1] = y
        }

    private fun chunk(chapterIndex: Int, text: String, x: Float = 1f, y: Float = 0f): BookChunk =
        BookChunk().also {
            it.bookId = 1
            it.chapterIndex = chapterIndex
            it.chunkIndex = 0
            it.text = text
            it.embedding = vector(x, y)
        }

    private fun memory(personaId: Long, summary: String): MemoryEntry =
        MemoryEntry().also {
            it.personaId = personaId
            it.summary = summary
            it.sourceType = "CHAT_SUMMARY"
            it.createdAt = 0
            it.embedding = vector(1f, 0f)
        }
}
