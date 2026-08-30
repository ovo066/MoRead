package com.mozhi.reader.core.library

import com.mozhi.reader.core.database.dao.NoteDao
import com.mozhi.reader.core.database.entity.NoteEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** 读书笔记 CRUD（personaId = null 为用户手写）。 */
@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao
) {
    fun observeAll(): Flow<List<NoteEntity>> = noteDao.observeAll()

    fun observeForBook(bookId: Long): Flow<List<NoteEntity>> = noteDao.observeForBook(bookId)

    fun observeCountForBook(bookId: Long): Flow<Int> = noteDao.observeCountForBook(bookId)

    suspend fun getNote(noteId: Long): NoteEntity? = noteDao.getNote(noteId)

    suspend fun getForBook(bookId: Long): List<NoteEntity> = noteDao.getForBook(bookId)

    suspend fun latestByKind(bookId: Long, personaId: Long, kind: String): NoteEntity? =
        noteDao.latestByKind(bookId, personaId, kind)

    suspend fun create(
        bookId: Long,
        personaId: Long?,
        title: String,
        contentMarkdown: String,
        kind: String = KIND_NOTE,
        sourceConversationId: Long? = null,
        relatedChapterIndex: Int? = null,
        relatedCharOffset: Int? = null
    ): Long {
        require(title.isNotBlank() || contentMarkdown.isNotBlank()) { "笔记内容不能为空" }
        val now = System.currentTimeMillis()
        return noteDao.insert(
            NoteEntity(
                bookId = bookId,
                personaId = personaId,
                title = title.trim(),
                contentMarkdown = contentMarkdown,
                kind = kind,
                sourceConversationId = sourceConversationId,
                relatedChapterIndex = relatedChapterIndex,
                relatedCharOffset = relatedCharOffset,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun updateContent(noteId: Long, title: String, contentMarkdown: String) {
        require(title.isNotBlank() || contentMarkdown.isNotBlank()) { "笔记内容不能为空" }
        noteDao.updateContent(
            noteId = noteId,
            title = title.trim(),
            contentMarkdown = contentMarkdown,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun updateContentAndPosition(
        noteId: Long,
        title: String,
        contentMarkdown: String,
        relatedChapterIndex: Int?,
        relatedCharOffset: Int?
    ) {
        require(title.isNotBlank() || contentMarkdown.isNotBlank()) { "笔记内容不能为空" }
        noteDao.updateContentAndPosition(
            noteId = noteId,
            title = title.trim(),
            contentMarkdown = contentMarkdown,
            relatedChapterIndex = relatedChapterIndex,
            relatedCharOffset = relatedCharOffset,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun delete(noteId: Long) {
        noteDao.delete(noteId)
    }

    companion object {
        const val KIND_NOTE = "NOTE"
        const val KIND_PLOT_SUMMARY = "PLOT_SUMMARY"
    }
}
