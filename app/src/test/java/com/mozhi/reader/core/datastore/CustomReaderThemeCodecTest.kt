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

    @Test
    fun `自定义主题与内置排版可往返保存左右负边距及上下新端点`() {
        val expanded = themes.mapIndexed { index, theme -> theme.copy(
            pageMarginLeft = if (index == 0) -1.4f else 3f,
            pageMarginRight = if (index == 0) 3f else -1.4f,
            pageMarginTop = if (index == 0) 0f else 3f,
            pageMarginBottom = if (index == 0) 3f else 0f
        ) }
        assertEquals(expanded, CustomReaderThemeCodec.decode(CustomReaderThemeCodec.encode(expanded)))
        val profiles = mapOf("PAPER" to expanded[0], "DARK" to expanded[1])
        assertEquals(profiles, ReaderThemeTypographyCodec.decode(ReaderThemeTypographyCodec.encode(profiles)))
    }

    @Test
    fun `主题编码前夹住越界边距并将非有限值回落默认`() {
        val invalid = themes[0].copy(pageMarginLeft = -9f, pageMarginRight = 9f, pageMarginTop = -9f, pageMarginBottom = 9f)
        val clamped = invalid.copy(pageMarginLeft = -1.4f, pageMarginRight = 3f, pageMarginTop = 0f, pageMarginBottom = 3f)
        assertEquals(listOf(clamped), CustomReaderThemeCodec.decode(CustomReaderThemeCodec.encode(listOf(invalid))))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            val nonFinite = themes[0].copy(pageMarginLeft = value, pageMarginRight = value, pageMarginTop = value, pageMarginBottom = value)
            assertEquals(listOf(themes[0]), CustomReaderThemeCodec.decode(CustomReaderThemeCodec.encode(listOf(nonFinite))))
            assertEquals(mapOf("PAPER" to themes[0]),
                ReaderThemeTypographyCodec.decode(ReaderThemeTypographyCodec.encode(mapOf("PAPER" to nonFinite))))
        }
    }

    @Test
    fun `直接解码主题也夹住越界边距`() {
        val rawTheme = """{"id":1,"name":"边距","backgroundArgb":1,"textArgb":2,"accentArgb":3,
            "pageMarginLeft":-9,"pageMarginRight":9,"pageMarginTop":-9,"pageMarginBottom":9}"""
        val expected = CustomReaderTheme(1, "边距", 1, 2, 3,
            pageMarginLeft = -1.4f, pageMarginRight = 3f, pageMarginTop = 0f, pageMarginBottom = 3f)
        assertEquals(listOf(expected), CustomReaderThemeCodec.decode("[$rawTheme]"))
        assertEquals(mapOf("PAPER" to expected), ReaderThemeTypographyCodec.decode("""{"PAPER":$rawTheme}"""))
    }
}
