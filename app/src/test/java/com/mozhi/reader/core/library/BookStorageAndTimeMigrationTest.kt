package com.mozhi.reader.core.library

import android.app.Application
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.mozhi.reader.core.database.DatabaseMigrations
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import dagger.Lazy
import java.io.File
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.TimeZone
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
class BookStorageAndTimeMigrationTest {
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
        library = LibraryRepository(context, db, db.bookDao(), BookTextStore(context), BookTextWriter(), BookMediaStore(context),
            BookLayoutStore(context), Lazy { error("optional index unavailable") })
    }
    @After fun close() { db.close() }
    private fun book(path: String = "") = BookEntity(title = "书", author = "", coverPath = null, epubPath = path,
        sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 1)

    @Test fun generatedTxtArchiveIsRemovedOnlyAfterAllCanonicalTextHasBeenVerified() = runBlocking {
        val archive = File(context.filesDir, "books/old.epub").apply { parentFile.mkdirs(); writeText("generated copy") }
        val body = "读过的正文🙂，坐标要保留。".repeat(3000)
        val id = library.insertBook(book(archive.path), listOf(ChapterDraft(0, "第一章", "", body.length)))
        library.materializeBookText(id, listOf(ChapterTextInput(0, body)))
        val revision = library.bookTextRevision(id)
        assertTrue(library.discardGeneratedTxtEpub(id) > 0)
        assertFalse(archive.exists())
        assertEquals("", library.getBook(id)!!.epubPath)
        assertEquals(revision, library.bookTextRevision(id))
        assertEquals(body, library.readChapterTextStrict(id, library.getChapters(id).single()))
    }

    @Test fun missingTextDoesNotDiscardTheTxtRecoveryArchive() = runBlocking {
        val archive = File(context.filesDir, "books/old.epub").apply { parentFile.mkdirs(); writeText("recovery") }
        val id = library.insertBook(book(archive.path), listOf(ChapterDraft(0, "第一章", "", 2)))
        library.materializeBookText(id, listOf(ChapterTextInput(0, "正文")))
        BookTextStore(context).textFile(id).writeText("坏")
        assertTrue(runCatching { library.discardGeneratedTxtEpub(id) }.isFailure)
        assertTrue(archive.isFile)
        assertEquals(archive.path, library.getBook(id)!!.epubPath)
    }

    @Test fun hourlyAndDailyTotalsAgreeAndFollowTheBooksRetentionPolicy() = runBlocking {
        val oldZone = TimeZone.getDefault()
        try {
            val zone = ZoneId.of("Asia/Shanghai")
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val id = db.bookDao().insertBook(book())
            val end = ZonedDateTime.of(2026, 9, 13, 1, 15, 0, 0, zone).toInstant().toEpochMilli()
            library.recordReadingDuration(id, 90 * 60_000L, end)
            library.recordReadingDuration(id, 2 * 60_000L, end + 2 * 60_000L)
            val hours = db.readingHourlyDao().getForBook(id)
            assertEquals(listOf(23, 0, 1), hours.map { it.hour })
            assertEquals(92 * 60_000L, hours.sumOf { it.durationMs })
            assertEquals(hours.sumOf { it.durationMs }, library.getReadingDays(id).sumOf { it.durationMs })
            library.removeBookKeepingRecords(library.getBook(id)!!)
            assertEquals(hours, db.readingHourlyDao().getForBook(id))
            library.deleteBook(library.getBook(id)!!)
            assertTrue(db.readingHourlyDao().getForBook(id).isEmpty())
        } finally { TimeZone.setDefault(oldZone) }
    }

    @Test fun schema29UpgradePreservesDailyHistoryWithoutInventingHourBuckets() = runBlocking {
        val relative = "schemas/com.mozhi.reader.core.database.MoReadDatabase/29.json"
        val schema = Json.parseToJsonElement(listOf(File(relative), File("app/$relative")).first { it.isFile }.readText())
            .jsonObject.getValue("database").jsonObject
        val path = context.getDatabasePath("hour-migration.db")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            schema.getValue("entities").jsonArray.forEach { item ->
                val entity = item.jsonObject
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content))
                entity["indices"]?.jsonArray.orEmpty().forEach { index ->
                    old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content))
                }
            }
            schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO books (id,title,author,coverPath,epubPath,sourceType,importedAt,totalChapters,lastReadLocator,lastReadChapterIndex,lastReadCharOffset,lastReadAt,textVersion,tags,metadataEdited) VALUES (1,'书','',NULL,'','TXT',1,1,NULL,0,0,0,4,'',0)")
            old.execSQL("INSERT INTO reading_daily VALUES (1,20709,60000,1)")
            old.version = 29
        }
        val upgraded = Room.databaseBuilder(context, MoReadDatabase::class.java, "hour-migration.db")
            .addMigrations(DatabaseMigrations.Migration29To30).build()
        try {
            assertEquals(60_000L, upgraded.bookDao().getReadingDays(1).single().durationMs)
            assertTrue(upgraded.readingHourlyDao().getForBook(1).isEmpty())
            assertEquals(MoReadDatabase.VERSION, upgraded.openHelper.writableDatabase.version)
        } finally { upgraded.close() }
    }
}
