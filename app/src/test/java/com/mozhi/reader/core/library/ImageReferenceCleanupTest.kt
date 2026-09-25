package com.mozhi.reader.core.library

import android.content.Context
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.datastore.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageReferenceCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bookDeletionKeepsSharedAndInactiveThemeReferences() = runBlocking {
        val directory = temporary.newFolder("reader-images")
        val assets = listOf("unused", "shared", "theme-id", "theme-path", "other-book").map { id ->
            val file = File(directory, "$id.png").apply { writeText("image") }
            ReaderImageAsset(id, id, file.path, ownerBookId = if (id == "other-book") 2L else 1L)
        }
        val preferences = ReaderSettings(imageLibrary = assets, customThemes = listOf(
            CustomReaderTheme(1, "Saved theme", 0, 0, 0, backgroundImageId = "theme-id"),
            CustomReaderTheme(2, "Legacy theme", 0, 0, 0, backgroundImagePath = assets[3].filePath)
        ))
        val settings = mockk<ReaderSettingsRepository> {
            every { settings } returns flowOf(preferences)
            coEvery { removeReaderImage(any()) } just Runs
        }
        val database = mockk<MoReadDatabase> {
            coEvery { imageConsistencyDao().referenceDocuments() } returns listOf("{\"referenceIds\":[\"shared\"]}")
            coEvery { bookDao().getAllBooks() } returns emptyList()
            coEvery { personaDao().getPersonas() } returns emptyList()
        }
        val context = mockk<Context> { every { filesDir } returns temporary.root }
        ImageReferenceCleanup(context, database, settings).forDeletedBook(1)
        coVerify(exactly = 1) { settings.removeReaderImage("unused") }
        coVerify(exactly = 1) { settings.removeReaderImage(any()) }
        assertFalse(File(assets.first().filePath).exists())
        assertTrue(assets.drop(1).all { File(it.filePath).isFile })
    }
}
