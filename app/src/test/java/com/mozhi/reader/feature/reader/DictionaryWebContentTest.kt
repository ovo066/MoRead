package com.mozhi.reader.feature.reader

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class DictionaryWebContentTest {
    @Test fun oxfordSpanOnlyEntriesGetReadableFormattingWithoutExternalCssOrScripts() {
        val source = "<link rel='stylesheet' href='oalecd8e.css'><span class='entry'><span class='h'>example</span><span class='def-g'><span class='d'>a sample<span class='oalecd8e_chn'>示例</span></span></span><span class='x-g'>an example<span class='oalecd8e_chn'>一个例子</span></span></span>"
        for (dark in listOf(false, true)) {
            val doc = Jsoup.parse(dictionaryHtml(source, dark))
            assertEquals("oxford", doc.body().attr("data-moread-format"))
            assertTrue(doc.select("style").joinToString { it.data() }.contains(".x-g{margin"))
            assertTrue(doc.body().text().contains("示例"))
            assertTrue(doc.select("script").isEmpty())
        }
    }
    @Test fun fallbackKeepsChineseMeaningAndDoesNotExposeStylesOrScripts() {
        val source = "<style>body{display:none}</style><script>document.write('invisible')</script><h2>故</h2><p>旧的；缘故。</p>"
        val plain = dictionaryPlainText(source)
        assertTrue(plain.contains("故"))
        assertTrue(plain.contains("旧的；缘故。"))
        assertFalse(plain.contains("document.write"))
        assertFalse(plain.contains("display:none"))
        assertTrue(dictionaryPlainText("<img src='word.png'>").contains("没有可显示的文字"))
        assertTrue(dictionaryHtml("<span style='color:black'>故</span>", true).contains("color:inherit!important"))
    }
    @Test fun dictionaryHtmlKeepsFormattingAndLocalStylesButRemovesActiveContent() {
        val html = dictionaryHtml("""<base href="https://evil.example"><script>fetch('/')</script><iframe src="https://evil.example"></iframe><link rel="stylesheet" href="style.css"><b onclick="alert(1)">book</b><img src="images/book.png"><a href="entry://books">books</a>""", false)
        val doc = Jsoup.parse(html)
        assertTrue(doc.select("script,iframe,base,[onclick]").isEmpty())
        assertEquals("book", doc.selectFirst("b")!!.text())
        assertEquals("style.css", doc.selectFirst("link")!!.attr("href"))
        assertEquals("entry://books", doc.selectFirst("a")!!.attr("href"))
        assertTrue(doc.selectFirst("meta[http-equiv]")!!.attr("content").contains("default-src 'none'"))
    }
}
