package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.library.BookTextException
import com.mozhi.reader.core.retrieval.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GrepBookToolTest {
    private var bodies = listOf("😀猫\n猫 猫", "后章猫")
    private var revision = "v1"
    private var allowed = ReadingScope.WholeBook
    private val registry = BookSourceRegistry()
    private fun tool(snapshot: ReadingScope = allowed, load: suspend (Int) -> ChapterDocument? = { i ->
        bodies.getOrNull(i)?.let { ChapterDocument(i, "章$i", it) }
    }, limits: GrepLimits = GrepLimits()) = GrepBookTool(1, { book(bodies.size) }, load,
        { revision }, snapshot, registry, { allowed }, limits)
    private suspend fun query(tool: GrepBookTool = tool(), pattern: String = "猫", cursor: String? = null,
        extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = Json.parseToJsonElement(tool.execute(buildJsonObject {
        put("pattern", pattern); put("max_samples", 1)
        cursor?.let { put("cursor", it) }; extra()
    })).jsonObject
    private fun JsonObject.cursor() = this["next_cursor"]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.code() = this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test fun chapterRangeFiltersBeforeScanningAndCountsOnlyThatRange() = runTest {
        bodies = listOf("范围外猫猫猫", "目标猫猫", "边界猫未读猫", "未读猫")
        allowed = ReadingScope.upto(2, 3)
        val visited = mutableSetOf<Int>()
        val result = query(tool(load = { i -> visited += i; ChapterDocument(i, "", bodies[i]) })) {
            put("from_chapter", 2); put("to_chapter", 4)
        }
        assertEquals(setOf(1, 2), visited)
        assertEquals(3, result["count"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(2, result["scope"]!!.jsonObject["from_chapter"]!!.jsonPrimitive.int)
        assertEquals(3, result["scope"]!!.jsonObject["to_chapter"]!!.jsonPrimitive.int)
        assertFalse(result.toString().contains("范围外"))
        assertFalse(result.toString().contains("未读"))
    }

    @Test fun cursorRetainsChapterLowerBoundAndRejectsScopeEdits() = runTest {
        bodies = listOf("范围外猫", "猫 猫 猫", "后章猫")
        allowed = ReadingScope.upto(1, bodies[1].length)
        val first = query { put("from_chapter", 2); put("to_chapter", 3) }
        allowed = ReadingScope.WholeBook
        val next = query(cursor = first.cursor())
        assertEquals(3, next["count"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(2, next["scope"]!!.jsonObject["from_chapter"]!!.jsonPrimitive.int)
        assertEquals(2, next["scope"]!!.jsonObject["to_chapter"]!!.jsonPrimitive.int)
        assertEquals("CURSOR_INVALID", query(cursor = first.cursor()) { put("from_chapter", 1) }.code())
        assertEquals("CURSOR_INVALID", query(cursor = first.cursor()) { put("to_chapter", 1) }.code())
    }

    @Test fun pagesRetainTheirSnapshotAcrossToolInstancesAndProgressGrowth() = runTest {
        allowed = ReadingScope.upto(0, bodies[0].length)
        val first = query()
        assertEquals("complete", first["status"]!!.jsonPrimitive.content)
        assertEquals(3, first["count"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        allowed = ReadingScope.WholeBook
        val second = query(cursor = first.cursor())
        val third = query(cursor = second.cursor())
        assertEquals(3, second["count"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertEquals(1, second["scope"]!!.jsonObject["to_chapter"]!!.jsonPrimitive.int)
        val positions = listOf(first, second, third).map { it["samples"]!!.jsonArray.single().jsonObject["match_start"]!!.jsonPrimitive.int }
        assertEquals(listOf(2, 4, 6), positions)
        assertNull(third.cursor())
    }

    @Test fun prefixIsReadableButUnreadTailNeverLeaks() = runTest {
        bodies = listOf("😀已读猫 未读猫")
        allowed = ReadingScope.upto(0, 5)
        val result = query()
        assertEquals(1, result["count"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        assertFalse(result.toString().contains("未读"))
        assertEquals("😀已读猫", result["samples"]!!.jsonArray.single().jsonObject["context_text"]!!.jsonPrimitive.content)
    }

    @Test fun scopeEndingInsideEmojiIsClippedSafely() = runTest {
        bodies = listOf("猫😀未读")
        allowed = ReadingScope.upto(0, 2)
        val result = query()
        assertEquals(1, result["scope"]!!.jsonObject["end_char"]!!.jsonPrimitive.int)
        assertEquals("猫", result["samples"]!!.jsonArray.single().jsonObject["context_text"]!!.jsonPrimitive.content)
    }

    @Test fun shrinkOrRevisionChangeInvalidatesCursor() = runTest {
        val first = query()
        allowed = ReadingScope.upto(0, 4)
        assertEquals("CURSOR_INVALID", query(cursor = first.cursor()).code())
        allowed = ReadingScope.WholeBook
        revision = "v2"
        assertEquals("CURSOR_INVALID", query(cursor = first.cursor()).code())
    }

    @Test fun cursorCannotSwitchPatternOrNormalization() = runTest {
        val first = query()
        assertEquals("CURSOR_INVALID", query(pattern = "别的", cursor = first.cursor()).code())
        assertEquals("CURSOR_INVALID", query(cursor = first.cursor()) { put("normalize", false) }.code())
        assertEquals("CURSOR_INVALID", query(cursor = first.cursor()) { put("context_chars", 1) }.code())
        assertEquals("CURSOR_INVALID", query(cursor = "g1.forged").code())
    }

    @Test fun failuresHaveNoFakeExactCount() = runTest {
        val partial = query(tool(load = { if (it == 0) ChapterDocument(0, "", "猫猫") else null }))
        assertEquals("partial", partial["status"]!!.jsonPrimitive.content)
        assertEquals("lower_bound", partial["count"]!!.jsonObject["relation"]!!.jsonPrimitive.content)
        val empty = query(tool(load = { throw BookTextException("INVALID_TEXT", "bad utf8") }))
        assertEquals("partial", empty["status"]!!.jsonPrimitive.content)
        assertEquals(0, empty["count"]!!.jsonObject["value"]!!.jsonPrimitive.int)
    }

    @Test fun recoveredReadCoverageInvalidatesPartialPage() = runTest {
        val first = query(tool(load = { if (it == 0) ChapterDocument(0, "", bodies[0]) else null }))
        assertEquals("CURSOR_INVALID", query(cursor = first.cursor()).code())
    }

    @Test fun scopeChangeDuringScanDiscardsSamples() = runTest {
        val result = query(tool(load = {
            allowed = ReadingScope.upto(0, 0)
            ChapterDocument(it, "", bodies[it])
        }))
        assertEquals("SCOPE_CHANGED", result.code())
        assertEquals(JsonNull, result["count"])
        assertTrue(result["samples"]!!.jsonArray.isEmpty())
    }

    @Test fun contentChangeDuringScanDiscardsSamples() = runTest {
        val result = query(tool(load = {
            revision = "v2"
            ChapterDocument(it, "", bodies[it])
        }))
        assertEquals("CONTENT_CHANGED", result.code())
        assertEquals(JsonNull, result["count"])
    }

    @Test fun outputReferenceResolvesOnlyToAppIssuedOriginalRange() = runTest {
        val result = query()
        val sample = result["samples"]!!.jsonArray.single().jsonObject
        val source = registry.source(sample["source_ref"]!!.jsonPrimitive.content)!!
        assertEquals("猫", source.quote)
        assertEquals(2, source.start)
        assertEquals(3, source.end)
        assertEquals("v1", source.revision)
        assertNull(registry.source("s1.forged"))
    }

    @Test fun resourceLimitIsPartialAndCancellationPropagates() = runTest {
        assertEquals("partial", query(tool(limits = GrepLimits(maxScanChars = 3)))["status"]!!.jsonPrimitive.content)
        try { query(tool(load = { throw CancellationException("stop") })); fail("must cancel") }
        catch (_: CancellationException) { }
    }

    @Test fun invalidArgumentsAreErrorsRatherThanEmptyMatches() = runTest {
        assertEquals("INVALID_ARGUMENT", query(pattern = "").code())
        assertEquals("UNSUPPORTED_MODE", query { put("mode", "regex") }.code())
        assertEquals("INVALID_ARGUMENT", query { put("max_samples", "many") }.code())
        assertEquals("INVALID_ARGUMENT", query { putJsonObject("normalize") {} }.code())
    }

    @Test fun registryEvictionAndRestartAreExplicit() {
        val cache = BookSourceRegistry(maxCursors = 1, maxSources = 1)
        val page = BookSourceRegistry.Page(1, "v1", allowed, "猫", true, 80, 1, "coverage")
        val first = cache.issueCursor(page)
        val second = cache.issueCursor(page.copy(skip = 2))
        assertNull(cache.page(first))
        assertNotNull(cache.page(second))
        assertNull(BookSourceRegistry().page(second))
        val source = BookSourceRegistry.Source(1, "v1", allowed, 0, 2, 3, "猫")
        val old = cache.issueSource(source)
        assertNotEquals(old, cache.issueSource(source))
        assertNull(cache.source(old))
    }

    private fun book(chapters: Int) = BookEntity(id = 1, title = "测试书", author = "", coverPath = null,
        epubPath = "test", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = chapters)
}
