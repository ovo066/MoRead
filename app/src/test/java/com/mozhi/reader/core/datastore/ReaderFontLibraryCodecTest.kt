package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFontLibraryCodecTest {
    @Test
    fun `多字体编码解码往返一致`() {
        val fonts = listOf(
            ReaderFontAsset("a", "甲字体", "/fonts/a.ttf", "a.ttf", 1L),
            ReaderFontAsset("b", "乙字体", "/fonts/b.otf", "b.otf", 2L)
        )

        assertEquals(fonts, ReaderFontLibraryCodec.decode(ReaderFontLibraryCodec.encode(fonts)))
    }

    @Test
    fun `旧单字体只迁移一次且使用稳定编号`() {
        val migrated = ReaderFontLibraryCodec.includeLegacy(emptyList(), "/legacy/font.ttf", "旧字体")
        val repeated = ReaderFontLibraryCodec.includeLegacy(migrated, "/legacy/font.ttf", "旧字体")

        assertEquals(1, repeated.size)
        assertEquals(ReaderFontLibraryCodec.legacyId("/legacy/font.ttf"), repeated.single().id)
        assertEquals("旧字体", repeated.single().displayName)
    }
}
