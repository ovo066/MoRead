package com.mozhi.reader.core.library

import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.entity.AnnotationEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 批注 CRUD（用户与 AI 统一走这里，personaId = null 为用户）。
 * 选区坐标是章内 UTF-16 字符偏移，左闭右开。
 */
@Singleton
class AnnotationRepository @Inject constructor(
    private val annotationDao: AnnotationDao
) {
    fun observeForBook(bookId: Long): Flow<List<AnnotationEntity>> =
        annotationDao.observeForBook(bookId)

    fun observeCountForBook(bookId: Long): Flow<Int> =
        annotationDao.observeCountForBook(bookId)

    suspend fun getForChapter(bookId: Long, chapterIndex: Int): List<AnnotationEntity> =
        annotationDao.getForChapter(bookId, chapterIndex)

    suspend fun getAnnotation(annotationId: Long): AnnotationEntity? =
        annotationDao.getAnnotation(annotationId)

    suspend fun add(
        bookId: Long,
        personaId: Long?,
        chapterIndex: Int,
        startCharOffset: Int,
        endCharOffset: Int,
        selectedText: String,
        note: String = "",
        colorTag: String = ""
    ): Long {
        require(chapterIndex >= 0) { "章节索引不合法" }
        require(startCharOffset in 0 until endCharOffset) { "批注选区为空" }
        return annotationDao.insert(
            AnnotationEntity(
                bookId = bookId,
                personaId = personaId,
                chapterIndex = chapterIndex,
                startCharOffset = startCharOffset,
                endCharOffset = endCharOffset,
                selectedText = selectedText,
                note = note,
                colorTag = colorTag,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateContent(annotationId: Long, note: String, colorTag: String) {
        annotationDao.updateContent(annotationId, note, colorTag)
    }

    suspend fun delete(annotationId: Long) {
        annotationDao.delete(annotationId)
    }
}
