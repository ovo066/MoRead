package com.mozhi.reader.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mozhi.reader.core.database.dao.AiProviderDao
import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.dao.AudiobookDao
import com.mozhi.reader.core.database.dao.BookDao
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.dao.IllustrationDao
import com.mozhi.reader.core.database.dao.NoteDao
import com.mozhi.reader.core.database.dao.PersonaDao
import com.mozhi.reader.core.database.dao.ShelfOrganizationDao
import com.mozhi.reader.core.database.dao.TtsVoiceDao
import com.mozhi.reader.core.database.entity.AiCreationEntity
import com.mozhi.reader.core.database.entity.AiCreationVersionEntity
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.AudiobookChapterEntity
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.ModelAssignmentEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.core.database.entity.TtsVoiceEntity

@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        ReadingDailyEntity::class,
        AiProviderEntity::class,
        AiModelEntity::class,
        ModelAssignmentEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        PersonaEntity::class,
        AnnotationEntity::class,
        AnnotationReplyEntity::class,
        AiCreationEntity::class,
        AiCreationVersionEntity::class,
        NoteEntity::class,
        IllustrationEntity::class,
        ShelfGroupEntity::class,
        BookCollectionEntity::class,
        BookTagEntity::class,
        BookTagRefEntity::class,
        TtsVoiceEntity::class,
        AudiobookRoleEntity::class,
        AudiobookSegmentEntity::class,
        AudiobookChapterEntity::class,
        BookTocEntryEntity::class
    ],
    version = 23,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class MoReadDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun aiProviderDao(): AiProviderDao
    abstract fun chatDao(): ChatDao
    abstract fun personaDao(): PersonaDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun noteDao(): NoteDao
    abstract fun illustrationDao(): IllustrationDao
    abstract fun shelfOrganizationDao(): ShelfOrganizationDao
    abstract fun ttsVoiceDao(): TtsVoiceDao
    abstract fun audiobookDao(): AudiobookDao
}
