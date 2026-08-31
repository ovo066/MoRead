package com.mozhi.reader.core.vector

import io.objectbox.BoxStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ObjectBox 向量检索 spike：在本机 JVM 上真跑 HNSW（原生库来自 objectbox-windows/linux），
 * 验证 M2 需要的三个查询形状——最近邻排序、chapterIndex 防剧透过滤、personaId 记忆隔离。
 */
class VectorStoreSpikeTest {

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
    fun nearestNeighborsRanksClosestChunkFirstAndScopesToBook() {
        val box = store.boxFor(BookChunk::class.java)
        box.put(chunk(bookId = 1, chapter = 0, index = 0, text = "正东", x = 1f, y = 0f))
        box.put(chunk(bookId = 1, chapter = 2, index = 0, text = "偏北", x = 0.7f, y = 0.7f))
        box.put(chunk(bookId = 1, chapter = 4, index = 0, text = "正北", x = 0f, y = 1f))
        // 另一本书的同向量切片，必须被 bookId 过滤掉。
        box.put(chunk(bookId = 2, chapter = 0, index = 0, text = "别的书", x = 1f, y = 0f))

        val hits = VectorQueries.searchChunks(store, 1, direction(1f, 0f), 10, 99)

        assertEquals(3, hits.size)
        assertEquals("正东", hits[0].get().text)
        assertTrue(hits.all { it.get().bookId == 1L })
        // 余弦距离升序：越靠前越相近。
        assertTrue(hits.zipWithNext().all { (a, b) -> a.score <= b.score })
    }

    @Test
    fun chapterCapExcludesChunksBeyondReadingProgress() {
        val box = store.boxFor(BookChunk::class.java)
        box.put(chunk(bookId = 1, chapter = 1, index = 0, text = "已读", x = 0.9f, y = 0.1f))
        // 与查询向量最相近的一条在第 8 章——超出进度，绝不能出现在结果里。
        box.put(chunk(bookId = 1, chapter = 8, index = 0, text = "未读剧透", x = 1f, y = 0f))

        val hits = VectorQueries.searchChunks(store, 1, direction(1f, 0f), 10, 3)

        assertEquals(1, hits.size)
        assertEquals("已读", hits[0].get().text)
    }

    @Test
    fun memoriesAreIsolatedByPersona() {
        val box = store.boxFor(MemoryEntry::class.java)
        box.put(
            memory(personaId = 1, summary = "用户喜欢悬疑", x = 1f, y = 0f),
            memory(personaId = 2, summary = "别的角色的记忆", x = 1f, y = 0f)
        )

        val hits = VectorQueries.searchMemories(store, 1, direction(1f, 0f), 5)

        assertEquals(1, hits.size)
        assertEquals("用户喜欢悬疑", hits[0].get().summary)
        assertEquals(1L, hits[0].get().personaId)
    }


    @Test
    fun memoriesRespectCurrentBookProvenanceAndWholeBookOverride() {
        val box = store.boxFor(MemoryEntry::class.java)
        box.put(
            memory(personaId = 1, summary = "已读记忆", x = 1f, y = 0f, bookId = 1, sourceChapter = 2, sourceOffset = 20),
            memory(personaId = 1, summary = "本章后半剧透", x = 1f, y = 0.001f, bookId = 1, sourceChapter = 3, sourceOffset = 100),
            memory(personaId = 1, summary = "后文章节剧透", x = 1f, y = 0.002f, bookId = 1, sourceChapter = 9, sourceOffset = 10),
            memory(personaId = 1, summary = "旧版无来源记忆", x = 1f, y = 0.003f, bookId = 1),
            memory(personaId = 1, summary = "其他书记忆", x = 1f, y = 0.004f, bookId = 2, sourceChapter = 99, sourceOffset = 10)
        )

        val strict = VectorQueries.searchMemories(
            store, 1, direction(1f, 0f), 10,
            null, 0, 1, 3, 50
        ).map { it.get().summary }
        val wholeBook = VectorQueries.searchMemories(
            store, 1, direction(1f, 0f), 10,
            null, 0, 1, Int.MAX_VALUE, Int.MAX_VALUE
        ).map { it.get().summary }

        assertTrue(strict.contains("已读记忆"))
        assertTrue(strict.contains("其他书记忆"))
        assertFalse(strict.contains("本章后半剧透"))
        assertFalse(strict.contains("后文章节剧透"))
        assertFalse(strict.contains("旧版无来源记忆"))
        assertEquals(5, wholeBook.size)
    }

    /** 维度必须等于 [VectorDb.EMBEDDING_DIMENSIONS]，否则不进 HNSW 索引；前两维承载方向。 */
    private fun direction(x: Float, y: Float): FloatArray =
        FloatArray(VectorDb.EMBEDDING_DIMENSIONS).also {
            it[0] = x
            it[1] = y
        }

    private fun chunk(
        bookId: Long,
        chapter: Int,
        index: Int,
        text: String,
        x: Float,
        y: Float
    ): BookChunk = BookChunk().also {
        it.bookId = bookId
        it.chapterIndex = chapter
        it.chunkIndex = index
        it.text = text
        it.embedding = direction(x, y)
    }

    private fun memory(
        personaId: Long,
        summary: String,
        x: Float,
        y: Float,
        bookId: Long? = null,
        sourceChapter: Int = -1,
        sourceOffset: Int = -1
    ): MemoryEntry = MemoryEntry().also {
        it.personaId = personaId
        it.bookId = bookId
        it.sourceChapterIndex = sourceChapter
        it.sourceCharOffset = sourceOffset
        it.summary = summary
        it.sourceType = "CHAT_SUMMARY"
        it.createdAt = 1000
        it.embedding = direction(x, y)
    }
}
