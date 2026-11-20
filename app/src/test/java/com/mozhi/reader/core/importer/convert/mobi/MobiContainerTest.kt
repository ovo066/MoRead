package com.mozhi.reader.core.importer.convert.mobi

import com.mozhi.reader.core.importer.convert.BookConversionException.Reason
import com.mozhi.reader.core.importer.convert.BookConverterTest
import com.mozhi.reader.core.importer.convert.BookDocumentInput
import com.mozhi.reader.core.importer.convert.mobi.MobiBinaryTest.Companion.expect
import com.mozhi.reader.core.importer.convert.mobi.MobiTestData.magic
import com.mozhi.reader.core.importer.convert.mobi.MobiTestData.put16
import com.mozhi.reader.core.importer.convert.mobi.MobiTestData.put32
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Base64
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobiContainerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun convert(bytes: ByteArray) = MobiBookConverter().convert(temporary.newFile().apply { writeBytes(bytes) }, "sample.azw3")

    @Test fun encryptedHuffBadOffsetsAndNonPalmFilesHaveSpecificReasons() {
        val text = "<p>正文</p>".toByteArray()
        expect(Reason.DRM_PROTECTED) { convert(MobiTestData.pdb(listOf(MobiTestData.header(text.size, encryption = 2), text))) }
        expect(Reason.UNSUPPORTED_FORMAT) { convert(MobiTestData.pdb(listOf(MobiTestData.header(text.size, compression = 17480), text))) }
        val outside = MobiTestData.pdb(listOf(MobiTestData.header(text.size), text)).apply { put32(78, size + 1) }
        expect(Reason.CORRUPT_FILE) { convert(outside) }
        expect(Reason.UNSUPPORTED_FORMAT) { convert("not a Palm database".toByteArray()) }
        expect(Reason.UNSUPPORTED_FORMAT) { convert(ByteArray(128)) }
    }

    @Test fun hostileHeaderLengthsCountsAndTruncatedExthAreBounded() {
        val text = "<p>正文</p>".toByteArray()
        listOf<(ByteArray) -> Unit>(
            { it.put32(20, Int.MAX_VALUE) }, { it.put32(84, Int.MAX_VALUE) },
            { it.put32(88, Int.MAX_VALUE) }, { it.put16(8, 65535) },
            { it.put32(128, 64) }, { it.put32(244, Int.MAX_VALUE) }
        ).forEach { mutation ->
            expect(Reason.CORRUPT_FILE) { convert(MobiTestData.pdb(listOf(MobiTestData.header(text.size).also(mutation), text))) }
        }
        expect(Reason.TEXT_TOO_LARGE) { convert(MobiTestData.pdb(listOf(MobiTestData.header(MobiBinary.MAX_TEXT_BYTES + 1), text))) }
        // 不创建 256 MiB 测试数组，用缩小的同一路径预算证明流式复制会拒绝越限数据。
        expect(Reason.MOBI_TOO_LARGE) {
            BookDocumentInput.bounded(ByteArrayInputStream(ByteArray(17)), 16, Reason.MOBI_TOO_LARGE).use { it.readBytes() }
        }
    }

    @Test fun sourceLimitIsCheckedWithoutReadingTheWholeFile() {
        val file = temporary.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(MobiBookConverter.MAX_SOURCE_BYTES + 1L) }
        expect(Reason.MOBI_TOO_LARGE) { MobiBookConverter().convert(file) }
    }

    @Test fun shortHeadersDoNotReadAbsentFieldsAndExthTitleHasPrecedence() {
        val text = "<p>caf\u00e9</p>".toByteArray(Charset.forName("windows-1252"))
        val short = MobiTestData.header(text.size, version = 7).copyOf(40).apply {
            put32(20, 24); put32(28, 1252)
        }
        val fallback = convert(MobiTestData.pdb(listOf(short, text)))
        assertEquals("sample", fallback.title)
        assertEquals("und", fallback.language)
        assertTrue(fallback.chapters.single().xhtmlBody.contains("caf\u00e9"))
        val unicode = "<p>中文</p>".toByteArray()
        val explicit = MobiTestData.header(unicode.size, exth = listOf(503 to "EXTH 书名".toByteArray()))
        assertEquals("EXTH 书名", convert(MobiTestData.pdb(listOf(explicit, unicode))).title)
    }

    @Test fun oldNcxOffsetsAreBytesAndPrivateLinksDoNotSurvive() {
        val one = "<h1>起点</h1><p>中文甲。</p><a filepos='8'>链接文字</a><mbp:test>私有文字</mbp:test>".toByteArray()
        val two = "<h1>终点</h1><p>中文乙。</p>".toByteArray()
        val firstLabelSize = MobiTestData.vint("起点".toByteArray().size).size + "起点".toByteArray().size
        val ncx = MobiTestData.index(listOf(1 to 1, 2 to 1, 3 to 1, 4 to 1), listOf(
            "0" to listOf(0, one.size, 0, 0), "1" to listOf(one.size, two.size, firstLabelSize, 0)), listOf("起点", "终点"))
        val header = MobiTestData.header(one.size + two.size, exth = listOf(
            100 to "甲作者".toByteArray(), 100 to "乙作者".toByteArray(), 524 to "zh".toByteArray()
        )).apply { put32(244, 2) }
        val book = convert(MobiTestData.pdb(listOf(header, one + two) + ncx))
        assertEquals("墨知样书", book.title)
        assertEquals("甲作者、乙作者", book.author)
        assertEquals("zh", book.language)
        assertEquals(listOf("起点", "终点"), book.chapters.map { it.title })
        assertFalse(book.chapters[0].xhtmlBody.contains("中文乙"))
        assertTrue(book.chapters[0].xhtmlBody.contains("链接文字"))
        assertFalse(book.chapters[0].xhtmlBody.contains("<a"))
        assertFalse(book.chapters[0].xhtmlBody.contains("mbp:"))
    }

    @Test fun oldPagebreakThenHeadingFallbacksShareHtmlSanitizer() {
        for (separator in listOf("<mbp:pagebreak/>", "")) {
            val text = "<h1>起点</h1><p>甲。</p>$separator<h1>终点</h1><blockquote><b>乙。</b></blockquote><script>secret</script>".toByteArray()
            val book = convert(MobiTestData.pdb(listOf(MobiTestData.header(text.size), text)))
            assertEquals(listOf("起点", "终点"), book.chapters.map { it.title })
            assertFalse(book.chapters.joinToString { it.xhtmlBody }.contains("secret"))
            assertTrue(book.chapters[1].xhtmlBody.contains("<blockquote>"))
        }
    }

    @Test fun kf8AndCombinedBooksUseRelativeRecordsAndKeepUntitledSpineParts() {
        val records = kf8Records()
        val pure = convert(MobiTestData.pdb(records))
        assertEquals(pure.chapters, convert(MobiTestData.pdb(kf8Records(localStarts = true))).chapters)
        for (boundaryPointsAtHeader in listOf(false, true)) {
            val oldText = "<p>旧段不能进入结果</p>".toByteArray()
            val old = MobiTestData.header(oldText.size, exth = listOf(121 to MobiTestData.integer(if (boundaryPointsAtHeader) 3 else 2)))
            val combined = convert(MobiTestData.pdb(listOf(old, oldText, "BOUNDARY".toByteArray()) + records))
            assertEquals(pure.chapters, combined.chapters)
            assertFalse(combined.chapters.any { it.xhtmlBody.contains("旧段") })
        }
        assertEquals(2, pure.chapters.size)
        assertEquals(listOf("起点"), pure.chapters.filter { it.inToc }.map { it.title })
        assertTrue(pure.chapters[0].xhtmlBody.contains("第二片段"))
        assertFalse(pure.chapters[0].xhtmlBody.contains("附录正文"))
        assertTrue(pure.chapters[1].xhtmlBody.contains("附录正文"))
        assertNotNull(pure.coverHref)
        val image = pure.resources.single { it.mediaType == "image/png" }
        assertEquals(image.href, pure.coverHref)
        assertEquals(image.href, Jsoup.parse(pure.chapters[0].xhtmlBody).selectFirst("img")!!.attr("src"))
        val css = pure.resources.single { it.mediaType == "text/css" }.bytes.toString(Charsets.UTF_8)
        assertTrue(css.contains(".note"))
        assertTrue(css.contains(image.href))
        assertFalse(css.contains("@font-face"))
        assertFalse(css.contains("kindle:"))
        pure.chapters.forEach { BookConverterTest.assertXhtml(it.xhtmlBody) }
    }

    @Test fun malformedIndicesAndFdstFailBeforeSlicing() {
        val brokenIdxt = kf8Records().map { it.copyOf() }.also { it[4].put32(20, Int.MAX_VALUE) }
        expect(Reason.CORRUPT_FILE) { convert(MobiTestData.pdb(brokenIdxt)) }
        val brokenFdst = kf8Records().map { it.copyOf() }.also { it[2].put32(16, Int.MAX_VALUE) }
        expect(Reason.CORRUPT_FILE) { convert(MobiTestData.pdb(brokenFdst)) }
    }

    @Test fun onlyImageMagicIsAccepted() {
        listOf("FLIS", "FCIS", "SRCS", "RESC", "CMET", "FONT", "BOUNDARY", "DATP", "AUDI", "VIDE", "\u00e9\u008e\r\n").forEach {
            assertNull(MobiBookConverter.imageType(it.toByteArray()))
        }
        assertEquals("image/svg+xml", MobiBookConverter.imageType("<?xml version='1.0'?><svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()))
    }

    private fun kf8Records(localStarts: Boolean = false): List<ByteArray> {
        val skeleton = "<html><body></body></html>".toByteArray()
        val first = "<h1>起点</h1><p>甲正文。</p><img src='kindle:embed:0001?mime=image/png'>".toByteArray()
        val second = "<p>第二片段</p>".toByteArray()
        val appendix = "<p>附录正文</p>".toByteArray()
        val next = skeleton.size + first.size + second.size
        val flow0 = skeleton + first + second + skeleton + appendix
        val css = ".note { color: #aa3333; background:url(kindle:embed:0001?mime=image/png); } @font-face { src:url(kindle:embed:0002?mime=font/ttf); }".toByteArray()
        val fdst = ByteArray(28).apply {
            magic(0, "FDST"); put32(4, 12); put32(8, 2); put32(12, 0); put32(16, flow0.size)
            put32(20, flow0.size); put32(24, flow0.size + css.size)
        }
        val skel = MobiTestData.index(listOf(1 to 1, 6 to 2), listOf(
            "0" to listOf(2, 0, skeleton.size), "1" to listOf(1, next, skeleton.size)))
        val frag = MobiTestData.index(listOf(3 to 1, 4 to 1, 6 to 2), listOf(
            "12" to listOf(0, 0, 0, first.size), "${12 + first.size}" to listOf(0, 1, if (localStarts) 0 else first.size, second.size),
            "${next + 12}" to listOf(1, 2, 0, appendix.size)))
        val ncx = MobiTestData.index(listOf(3 to 1, 4 to 1, 6 to 2), listOf("0" to listOf(0, 0, 0, 0)), listOf("起点"))
        val header = MobiTestData.header(flow0.size + css.size, version = 8, exth = listOf(201 to MobiTestData.integer(0))).apply {
            put32(192, 2); put32(196, 2); put32(252, 3); put32(248, 5); put32(244, 7); put32(108, 10)
        }
        return listOf(header, flow0 + css, fdst) + skel + frag + ncx +
            listOf(Base64.getDecoder().decode(BookConverterTest.PNG), "FONT".toByteArray())
    }
}
