package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.library.QuoteLocation
import com.mozhi.reader.core.retrieval.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AddAnnotationToolTest {
    private var body = "😀同一句原文\n同一句原文"
    private var revision = "v1"
    private var scope = ReadingScope.WholeBook
    private val registry = BookSourceRegistry()
    private val writes = mutableListOf<QuoteLocation>()
    private fun tool(load: suspend (Int) -> ChapterDocument? = { ChapterDocument(it, "", body) }) = AddAnnotationTool(
        1, { BookEntity(id = 1, title = "测试", author = "", coverPath = null, epubPath = "test",
            sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1) }, load,
        { revision }, registry, ReadingScope.WholeBook, { scope },
        { location, _, _, _, _ -> writes += location; 7L })
    private fun source(start: Int = body.lastIndexOf("同一句原文"), quote: String = "同一句原文", bookId: Long = 1) =
        registry.issueSource(BookSourceRegistry.Source(bookId, revision, scope, 0, start, start + quote.length, quote))
    private fun args(ref: String? = null, quote: String = "同一句原文") = buildJsonObject {
        put("quote", quote); put("comment", "这段值得回味")
        ref?.let { put("source_ref", it) }
    }

    @Test fun repeatedQuoteOnlyAnnotatesTheSpecifiedRange() = runTest {
        val result = tool().execute(args(source()))
        assertTrue(result, result.contains("已在第 1 章"))
        assertEquals(listOf(QuoteLocation(0, 8, 13)), writes)
        assertEquals("同一句原文", body.substring(writes.single().startCharOffset, writes.single().endCharOffset))
    }

    @Test fun literalGrepToAnnotationWorksWithNormalizedOriginalAndWhitespace() = runTest {
        body = "😀　Ａ１２３　\n　Ａ１２３　"
        val grep = GrepBookTool(1, { BookEntity(id = 1, title = "测试", author = "", coverPath = null,
            epubPath = "test", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1) },
            { ChapterDocument(it, "", body) }, { revision }, scope, registry)
        val result = Json.parseToJsonElement(grep.execute(buildJsonObject { put("pattern", " A123 ") })).jsonObject
        val last = result["samples"]!!.jsonArray.last().jsonObject
        val ref = last["source_ref"]!!.jsonPrimitive.content
        val quote = last["matched_text"]!!.jsonPrimitive.content
        assertTrue(tool().execute(args(ref, quote)).contains("已在第"))
        assertEquals(body.lastIndexOf("　Ａ１２３　"), writes.single().startCharOffset)
        assertEquals(quote, body.substring(writes.single().startCharOffset, writes.single().endCharOffset))
    }

    @Test fun forgedOrWrongBookReferenceNeverFallsBackToFirstQuote() = runTest {
        assertTrue(tool().execute(args("s1.forged")).contains("无效"))
        tool().execute(args(source(bookId = 2)))
        assertTrue(writes.isEmpty())
    }

    @Test fun changedVersionOrTextIsRejected() = runTest {
        val ref = source()
        revision = "v2"
        tool().execute(args(ref))
        revision = "v1"
        body = body.replace("同一句原文", "另一句原文")
        tool().execute(args(ref))
        assertTrue(writes.isEmpty())
    }

    @Test fun aDifferentQuoteCannotUseTheReference() = runTest {
        tool().execute(args(source(), "伪造的引文"))
        assertTrue(writes.isEmpty())
    }

    @Test fun smallerScopeCannotBeBypassedBySource() = runTest {
        val ref = source()
        scope = ReadingScope.upto(0, 7)
        tool().execute(args(ref))
        assertTrue(writes.isEmpty())
    }

    @Test fun scopeAndVersionAreRecheckedImmediatelyBeforeWrite() = runTest {
        val ref = source()
        tool { scope = ReadingScope.upto(0, 0); ChapterDocument(it, "", body) }.execute(args(ref))
        assertTrue(writes.isEmpty())
        scope = ReadingScope.WholeBook
        tool { revision = "v2"; ChapterDocument(it, "", body) }.execute(args(ref))
        assertTrue(writes.isEmpty())
    }

    @Test fun oldUniqueQuoteCallsRemainCompatibleAndAmbiguityIsRejected() = runTest {
        assertTrue(tool().execute(args()).contains("多次"))
        assertTrue(writes.isEmpty())
        body = "这是一段唯一的原文。"
        assertTrue(tool().execute(args(quote = "一段唯一的原文")).contains("已在第"))
        assertEquals(2, writes.single().startCharOffset)
    }

    @Test fun missingCanonicalSourceDoesNotWrite() = runTest {
        tool { null }.execute(args(source()))
        assertTrue(writes.isEmpty())
    }
}
