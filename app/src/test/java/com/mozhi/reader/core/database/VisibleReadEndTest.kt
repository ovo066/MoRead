package com.mozhi.reader.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class VisibleReadEndTest {
    @Test fun onlyHighWaterAdvancesAndBacktrackingCannotLowerIt() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val original = BookEntity(id = 1, title = "书", author = "", coverPath = null, epubPath = "",
                sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 10,
                lastReadLocator = "resume-anchor", lastReadChapterIndex = 2, lastReadCharOffset = 10,
                lastReadAt = 123, maxReachedChapterIndex = 2, maxReachedCharOffset = 10, reachedEnd = false)
            val dao = database.bookDao()
            dao.insertBook(original)
            dao.markVisibleReadEnd(1, 2, 80)
            assertEquals(original.copy(maxReachedCharOffset = 80), dao.getBook(1))
            dao.markVisibleReadEnd(1, 2, 40)
            dao.markVisibleReadEnd(1, 1, 999)
            dao.markVisibleReadEnd(1, -1, 500)
            dao.markVisibleReadEnd(1, 3, -1)
            assertEquals(original.copy(maxReachedCharOffset = 80), dao.getBook(1))
            dao.markVisibleReadEnd(1, 3, 5)
            assertEquals(original.copy(maxReachedChapterIndex = 3, maxReachedCharOffset = 5), dao.getBook(1))
            dao.markVisibleReadEnd(999, 3, 5) // deleted/missing book is a harmless no-op
            assertEquals(original.copy(maxReachedChapterIndex = 3, maxReachedCharOffset = 5), dao.getBook(1))
        } finally { database.close() }
    }
}
