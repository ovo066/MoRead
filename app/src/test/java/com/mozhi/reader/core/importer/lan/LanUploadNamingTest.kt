package com.mozhi.reader.core.importer.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanUploadNamingTest {

    @Test
    fun keepsOrdinaryChineseNames() {
        assertEquals("三体 全集.txt", LanUploadNaming.sanitize("三体 全集.txt"))
        assertEquals("Dune.epub", LanUploadNaming.sanitize("Dune.epub"))
    }

    /** 路径穿越是传书服务最直接的攻击面：只能留下最后一节文件名。 */
    @Test
    fun stripsPathTraversalAttempts() {
        assertEquals("moread.txt", LanUploadNaming.sanitize("../../databases/moread.txt"))
        assertEquals("a.txt", LanUploadNaming.sanitize("C:\\Windows\\System32\\a.txt"))
        assertEquals("x.epub", LanUploadNaming.sanitize("/etc/x.epub"))
    }

    @Test
    fun rejectsUnsupportedOrEmptyNames() {
        assertNull(LanUploadNaming.sanitize("恶意.apk"))
        assertNull(LanUploadNaming.sanitize("无扩展名"))
        assertNull(LanUploadNaming.sanitize(""))
        assertNull(LanUploadNaming.sanitize(null))
        assertNull("`..` 去掉路径后没有扩展名，必须拒绝", LanUploadNaming.sanitize(".."))
    }

    @Test
    fun replacesControlAndReservedCharacters() {
        val cleaned = LanUploadNaming.sanitize("书\u0000名<:*?>|.txt")!!
        assertTrue(cleaned.endsWith(".txt"))
        assertTrue(
            "清洗后不得残留保留字符：$cleaned",
            listOf('<', ':', '*', '?', '>', '|', '\u0000').none { it in cleaned }
        )
    }

    @Test
    fun blankStemFallsBackInsteadOfProducingDotFile() {
        assertEquals("未命名.txt", LanUploadNaming.sanitize("...txt"))
    }

    @Test
    fun escapesWindowsReservedStems() {
        assertEquals("_CON.txt", LanUploadNaming.sanitize("CON.txt"))
        assertEquals("_nul.epub", LanUploadNaming.sanitize("nul.epub"))
    }

    @Test
    fun truncatesOverlongNames() {
        val long = "长".repeat(400) + ".txt"
        val cleaned = LanUploadNaming.sanitize(long)!!
        assertEquals(LanUploadNaming.MAX_NAME_CHARS + ".txt".length, cleaned.length)
    }

    @Test
    fun deduplicatesAgainstExistingFiles() {
        val taken = setOf("三体.txt", "三体 (2).txt")

        assertEquals("三体 (3).txt", LanUploadNaming.uniqueName("三体.txt", taken))
        assertEquals("活着.txt", LanUploadNaming.uniqueName("活着.txt", taken))
    }

    @Test
    fun extensionMatchIsCaseInsensitiveAndNormalized() {
        // 大写扩展名可以收，但统一规范成小写，后续按扩展名分流不必再处处 ignoreCase。
        assertEquals("A.txt", LanUploadNaming.sanitize("A.TXT"))
        assertEquals("B.epub", LanUploadNaming.sanitize("B.EPUB"))
    }
}
