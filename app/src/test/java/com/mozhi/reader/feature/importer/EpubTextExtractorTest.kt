package com.mozhi.reader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EpubTextExtractorTest {

    private val extractor = EpubTextExtractor()

    @Test
    fun `block elements become paragraphs`() {
        val text = extractor.extract(
            """
            <html><body>
              <div><p>第一段。</p><p>第二段。</p></div>
              <h2>小节标题</h2>
              <p>第三段。</p>
            </body></html>
            """.trimIndent().toByteArray()
        )

        assertEquals("第一段。\n第二段。\n小节标题\n第三段。", text)
    }

    @Test
    fun `br splits a paragraph`() {
        val text = extractor.extract("<body><p>上句<br/>下句</p></body>".toByteArray())

        assertEquals("上句\n下句", text)
    }

    @Test
    fun `ruby annotations are dropped keeping the base text`() {
        val text = extractor.extract(
            "<body><p><ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>です</p></body>"
                .toByteArray()
        )

        assertEquals("漢字です", text)
        assertFalse(text.contains("かんじ"))
    }

    @Test
    fun `script style and hidden nodes are removed`() {
        val text = extractor.extract(
            """
            <body>
              <script>var a = 1;</script>
              <style>p { color: red }</style>
              <p style="display:none">隐藏文字</p>
              <p>可见文字</p>
            </body>
            """.trimIndent().toByteArray()
        )

        assertEquals("可见文字", text)
    }

    @Test
    fun `images become a placeholder line`() {
        val text = extractor.extract(
            "<body><p>图前</p><p><img src=\"a.png\"/></p><p>图后</p></body>".toByteArray()
        )

        assertEquals("图前\n［图片］\n图后", text)
    }

    @Test
    fun `image extraction keeps resource and utf16 anchor`() {
        val result = extractor.extractWithImages(
            "<body><p>图前</p><img src=\"../images/a%20b.png\" alt=\"插 图\"/><p>图后</p></body>"
                .toByteArray(),
            baseUri = "OPS/text/chapter.xhtml"
        )

        assertEquals("图前\n［图片］\n图后", result.text)
        assertEquals(1, result.images.size)
        assertEquals(3, result.images.single().charOffset)
        assertEquals("OPS/images/a b.png", result.images.single().href)
        assertEquals("插 图", result.images.single().altText)
    }

    @Test
    fun `whitespace inside a paragraph collapses`() {
        val text = extractor.extract(
            "<body><p>  中文   与\n\n  latin   words  </p></body>".toByteArray()
        )

        assertEquals("中文 与 latin words", text)
    }

    @Test
    fun `nested blocks do not emit empty paragraphs`() {
        val text = extractor.extract(
            "<body><div><div><div><p>唯一一段</p></div></div></div></body>".toByteArray()
        )

        assertEquals("唯一一段", text)
    }

    @Test
    fun `empty body yields empty text`() {
        assertEquals("", extractor.extract("<body></body>".toByteArray()))
    }
}
