package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubV2RegressionTest {
    @Test
    fun `publisher font and color resolve from raw css at layout time`() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><p>测试字体</p></body></html>".toByteArray(),
            0,
            "OEBPS/Text/ch.xhtml",
            mapOf("OEBPS/Styles/main.css" to "p { font-family: \"Demo\"; color: #123456; }")
        )
        val bundle = EpubLayoutChapterBundle(
            document = parsed.document,
            resourcePaths = emptyMap(),
            fontPaths = mapOf("demo" to "demo.ttf"),
            dom = parsed.dom,
            stylesheets = listOf(
                EpubStylesheetText("OEBPS/Styles/main.css", "p { font-family: \"Demo\"; color: #123456; }")
            )
        )
        val chapter = ChapterTypesetter(
            TypesetSpec(
                visibleWidth = 200f, visibleHeight = 300f, contentLineStep = 24f,
                titleLineStep = 32f, paragraphSpacing = 0f, blankLineSpacing = 0f,
                titleTopSpacing = 0f, titleBottomSpacing = 0f, contentFontSizePx = 20f,
                publisherStyleMode = PublisherStyleMode.RESPECT
            ),
            FakeMeasure()
        ).typeset(0, "", parsed.text, epubLayout = bundle)
        val column = chapter.pages.first().lines.first().columns.first()

        assertEquals("Demo", column.fontFamily)
        assertEquals("demo.ttf", column.fontFilePath)
        assertEquals(0xFF123456.toInt(), column.syntaxColorArgb)
    }

    @Test
    fun `block image honors percentage width auto margins padding and border`() {
        val css = "img.cover { display:block; width:50%; margin-left:auto; margin-right:auto; padding:10px; border:2px solid red; }"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><p>前文</p><img class='cover' src='../Images/cover.jpg' alt='封面'/><p>后文</p></body></html>".toByteArray(),
            0,
            "OEBPS/Text/ch.xhtml",
            mapOf("OEBPS/Styles/main.css" to css)
        )
        val source = parsed.images.single()
        val chapter = ChapterTypesetter(testSpec(), FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = parsed.text,
            inlineImages = listOf(InlineImageSource(source.charOffset, "cover.jpg", 400, 200, source.altText)),
            epubLayout = EpubLayoutChapterBundle(
                document = parsed.document,
                resourcePaths = emptyMap(),
                fontPaths = emptyMap(),
                dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
            )
        )
        val line = chapter.pages.flatMap { it.lines }.single { it.inlineImages.isNotEmpty() }
        val image = line.inlineImages.single()

        assertEquals(35f, line.startX, .01f)
        assertEquals(50f, image.left, .01f)
        assertEquals(100f, image.width, .01f)
        assertEquals(50f, image.height, .01f)
        assertEquals(80f, line.lineBottom - line.lineTop, .01f)
        assertTrue(chapter.pages.flatMap { it.decorations }.any { it.left == 35f && it.right == 165f })
    }

    @Test
    fun `floating image participates in float layout and following text wraps beside it`() {
        val css = "img.avatar { float:left; width:30%; margin-right:10px; } p { text-indent:0; margin:0; }"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><p><img class='avatar' src='../Images/avatar.jpg' alt='头像'/>这是一段需要环绕浮动图片的长文字，用来验证首行不会覆盖图片。</p></body></html>".toByteArray(),
            0,
            "OEBPS/Text/ch.xhtml",
            mapOf("OEBPS/Styles/main.css" to css)
        )
        val source = parsed.images.single()
        val chapter = ChapterTypesetter(testSpec(), FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = parsed.text,
            inlineImages = listOf(InlineImageSource(source.charOffset, "avatar.jpg", 200, 200, source.altText)),
            epubLayout = EpubLayoutChapterBundle(
                document = parsed.document,
                resourcePaths = emptyMap(),
                fontPaths = emptyMap(),
                dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
            )
        )
        val lines = chapter.pages.flatMap { it.lines }
        val imageLine = lines.single { it.inlineImages.isNotEmpty() }
        val wrapped = lines.first { it.columns.isNotEmpty() && it.lineTop < imageLine.lineBottom }

        assertEquals(60f, imageLine.inlineImages.single().width, .01f)
        assertTrue("text should start after the float and its right margin", wrapped.startX >= 72.5f)
    }

    @Test
    fun `picture uses fallback img resource and preserves img css box`() {
        val css = "picture > img.art { display:block; width:50%; margin-left:auto; margin-right:auto; }"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            """<html><body><picture><source srcset='../Images/modern.webp 1x'/><img class='art' src='../Images/fallback.jpg' alt='插图'/></picture></body></html>""".toByteArray(),
            0,
            "OEBPS/Text/ch.xhtml",
            mapOf("OEBPS/Styles/main.css" to css)
        )
        val source = parsed.images.single()
        assertEquals("OEBPS/Images/fallback.jpg", source.href)

        val chapter = ChapterTypesetter(testSpec(), FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = parsed.text,
            inlineImages = listOf(InlineImageSource(source.charOffset, "fallback.jpg", 400, 200, source.altText)),
            epubLayout = EpubLayoutChapterBundle(
                document = parsed.document,
                resourcePaths = emptyMap(),
                fontPaths = emptyMap(),
                dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
            )
        )
        val line = chapter.pages.flatMap { it.lines }.single { it.inlineImages.isNotEmpty() }
        val image = line.inlineImages.single()

        assertEquals(50f, line.startX, .01f)
        assertEquals(50f, image.left, .01f)
        assertEquals(100f, image.width, .01f)
        assertEquals(50f, image.height, .01f)
    }

    @Test
    fun `explicit image width and height use css replacement rectangle`() {
        val css = "img.stretched { display:block; width:80px; height:40px; }"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><img class='stretched' src='../Images/square.png' alt='方图'/></body></html>".toByteArray(),
            0,
            "OEBPS/Text/ch.xhtml",
            mapOf("OEBPS/Styles/main.css" to css)
        )
        val source = parsed.images.single()
        val chapter = ChapterTypesetter(testSpec(), FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = parsed.text,
            inlineImages = listOf(InlineImageSource(source.charOffset, "square.png", 100, 100, source.altText)),
            epubLayout = EpubLayoutChapterBundle(
                document = parsed.document,
                resourcePaths = emptyMap(),
                fontPaths = emptyMap(),
                dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
            )
        )
        val image = chapter.pages.flatMap { it.lines }
            .single { it.inlineImages.isNotEmpty() }
            .inlineImages.single()

        assertEquals(100f, image.width, .01f)
        assertEquals(50f, image.height, .01f)
    }

    private fun testSpec() = TypesetSpec(
        visibleWidth = 200f,
        visibleHeight = 300f,
        contentLineStep = 24f,
        titleLineStep = 32f,
        paragraphSpacing = 0f,
        blankLineSpacing = 0f,
        titleTopSpacing = 0f,
        titleBottomSpacing = 0f,
        indentCharCount = 0f,
        justifyContent = false,
        bottomAlign = false,
        contentFontSizePx = 20f,
        publisherStyleMode = PublisherStyleMode.RESPECT
    )

}
