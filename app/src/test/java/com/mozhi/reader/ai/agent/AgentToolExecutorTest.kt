package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.retrieval.BookSourceRegistry
import com.mozhi.reader.core.retrieval.ReadingScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class AgentToolExecutorTest {
    private val reports = mutableListOf<String>()
    private val executor = AgentToolExecutor(reports::add)
    private val book = BookEntity(id = 1, title = "测试", author = "", coverPath = null,
        epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1)

    private fun tool(body: suspend (JsonObject) -> ToolResult) = object : AgentTool {
        override val spec = ToolSpec("test_tool", "test", JsonObject(emptyMap()))
        override val displayName = "测试"
        override suspend fun execute(arguments: JsonObject) = body(arguments)
    }

    private suspend fun execute(tool: AgentTool?, arguments: String = "{}") =
        executor.execute(ToolCall("call-1", tool?.spec?.name ?: "unknown", arguments), tool)

    @Test fun payloadAndLanguageNeverDetermineTheOutcome() = runTest {
        val outcomes = listOf(
            ToolResult.Success("原文：无法确定来人的身份，尚未配置的设备也无法使用。"),
            ToolResult.Success("没有匹配的结果。"),
            ToolResult.Success("部分正文可用", partial = true),
            ToolResult.Failure("INVALID_REFERENCE", "source_ref 无效或已过期"),
            ToolResult.Failure("WRITE_FAILED", "正文核验或批注保存失败"),
            ToolResult.Failure("INVALID_ARGUMENT", "Please provide a query")
        )
        outcomes.forEach { expected -> assertSame(expected, execute(tool { expected })) }
    }

    @Test fun malformedArgumentsNeverInvokeTheTool() = runTest {
        var invocations = 0
        val tool = tool { invocations++; ToolResult.Success("ok") }
        listOf("", "{bad", "[]", "null", "42").forEach { arguments ->
            assertEquals("INVALID_ARGUMENT", (execute(tool, arguments) as ToolResult.Failure).code)
        }
        assertEquals(0, invocations)
        assertEquals("UNKNOWN_TOOL", (execute(null) as ToolResult.Failure).code)
    }

    @Test fun failuresRetainTheirCauseAndReportOnlySafeDiagnosticDetails() = runTest {
        val error = IllegalStateException("private book text and secret token")
        val result = execute(tool { throw error }) as ToolResult.Failure
        assertSame(error, result.cause)
        assertEquals("EXECUTION_FAILED", result.code)
        val report = reports.single()
        assertTrue(report.contains("call-1"))
        assertTrue(report.contains("test_tool"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("AgentToolExecutorTest"))
        assertFalse(report.contains(error.message!!))
    }

    @Test fun cancellationPropagatesWithoutBecomingAToolFailure() = runTest {
        val cancellation = CancellationException("cancelled")
        val result = runCatching { execute(tool { throw cancellation }) }
        assertSame(cancellation, result.exceptionOrNull())
        assertTrue(reports.isEmpty())
    }

    @Test fun recoveredFailuresKeepUsefulPartialResultsAndDiagnostics() = runTest {
        val result = ToolResult.Success("关键词检索结果", partial = true,
            diagnostics = listOf(ToolDiagnostic("VECTOR_RECALL_FAILED", IllegalStateException("private query"))))
        assertSame(result, execute(tool { result }))
        assertTrue(reports.single().contains("VECTOR_RECALL_FAILED"))
        assertFalse(reports.single().contains("private query"))
    }

    @Test fun realGrepErrorsAndSuccessfulQuotedTextKeepTheirTypedStatus() = runTest {
        val grep = GrepBookTool(1, { book }, { ChapterDocument(it, "", "无法确定来人的身份") },
            { "v1" }, ReadingScope.WholeBook, BookSourceRegistry())
        val failure = execute(grep) as ToolResult.Failure
        assertEquals("INVALID_ARGUMENT", failure.code)
        assertTrue(failure.content.contains("\"status\":\"error\""))
        val found = execute(grep, "{\"pattern\":\"无法确定\"}") as ToolResult.Success
        assertFalse(found.partial)
        assertTrue(found.content.contains("无法确定"))
        assertTrue(execute(grep, "{\"pattern\":\"没有出现\"}") is ToolResult.Success)
    }

    @Test fun annotationRejectionAndWriteExceptionsCannotReportSuccess() = runTest {
        var writes = 0
        val annotation = AddAnnotationTool(1, { book }, { ChapterDocument(it, "", "唯一原文") },
            { "v1" }, BookSourceRegistry(), ReadingScope.WholeBook,
            save = { _, _, _, _, _ -> writes++; error("disk unavailable") })
        val rejected = execute(annotation, buildJsonObject {
            put("quote", "唯一原文"); put("comment", "批注"); put("source_ref", "forged")
        }.toString()) as ToolResult.Failure
        assertEquals("INVALID_REFERENCE", rejected.code)
        assertEquals(0, writes)
        val failed = execute(annotation, buildJsonObject {
            put("quote", "唯一原文"); put("comment", "批注")
        }.toString()) as ToolResult.Failure
        assertEquals("WRITE_FAILED", failed.code)
        assertEquals(1, writes)
        assertNotNull(failed.cause)
    }
}
