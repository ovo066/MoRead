package com.mozhi.reader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubMetadataResolverTest {
    /** 实际样本：WPS 导出经 Calibre 转换的 `(NEW)千钧雪.epub`，元数据全是占位值。 */
    @Test
    fun `falls back to file name when title is Unknown and author is a WPS id`() {
        val resolved = EpubMetadataResolver.resolve(
            rawTitle = "Unknown",
            rawAuthor = "WPS_1532705572",
            identifier = "284820e3-fede-41ee-ad47-e33ba66d65b6",
            navTitle = "Unknown",
            displayName = "(NEW)千钧雪.epub"
        )

        assertEquals("千钧雪", resolved.title)
        assertEquals("", resolved.author)
    }

    @Test
    fun `keeps good metadata untouched`() {
        val resolved = EpubMetadataResolver.resolve(
            rawTitle = "墨知 EPUB 示例",
            rawAuthor = "墨知项目组",
            identifier = "urn:uuid:moread-sample",
            displayName = "moread-sample.epub"
        )

        assertEquals("墨知 EPUB 示例", resolved.title)
        assertEquals("墨知项目组", resolved.author)
    }

    @Test
    fun `drops title and author that merely repeat the identifier`() {
        val resolved = EpubMetadataResolver.resolve(
            rawTitle = "urn:uuid:abcd-1234",
            rawAuthor = "urn:uuid:abcd-1234",
            identifier = "urn:uuid:abcd-1234",
            displayName = "沉默的大多数.epub"
        )

        assertEquals("沉默的大多数", resolved.title)
        assertEquals("", resolved.author)
    }

    @Test
    fun `blank and whitespace-only metadata falls through to the file name`() {
        val resolved = EpubMetadataResolver.resolve(
            rawTitle = "   ",
            rawAuthor = "\t",
            identifier = null,
            displayName = "活着.epub"
        )

        assertEquals("活着", resolved.title)
        assertEquals("", resolved.author)
    }

    @Test
    fun `null metadata with no usable file name yields the generic placeholder`() {
        val resolved = EpubMetadataResolver.resolve(
            rawTitle = null,
            rawAuthor = null,
            identifier = null,
            displayName = ""
        )

        assertEquals("未命名书籍", resolved.title)
        assertEquals("", resolved.author)
    }

    @Test
    fun `prefers the nav document title when the OPF title is a placeholder`() {
        val resolved = EpubMetadataResolver.resolve(
            rawTitle = "untitled",
            rawAuthor = "余华",
            identifier = null,
            navTitle = "许三观卖血记",
            displayName = "book-final-v2.epub"
        )

        assertEquals("许三观卖血记", resolved.title)
        assertEquals("余华", resolved.author)
    }

    @Test
    fun `recognises the common placeholder spellings in both languages`() {
        listOf("unknown", "UNKNOWN", "Untitled", "未知", "无标题", "未命名", "N/A", "null")
            .forEach { placeholder ->
                assertEquals(
                    "expected \"$placeholder\" to be treated as a placeholder",
                    null,
                    EpubMetadataResolver.cleanTitle(placeholder)
                )
            }
    }

    @Test
    fun `rejects tool-generated author names but keeps real ones`() {
        listOf("WPS_1532705572", "wps-42", "calibre (3.23.0)", "user_1001", "1234567")
            .forEach { generated ->
                assertEquals(
                    "expected \"$generated\" to be rejected",
                    null,
                    EpubMetadataResolver.cleanAuthor(generated)
                )
            }
        // 不能误伤：真名里带数字或以 calibre 开头的词都要留住
        assertEquals("阿西莫夫", EpubMetadataResolver.cleanAuthor("阿西莫夫"))
        assertEquals("F. Scott Fitzgerald", EpubMetadataResolver.cleanAuthor("F. Scott Fitzgerald"))
        assertEquals("三三", EpubMetadataResolver.cleanAuthor("三三"))
    }

    @Test
    fun `strips share noise from file names`() {
        assertEquals("千钧雪", EpubMetadataResolver.titleFromFileName("(NEW)千钧雪.epub"))
        assertEquals("三体", EpubMetadataResolver.titleFromFileName("【完结】三体.epub"))
        assertEquals("围城", EpubMetadataResolver.titleFromFileName("[精校]围城.epub"))
        assertEquals("野草", EpubMetadataResolver.titleFromFileName("野草（精校版）.epub"))
    }

    /** 整个书名就是括号内容时，剥离会清空——此时必须退回原名而不是返回空。 */
    @Test
    fun `keeps the original file name when stripping would empty it`() {
        assertEquals("（人间失格）", EpubMetadataResolver.titleFromFileName("（人间失格）.epub"))
    }

    @Test
    fun `handles file names without an extension`() {
        assertEquals("没有扩展名的书", EpubMetadataResolver.titleFromFileName("没有扩展名的书"))
    }
}
