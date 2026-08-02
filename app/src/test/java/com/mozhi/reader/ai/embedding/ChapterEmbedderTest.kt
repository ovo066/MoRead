package com.mozhi.reader.ai.embedding

import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.vector.BookChunk
import com.mozhi.reader.core.vector.ChapterChunker
import com.mozhi.reader.core.vector.VectorDb
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 流式批量 embedding 的落库语义：批 ≤32、按章原子、断点续跑。桌面 ObjectBox 真跑。 */
class ChapterEmbedderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: BoxStore
    private lateinit var embedder: ChapterEmbedder

    @Before
    fun setUp() {
        store = VectorDb.openAt(tempFolder.newFolder("objectbox"))
        embedder = ChapterEmbedder(store)
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun embedsChaptersAtomicallyWithTitlePrefixedInputs() = runTest {
        val texts = mapOf(
            0 to "甲说完了。\n乙接着说。",
            1 to List(5) { "长段落${it}".padEnd(300, '文') }.joinToString("\n")
        )
        val inputs = mutableListOf<String>()
        val outcome = embedder.embedChapters(
            bookId = 1,
            chapters = listOf(chapter(0), chapter(1)),
            readText = { texts.getValue(it.chapterIndex) },
            embed = { batch ->
                inputs += batch
                batch.map { vector() }
            }
        )

        assertEquals(EmbedOutcome.Completed, outcome)
        assertEquals(listOf(0, 1), VectorQueries.chaptersWithChunks(store, 1).sorted())
        // 入库的 text 是纯正文切片，与切片器输出严格一致、序号连续。
        val stored = chunksOf(bookId = 1, chapterIndex = 1)
        assertEquals(ChapterChunker.chunk(texts.getValue(1)), stored.map { it.text })
        assertEquals(stored.indices.toList(), stored.map { it.chunkIndex })
        // embedding 输入带章节标题前缀。
        assertTrue(inputs.first().startsWith("第1章\n"))
    }

    @Test
    fun batchesNeverExceedLimitAndCoverEverything() = runTest {
        val text = List(40) { "满段${it}".padEnd(500, '字') }.joinToString("\n")
        val batchSizes = mutableListOf<Int>()

        val outcome = embedder.embedChapters(
            bookId = 1,
            chapters = listOf(chapter(0)),
            readText = { text },
            embed = { batch ->
                batchSizes += batch.size
                batch.map { vector() }
            }
        )

        assertEquals(EmbedOutcome.Completed, outcome)
        assertTrue(batchSizes.all { it <= ChapterEmbedder.BATCH_SIZE })
        assertEquals(40, batchSizes.sum())
        assertEquals(40, chunksOf(1, 0).size)
    }

    @Test
    fun resumeSkipsChaptersThatAlreadyHaveChunks() = runTest {
        store.boxFor(BookChunk::class.java).put(
            BookChunk().also {
                it.bookId = 1
                it.chapterIndex = 0
                it.chunkIndex = 0
                it.text = "旧切片"
                it.embedding = vector()
            }
        )
        val readChapters = mutableListOf<Int>()

        val outcome = embedder.embedChapters(
            bookId = 1,
            chapters = listOf(chapter(0), chapter(1)),
            readText = { chapter ->
                readChapters += chapter.chapterIndex
                "新章节内容。"
            },
            embed = { batch -> batch.map { vector() } }
        )

        assertEquals(EmbedOutcome.Completed, outcome)
        assertEquals(listOf(1), readChapters)
        assertEquals(1, chunksOf(1, 0).size) // 旧章原样，没有重复写入
    }

    @Test
    fun failureKeepsOnlyCompleteChaptersAndRerunFinishesTheRest() = runTest {
        val texts = mapOf(
            0 to "只有一个切片。",
            1 to List(40) { "满段${it}".padEnd(500, '字') }.joinToString("\n")
        )
        var calls = 0

        val failed = embedder.embedChapters(
            bookId = 1,
            chapters = listOf(chapter(0), chapter(1)),
            readText = { texts.getValue(it.chapterIndex) },
            embed = { batch ->
                // 第一批 32 条会带完第 0 章；第二批模拟网络失败，第 1 章还差 9 条。
                if (++calls == 2) throw java.io.IOException("网络中断")
                batch.map { vector() }
            }
        )

        assertTrue(failed is EmbedOutcome.Failed)
        assertEquals(1, (failed as EmbedOutcome.Failed).chaptersEmbedded)
        assertEquals(listOf(0), VectorQueries.chaptersWithChunks(store, 1).sorted())

        val rerun = embedder.embedChapters(
            bookId = 1,
            chapters = listOf(chapter(0), chapter(1)),
            readText = { texts.getValue(it.chapterIndex) },
            embed = { batch -> batch.map { vector() } }
        )

        assertEquals(EmbedOutcome.Completed, rerun)
        assertEquals(1, chunksOf(1, 0).size)
        assertEquals(40, chunksOf(1, 1).size)
    }

    @Test
    fun blankChaptersProduceNoChunksButComplete() = runTest {
        val outcome = embedder.embedChapters(
            bookId = 1,
            chapters = listOf(chapter(0)),
            readText = { "  \n\n " },
            embed = { error("空章节不应触发 embedding") }
        )
        assertEquals(EmbedOutcome.Completed, outcome)
        assertTrue(VectorQueries.chaptersWithChunks(store, 1).isEmpty())
    }

    private fun chapter(index: Int) = ChapterEntity(
        id = index + 1L,
        bookId = 1,
        chapterIndex = index,
        title = "第${index + 1}章",
        href = "",
        charCount = 0
    )

    private fun vector(): FloatArray =
        FloatArray(VectorDb.EMBEDDING_DIMENSIONS).also { it[0] = 1f }

    private fun chunksOf(bookId: Long, chapterIndex: Int): List<BookChunk> =
        store.boxFor(BookChunk::class.java).all
            .filter { it.bookId == bookId && it.chapterIndex == chapterIndex }
            .sortedBy { it.chunkIndex }
}
