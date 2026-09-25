package com.mozhi.reader.core.database.dao

import androidx.room.*
import com.mozhi.reader.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ImageConsistencyDao {
    @Query("SELECT * FROM book_image_styles WHERE bookId = :bookId")
    fun observeStyle(bookId: Long): Flow<BookImageStyleEntity?>
    @Query("SELECT * FROM book_image_styles WHERE bookId = :bookId")
    suspend fun style(bookId: Long): BookImageStyleEntity?
    @Query("SELECT * FROM character_looks WHERE bookId = :bookId ORDER BY sinceChapter")
    fun observeLooks(bookId: Long): Flow<List<CharacterLookEntity>>
    @Query("SELECT * FROM character_looks WHERE bookId = :bookId ORDER BY sinceChapter")
    suspend fun looks(bookId: Long): List<CharacterLookEntity>
    @Upsert suspend fun saveStyle(value: BookImageStyleEntity)
    @Query("SELECT * FROM image_style_templates ORDER BY updatedAt DESC, id")
    fun observeTemplates(): Flow<List<ImageStyleTemplateEntity>>
    @Query("SELECT * FROM image_style_templates WHERE id = :id")
    suspend fun template(id: String): ImageStyleTemplateEntity?
    @Upsert suspend fun saveTemplate(value: ImageStyleTemplateEntity)
    @Query("DELETE FROM image_style_templates WHERE id = :id")
    suspend fun deleteTemplate(id: String)
    @Upsert suspend fun saveLook(value: CharacterLookEntity)
    @Query("DELETE FROM character_looks WHERE id = :id") suspend fun deleteLook(id: String)
    @Query("SELECT * FROM illustration_queue WHERE bookId = :bookId ORDER BY createdAt, chapterIndex")
    fun observeQueue(bookId: Long): Flow<List<IllustrationQueueEntity>>
    @Query("SELECT * FROM illustration_queue WHERE bookId = :bookId ORDER BY createdAt, chapterIndex")
    suspend fun queue(bookId: Long): List<IllustrationQueueEntity>
    @Upsert suspend fun saveQueue(value: IllustrationQueueEntity)
    @Upsert suspend fun saveQueue(values: List<IllustrationQueueEntity>)
    @Query("DELETE FROM illustration_queue WHERE bookId = :bookId AND status != 'done'") suspend fun clearPending(bookId: Long)
    @Query("SELECT specJson FROM book_image_styles UNION ALL SELECT specJson FROM image_style_templates UNION ALL SELECT specJson FROM character_looks UNION ALL SELECT recipeJson FROM illustrations UNION ALL SELECT recipeJson FROM illustration_queue")
    suspend fun referenceDocuments(): List<String>
}
