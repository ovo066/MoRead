package com.mozhi.reader.core.importer.convert

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class BookConverterTest {
    private fun markdown(text: String, name: String = "文件名.md") = MarkdownBookConverter().convert(text.toByteArray(), name)
    private fun html(text: String, name: String = "文件名.html") = HtmlBookConverter().convert(text.toByteArray(), name)
    private fun text(book: ConvertedBook) = book.chapters.joinToString("\n") { Jsoup.parse(it.xhtmlBody).text() }

    @Test fun markdownSingleH1UsesH2AndFrontMatterOverridesMetadata() {
        val book = markdown("""
            ---
            title: '元信息书名' # 标量后允许注释
            author: "作者"
            language: zh-CN
            ---
            # 正文书名
            开篇文字。
            ## 第一章 **相遇**
            第一章正文。
            ### 下级标题
            更多正文。
            ## 第二章
            第二章正文。
        """.trimIndent())
        assertEquals("元信息书名", book.title)
        assertEquals("作者", book.author)
        assertEquals("zh-CN", book.language)
        assertEquals(listOf("元信息书名", "第一章 相遇", "第二章"), book.chapters.map { it.title })
        assertFalse(text(book).contains("language:"))
        assertFalse(text(book).contains("正文书名"))
        assertTrue(book.chapters[1].xhtmlBody.contains("<h3"))
        book.chapters.forEach { assertXhtml(it.xhtmlBody) }
    }

    @Test fun multipleH1SplitAndKeepTextBeforeTheFirstHeading() {
        val book = markdown("前言文字。\n\n# 第一卷\n甲正文。\n\n# 第二卷\n乙正文。", "长篇.MARKDOWN")
        assertEquals("长篇", book.title)
        assertEquals(listOf("长篇", "第一卷", "第二卷"), book.chapters.map { it.title })
        assertTrue(text(book).contains("前言文字"))
    }

    @Test fun noSplittingHeadingsMeansOneChapterNamedAfterBook() {
        val plain = markdown("普通正文。\n\n### 小标题\n正文继续。", "单篇.md")
        assertEquals("单篇", plain.title)
        assertEquals(listOf("单篇"), plain.chapters.map { it.title })
        val titled = markdown("# 唯一书名\n\n正文继续。")
        assertEquals("唯一书名", titled.title)
        assertEquals(listOf("唯一书名"), titled.chapters.map { it.title })
        assertEquals("正文标题", markdown("<title>内嵌标题</title>\n\n# 正文标题\n\n正文。").title)
        val sections = markdown("## 第一节\n甲。\n\n## 第二节\n乙。", "分节.md")
        assertEquals(listOf("第一节", "第二节"), sections.chapters.map { it.title })
    }

    @Test fun fencedAndIndentedCodeDoNotCountAsHeadings() {
        val book = markdown("# 书名\n\n```markdown\n# 假标题\n## 不是章节\n```\n\n    # 缩进代码\n\n## 真章节\n正文。")
        assertEquals("书名", book.title)
        assertEquals(listOf("书名", "真章节"), book.chapters.map { it.title })
        assertTrue(book.chapters.first().xhtmlBody.contains("<pre"))
        assertTrue(text(book).contains("# 假标题"))
    }

    @Test fun richMarkdownAndRawHtmlProduceWellFormedXhtml() {
        val book = markdown("""
            # 富文本

            **粗体**、*斜体*、~~删除~~、`a < b && c`。

            > 引用 & 内容

            - 无序项
            - [x] 已完成
            - [ ] 待完成

            1. 有序项
            2. 下一项

            | 列一 | 列二 |
            | --- | --- |
            | 甲 | 乙 |

            ```html
            <p>& text</p>
            ```

            ---

            [网页](https://example.org) [邮件](mailto:reader@example.org)

            <div class="paper" onclick="bad()">内嵌 <strong>HTML</strong><br>实体&nbsp;文字<script>secret()</script></div>

            注记[^note]。

            [^note]: 脚注原文。
        """.trimIndent())
        val body = book.chapters.single().xhtmlBody
        assertXhtml(body)
        val dom = Jsoup.parse(body)
        listOf("strong", "em", "blockquote", "ul", "ol", "pre", "code", "table", "hr").forEach {
            assertFalse("Missing $it", dom.select(it).isEmpty())
        }
        assertFalse(body, dom.select("del,s").isEmpty())
        assertEquals(2, dom.select("a[href]").size)
        assertTrue(dom.text().contains("[x]"))
        assertTrue(dom.text().contains("[ ]"))
        assertTrue(dom.text().contains("脚注原文"))
        assertFalse(body.contains("onclick"))
        assertFalse(body.contains("secret()"))
        assertFalse(body.contains("<input"))
    }

    @Test fun dataImagesAreExtractedAndRemoteAndRelativeImagesBecomeAltText() {
        val data = "data:image/png;base64,$PNG"
        val sources = listOf(
            markdown("正文。\n\n![内嵌]($data)\n\n![远程说明](https://example.org/image.png)\n\n![相对说明](cover.png)\n\n![](//example.org/empty.png)"),
            html("<p>正文。</p><img src='$data' alt='内嵌'><img src='https://example.org/image.png' alt='远程说明'><img src='cover.png' alt='相对说明'><img src='//example.org/empty.png'>")
        )
        sources.forEach { book ->
            val resource = book.resources.single { it.mediaType == "image/png" }
            assertArrayEquals(Base64.getDecoder().decode(PNG), resource.bytes)
            val dom = Jsoup.parse(book.chapters.single().xhtmlBody)
            assertEquals(resource.href, dom.select("img").single().attr("src"))
            assertTrue(book.chapters.single().xhtmlBody, dom.text().contains("远程说明"))
            assertTrue(book.chapters.single().xhtmlBody, dom.text().contains("相对说明"))
            assertFalse(book.chapters.single().xhtmlBody.contains("example.org"))
            assertXhtml(book.chapters.single().xhtmlBody)
        }
    }

    @Test fun htmlCleansActiveContentLinksAndExternalCssButKeepsPresentation() {
        val book = html("""
            <html lang="zh-CN"><head><title>HTML 书名</title><meta name="author" content="网页作者">
            <link rel="stylesheet" href="https://example.org/a.css">
            <style>@import 'https://example.org/b.css'; .paper {color: red; background: url(https://example.org/bg.png)} p {font-weight:bold}</style>
            </head><body><h1>被 title 覆盖</h1>
            <div class="paper" style="color:green;background:url(local.png)" onclick="bad()"><p>安全正文 &amp; 内容&nbsp;结束。</p>
            <script>secret()</script><iframe>frame</iframe><object>object</object><embed src="remote">
            <form>表单<input value="private"><button>按钮</button></form>
            <a href="java&#x73;cript:bad()" onmouseover="bad()">坏链接</a>
            <a href="https://example.org">好链接</a><a href="mailto:a@example.org">邮件</a><a href="file:///tmp/a">本地</a>
            </div></body></html>
        """.trimIndent())
        assertEquals("HTML 书名", book.title)
        assertEquals("网页作者", book.author)
        assertEquals("zh-CN", book.language)
        val body = book.chapters.single().xhtmlBody
        assertXhtml(body)
        val dom = Jsoup.parse(body)
        assertTrue(dom.select("script,iframe,object,embed,form,input,button,link").isEmpty())
        assertFalse(body.contains("secret"))
        assertFalse(body.contains("onclick"))
        assertFalse(body.contains("onmouseover"))
        assertEquals(2, dom.select("a[href]").size)
        assertTrue(dom.selectFirst(".paper")!!.attr("style").contains("color:green"))
        val css = book.resources.single { it.mediaType == "text/css" }.bytes.toString(Charsets.UTF_8)
        assertTrue(css.contains("font-weight:bold"))
        assertFalse(css.contains("@import"))
        assertFalse(css.contains("https:"))
        assertFalse(body.contains("local.png"))
    }

    @Test fun nestedHtmlHeadingsReopenContainersAtChapterBoundaries() {
        val book = html("""
            <title>嵌套</title><body><article class="outer"><p>前言</p><section class="volume">
            <div class="inner"><h1>第一章 <em>标题</em></h1><p>甲&nbsp;正文<br>下一行</p>
            <section><h1>第二章</h1><p>乙正文</p></section><p>第二章结尾</p></div>
            </section></article></body>
        """.trimIndent())
        assertEquals(listOf("嵌套", "第一章 标题", "第二章"), book.chapters.map { it.title })
        book.chapters.forEach {
            assertXhtml(it.xhtmlBody)
            assertEquals(1, Jsoup.parse(it.xhtmlBody).select("article.outer").size)
        }
        assertTrue(book.chapters.last().xhtmlBody.contains("第二章结尾"))
        assertFalse(book.chapters[1].xhtmlBody.contains("乙正文"))
    }

    @Test fun gbkGb18030AndBomHaveTheRightPrecedence() {
        for (encoding in listOf("GBK", "GB18030")) {
            val source = "<meta charset='$encoding'><title>中文书名</title><p>中文正文，天地玄黄。</p>"
            val book = HtmlBookConverter().convert(source.toByteArray(Charset.forName(encoding)), "编码.html")
            assertEquals("中文书名", book.title)
            assertTrue(text(book).contains("中文正文，天地玄黄。"))
        }
        val oldMeta = "<meta http-equiv='Content-Type' content='text/html; charset=gbk'><p>旧式编码声明。</p>"
        assertTrue(text(HtmlBookConverter().convert(oldMeta.toByteArray(Charset.forName("GBK")), "a.htm")).contains("旧式编码声明"))
        val bomWins = "<meta charset='gbk'><p>优先使用字节序标记。</p>".toByteArray(Charsets.UTF_16LE)
        assertTrue(text(HtmlBookConverter().convert(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + bomWins, "a.htm")).contains("优先使用字节序标记"))
        assertTrue(text(markdown("普通中文正文。")).contains("普通中文正文"))
    }

    @Test fun htmlUsesUniqueH1ThenFileNameAndTrimsChapterTitlesByCodePoint() {
        val one = html("<h1>标题 <b>加粗</b></h1><div><h2>章节</h2><p>正文</p></div>")
        assertEquals("标题 加粗", one.title)
        assertEquals(listOf("章节"), one.chapters.map { it.title })
        val fallback = html("<p>正文</p>", "文档.XHTML")
        assertEquals("文档", fallback.title)
        val long = "长😀".repeat(60)
        val book = html("<h1>书名</h1><h2>$long<br>后半</h2><p>正文</p>")
        assertEquals(80, book.chapters.single().title.codePointCount(0, book.chapters.single().title.length))
        assertFalse(book.chapters.single().title.contains('\n'))
        assertXhtml(book.chapters.single().xhtmlBody)
    }

    @Test fun escapedCssUrlsInvalidXmlCharactersAndSvgCannotRetainExternalLoads() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20"><script>secret()</script><image href="https://example.org/a.png"/><rect width="20" height="20" fill="red" onclick="bad()"/><use href="https://example.org/a.svg#x"/></svg>"""
        val uri = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.toByteArray())
        val book = html("""
            <html class="root" style="color:red"><head><style>@im/**/port 'https://example.org/a.css'; .p{background:u\72l(https://example.org/b.png)}</style></head>
            <body class="paper" style="font-size:1.1em"><p class="p">合法&#1;文字 &amp; 实体&#160;结尾。</p><img src='$uri'></body></html>
        """.trimIndent())
        assertEquals("root", book.htmlAttributes["class"])
        assertEquals("paper", book.bodyAttributes["class"])
        assertXhtml(book.chapters.single().xhtmlBody)
        val css = book.resources.single { it.mediaType == "text/css" }.bytes.toString(Charsets.UTF_8)
        assertFalse(css.contains("example.org"))
        assertFalse(css.contains("@import"))
        val image = book.resources.single { it.mediaType == "image/svg+xml" }.bytes.toString(Charsets.UTF_8)
        assertTrue(image.contains("<rect"))
        assertFalse(image.contains("example.org"))
        assertFalse(image.contains("secret"))
        assertFalse(image.contains("onclick"))
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(image.toByteArray()))
    }

    @Test fun emptyDocumentsAndOversizedSourcesOrImagesGiveSpecificReasons() {
        for (source in listOf("", "   ", "---\ntitle: 元信息\n---\n", "![空](missing.png)".replace("空", ""))) {
            expectReason(BookConversionException.Reason.EMPTY_DOCUMENT) { markdown(source) }
        }
        expectReason(BookConversionException.Reason.EMPTY_DOCUMENT) { html("<title>仅元数据</title><script>code</script>") }
        val oversized = ByteArray(BookDocumentInput.MAX_SOURCE_BYTES + 1)
        expectReason(BookConversionException.Reason.SOURCE_TOO_LARGE) { MarkdownBookConverter().convert(oversized, "big.md") }
        expectReason(BookConversionException.Reason.SOURCE_TOO_LARGE) { HtmlBookConverter().convert(oversized, "big.html") }
        expectReason(BookConversionException.Reason.SOURCE_TOO_LARGE) {
            BookDocumentInput.bounded(ByteArrayInputStream(oversized)).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (stream.read(buffer) >= 0) { /* 验证未声明大小的输入流也受限。 */ }
            }
        }
        val oversizedImage = Base64.getEncoder().encodeToString(ByteArray(BookDocumentInput.MAX_IMAGE_BYTES + 1))
        expectReason(BookConversionException.Reason.IMAGE_TOO_LARGE) { html("<p>正文</p><img src='data:image/png;base64,$oversizedImage'>") }
    }

    private fun expectReason(reason: BookConversionException.Reason, block: () -> Unit) {
        try { block(); fail("Expected $reason") } catch (error: BookConversionException) { assertEquals(reason, error.reason) }
    }

    companion object {
        const val PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aF8sAAAAASUVORK5CYII="
        fun assertXhtml(body: String) {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$body</body></html>".toByteArray()))
        }
    }
}
