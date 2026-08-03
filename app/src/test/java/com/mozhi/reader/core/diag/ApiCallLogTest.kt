package com.mozhi.reader.core.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCallLogTest {

    @Test
    fun `gemini style key query is redacted`() {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini:streamGenerateContent?alt=sse&key=AIzaSySECRET"
        val redacted = redactSensitiveQuery(url)
        assertFalse(redacted.contains("AIzaSySECRET"))
        assertTrue(redacted.contains("key=***"))
        assertTrue("无关参数保持原样", redacted.contains("alt=sse"))
    }

    @Test
    fun `common secret parameter names are redacted case-insensitively`() {
        val url = "https://api.example.com/v1/chat?API_KEY=abc&access_token=tok&normal=1"
        val redacted = redactSensitiveQuery(url)
        // 参数名保留（access_token 本身含 "tok"），断言只针对参数值。
        assertFalse(redacted.contains("=abc"))
        assertFalse(redacted.contains("=tok"))
        assertTrue(redacted.contains("API_KEY=***"))
        assertTrue(redacted.contains("access_token=***"))
        assertTrue(redacted.contains("normal=1"))
    }

    @Test
    fun `urls without sensitive queries pass through untouched`() {
        val url = "https://api.openai.com/v1/chat/completions"
        assertEquals(url, redactSensitiveQuery(url))
    }

    @Test
    fun `unparseable urls drop their query part entirely`() {
        val redacted = redactSensitiveQuery("not a url?key=secret")
        assertFalse(redacted.contains("secret"))
    }

    @Test
    fun `clipPreview keeps short text and truncates long text with a note`() {
        assertEquals("short", clipPreview("short", 100))
        val clipped = clipPreview("a".repeat(500), 100)
        assertTrue(clipped.startsWith("a".repeat(100)))
        assertTrue(clipped.contains("已截断"))
        assertTrue(clipped.contains("500"))
    }

    @Test
    fun `jsonl round trips and skips corrupt lines`() {
        val entries = listOf(
            ApiCallLogEntry(
                timestamp = 1_722_600_000_000,
                method = "POST",
                url = "https://api.openai.com/v1/chat/completions",
                status = 200,
                durationMs = 843,
                streaming = true,
                requestBytes = 2048,
                requestPreview = "{\"model\":\"gpt\"}",
                responseType = "text/event-stream"
            ),
            ApiCallLogEntry(
                timestamp = 1_722_600_100_000,
                method = "POST",
                url = "https://api.anthropic.com/v1/messages",
                status = 0,
                durationMs = 20_000,
                error = "SocketTimeoutException：timeout"
            )
        )
        val raw = buildString {
            appendLine(ApiCallLogCodec.encodeLine(entries[0]))
            appendLine("{corrupt json line")
            appendLine()
            appendLine(ApiCallLogCodec.encodeLine(entries[1]))
        }
        val decoded = ApiCallLogCodec.decodeLines(raw)
        assertEquals(entries, decoded)
        assertTrue(decoded[0].succeeded)
        assertFalse(decoded[1].succeeded)
    }

    @Test
    fun `decode of blank or missing content is empty`() {
        assertTrue(ApiCallLogCodec.decodeLines(null).isEmpty())
        assertTrue(ApiCallLogCodec.decodeLines("  \n ").isEmpty())
    }
}
