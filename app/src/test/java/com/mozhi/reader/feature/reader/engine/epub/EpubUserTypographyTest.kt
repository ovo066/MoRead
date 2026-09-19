package com.mozhi.reader.feature.reader.engine.epub

import android.app.Application
import android.graphics.Typeface
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.AndroidTextMeasure
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.TextLine
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 用户排版对 EPUB 必须真的生效：原书 CSS 写过 `text-indent` 不等于用户的「段首缩进」失效，
 * 逐簇测量也不能把「字间距」丢掉。两者都只在真实 Paint 下才测得出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubUserTypographyTest {
    private val body = "云雾散开的时候，山谷里传来一声长长的钟响。他抬头望向远处的塔楼，想起许多年前的那个清晨。"

    private fun spec(indent: Float, mode: PublisherStyleMode) = TypesetSpec(
        visibleWidth = 400f, visibleHeight = 800f, contentLineStep = 40f, titleLineStep = 48f,
        paragraphSpacing = 10f, blankLineSpacing = 20f, titleTopSpacing = 0f, titleBottomSpacing = 0f,
        indentCharCount = indent, justifyContent = false, contentFontSizePx = 30f,
        publisherStyleMode = mode
    )

    private fun firstBodyLine(
        css: String,
        indent: Float,
        spacing: Float = 0f,
        mode: PublisherStyleMode = PublisherStyleMode.SMART
    ): TextLine {
        val html = "<html><head><link rel='stylesheet' href='OEBPS/Styles/main.css'/></head>" +
            "<body><p>$body</p></body></html>"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            html.toByteArray(), 0, "OEBPS/Text/ch.xhtml", mapOf("OEBPS/Styles/main.css" to css)
        )
        val bundle = EpubLayoutChapterBundle(
            document = parsed.document, resourcePaths = emptyMap(), fontPaths = emptyMap(),
            dom = parsed.dom, stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
        )
        val chapter = ChapterTypesetter(
            spec(indent, mode), AndroidTextMeasure(30f, 40f, Typeface.DEFAULT, spacing)
        ).typeset(0, "标题", parsed.text, epubLayout = bundle)
        return chapter.pages.first().lines.first { it.text.isNotBlank() && !it.isTitle }
    }

    @Test fun `user indent applies when the book declares its own paragraph indent`() {
        val css = "p { text-indent:2em; margin:0; }"
        assertEquals(0f, firstBodyLine(css, indent = 0f).startX, .01f)
        // 出厂值（2 字）下与原书声明一致，老用户的书不会因为这条规则突然换样。
        assertEquals(60f, firstBodyLine(css, indent = 2f).startX, .01f)
        assertEquals(120f, firstBodyLine(css, indent = 4f).startX, .01f)
    }

    @Test fun `smart mode keeps the relative indent a book gives to quotes`() {
        val css = "p { text-indent:2em; margin:0; } p.quote { text-indent:4em; }"
        val html = "<html><head><link rel='stylesheet' href='OEBPS/Styles/main.css'/></head>" +
            "<body><p class='quote'>$body</p></body></html>"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            html.toByteArray(), 0, "OEBPS/Text/ch.xhtml", mapOf("OEBPS/Styles/main.css" to css)
        )
        val bundle = EpubLayoutChapterBundle(
            document = parsed.document, resourcePaths = emptyMap(), fontPaths = emptyMap(),
            dom = parsed.dom, stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css))
        )
        val chapter = ChapterTypesetter(
            spec(3f, PublisherStyleMode.SMART), AndroidTextMeasure(30f, 40f, Typeface.DEFAULT, 0f)
        ).typeset(0, "标题", parsed.text, epubLayout = bundle)
        val line = chapter.pages.first().lines.first { it.text.isNotBlank() && !it.isTitle }
        // 引文原本缩 4 字（正文 2 字的两倍），用户调到 3 字后仍是正文的两倍。
        assertEquals(180f, line.startX, .01f)
    }

    @Test fun `take over mode uses the reader's own indent verbatim`() {
        val css = "p { text-indent:4em; margin:0; }"
        assertEquals(60f, firstBodyLine(css, indent = 2f, mode = PublisherStyleMode.TAKE_OVER).startX, .01f)
    }

    @Test fun `user indent applies when the book resets indent on an ancestor`() {
        // text-indent 会继承，body 上的一条声明就让每个段落都算「已声明」。
        val css = "body { text-indent:0; } p { margin:0; }"
        assertEquals(0f, firstBodyLine(css, indent = 0f).startX, .01f)
        assertEquals(60f, firstBodyLine(css, indent = 2f).startX, .01f)
    }

    @Test fun `respect mode still renders the publisher indent`() {
        val css = "p { text-indent:2em; margin:0; }"
        val mode = PublisherStyleMode.RESPECT
        assertEquals(60f, firstBodyLine(css, indent = 0f, mode = mode).startX, .01f)
        assertEquals(60f, firstBodyLine(css, indent = 4f, mode = mode).startX, .01f)
    }

    @Test fun `hanging indent is structure and survives every publisher mode`() {
        val css = "p { text-indent:-2em; margin-left:2em; margin-right:0; }"
        val smart = firstBodyLine(css, indent = 4f).startX
        val respect = firstBodyLine(css, indent = 4f, mode = PublisherStyleMode.RESPECT).startX
        assertEquals(respect, smart, .01f)
        assertTrue("悬挂缩进应把首行拉回左边", smart < 4f * 30f)
    }

    @Test fun `letter spacing changes epub line breaking just like plain text`() {
        val css = "p { margin:0; }"
        val tight = firstBodyLine(css, indent = 0f, spacing = 0f).text.length
        val loose = firstBodyLine(css, indent = 0f, spacing = 0.2f).text.length
        assertTrue("字间距变大后每行应装下更少的字（$tight -> $loose）", loose < tight)
    }
}
