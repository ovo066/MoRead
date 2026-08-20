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
}
