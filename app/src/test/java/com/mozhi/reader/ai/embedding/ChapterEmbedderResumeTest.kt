package com.mozhi.reader.ai.embedding

import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.vector.BookChunk
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

/**
 * 「索引越来越少」的回归测试（2026-09-05）。
 *
 * 索引任务会因网络/限流失败被 WorkManager 反复重试，所以重跑必须是**纯增量续跑**：
 * 已落库的章节既不能被清掉，也不能重复请求 embedding。这里用本机 JVM 真跑 ObjectBox
 * 来钉住这个不变量——中途失败一次，第二轮只补缺的章节，切片总量只增不减。
 */
class ChapterEmbedderResumeTest {

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
    fun failedRunKeepsFinishedChaptersAndSecondRunOnlyEmbedsTheRest() = runTest {
        val chapters = (0 until 6).map(::chapter)

        // 每章 8 个切片、批量 32 条 ⇒ 首批装满第 0..3 章并整章落库，含第 4 章的第二批失败。
        val failed = embedder.embedChapters(
            bookId = BOOK_ID,
            chapters = chapters,
            readText = { body(it.chapterIndex) },
            embed = { texts ->
                if (texts.any { it.startsWith("第 4 章") }) error("HTTP 429 请求过于频繁")
                texts.map { vector() }
            }
        )

        assertTrue("中途失败应报 Failed", failed is EmbedOutcome.Failed)
        val afterFailure = VectorQueries.chaptersWithChunks(store, BOOK_ID).sorted()
        assertEquals("失败前完成的章节必须留在库里", listOf(0, 1, 2, 3), afterFailure)

        // 第二轮：只该看到缺失的第 4、5 章，且已落库章节不被重复请求。
        val secondRoundTitles = mutableListOf<String>()
        val completed = embedder.embedChapters(
            bookId = BOOK_ID,
            chapters = chapters,
            readText = { body(it.chapterIndex) },
            embed = { texts ->
                secondRoundTitles += texts.map { it.substringBefore('\n') }
                texts.map { vector() }
            }
        )

        assertEquals(EmbedOutcome.Completed, completed)
        assertEquals(
            "续跑只请求缺失章节",
            setOf("第 4 章", "第 5 章"),
            secondRoundTitles.toSet()
        )
        assertEquals(
            "全书章节最终都有切片",
            (0 until 6).toList(),
            VectorQueries.chaptersWithChunks(store, BOOK_ID).sorted()
        )
    }

    @Test
    fun rerunOnCompleteIndexIsNoOpAndNeverDropsChunks() = runTest {
        val chapters = (0 until 3).map(::chapter)
        embedder.embedChapters(
            bookId = BOOK_ID,
            chapters = chapters,
            readText = { body(it.chapterIndex) },
            embed = { texts -> texts.map { vector() } }
        )
        val chunkCount = store.boxFor(BookChunk::class.java).count()
        assertTrue("首轮应写入切片", chunkCount > 0)

        var requests = 0
        val outcome = embedder.embedChapters(
            bookId = BOOK_ID,
            chapters = chapters,
            readText = { body(it.chapterIndex) },
            embed = { texts -> requests++; texts.map { vector() } }
        )

        assertEquals(EmbedOutcome.Completed, outcome)
        assertEquals("已完整的书不再发请求", 0, requests)
        assertEquals("切片数量不变", chunkCount, store.boxFor(BookChunk::class.java).count())
    }

    private fun chapter(index: Int) = ChapterEntity(
        bookId = BOOK_ID,
        chapterIndex = index,
        title = "第 $index 章",
        href = "",
        charCount = body(index).length
    )

    /** 每段 ~300 字、段间无法合并（300+300 > TARGET_CHARS），所以每章稳定切出 8 片。 */
    private fun body(index: Int) = (0 until CHUNKS_PER_CHAPTER).joinToString("\n") { paragraph ->
        "第 $index 章第 $paragraph 段。" + "文".repeat(290)
    }

    private fun vector() = FloatArray(VectorDb.EMBEDDING_DIMENSIONS).also { it[0] = 1f }

    private companion object {
        const val BOOK_ID = 7L
        const val CHUNKS_PER_CHAPTER = 8
    }
}
