package com.mozhi.reader.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShelfCollectionDaoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: MoReadDatabase

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MoReadDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Before
    fun openMigratedDatabase() {
        helper.createDatabase(DB_NAME, 22).close()
        database = Room.databaseBuilder(context, MoReadDatabase::class.java, DB_NAME)
            .addMigrations(DatabaseMigrations.Migration22To23)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun collectionMovesPreserveOrderAndIndependentGroupTagData() = runBlocking {
        val shelfDao = database.shelfOrganizationDao()
        val bookDao = database.bookDao()
        val firstGroup = shelfDao.insertGroup(ShelfGroupEntity(name = "甲", createdAt = 1))
        val secondGroup = shelfDao.insertGroup(ShelfGroupEntity(name = "乙", createdAt = 2))
        val tagId = shelfDao.insertTag(BookTagEntity(name = "标签", colorTag = "blue", createdAt = 3))
        val first = bookDao.insertBook(book("一", 1, firstGroup))
        val second = bookDao.insertBook(book("二", 2, firstGroup))
        val third = bookDao.insertBook(book("三", 3, secondGroup))
        val refs = listOf(BookTagRefEntity(first, tagId), BookTagRefEntity(third, tagId))
        shelfDao.insertTagRefs(refs)

        shelfDao.createCollection("源", listOf(first, second))
        val destination = shelfDao.createCollection("目标", listOf(third))
        shelfDao.addBooksToCollection(destination, listOf(first, second))

        assertEquals(listOf(destination), shelfDao.observeCollections().first().map { it.id })
        assertEquals(
            listOf(third to 0, first to 1, second to 2),
            bookDao.getBooks().filter { it.collectionId == destination }
                .sortedBy(BookEntity::collectionOrder)
                .map { it.id to it.collectionOrder }
        )

        shelfDao.removeBooksFromCollection(listOf(first))
        assertNull(bookDao.getBook(first)?.collectionId)
        shelfDao.dissolveCollection(destination)

        assertEquals(emptyList<Long>(), shelfDao.observeCollections().first().map { it.id })
        assertEquals(listOf(firstGroup, firstGroup, secondGroup), bookDao.getBooks().map(BookEntity::groupId))
        assertEquals(refs.toSet(), shelfDao.observeTagRefs().first().toSet())
        assertEquals(listOf(null, null, null), bookDao.getBooks().map(BookEntity::collectionId))
    }

    @Test
    fun createCollectionRejectsEmptyMembers() = runBlocking {
        val shelfDao = database.shelfOrganizationDao()

        val failure = runCatching { shelfDao.createCollection("空合集", emptyList()) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(shelfDao.observeCollections().first().isEmpty())
    }

    @Test
    fun createCollectionRejectsMissingBooksWithoutMovingExistingMembers() = runBlocking {
        val shelfDao = database.shelfOrganizationDao()
        val bookDao = database.bookDao()
        val first = bookDao.insertBook(book("一", 1, 1))
        val original = shelfDao.createCollection("原合集", listOf(first, first))

        for (members in listOf(listOf(999_999L), listOf(first, 999_999L))) {
            val failure = runCatching { shelfDao.createCollection("新合集", members) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(listOf(original), shelfDao.observeCollections().first().map { it.id })
            assertEquals(original, bookDao.getBook(first)?.collectionId)
            assertEquals(0, bookDao.getBook(first)?.collectionOrder)
        }
    }

    private fun book(title: String, importedAt: Long, groupId: Long) = BookEntity(
        title = title,
        author = "",
        coverPath = null,
        epubPath = "/$title.epub",
        sourceType = BookSourceType.EPUB,
        importedAt = importedAt,
        totalChapters = 1,
        groupId = groupId
    )

    private companion object {
        const val DB_NAME = "shelf-collection-migration-test.db"
    }
}
