package com.mozhi.reader.core.library

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.core.IsolatedTestContext
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import dagger.Lazy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryRepositoryDeleteBookTest {
    private val context = IsolatedTestContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val database = Room.inMemoryDatabaseBuilder(context, MoReadDatabase::class.java).build()
    private val bookDao = database.bookDao()
    private val shelfDao = database.shelfOrganizationDao()
    private val repository = LibraryRepository(
        context, database, bookDao, BookTextStore(context), BookTextWriter(),
        BookMediaStore(context), BookLayoutStore(context),
        Lazy { error("No vector store is needed for collection cleanup") }
    )

    @After
    fun closeDatabase() {
        database.close()
        context.root.deleteRecursively()
    }

    @Test
    fun deletingLastMemberRemovesCollectionForAllRepositoryCallers() = runBlocking {
        val first = insertBook("一")
        val second = insertBook("二")
        val collection = shelfDao.createCollection("合集", listOf(first.id, second.id))

        repository.deleteBook(first)
        assertNull(bookDao.getBook(first.id))
        assertEquals(listOf(collection), shelfDao.observeCollections().first().map { it.id })
        assertEquals(collection, bookDao.getBook(second.id)?.collectionId)

        repository.deleteBook(second)
        assertNull(bookDao.getBook(second.id))
        assertTrue(shelfDao.observeCollections().first().isEmpty())
    }

    @Test
    fun cleanupFailureRollsBackBookDeletion() = runBlocking {
        val book = insertBook("保留")
        val collection = shelfDao.createCollection("合集", listOf(book.id))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_collection_cleanup BEFORE DELETE ON book_collections
            BEGIN SELECT RAISE(ABORT, 'collection cleanup failed'); END
            """.trimIndent()
        )

        val failure = runCatching { repository.deleteBook(book) }.exceptionOrNull()

        assertTrue(failure is SQLiteException)
        assertEquals(book.copy(collectionId = collection), bookDao.getBook(book.id))
        assertEquals(listOf(collection), shelfDao.observeCollections().first().map { it.id })
    }

    private suspend fun insertBook(title: String): BookEntity {
        val book = BookEntity(
            title = title,
            author = "",
            coverPath = null,
            epubPath = "/$title.epub",
            sourceType = BookSourceType.EPUB,
            importedAt = 1000,
            totalChapters = 1
        )
        return book.copy(id = bookDao.insertBook(book))
    }
}
