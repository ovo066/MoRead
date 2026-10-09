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
        val dark = dictionaryHtml("<span style='color:black'>故</span>", true)
        assertFalse(dark.contains("color:inherit!important"))
        assertFalse(dark.contains("color:black"))
    }
    @Test fun publisherStylesFollowReaderDefaultsAndDarkModeKeepsDistinctHeadingColorsAndPanels() {
        val source = "<style>body{font-size:21px}h2{color:teal}p{color:black;background-color:white}</style><h2>【正】</h2><p>正直</p>"
        val light = dictionaryHtml(source, false)
        assertTrue(light.indexOf("font-size:17px") < light.indexOf("font-size:21px"))
        val dark = dictionaryHtml(source, true)
        assertFalse(dark.contains("color:inherit!important"))
        assertFalse(dark.contains("background-color:transparent!important"))
        val adapted = dictionaryCssForTheme("h2{color:teal}p{color:black;background-color:white}", true)
        val colors = Regex("(?<!-)color:([^;}]+)").findAll(adapted).map { it.groupValues[1] }.toList()
        assertEquals(2, colors.toSet().size)
        assertFalse(adapted.contains("background-color:white"))
        assertEquals("h2{color:teal}", dictionaryCssForTheme("h2{color:teal}", false))
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
