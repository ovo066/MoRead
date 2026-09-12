package com.mozhi.reader.core.storage

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = STORAGE_CLEANUP_MIN_AGE_MS * 3
    private fun entry(path: String, age: Long = STORAGE_CLEANUP_MIN_AGE_MS + 1): StorageFile =
        StorageFile(File(temporary.root, path).absoluteFile, path, 10L, now - age)

    @Test fun safeCleanupExcludesActiveImportsAudioAndCurrentInstaller() {
        assertTrue(isSafeTemporaryFile(entry("cache/updates/v1.0.8.apk"), now, "1.0.9"))
        assertTrue(isSafeTemporaryFile(entry("cache/export/notes.md"), now, "1.0.9"))
        listOf("cache/updates/v1.0.9.apk", "cache/updates/v1.0.8.apk.part", "cache/reader-font-import/a.ttf",
            "cache/reader-image-import/a.png", "cache/agent-speech/1/a.mp3", "backups/restore/a.zip").forEach {
            assertFalse(it, isSafeTemporaryFile(entry(it), now, "1.0.9"))
        }
        assertFalse(isSafeTemporaryFile(entry("cache/export/active.md", age = 100L), now, "1.0.9"))
    }

    @Test fun ownershipProtectsRemovedBookRecordsAndUnselectedLibraryAssets() {
        fun orphan(path: String) = isOrphanStorageFile(entry(path), setOf(7L), setOf(9L), emptySet(), now)
        assertTrue(orphan("files/book-layout/8/chapters/ch-1.json.gz"))
        assertTrue(orphan("files/attachments/8/a.png"))
        assertTrue(orphan("cache/agent-speech/8/a.mp3"))
        assertFalse(orphan("cache/agent-speech/7/a.mp3"))
        assertFalse(orphan("files/illustrations/7/a.png")) // includes retained book IDs
        assertFalse(orphan("files/attachments/9/a.png"))
        assertFalse(orphan("files/reader-images/unused.png"))
        assertFalse(orphan("files/reader-custom/unused.ttf"))
        assertFalse(orphan("files/book-layout/7.part/ch-1.json"))
        val original = entry("files/books/owned.epub")
        assertFalse(isOrphanStorageFile(original, emptySet(), emptySet(), setOf(original.file.canonicalPath), now))
        assertFalse(isOrphanStorageFile(entry("files/books/new.epub", 100), emptySet(), emptySet(), emptySet(), now))
    }

    @Test fun inventoryCountsNestedFilesOnceAndDeleteRejectsChangedFiles() {
        val root = temporary.newFolder("inventory")
        val file = File(root, "nested/a.txt").apply { parentFile.mkdirs(); writeText("hello") }
        val rows = storageFiles(root, "files")
        assertEquals(1, rows.size)
        assertEquals("files/nested/a.txt", rows.single().relativePath)
        assertEquals(5L, rows.single().bytes)
        file.appendText("changed")
        assertFalse(deleteUnchangedStorageFile(rows.single()))
        assertTrue(file.exists())
        assertTrue(deleteUnchangedStorageFile(storageFiles(root, "files").single()))
        assertFalse(file.exists())
    }

    @Test fun largeUncountedCategoriesHaveExplicitBuckets() {
        assertEquals(StorageCategory.VECTOR, categoryOf("files/objectbox/vectors/data.mdb"))
        assertEquals(StorageCategory.DATABASE, categoryOf("database/moread.db-wal"))
        assertEquals(StorageCategory.SPEECH, categoryOf("files/speech-cache/1/a.mp3"))
        assertEquals(StorageCategory.SPEECH, categoryOf("cache/agent-speech/1/a.mp3"))
        assertEquals(StorageCategory.CUSTOM, categoryOf("files/reader-images/a.png"))
        assertEquals(StorageCategory.OTHER, categoryOf("preferences/settings.xml"))
    }
}
