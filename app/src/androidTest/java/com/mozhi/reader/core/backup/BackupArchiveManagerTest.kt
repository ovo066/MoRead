package com.mozhi.reader.core.backup

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.core.IsolatedTestContext
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupArchiveManagerTest {
    private val context = IsolatedTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private var database = openDatabase()

    @After
    fun closeDatabase() {
        database.close()
        context.root.deleteRecursively()
    }

    @Test
    fun currentRoomSchemaCanCreateValidateAndRestoreItsOwnBackup() = runBlocking {
        val book = BookEntity(
            id = 1,
            title = "备份中的书",
            author = "作者",
            coverPath = null,
            epubPath = "/books/backup.epub",
            sourceType = BookSourceType.EPUB,
            importedAt = 1000,
            totalChapters = 1
        )
        database.bookDao().insertBook(book)
        // This reads Room's generated schema version, which comes from @Database.
        val schemaVersion = database.openHelper.writableDatabase.version
        assertEquals(schemaVersion, BackupArchiveManager.CURRENT_DATABASE_VERSION)
        val manager = BackupArchiveManager(context, database)

        val archive = manager.create(BackupMode.LIGHTWEIGHT)
        val manifest = manager.validate(archive)
        assertEquals(schemaVersion, manifest.databaseVersion)
        assertEquals(manifest, manager.stageRestore(archive.inputStream()))

        val stagedDatabase = File(
            BackupArchiveManager.preparedRestoreDirectory(context),
            BackupArchiveManager.DATABASE_ENTRY
        )
        SQLiteDatabase.openDatabase(stagedDatabase.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { snapshot ->
            snapshot.rawQuery("SELECT title FROM books WHERE id = ?", arrayOf(book.id.toString())).use { cursor ->
                assertTrue("The backup must include committed rows from the WAL", cursor.moveToFirst())
                assertEquals(book.title, cursor.getString(0))
            }
        }

        database.bookDao().deleteBook(book.id)
        database.close()
        BackupRestoreBootstrap.applyPending(context)
        database = openDatabase()

        assertEquals(book, database.bookDao().getBook(book.id))
        assertFalse(BackupArchiveManager.preparedRestoreDirectory(context).exists())
    }

    private fun openDatabase(): MoReadDatabase = Room.databaseBuilder(
        context,
        MoReadDatabase::class.java,
        context.getDatabasePath(BackupArchiveManager.DATABASE_NAME).absolutePath
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
}
