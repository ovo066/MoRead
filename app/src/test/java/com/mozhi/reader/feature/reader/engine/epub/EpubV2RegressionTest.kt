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

    @Test
    fun `oversized explicit cover shrinks both axes without changing declared ratio`() {
        val css = "img.cover { display:block; width:590px; height:750px; }"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><img class='cover' src='../Images/cover.jpg' alt='封面'/></body></html>".toByteArray(),
            0,
            "OEBPS/Text/cover.xhtml",
            mapOf("OEBPS/Styles/main.css" to css)
        )
        val source = parsed.images.single()
        val chapter = ChapterTypesetter(testSpec(), FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = parsed.text,
            inlineImages = listOf(InlineImageSource(source.charOffset, "cover.jpg", 590, 750, source.altText)),
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

        assertTrue(image.width <= 200.01f)
        assertTrue(image.height <= 300.01f)
        assertEquals(590f / 750f, image.width / image.height, .001f)
    }

    @Test
    fun `duokan private family names fall back to the intended generic family`() {
        val css = """
            .song { font-family: "DK-SONGTI", "ExtB", "st", "songti", "宋体"; }
            .hei { font-family: "DK-XIHEITI", "xht", "xiheiti", "细黑体", "黑体"; }
        """.trimIndent()
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><p class='song'>宋体段</p><p class='hei'>黑体段</p></body></html>".toByteArray(),
            0,
            "OEBPS/Text/ch.xhtml",
            mapOf("OEBPS/Styles/main.css" to css)
        )
        val chapter = ChapterTypesetter(testSpec(), FakeMeasure()).typeset(
            chapterIndex = 0,
            title = "",
            body = parsed.text,
            epubLayout = EpubLayoutChapterBundle(
                document = parsed.document,
                resourcePaths = emptyMap(),
                fontPaths = emptyMap(),
                dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
            )
        )
        val families = chapter.pages.flatMap { it.lines }
            .map { line -> line.columns.first().fontFamily }

        assertEquals(listOf("serif", "sans-serif"), families)
    }

    @Test
    fun `bottom aligned page keeps a floated bubble around its own lines`() {
        // 多看系精排书的聊天气泡：浮动 inline-block + 背景 + 圆角，正文在气泡里换行。
        val css = """
            .tk { padding: 3px 7px; margin: 1em 1em; line-height: 1; }
            .tk p { margin: 0; text-indent: 0; }
            div.ot { border: 1px solid #000; padding: 3px 7px; margin: 3px auto 3px -7px;
                     display: inline-block; border-radius: 0 10px 10px;
                     background-color: #F7F7F7; float: left; }
        """.trimIndent()
        val html = "<html><body><p>前文一段收个尾。</p>" +
            "<div class='tk'><p>宋歆</p><div class='ot'><p>" +
            "庭霜你死了？今天开学第一天，周一，第一节课！这你都敢不来？快给教授发邮件说明情况，说不定还有救。" +
            "</p></div></div><div style='clear: both;'></div></body></html>"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            html.toByteArray(), 0, "OEBPS/Text/ch.xhtml", mapOf("OEBPS/Styles/main.css" to css)
        )
        val bundle = EpubLayoutChapterBundle(
            document = parsed.document,
            resourcePaths = emptyMap(),
            fontPaths = emptyMap(),
            dom = parsed.dom,
            stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
        )
        fun typeset(visibleHeight: Float) = ChapterTypesetter(
            testSpec().copy(visibleWidth = 260f, visibleHeight = visibleHeight, bottomAlign = true),
            FakeMeasure()
        ).typeset(chapterIndex = 0, title = "", body = parsed.text, epubLayout = bundle)

        // 先量出整章内容高度，再把页高设成「只多出不足一行的零头」，逼出页底对齐。
        val contentBottom = typeset(4_000f).pages.single().lines.maxOf { it.lineBottom }
        val page = typeset(contentBottom + testSpec().contentLineStep / 2f).pages.single()

        val bubble = page.decorations.single { it.backgroundColorArgb != null }
        val firstLine = page.lines.first { it.text.startsWith("庭霜你死了") }
        val lastLine = page.lines.first { it.text.contains("说不定还有救") }
        assertTrue(
            "气泡顶边 ${bubble.top} 应在首行 ${firstLine.lineTop} 之上",
            bubble.top <= firstLine.lineTop + .01f
        )
        assertTrue(
            "气泡底边 ${bubble.bottom} 应在末行 ${lastLine.lineBottom} 之下",
            bubble.bottom >= lastLine.lineBottom - .01f
        )
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
