package com.mozhi.reader.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.DatabaseMigrations
import com.mozhi.reader.core.database.PersonaSeeds
import com.mozhi.reader.core.database.dao.AiProviderDao
import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.dao.BookDao
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.dao.IllustrationDao
import com.mozhi.reader.core.database.dao.NoteDao
import com.mozhi.reader.core.database.dao.PersonaDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.readerSettingsDataStore by preferencesDataStore(name = "reader_settings")

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MoReadDatabase =
        Room.databaseBuilder(
            context,
            MoReadDatabase::class.java,
            "moread.db"
        )
            .addMigrations(
                DatabaseMigrations.Migration1To2,
                DatabaseMigrations.Migration2To3,
                DatabaseMigrations.Migration3To4,
                DatabaseMigrations.Migration4To5,
                DatabaseMigrations.Migration5To6,
                DatabaseMigrations.Migration6To7,
                DatabaseMigrations.Migration7To8,
                DatabaseMigrations.Migration8To9,
                DatabaseMigrations.Migration9To10,
                DatabaseMigrations.Migration10To11,
                DatabaseMigrations.Migration11To12,
                DatabaseMigrations.Migration12To13,
                DatabaseMigrations.Migration13To14
            )
            .addCallback(PersonaSeeds.onCreate)
            .build()

    @Provides
    fun provideBookDao(database: MoReadDatabase): BookDao = database.bookDao()

    @Provides
    fun provideAiProviderDao(database: MoReadDatabase): AiProviderDao = database.aiProviderDao()

    @Provides
    fun provideChatDao(database: MoReadDatabase): ChatDao = database.chatDao()

    @Provides
    fun providePersonaDao(database: MoReadDatabase): PersonaDao = database.personaDao()

    @Provides
    fun provideAnnotationDao(database: MoReadDatabase): AnnotationDao = database.annotationDao()

    @Provides
    fun provideNoteDao(database: MoReadDatabase): NoteDao = database.noteDao()

    @Provides
    fun provideIllustrationDao(database: MoReadDatabase): IllustrationDao = database.illustrationDao()

    @Provides
    @Singleton
    fun provideReaderSettingsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.readerSettingsDataStore
}
