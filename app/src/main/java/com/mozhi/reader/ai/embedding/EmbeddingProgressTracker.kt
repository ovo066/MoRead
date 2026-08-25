package com.mozhi.reader.ai.embedding

import com.mozhi.reader.core.database.dao.AiProviderDao
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** 用户可见的书籍向量索引阶段。 */
enum class EmbeddingIndexStage {
    DISABLED,
    NOT_CONFIGURED,
    QUEUED,
    INDEXING,
    READY,
    BLOCKED,
    FAILED
}

data class BookEmbeddingProgress(
    val bookId: Long,
    val stage: EmbeddingIndexStage = EmbeddingIndexStage.DISABLED,
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
    val stage: EmbeddingIndexStage = EmbeddingIndexStage.DISABLED,
    val indexedChapters: Int = 0,
    val totalChapters: Int = 0,
    val enabledBooks: Int = 0,
    val totalBooks: Int = 0,
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
    private val scheduler: BookEmbeddingScheduler,
    private val settingsStore: BookEmbeddingSettingsStore
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
        combine(runtime, settingsStore.enabledBookIds) { running, enabled -> running to enabled }
    ) { book, chapters, assignments, models, runtimeAndEnabled ->
        val (running, enabledBookIds) = runtimeAndEnabled
        val modelId = assignments.firstOrNull { it.role == ModelRole.EMBEDDING }?.modelId
        val model = models.firstOrNull { it.id == modelId }
        val total = chapters.count { it.charCount > 0 }
        val indexed = runCatching {
            VectorQueries.chaptersWithChunks(vectorStore.get(), bookId).size
        }.getOrDefault(0).coerceAtMost(total.coerceAtLeast(0))
        val current = running[bookId]
        val enabled = bookId in enabledBookIds || indexed > 0

        when {
            !enabled -> BookEmbeddingProgress(
                bookId = bookId,
                stage = EmbeddingIndexStage.DISABLED,
                indexedChapters = 0,
                totalChapters = total,
                modelName = model?.modelName,
                message = "本书未启用 AI 索引"
            )

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
        combine(runtime, settingsStore.enabledBookIds) { running, enabled -> running to enabled }
    ) { books, assignments, models, runtimeAndEnabled ->
        val (running, enabledBookIds) = runtimeAndEnabled
        val modelId = assignments.firstOrNull { it.role == ModelRole.EMBEDDING }?.modelId
        val model = models.firstOrNull { it.id == modelId }
        val indexedByBook = books.associate { book ->
            book.id to runCatching {
                VectorQueries.chaptersWithChunks(vectorStore.get(), book.id).size
            }.getOrDefault(0)
        }
        // 已有索引来自旧版本时自动纳入“已选书籍”，用户可在书籍详情关闭并清理。
        val selectedBooks = books.filter { book ->
            book.id in enabledBookIds || indexedByBook.getValue(book.id) > 0
        }
        val total = selectedBooks.filter { it.textVersion >= LibraryRepository.CURRENT_TEXT_VERSION }
            .sumOf { it.totalChapters }
        val indexed = selectedBooks.sumOf { book ->
            indexedByBook.getValue(book.id)
        }.coerceAtMost(total.coerceAtLeast(0))
        val active = running.entries.firstOrNull {
            it.key in enabledBookIds && it.value.stage == EmbeddingIndexStage.INDEXING
        }?.value
        val problem = running.entries.firstOrNull { (bookId, value) ->
            bookId in enabledBookIds &&
                (value.stage == EmbeddingIndexStage.FAILED ||
                    value.stage == EmbeddingIndexStage.BLOCKED)
        }?.value
        val stage = when {
            selectedBooks.isEmpty() -> EmbeddingIndexStage.DISABLED
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
            enabledBooks = selectedBooks.size,
            totalBooks = books.size,
            modelName = model?.modelName,
            activeBookTitle = active?.bookTitle,
            message = when {
                selectedBooks.isEmpty() -> "请在书籍详情中选择需要语义检索的书"
                model == null -> "尚未分配 Embedding 模型"
                active != null -> active.message
                problem != null -> problem.message
                total > 0 && indexed >= total -> "已选书籍索引均已就绪"
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
        scheduler.enqueueForBook(bookId)
    }

    suspend fun enable(bookId: Long) {
        settingsStore.setEnabled(bookId, true)
        retry(bookId)
    }

    suspend fun disable(bookId: Long) {
        settingsStore.setEnabled(bookId, false)
        scheduler.cancelForBook(bookId)
        VectorQueries.removeChunksForBook(vectorStore.get(), bookId)
        clear(bookId)
    }

    suspend fun rebuild(bookId: Long) {
        settingsStore.setEnabled(bookId, true)
        markQueued(bookId, message = "已加入重建队列")
        scheduler.enqueueForBook(bookId, resetBookIndex = true)
    }

    suspend fun retryAll() {
        val enabledBookIds = selectedBookIds()
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
        enabledBookIds.forEach(scheduler::enqueueForBook)
    }

    suspend fun rebuildAll() {
        val enabledBookIds = selectedBookIds()
        enabledBookIds.forEach { bookId ->
            markQueued(bookId, message = "已加入重建队列")
            scheduler.enqueueForBook(bookId, resetBookIndex = true)
        }
    }

    private suspend fun selectedBookIds(): Set<Long> {
        val explicit = settingsStore.enabledBookIds.first()
        return libraryRepository.getBooks().mapNotNullTo(linkedSetOf()) { book ->
            val hasLegacyIndex = runCatching {
                VectorQueries.chaptersWithChunks(vectorStore.get(), book.id).isNotEmpty()
            }.getOrDefault(false)
            book.id.takeIf { it in explicit || hasLegacyIndex }
        }
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
