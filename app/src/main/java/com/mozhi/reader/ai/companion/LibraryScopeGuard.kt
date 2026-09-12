package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.library.BookContentMutation
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.retrieval.ReadingScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryScopeGuard @Inject constructor(private val library: LibraryRepository) {
    suspend fun capture(bookIds: List<Long>): List<LibraryBookScope> {
        require(bookIds.size <= LibraryBookScopes.MAX_BOOKS && bookIds.distinct().size == bookIds.size)
        return bookIds.map { id ->
            BookContentMutation.withBook(id) {
                val book = library.getBook(id)?.takeIf { it.removedAt == 0L } ?: error("所选书籍已被移除")
                val scope = ReadingScope.uptoProgress(book)
                LibraryBookScope(id, book.title.take(500), library.bookTextRevision(id), scope.maxChapterIndex, scope.maxCharOffset)
            }
        }
    }

    suspend fun validate(scopes: List<LibraryBookScope>) {
        scopes.forEach { validate(it) }
    }

    /** Advancing real reading can broaden a NEW turn; a rewind cannot re-send unsafe history. */
    suspend fun refresh(scopes: List<LibraryBookScope>): List<LibraryBookScope> = scopes.map { previous ->
        BookContentMutation.withBook(previous.bookId) {
            val current = ReadingScope.uptoProgress(validateCurrent(previous))
            previous.copy(maxChapterIndex = current.maxChapterIndex, maxCharOffset = current.maxCharOffset)
        }
    }

    suspend fun validate(scope: LibraryBookScope) {
        BookContentMutation.withBook(scope.bookId) { validateCurrent(scope) }
    }

    /** Local reads only: never hold a source mutation lock across a model/network call. */
    internal suspend fun <T> withVerifiedSource(scope: LibraryBookScope, read: suspend () -> T): T =
        BookContentMutation.withBook(scope.bookId) {
            validateCurrent(scope)
            read().also { validateCurrent(scope) }
        }

    private suspend fun validateCurrent(scope: LibraryBookScope): BookEntity {
        val book = library.getBook(scope.bookId)?.takeIf { it.removedAt == 0L }
            ?: error("《${scope.title.take(24)}》已移除，请新建话题")
        require(ReadingScope.uptoProgress(book).contains(scope.readingScope)) {
            "《${scope.title.take(24)}》已读范围已重置，请新建话题"
        }
        require(library.bookTextRevision(scope.bookId) == scope.sourceRevision) {
            "《${scope.title.take(24)}》正文已变更，请新建话题"
        }
        return book
    }
}
