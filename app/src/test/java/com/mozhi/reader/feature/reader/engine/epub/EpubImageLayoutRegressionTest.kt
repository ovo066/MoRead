package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.*
import org.junit.Assert.*
import org.junit.Test

class EpubImageLayoutRegressionTest {
    private val spec = TypesetSpec(visibleWidth = 300f, visibleHeight = 700f, contentLineStep = 30f,
        titleLineStep = 36f, paragraphSpacing = 10f, blankLineSpacing = 10f,
        titleTopSpacing = 0f, titleBottomSpacing = 0f, contentFontSizePx = 24f, titleFontSizePx = 30f)
    private fun typeset(html: String, css: String, width: Int = 1080, height: Int = 100,
        mode: PublisherStyleMode = PublisherStyleMode.SMART, indent: Int = 2): TextChapter {
        val parsed = EpubLayoutDocumentParser().parseWithText("<html><body>$html</body></html>".toByteArray(),
            0, "OPS/ch.xhtml", emptyMap())
        return ChapterTypesetter(spec.copy(publisherStyleMode = mode, indentCharCount = indent.toFloat()), FakeMeasure()).typeset(0, "", parsed.text,
            inlineImages = parsed.images.map { InlineImageSource(it.charOffset, it.href, width, height, it.altText) },
            epubLayout = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OPS/main.css", css))))
    }
    @Test fun wideDividersAndTallIllustrationsKeepTheirIntrinsicAspectRatio() {
        for ((w, h) in listOf(1080 to 100, 1000 to 78, 50 to 1000)) {
            val chapter = typeset("<img src='image.png'/>", "img{display:block;width:100%}", w, h)
            val image = chapter.pages.flatMap { it.lines }.flatMap { it.inlineImages + it.inlineGlyphImages }.single()
            assertEquals(w.toFloat() / h, image.width / image.height, .001f)
            assertTrue(image.width <= spec.visibleWidth + .1f)
            assertTrue(image.height <= spec.visibleHeight + .1f)
        }
    }
    @Test fun centeredFixedWidthPanelKeepsBothBordersInsideTheReadingColumn() {
        for (mode in listOf(PublisherStyleMode.SMART, PublisherStyleMode.RESPECT)) {
            val chapter = typeset("<div class='outer'><div class='inner'><p>测试卡片</p></div></div>",
                ".outer{width:18em;margin:40% auto auto;padding:7px;background:#ede9e0;border:1px solid transparent}" +
                    ".inner{border:1px solid #000;padding:7px;background:#fff}p{text-align:center}", mode = mode)
            val decorations = chapter.pages.first().decorations
            assertTrue(decorations.size >= 2)
            assertTrue(decorations.all { it.left >= -.1f && it.right <= 300.1f })
            assertEquals(120f, decorations.first().top, .1f)
        }
    }
    @Test fun explicitPercentageBleedAndNegativeMarginsRemainOutsideTheColumn() {
        for (rule in listOf("width:120%;margin:0 auto", "margin:0 -10%")) {
            val chapter = typeset("<div class='bleed'><p>装饰出血框</p></div>",
                ".bleed{$rule;border:1px solid black;box-sizing:border-box}p{margin:0}")
            val decoration = chapter.pages.first().decorations.single()
            assertEquals(-30f, decoration.left, .1f)
            assertEquals(330f, decoration.right, .1f)
        }
    }
    @Test fun nestedPercentageMarginsUseTheContainingBoxWidth() {
        val chapter = typeset("<div class='outer'><div class='inner'><p>正文</p></div></div>",
            ".outer{width:50%}.inner{margin-top:20%;background:#eee;border:1px solid #000}p{margin:0}")
        assertEquals(30f, chapter.pages.first().decorations.single().top, .1f)
    }
    @Test fun smartModePreservesArtworkNegativeMarginsButStillAdjustsTextSpacing() {
        val withGap = typeset("<div class='art'><img src='image.png'/></div><p>后文</p>",
            ".art{margin-bottom:-5%}img{width:100%}p{margin:0;text-indent:0}")
        val withoutGap = typeset("<div class='art'><img src='image.png'/></div><p>后文</p>",
            "img{width:100%}p{margin:0;text-indent:0}")
        val a = withGap.pages.flatMap { it.lines }.last().lineTop
        val b = withoutGap.pages.flatMap { it.lines }.last().lineTop
        assertEquals(-15f, a - b, .1f)
    }
    @Test fun inlineImagesRetainTheAnchorOfEachOccurrence() {
        val chapter = typeset("<p>甲<img src='same.png'/>乙<img src='same.png'/></p>",
            "img{height:1em}p{margin:0;text-indent:0}", 30, 30)
        val images = chapter.pages.flatMap { it.lines }.flatMap { it.inlineGlyphImages }
        assertEquals(2, images.size)
        assertTrue(images[0].charOffset!! < images[1].charOffset!!)
    }
    @Test fun imageParagraphAlignmentIsIndependentOfProseIndentation() {
        for (mode in PublisherStyleMode.entries) for (indent in listOf(0, 2, 4)) {
            for (wrapper in listOf("<p><img src='image.png'/></p>", "<div><span><img src='image.png'/></span></div>")) {
                val chapter = typeset(wrapper + "<p>正文仍保留段首缩进</p>",
                    "p,div{margin:0;text-indent:3em}img{width:60%}", mode = mode, indent = indent)
                val image = chapter.pages.flatMap { it.lines }.flatMap { it.inlineImages + it.inlineGlyphImages }.single()
                assertEquals("$mode / $indent", spec.visibleWidth / 2, image.left + image.width / 2, .1f)
                assertTrue(image.left >= 0f && image.left + image.width <= spec.visibleWidth + .1f)
            }
        }
    }
    @Test fun fullWidthAndNearlyFullWidthFloatsMoveProseBelowInsteadOfMakingVerticalSlivers() {
        val prose = "温故而知新可以为师矣学而时习之不亦说乎".repeat(3)
        for (side in listOf("left", "right")) for (percent in listOf(95, 100)) {
            val chapter = typeset("<p><img src='image.png'/>$prose</p>",
                "p{margin:0}img{float:$side;width:$percent%}", 300, 100)
            val lines = chapter.pages.flatMap { it.lines }
            val imageLine = lines.single { (it.inlineImages + it.inlineGlyphImages).isNotEmpty() }
            val image = (imageLine.inlineImages + imageLine.inlineGlyphImages).single()
            val text = lines.filter { it.columns.any { column -> column.charData.isNotBlank() } }
            assertTrue("$side / $percent", text.first().lineTop >= imageLine.lineTop + image.topOffset + image.height - .1f)
            assertTrue(text.first().charLength > 10)
            assertEquals(prose, text.flatMap { it.columns }.joinToString("") { it.charData })
            assertTrue(text.flatMap { it.columns }.all { it.start >= 0 && it.end <= spec.visibleWidth + .1f })
        }
    }
    @Test fun ordinaryFloatsStillAllowReadableWrappingBesideTheImage() {
        val chapter = typeset("<p><img src='image.png'/>${"正文环绕图片".repeat(50)}</p>",
            "p{margin:0}img{float:left;width:30%}", 100, 200)
        val lines = chapter.pages.flatMap { it.lines }
        val imageLine = lines.single { (it.inlineImages + it.inlineGlyphImages).isNotEmpty() }
        val image = (imageLine.inlineImages + imageLine.inlineGlyphImages).single()
        val text = lines.first { it.columns.any { column -> column.charData.isNotBlank() } }
        assertTrue(text.lineTop < imageLine.lineTop + image.topOffset + image.height)
        assertTrue(text.columns.first().start >= image.left + image.width)
        assertTrue(text.charLength > 5)
    }
    @Test fun shortCenteredTitleCardKeepsTheBodyArtworkImmersiveButLongProseDoesNot() {
        val css = "body{background-image:url(bg.jpg);background-size:cover}" +
            ".card{width:18em;margin:40% auto auto;border:1px solid black}img{height:.8em}"
        val card = "<h2 style='display:none'>标题</h2><div class='card'>" +
            "<p><img src='star.png'/></p><p>这是标题<br/>第二行标题</p><p><img src='star.png'/></p></div>"
        assertTrue(typeset(card, css).pages.first().immersive)
        assertFalse(typeset(card + "<p>${"正文".repeat(120)}</p>", css).pages.first().immersive)
        assertFalse(typeset(card, css.replace("background-size:cover", "background-size:auto")).pages.first().immersive)
    }

}
