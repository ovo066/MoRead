package com.mozhi.reader.ai.embedding

import com.mozhi.reader.core.database.dao.AiProviderDao
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update

/** 用户可见的书籍向量索引阶段。 */
enum class EmbeddingIndexStage {
    NOT_CONFIGURED,
    QUEUED,
    INDEXING,
    READY,
    BLOCKED,
    FAILED
}

data class BookEmbeddingProgress(
    val bookId: Long,
    val stage: EmbeddingIndexStage = EmbeddingIndexStage.QUEUED,
    val indexedChapters: Int = 0,
    val totalChapters: Int = 0,
    val modelName: String? = null,
    val message: String = ""
) {
    val fraction: Float
        get() = if (totalChapters <= 0) 0f else {
            indexedChapters.toFloat().div(totalChapters).coerceIn(0f, 1f)
        }
}

data class LibraryEmbeddingProgress(
    val stage: EmbeddingIndexStage = EmbeddingIndexStage.NOT_CONFIGURED,
    val indexedChapters: Int = 0,
    val totalChapters: Int = 0,
    val modelName: String? = null,
    val activeBookTitle: String? = null,
    val message: String = ""
) {
    val fraction: Float
        get() = if (totalChapters <= 0) 0f else {
            indexedChapters.toFloat().div(totalChapters).coerceIn(0f, 1f)
        }
}

/**
 * 把 ObjectBox 中已经落盘的章节数与当前后台任务状态合成一条可观察进度。
 *
 * 已完成章数直接从向量库读取，因此进程被杀、WorkManager 续跑后仍准确；运行阶段只是一层
 * 轻量内存信号。界面不再把「已分配模型」误写成「索引可用」，失败原因也会原样显示。
 */
