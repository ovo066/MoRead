package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomReaderThemeCodecTest {

    private val themes = listOf(
        CustomReaderTheme(1L, "米纸", 0xFFFAF3E6.toInt(), 0xFF32302A.toInt(), 0xFFB0442B.toInt()),
        CustomReaderTheme(2L, "夜蓝", 0xFF0D1420.toInt(), 0xFFB8C4D8.toInt(), 0xFF87B4E8.toInt())
    )

    @Test
    fun `编码解码往返一致`() {
        assertEquals(themes, CustomReaderThemeCodec.decode(CustomReaderThemeCodec.encode(themes)))
    }

    @Test
    fun `空与空白输入回落为空列表`() {
        assertTrue(CustomReaderThemeCodec.decode(null).isEmpty())
        assertTrue(CustomReaderThemeCodec.decode("").isEmpty())
        assertTrue(CustomReaderThemeCodec.decode("   ").isEmpty())
    }

    @Test
    fun `损坏内容不抛异常而是回落为空`() {
        assertTrue(CustomReaderThemeCodec.decode("not-json").isEmpty())
        assertTrue(CustomReaderThemeCodec.decode("{\"id\":1}").isEmpty())
        assertTrue(CustomReaderThemeCodec.decode("[{\"id\":\"坏\"}]").isEmpty())
    }

    @Test
    fun `未知字段被忽略以兼容将来扩展`() {
        val raw = """[{"id":7,"name":"n","backgroundArgb":1,"textArgb":2,"accentArgb":3,"future":"x"}]"""
        assertEquals(
            listOf(CustomReaderTheme(7L, "n", 1, 2, 3)),
            CustomReaderThemeCodec.decode(raw)
        )
    }

    @Test
    fun `单个损坏主题不会清空其他主题`() {
        val raw = """[
            {"id":7,"name":"保留","backgroundArgb":1,"textArgb":2,"accentArgb":3},
            {"id":"坏"}
        ]""".trimIndent()

        assertEquals(
            listOf(CustomReaderTheme(7L, "保留", 1, 2, 3)),
            CustomReaderThemeCodec.decode(raw)
        )
    }

    @Test
    fun `主题素材路径可以往返保存`() {
        val theme = CustomReaderTheme(
            id = 8L,
            name = "图片主题",
            backgroundArgb = 1,
            textArgb = 2,
            accentArgb = 3,
            font = ReaderFont.CUSTOM,
            customFontId = "font-1",
            customFontPath = "/fonts/a.ttf",
            customFontName = "阅读字体",
            backgroundImageId = "image-1",
            backgroundImagePath = "/images/a.jpg"
        )

        assertEquals(listOf(theme), CustomReaderThemeCodec.decode(CustomReaderThemeCodec.encode(listOf(theme))))
    }
}
