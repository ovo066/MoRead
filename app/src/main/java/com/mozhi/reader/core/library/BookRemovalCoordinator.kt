package com.mozhi.reader.core.library

import com.mozhi.reader.ai.chat.CompanionGenerationTracker
import com.mozhi.reader.ai.companion.ProactiveAnnotationScheduler
import com.mozhi.reader.ai.embedding.EmbeddingProgressTracker
import com.mozhi.reader.ai.listen.ListenEngine
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.BookEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User-facing deletion policy. Import rollback keeps using the repository's permanent delete. */
@Singleton
class BookRemovalCoordinator @Inject constructor(
    private val library: LibraryRepository,
    private val chats: ChatDao,
    private val generations: CompanionGenerationTracker,
    private val embedding: EmbeddingProgressTracker,
    private val settings: com.mozhi.reader.core.datastore.ReaderSettingsRepository,
    private val listen: dagger.Lazy<ListenEngine>,
    private val annotations: dagger.Lazy<ProactiveAnnotationScheduler>
) {
    private val mutex = Mutex()

    suspend fun requireIdle(bookId: Long) {
        require(!listen.get().isListening(bookId)) { "请先停止这本书的听书播放，再清理数据" }
        val related = chats.getConversationIdsForBook(bookId) + chats.getLibraryConversations().filter { conversation ->
            runCatching { com.mozhi.reader.ai.companion.LibraryBookScopes.decode(conversation.bookScopesJson) }
                .getOrNull()?.any { it.bookId == bookId } == true
        }.map { it.id }
        require(related.none(generations::isActive)) {
            "这本书的伴读仍在生成，请先停止或等待完成"
        }
    }

    suspend fun remove(book: BookEntity, deleteRecords: Boolean = false) = mutex.withLock {
        requireIdle(book.id)
        annotations.get().clearReaderBook(book.id)
        // A damaged optional vector database must not prevent local book removal.
        try { embedding.disable(book.id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* The enabled flag and queued work are already cleared. */ }
        val current = library.getBook(book.id) ?: return@withLock
        if (deleteRecords) {
            library.deleteBook(current)
            settings.removeBookOverrides(book.id)
        } else library.removeBookKeepingRecords(current)
    }
}
