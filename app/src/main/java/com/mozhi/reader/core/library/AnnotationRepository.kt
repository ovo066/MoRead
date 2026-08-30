package com.mozhi.reader.core.library

import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 批注 CRUD（用户与 AI 统一走这里，personaId = null 为用户）。
 * 选区坐标是章内 UTF-16 字符偏移，左闭右开。
 * 段评讨论串：楼主层 = annotations.note，回复层 = annotation_replies。
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

    suspend fun getForChapterRange(
        bookId: Long,
        fromIndex: Int,
        toIndex: Int
    ): List<AnnotationEntity> = annotationDao.getForChapterRange(bookId, fromIndex, toIndex)

    suspend fun getCountForBook(bookId: Long): Int = annotationDao.getCountForBook(bookId)

    suspend fun getCountForChapter(bookId: Long, chapterIndex: Int): Int =
        annotationDao.getCountForChapter(bookId, chapterIndex)

    suspend fun getReplyCounts(annotationIds: List<Long>): Map<Long, Int> =
        if (annotationIds.isEmpty()) emptyMap()
        else annotationDao.getReplyCounts(annotationIds).associate { it.annotationId to it.replyCount }

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
        colorTag: String = "",
        style: AnnotationStyle = AnnotationStyle.HIGHLIGHT,
        mediaJson: String = "{}"
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
                style = style.wire,
                mediaJson = mediaJson,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateContent(annotationId: Long, note: String, colorTag: String) {
        annotationDao.updateContent(annotationId, note, colorTag)
    }

    /** 即划即改浮条：只动样式与颜色，不碰想法内容。 */
    suspend fun updateStyle(annotationId: Long, style: AnnotationStyle, colorTag: String) {
        annotationDao.updateStyle(annotationId, style.wire, AnnotationColors.normalize(colorTag))
    }

    /** 给纯高亮补写想法（讨论串楼主层）。 */
    suspend fun updateNote(annotationId: Long, note: String) {
        annotationDao.updateNote(annotationId, note)
    }

    suspend fun delete(annotationId: Long) {
        annotationDao.delete(annotationId)
    }

    // ---- 讨论串回复层 ----

    fun observeReplies(annotationIds: List<Long>): Flow<List<AnnotationReplyEntity>> =
        if (annotationIds.isEmpty()) flowOf(emptyList()) else annotationDao.observeReplies(annotationIds)

    fun observeRepliedAnnotationIds(bookId: Long): Flow<List<Long>> =
        annotationDao.observeRepliedAnnotationIds(bookId)

    suspend fun getReplies(annotationId: Long): List<AnnotationReplyEntity> =
        annotationDao.getReplies(annotationId)

    suspend fun addReply(
        annotationId: Long,
        personaId: Long?,
        contentMarkdown: String,
        replyToId: Long? = null,
        mediaJson: String = "{}"
    ): Long {
        require(contentMarkdown.isNotBlank()) { "回复内容为空" }
        return annotationDao.insertReply(
            AnnotationReplyEntity(
                annotationId = annotationId,
                personaId = personaId,
                replyToId = replyToId,
                contentMarkdown = contentMarkdown.trim(),
                mediaJson = mediaJson,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteReply(replyId: Long) {
        annotationDao.deleteReply(replyId)
    }
}
