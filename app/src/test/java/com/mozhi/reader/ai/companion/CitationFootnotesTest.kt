package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.WebSourceParser
import com.mozhi.reader.core.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationFootnotesTest {

    private val reader = CompanionFootnotes.Dialect.READER

    @Test fun `quotes and web sources share one numbering in order of appearance`() {
        val raw = "开头〔原文 第3章〕「城门忽然关闭了」，后来〔来源 https://example.com/a〕又〔原文 第5章〕「他终于回到故乡」。"
        val notes = CompanionFootnotes.footnotes(raw, reader)
        assertEquals(listOf(1, 2, 3), notes.map { it.number })
        assertEquals("城门忽然关闭了", (notes[0].target as FootnoteTarget.Book).quote)
        assertEquals(3, (notes[0].target as FootnoteTarget.Book).chapterNumber)
        assertEquals("https://example.com/a", (notes[1].target as FootnoteTarget.Web).url)
        val rendered = CompanionFootnotes.render(raw, notes, reader)
        assertEquals("开头「城门忽然关闭了」[¹](moread-cite:1)，后来[²](moread-cite:2)又「他终于回到故乡」[³](moread-cite:3)。", rendered)
    }

    @Test fun `repeated quotes reuse their number and short quotes are not numbered`() {
        val raw = "〔原文〕「城门忽然关闭了」与〔原文 第2章〕「城门忽然关闭了」，〔原文〕「短句」"
        val notes = CompanionFootnotes.footnotes(raw, reader)
        assertEquals(1, notes.size)
        assertEquals("「城门忽然关闭了」[¹](moread-cite:1)与「城门忽然关闭了」[¹](moread-cite:1)，「短句」",
            CompanionFootnotes.render(raw, notes, reader))
    }

    @Test fun `a bubble uses the numbers of the whole message`() {
        val message = "第一段〔原文〕「城门忽然关闭了」\n第二段〔原文〕「他终于回到故乡」"
        val notes = CompanionFootnotes.footnotes(message, reader)
        assertEquals("第二段「他终于回到故乡」[²](moread-cite:2)", CompanionFootnotes.render("第二段〔原文〕「他终于回到故乡」", notes, reader))
    }

    @Test fun `unfinished markers while streaming are left alone`() {
        val raw = "看这句〔原文 第3章〕「城门忽然"
        assertTrue(CompanionFootnotes.footnotes(raw, reader).isEmpty())
        assertEquals(raw, CompanionFootnotes.render(raw, emptyList(), reader))
    }

    @Test fun `library markers carry the book`() {
        val raw = "〔书籍#12 第4章〕「风从北边吹来」"
        val note = CompanionFootnotes.footnotes(raw, CompanionFootnotes.Dialect.LIBRARY).single().target as FootnoteTarget.Book
        assertEquals(12L, note.bookId)
        assertEquals(4, note.chapterNumber)
    }

    @Test fun `superscripts and uri numbers round trip`() {
        assertEquals("¹²", CompanionFootnotes.superscript(12))
        assertEquals(7, CompanionFootnotes.numberOf("moread-cite:7"))
        assertEquals(null, CompanionFootnotes.numberOf("https://example.com"))
    }

    @Test fun `web sources are recovered from search and scrape results`() {
        val search = "互联网搜索结果（…）：\n\n[1] 第一篇\nhttps://a.example/x\n摘要一\n\n[2] 第二篇\nhttps://b.example/y\n"
        val scrape = "网页正文（…）：\n标题：第三篇\n来源：https://c.example/z\n\n正文内容"
        val sources = companionWebSources(listOf(
            MessageEntity(conversationId = 1, role = "tool", content = search, createdAt = 0),
            MessageEntity(conversationId = 1, role = "tool", content = scrape, createdAt = 0),
            MessageEntity(conversationId = 1, role = "tool", content = "原文：别的工具", createdAt = 0)
        ))
        assertEquals(listOf("https://a.example/x", "https://b.example/y", "https://c.example/z"), sources.keys.toList())
        assertEquals("摘要一", sources.getValue("https://a.example/x").snippet)
        assertEquals("", sources.getValue("https://b.example/y").snippet)
        assertEquals("第三篇", sources.getValue("https://c.example/z").title)
        assertEquals("https://a.example/x", WebSourceParser.normalize("https://a.example/x/"))
    }
}
