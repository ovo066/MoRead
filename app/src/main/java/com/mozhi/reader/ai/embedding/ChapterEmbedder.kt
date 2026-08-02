package com.mozhi.reader.ai.embedding

import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.vector.BookChunk
import com.mozhi.reader.core.vector.ChapterChunker
import com.mozhi.reader.core.vector.Embeddings
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 一次整书 embedding 的结局。 */
sealed interface EmbedOutcome {
    /** 全书切片已入库（含本轮无事可做）。 */
    data object Completed : EmbedOutcome

    /** 环境未就绪（未配模型 / 无 Key / 正文未落盘），属「待处理」而非失败。 */
    data class Skipped(val reason: String) : EmbedOutcome

    /** 中途失败；已完成章节均已落库，重跑自动从缺的章节续接。 */
    data class Failed(val error: Throwable, val chaptersEmbedded: Int) : EmbedOutcome
}

/**
 * 章节切片 → 批量 embedding → ObjectBox 的流式核心。
 *
 * - 批量跨章凑满 [BATCH_SIZE] 才发请求，尾批不足照发；
 * - 落库以「章」为原子单位（一章的全部切片在一次 put 里），
 *   所以断点续跑只需跳过已有切片的章节，不存在半章状态；
 * - embedding 输入带章节标题前缀增强检索，入库的 text 保持纯正文。
 */
@Singleton
class ChapterEmbedder @Inject constructor(
    private val store: BoxStore
) {
    suspend fun embedChapters(
        bookId: Long,
        chapters: List<ChapterEntity>,
        readText: suspend (ChapterEntity) -> String,
        embed: suspend (List<String>) -> List<FloatArray>,
        onProgress: suspend (indexedChapters: Int, totalChapters: Int) -> Unit = { _, _ -> }
    ): EmbedOutcome {
        val embedded = VectorQueries.chaptersWithChunks(store, bookId).toHashSet()
        val totalChapters = chapters.count { it.charCount > 0 }
        val initiallyIndexed = embedded.size.coerceAtMost(totalChapters)
        onProgress(initiallyIndexed, totalChapters)
        val pending = chapters.filter { it.chapterIndex !in embedded }
        if (pending.isEmpty()) return EmbedOutcome.Completed

        val buffer = ArrayDeque<PendingChunk>()
        val accumulators = HashMap<Int, ChapterAccumulator>()
        var chaptersEmbedded = 0
        return try {
            pending.forEach { chapter ->
                currentCoroutineContext().ensureActive()
                val pieces = ChapterChunker.chunk(readText(chapter))
                if (pieces.isEmpty()) return@forEach
                accumulators[chapter.chapterIndex] = ChapterAccumulator(pieces.size)
                pieces.forEachIndexed { index, piece ->
                    buffer += PendingChunk(chapter, index, piece)
                }
                while (buffer.size >= BATCH_SIZE) {
                    chaptersEmbedded += embedBatch(bookId, buffer, accumulators, embed)
                    onProgress(
                        (initiallyIndexed + chaptersEmbedded).coerceAtMost(totalChapters),
                        totalChapters
                    )
                }
            }
            while (buffer.isNotEmpty()) {
                chaptersEmbedded += embedBatch(bookId, buffer, accumulators, embed)
                onProgress(
                    (initiallyIndexed + chaptersEmbedded).coerceAtMost(totalChapters),
                    totalChapters
                )
            }
            EmbedOutcome.Completed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            EmbedOutcome.Failed(error, chaptersEmbedded)
        }
    }

    /** 取一批发请求，把返回向量归位到各章累积器；集齐的章节立即整章落库。 */
    private suspend fun embedBatch(
        bookId: Long,
        buffer: ArrayDeque<PendingChunk>,
        accumulators: MutableMap<Int, ChapterAccumulator>,
        embed: suspend (List<String>) -> List<FloatArray>
    ): Int {
        val batch = List(minOf(BATCH_SIZE, buffer.size)) { buffer.removeFirst() }
        val vectors = embed(batch.map { embedInput(it.chapter.title, it.text) })
        check(vectors.size == batch.size) {
            "embedding 返回 ${vectors.size} 条，与输入 ${batch.size} 条不一致"
        }
        val box = store.boxFor(BookChunk::class.java)
        var completedChapters = 0
        batch.forEachIndexed { i, pendingChunk ->
            val chapterIndex = pendingChunk.chapter.chapterIndex
            val accumulator = accumulators.getValue(chapterIndex)
            accumulator.fill(
                index = pendingChunk.chunkIndex,
                text = pendingChunk.text,
                vector = Embeddings.conformToIndex(vectors[i])
            )
            if (accumulator.isComplete) {
                box.put(accumulator.toEntities(bookId, chapterIndex))
                accumulators.remove(chapterIndex)
                completedChapters++
            }
        }
        return completedChapters
    }

    private fun embedInput(chapterTitle: String, text: String): String =
        if (chapterTitle.isBlank()) text else "$chapterTitle\n$text"

    private class PendingChunk(
        val chapter: ChapterEntity,
        val chunkIndex: Int,
        val text: String
    )

    private class ChapterAccumulator(size: Int) {
        private val texts = arrayOfNulls<String>(size)
        private val vectors = arrayOfNulls<FloatArray>(size)
        private var filled = 0

        val isComplete: Boolean get() = filled == texts.size

        fun fill(index: Int, text: String, vector: FloatArray) {
            check(texts[index] == null) { "切片 $index 重复回填" }
            texts[index] = text
            vectors[index] = vector
            filled++
        }

        fun toEntities(bookId: Long, chapterIndex: Int): List<BookChunk> =
            texts.indices.map { i ->
                BookChunk().also {
                    it.bookId = bookId
                    it.chapterIndex = chapterIndex
                    it.chunkIndex = i
                    it.text = checkNotNull(texts[i])
                    it.embedding = checkNotNull(vectors[i])
                }
            }
    }

    companion object {
        /** 单次 embedding 请求的最大条数（DEVELOPMENT_PLAN：批 ≤32）。 */
        const val BATCH_SIZE = 32
    }
}
