package com.mozhi.reader.core.library

import android.app.Application
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.DatabaseMigrations
import com.mozhi.reader.core.database.entity.*
import dagger.Lazy
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryRepositoryRemovalTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ContextWrapper
    private lateinit var db: MoReadDatabase
    private lateinit var library: LibraryRepository
    @Before fun setup() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(temporary.root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(temporary.root, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(temporary.root, "databases/$name").apply { parentFile.mkdirs() }
        }
        db = Room.inMemoryDatabaseBuilder(context, MoReadDatabase::class.java).build()
        library = LibraryRepository(context, db, db.bookDao(), BookTextStore(context), BookTextWriter(),
            BookMediaStore(context), BookLayoutStore(context), Lazy { error("optional index unavailable") })
    }
    @After fun close() { db.close() }
    private fun file(path: String) = File(context.filesDir, path).apply { parentFile.mkdirs(); writeText("data") }

    @Test fun removingContentPreservesHistoryAndAttachmentsThenExplicitEraseDeletesThem() = runBlocking {
        val original = file("books/source.epub")
        val cover = file("covers/cover.png")
        val book = BookEntity(title = "保留记录", author = "", coverPath = cover.path, epubPath = original.path,
            sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 1, reachedEnd = true)
        val saved = book.copy(id = db.bookDao().insertBook(book))
        val conversation = db.chatDao().insertConversation(ConversationEntity(bookId = saved.id, title = "对话", type = "COMPANION", createdAt = 1))
        db.chatDao().insertMessage(MessageEntity(conversationId = conversation, role = "user", content = "保留内容", createdAt = 1))
        db.bookDao().insertBookmark(BookmarkEntity(bookId = saved.id, locatorJson = "{}", label = "书签", createdAt = 1))
        db.bookDao().insertReadingDay(ReadingDailyEntity(saved.id, 1, 60_000, 1))
        val body = file("book-text/${saved.id}/text.mz")
        val audio = file("speech-cache/${saved.id}/voice.mp3")
        val art = file("illustrations/${saved.id}/image.png")
        val attachment = file("attachments/$conversation/image.png")
        val collection = db.shelfOrganizationDao().createCollection("临时合集", listOf(saved.id))

        library.removeBookKeepingRecords(saved)
        val retained = requireNotNull(db.bookDao().getBook(saved.id))
        assertTrue(retained.removedAt > 0)
        assertTrue(library.getBooks().isEmpty())
        assertEquals(1, library.getBooksIncludingRemoved().size)
        assertTrue(db.shelfOrganizationDao().observeCollections().first().none { it.id == collection })
        assertEquals(60_000L, db.bookDao().observeReadingDays(saved.id).first().single().durationMs)
        assertEquals(1, db.bookDao().observeBookmarks(saved.id).first().size)
        assertEquals("保留内容", db.chatDao().getMessages(conversation).single().content)
        assertFalse(original.exists()); assertFalse(body.exists()); assertFalse(audio.exists())
        assertTrue(cover.exists()); assertTrue(art.exists()); assertTrue(attachment.exists())

        library.deleteBook(retained)
        assertNull(db.bookDao().getBook(saved.id))
        assertTrue(db.bookDao().observeAllReadingDays().first().isEmpty())
        assertTrue(db.chatDao().getMessages(conversation).isEmpty())
        assertFalse(cover.exists()); assertFalse(art.exists()); assertFalse(attachment.exists())
    }

    @Test fun deletingBookNeverDeletesExternalSourceOrSharedImageLibraryAsset() = runBlocking {
        val external = File(temporary.root, "user-original.epub").apply { writeText("user source") }
        val shared = file("reader-images/shared.png")
        val book = BookEntity(title = "共享", author = "", coverPath = shared.path, epubPath = external.path,
            sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 1)
        library.deleteBook(book.copy(id = db.bookDao().insertBook(book)))
        assertTrue(external.exists()); assertTrue(shared.exists())
    }

    @Test fun realVersion28DatabasePreservesSavedOutlinesAndAddsCharacterTables() = runBlocking {
        val relative = "schemas/com.mozhi.reader.core.database.MoReadDatabase/28.json"
        val schema = Json.parseToJsonElement(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText())
            .jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("knowledge-migration.db"), null).use { old ->
            schema.getValue("entities").jsonArray.forEach { item ->
                val entity = item.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                fun sql(raw: JsonElement) = raw.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                old.execSQL(sql(entity.getValue("createSql")))
                entity["indices"]?.jsonArray?.forEach { old.execSQL(sql(it.jsonObject.getValue("createSql"))) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO books (id,title,author,epubPath,sourceType,importedAt,totalChapters,lastReadChapterIndex,lastReadCharOffset,lastReadAt,textVersion,tags,metadataEdited) VALUES (1,'旧书','作者','/book.epub','EPUB',1,3,1,20,10,4,'',0)")
            old.execSQL("INSERT INTO chapter_knowledge (bookId,chapterIndex,sourceRevision,sourceEnd,sourceHash,modelKey,modelLabel,promptVersion,contentJson,createdAt) VALUES (1,0,'revision',100,'hash','key','model',1,'旧版章节资料',1)")
            old.version = 28
        }
        val migrated = Room.databaseBuilder(context, MoReadDatabase::class.java, "knowledge-migration.db")
            .addMigrations(DatabaseMigrations.Migration28To29).build()
        try {
            assertEquals("旧版章节资料", migrated.chapterKnowledgeDao().get(1, 0)?.contentJson)
            assertEquals(29, migrated.openHelper.writableDatabase.version)
            assertNull(migrated.bookCharacterDao().getGuide(1))
            assertTrue(migrated.bookCharacterDao().getParts(1).isEmpty())
            assertEquals("旧书", migrated.bookDao().getBook(1)?.title)
        } finally { migrated.close() }
    }

    @Test fun realVersion25DatabaseMigratesWithoutRemovingExistingBooks() = runBlocking {
        val relative = "schemas/com.mozhi.reader.core.database.MoReadDatabase/25.json"
        val schemaFile = listOf(File(relative), File("app/$relative")).first { it.isFile }
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("migration.db"), null).use { old ->
            schema.getValue("entities").jsonArray.forEach { item ->
                val entity = item.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                fun sql(raw: JsonElement) = raw.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                old.execSQL(sql(entity.getValue("createSql")))
                entity["indices"]?.jsonArray?.forEach { old.execSQL(sql(it.jsonObject.getValue("createSql"))) }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO books (id,title,author,epubPath,sourceType,importedAt,totalChapters,lastReadChapterIndex,lastReadCharOffset,lastReadAt,textVersion,tags,metadataEdited) VALUES (1,'旧书','作者','/book.epub','EPUB',1,3,1,20,10,4,'',0)")
            old.execSQL("INSERT INTO conversations (id,bookId,title,type,createdAt) VALUES (1,1,'旧对话','COMPANION',1)")
            old.execSQL("INSERT INTO messages (id,conversationId,role,content,createdAt) VALUES (1,1,'user','历史消息',1)")
            old.version = 25
        }
        val migrated = Room.databaseBuilder(context, MoReadDatabase::class.java, "migration.db")
            .addMigrations(DatabaseMigrations.Migration25To26, DatabaseMigrations.Migration26To27, DatabaseMigrations.Migration27To28, DatabaseMigrations.Migration28To29).build()
        try {
            val book = requireNotNull(migrated.bookDao().getBook(1))
            assertEquals("旧书", book.title)
            assertEquals(0L, book.removedAt)
            assertEquals(1, book.lastReadChapterIndex)
            assertEquals(20, book.lastReadCharOffset)
            assertEquals(29, migrated.openHelper.writableDatabase.version)
            assertEquals("[]", migrated.chatDao().getConversation(1)?.bookScopesJson)
            assertEquals("历史消息", migrated.chatDao().getMessages(1).single().content)
            assertNull(migrated.chatDao().getMessages(1).single().sourceBookIdsJson)
        } finally { migrated.close() }
    }
}
