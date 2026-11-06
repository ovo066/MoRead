package com.mozhi.reader.ai.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSpecCodecTest {
    private val body = "# 读书报告\n\n## 总评\n\n**主角**的成长线清晰，前十章铺垫扎实。\n\n| 人物 | 印象 |\n|---|---|\n| 甲 | 冷静 |"

    @Test
    fun validDocumentRoundTripsThroughToolCall() = runBlocking {
        val args = buildJsonObject { put("title", "  《某书》读书报告\n多余行 "); put("kind", "READING_REPORT"); put("content_md", body) }
        val result = CreateDocumentTool().execute(args)
        assertTrue(result is ToolResult.Success)
        val spec = DocumentSpecCodec.fromToolCall(args.toString(), result.content)!!
        assertEquals("《某书》读书报告", spec.title)
        assertEquals(DocumentKind.READING_REPORT, spec.documentKind)
        assertEquals(body, spec.content)
    }

    @Test
    fun failedOrShortCallsDoNotRenderCards() = runBlocking {
        val args = buildJsonObject { put("title", "短"); put("content_md", "太短了") }
        val result = CreateDocumentTool().execute(args)
        assertTrue(result is ToolResult.Failure)
        assertNull(DocumentSpecCodec.fromToolCall(args.toString(), result.content))
        assertNull(DocumentSpecCodec.fromToolCall("{not json", DocumentSpecCodec.SUCCESS_PREFIX))
    }

    @Test
    fun unknownKindFallsBackToFree() {
        val outcome = DocumentSpecCodec.validate(DocumentSpec(title = "x", kind = "poster", content_md = body))
        assertEquals(DocumentKind.FREE, (outcome as DocumentSpecCodec.Outcome.Valid).spec.documentKind)
    }

    @Test
    fun fileNameStripsIllegalCharacters() {
        val spec = DocumentSpec(title = "第1/2章: 总结?", content_md = body)
        assertEquals("第1 2章 总结.md", DocumentSpecCodec.fileName(spec, "伴读文件"))
        assertEquals("伴读文件.md", DocumentSpecCodec.fileName(spec.copy(title = "///"), "伴读文件"))
    }

    @Test
    fun previewSkipsMarkupTablesAndTitle() {
        val spec = DocumentSpec(title = "读书报告", content_md = body)
        assertEquals("总评 主角的成长线清晰，前十章铺垫扎实。", DocumentSpecCodec.preview(spec))
    }
}