@Singleton
class EmbeddingProgressTracker @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val providerDao: AiProviderDao,
    private val vectorStore: dagger.Lazy<BoxStore>,
    private val scheduler: BookEmbeddingScheduler
) {
    private data class RuntimeProgress(
        val stage: EmbeddingIndexStage,
        val indexedChapters: Int = 0,
        val totalChapters: Int = 0,
        val bookTitle: String? = null,
        val message: String = ""
    )

    private val runtime = MutableStateFlow<Map<Long, RuntimeProgress>>(emptyMap())

    fun observeBook(bookId: Long): Flow<BookEmbeddingProgress> = combine(
        libraryRepository.observeBook(bookId),
        libraryRepository.observeChapters(bookId),
        providerDao.observeAssignments(),
        providerDao.observeModels(),
        runtime
    ) { book, chapters, assignments, models, running ->
        val modelId = assignments.firstOrNull { it.role == ModelRole.EMBEDDING }?.modelId
        val model = models.firstOrNull { it.id == modelId }
        val total = chapters.count { it.charCount > 0 }
        val indexed = runCatching {
            VectorQueries.chaptersWithChunks(vectorStore.get(), bookId).size
        }.getOrDefault(0).coerceAtMost(total.coerceAtLeast(0))
        val current = running[bookId]

        when {
            model == null -> BookEmbeddingProgress(
                bookId = bookId,
                stage = EmbeddingIndexStage.NOT_CONFIGURED,
                indexedChapters = indexed,
                totalChapters = total,
                message = "尚未分配 Embedding 模型"
            )

            current != null -> BookEmbeddingProgress(
                bookId = bookId,
                stage = current.stage,
                indexedChapters = when (current.stage) {
                    EmbeddingIndexStage.READY -> total
                    else -> maxOf(indexed, current.indexedChapters).coerceAtMost(total)
                },
                totalChapters = maxOf(total, current.totalChapters),
                modelName = model.modelName,
                message = current.message
            )

            total > 0 && indexed >= total -> BookEmbeddingProgress(
                bookId = bookId,
                stage = EmbeddingIndexStage.READY,
                indexedChapters = indexed,
                totalChapters = total,
                modelName = model.modelName,
                message = "全文索引已就绪"
            )

            book == null || book.textVersion < LibraryRepository.CURRENT_TEXT_VERSION ->
                BookEmbeddingProgress(
                    bookId = bookId,
                    stage = EmbeddingIndexStage.BLOCKED,
                    indexedChapters = indexed,
                    totalChapters = total,
                    modelName = model.modelName,
                    message = "正文尚未准备完成"
                )

            else -> BookEmbeddingProgress(
                bookId = bookId,
                stage = EmbeddingIndexStage.QUEUED,
                indexedChapters = indexed,
                totalChapters = total,
                modelName = model.modelName,
                message = if (indexed > 0) "索引已部分完成，等待后台继续" else "等待建立全文索引"
            )
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun observeLibrary(): Flow<LibraryEmbeddingProgress> = combine(
        libraryRepository.observeBooks(),
        providerDao.observeAssignments(),
        providerDao.observeModels(),
        runtime
    ) { books, assignments, models, running ->
        val modelId = assignments.firstOrNull { it.role == ModelRole.EMBEDDING }?.modelId
        val model = models.firstOrNull { it.id == modelId }
        val total = books.filter { it.textVersion >= LibraryRepository.CURRENT_TEXT_VERSION }
            .sumOf { it.totalChapters }
        val indexed = books.sumOf { book ->
            runCatching { VectorQueries.chaptersWithChunks(vectorStore.get(), book.id).size }
                .getOrDefault(0)
        }.coerceAtMost(total.coerceAtLeast(0))
        val active = running.entries.firstOrNull {
            it.value.stage == EmbeddingIndexStage.INDEXING
        }?.value
        val problem = running.values.firstOrNull {
            it.stage == EmbeddingIndexStage.FAILED || it.stage == EmbeddingIndexStage.BLOCKED
        }
        val stage = when {
            model == null -> EmbeddingIndexStage.NOT_CONFIGURED
            active != null -> EmbeddingIndexStage.INDEXING
            problem != null -> problem.stage
            total > 0 && indexed >= total -> EmbeddingIndexStage.READY
            else -> EmbeddingIndexStage.QUEUED
        }
        LibraryEmbeddingProgress(
            stage = stage,
            indexedChapters = if (stage == EmbeddingIndexStage.READY) total else indexed,
            totalChapters = total,
            modelName = model?.modelName,
            activeBookTitle = active?.bookTitle,
            message = when {
                model == null -> "尚未分配 Embedding 模型"
                active != null -> active.message
                problem != null -> problem.message
                total > 0 && indexed >= total -> "全部书籍索引已就绪"
                indexed > 0 -> "索引已部分完成，等待后台继续"
                else -> "等待建立全文索引"
            }
        )
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    fun markQueued(bookId: Long, totalChapters: Int = 0, message: String = "等待后台索引") {
        update(bookId, EmbeddingIndexStage.QUEUED, totalChapters = totalChapters, message = message)
    }

    fun markIndexing(
        bookId: Long,
        bookTitle: String,
        indexedChapters: Int,
        totalChapters: Int
    ) {
        update(
            bookId = bookId,
            stage = EmbeddingIndexStage.INDEXING,
            indexedChapters = indexedChapters,
            totalChapters = totalChapters,
            bookTitle = bookTitle,
            message = "正在索引《$bookTitle》 $indexedChapters/$totalChapters 章"
        )
    }

    fun markReady(bookId: Long, totalChapters: Int) {
        update(
            bookId = bookId,
            stage = EmbeddingIndexStage.READY,
            indexedChapters = totalChapters,
            totalChapters = totalChapters,
            message = "全文索引已就绪"
        )
    }

    fun markBlocked(bookId: Long, reason: String) {
        update(bookId, EmbeddingIndexStage.BLOCKED, message = reason.ifBlank { "索引暂不可用" })
    }

    fun markFailed(bookId: Long, reason: String) {
        update(bookId, EmbeddingIndexStage.FAILED, message = reason.ifBlank { "向量生成失败" })
    }

    fun clear(bookId: Long) {
        runtime.update { it - bookId }
    }

    /** 用户点「重试」时立即进入排队态，而不是等 Worker 真正启动后才有反馈。 */
    fun retry(bookId: Long) {
        markQueued(bookId, message = "已加入索引队列")
        scheduler.enqueue()
    }

    fun retryAll() {
        runtime.update { current ->
            current.mapValues { (_, value) ->
                if (value.stage == EmbeddingIndexStage.FAILED ||
                    value.stage == EmbeddingIndexStage.BLOCKED
                ) {
                    value.copy(stage = EmbeddingIndexStage.QUEUED, message = "已加入索引队列")
                } else {
                    value
                }
            }
        }
        scheduler.enqueue()
    }

    fun rebuildAll() {
        runtime.value = emptyMap()
        scheduler.enqueue(resetBookIndex = true)
    }

    private fun update(
        bookId: Long,
        stage: EmbeddingIndexStage,
        indexedChapters: Int = 0,
        totalChapters: Int = 0,
        bookTitle: String? = null,
        message: String = ""
    ) {
        runtime.update { current ->
            current + (bookId to RuntimeProgress(
                stage = stage,
                indexedChapters = indexedChapters,
                totalChapters = totalChapters,
                bookTitle = bookTitle ?: current[bookId]?.bookTitle,
                message = message
            ))
        }
    }
}
