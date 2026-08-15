package com.mozhi.reader.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫描的取舍规则是纯函数，可以脱离 SAF 测。真正的目录遍历需要 ContentResolver，
 * 留给真机验收。
 */
class FolderScannerFilterTest {

    @Test
    fun acceptsOnlySupportedBookExtensions() {
        assertTrue(FolderScanner.isSupportedBook("三体.txt"))
        assertTrue(FolderScanner.isSupportedBook("Dune.EPUB"))
        assertFalse(FolderScanner.isSupportedBook("封面.jpg"))
        assertFalse(FolderScanner.isSupportedBook("说明"))
        assertFalse(FolderScanner.isSupportedBook("book.txt.bak"))
    }

    @Test
    fun skipsHiddenAndCacheDirectories() {
        assertTrue(FolderScanner.isSkippableDirectory(".thumbnails"))
        assertTrue(FolderScanner.isSkippableDirectory("Android"))
        assertTrue(FolderScanner.isSkippableDirectory("cache"))
        assertFalse(FolderScanner.isSkippableDirectory("小说"))
        assertFalse(FolderScanner.isSkippableDirectory("Books"))
    }

    @Test
    fun marksFilesWhoseNameMatchesAnExistingBook() {
        val existing = setOf("三体", "活着")

        assertTrue(FolderScanner.looksImported("三体.txt", existing))
        assertTrue("扩展名不同仍算同名书", FolderScanner.looksImported("三体.epub", existing))
        assertFalse(FolderScanner.looksImported("三体 II.txt", existing))
        assertFalse(FolderScanner.looksImported("白夜行.txt", emptySet()))
    }

    @Test
    fun groupsByDirectoryInStableOrder() {
        val files = listOf(
            "科幻" to "b.txt",
            "" to "a.txt",
            "科幻" to "c.txt",
            "历史/近代" to "d.txt"
        )

        val groups = FolderScanner.groupByDirectory(files) { it.first }

        // 根目录排最前，其余按路径字典序；同组内保持扫描时的相对顺序。
        assertEquals(listOf("", "历史/近代", "科幻"), groups.map { it.first })
        assertEquals(2, groups.first { it.first == "科幻" }.second.size)
    }
}
