package com.mozhi.reader.core.backup

import com.mozhi.reader.core.datastore.ReaderImageImporter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchivePathsTest {
    @Test
    fun rejectsZipSlipAndUnknownRoots() {
        assertTrue(isSafeBackupEntry("files/books/1.epub"))
        assertTrue(isSafeBackupEntry("database/moread.db"))
        assertFalse(isSafeBackupEntry("../databases/moread.db"))
        assertFalse(isSafeBackupEntry("files/../../secret"))
        assertFalse(isSafeBackupEntry("shared_prefs/credentials.xml"))
    }

    @Test
    fun fullBackupIncludesReaderImageLibrary() {
        assertTrue(
            BackupArchiveManager.MANAGED_FILE_DIRECTORIES.contains(
                ReaderImageImporter.IMAGE_LIBRARY_DIRECTORY
            )
        )
    }

    @Test
    fun lightweightBackupExcludesLargeBookDirectories() {
        val directories = BackupArchiveManager.directoriesFor(BackupMode.LIGHTWEIGHT)
        assertFalse("books" in directories)
        assertFalse("book-text" in directories)
        assertFalse("book-media" in directories)
        assertFalse("illustrations" in directories)
        assertTrue("covers" in directories)
        assertTrue(ReaderImageImporter.IMAGE_LIBRARY_DIRECTORY in directories)
    }

    @Test
    fun backupFileNameDescribesPackageMode() {
        val timestamp = 0L
        assertTrue(BackupArchiveManager.backupFileName(timestamp, BackupMode.FULL).startsWith("backup-full-"))
        assertTrue(BackupArchiveManager.backupFileName(timestamp, BackupMode.LIGHTWEIGHT).startsWith("backup-lite-"))
        assertTrue(BackupArchiveManager.backupFileName(timestamp).endsWith(WebDavClient.BACKUP_EXTENSION))
    }

    @Test
    fun configuredStateRequiresWebDavEndpointAndUsername() {
        assertTrue(
            BackupSettings(
                webDavUrl = "https://dav.example.com",
                username = "reader"
            ).configured
        )
        assertFalse(BackupSettings(webDavUrl = "https://dav.example.com").configured)
        assertFalse(BackupSettings(username = "reader").configured)
    }
}
