package com.mozhi.reader.ai.client

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParsingTest {

    // ---- OpenAI chat/completions tool-call fragments ----

    @Test
    fun `openai fragments assemble into complete calls by index`() {
        val accumulator = OpenAiToolCallAccumulator()
        accumulator.accept(
            listOf(
                WireToolCall(index = 0, id = "call_a", function = WireToolFunction(name = "search_book"))
            )
        )
        accumulator.accept(
            listOf(WireToolCall(index = 0, function = WireToolFunction(arguments = "{\"query\":")))
        )
        accumulator.accept(
            listOf(WireToolCall(index = 0, function = WireToolFunction(arguments = "\"主角\"}")))
        )
        accumulator.accept(
            listOf(
                WireToolCall(
                    index = 1,
                    id = "call_b",
                    function = WireToolFunction(name = "get_reading_progress", arguments = "{}")
                )
            )
        )
        val calls = accumulator.build()
        assertEquals(2, calls.size)
        assertEquals(ToolCall("call_a", "search_book", "{\"query\":\"主角\"}"), calls[0])
        assertEquals(ToolCall("call_b", "get_reading_progress", "{}"), calls[1])
    }

    @Test
    fun `openai accumulator is empty without fragments and skips nameless slots`() {
        val accumulator = OpenAiToolCallAccumulator()
        assertTrue(accumulator.isEmpty())
        accumulator.accept(listOf(WireToolCall(index = 0, function = WireToolFunction(arguments = "{}"))))
        assertTrue(accumulator.build().isEmpty())
    }

    // ---- Claude stream events ----

    @Test
    fun `claude text deltas pass through and tool_use blocks accumulate`() {
        val accumulator = ClaudeStreamAccumulator()
        assertNull(
            accumulator.onEvent(
                "content_block_start",
                """{"index":0,"content_block":{"type":"text"}}"""
            )
        )
        assertEquals(
            ChatDelta.Text("你好"),
            accumulator.onEvent(
                "content_block_delta",
                """{"index":0,"delta":{"type":"text_delta","text":"你好"}}"""
            )
        )
        accumulator.onEvent(
            "content_block_start",
            """{"index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"write_note"}}"""
        )
        accumulator.onEvent(
            "content_block_delta",
            """{"index":1,"delta":{"type":"input_json_delta","partial_json":"{\"title\":"}}"""
        )
        accumulator.onEvent(
            "content_block_delta",
            """{"index":1,"delta":{"type":"input_json_delta","partial_json":"\"笔记\"}"}}"""
        )
        val calls = accumulator.finish()
        assertEquals(listOf(ToolCall("toolu_1", "write_note", "{\"title\":\"笔记\"}")), calls)
        // finish() drains the slots so a stray onClosed cannot double-emit.
        assertTrue(accumulator.finish().isEmpty())
    }

    @Test
    fun `claude thinking deltas surface as reasoning, signatures do not`() {
        val accumulator = ClaudeStreamAccumulator()
        assertEquals(
            ChatDelta.Reasoning("先看看进度…"),
            accumulator.onEvent(
                "content_block_delta",
                """{"index":0,"delta":{"type":"thinking_delta","thinking":"先看看进度…"}}"""
            )
        )
        // 签名块是给 API 校验用的，不是给人读的，绝不能上屏。
        assertNull(
            accumulator.onEvent(
                "content_block_delta",
                """{"index":0,"delta":{"type":"signature_delta","signature":"abc"}}"""
            )
        )
        assertTrue(accumulator.finish().isEmpty())
    }

    // ---- OpenAI-compatible reasoning fields ----

    @Test
    fun `openai chunk exposes both reasoning spellings and prefers reasoning_content`() {
        fun reasoningOf(json: String): String? = AiJson
            .decodeFromString(ChatCompletionChunk.serializer(), json)
            .choices
            .first()
            .delta
            .reasoningText

        // DeepSeek / Qwen
        assertEquals(
            "在想…",
            reasoningOf("""{"choices":[{"delta":{"reasoning_content":"在想…"}}]}""")
        )
        // OpenRouter
        assertEquals(
            "在想…",
            reasoningOf("""{"choices":[{"delta":{"reasoning":"在想…"}}]}""")
        )
        assertEquals(
            "标准字段优先",
            reasoningOf(
                """{"choices":[{"delta":{"reasoning_content":"标准字段优先","reasoning":"备用"}}]}"""
            )
        )
        // 普通正文帧不该被误判成思维链。
        assertNull(reasoningOf("""{"choices":[{"delta":{"content":"你好"}}]}"""))
        assertNull(reasoningOf("""{"choices":[{"delta":{"reasoning_content":""}}]}"""))
    }

    // ---- Gemini thought parts ----

    @Test
    fun `gemini marks thought parts apart from answer parts`() {
        val response = AiJson.decodeFromString(
            GeminiClient.GenerateResponse.serializer(),
            """
            {"candidates":[{"content":{"parts":[
                {"text":"先回忆一下前文","thought":true},
                {"text":"他其实早就知道了"}
            ]}}]}
            """.trimIndent()
        )
        val parts = response.candidates.first().content!!.parts
        assertEquals(true, parts[0].thought)
        assertNull(parts[1].thought)
        assertEquals("他其实早就知道了", parts[1].text)
    }

    // ---- OpenAI Responses completed payload ----

    @Test
    fun `responses completed output yields function calls`() {
        val response = AiJson.decodeFromString(
            OpenAiResponsesClient.ResponsesResponse.serializer(),
            """
            {"output":[
                {"type":"reasoning","summary":[]},
                {"type":"message","content":[{"type":"output_text","text":"好的"}]},
                {"type":"function_call","call_id":"call_1","name":"search_book","arguments":"{\"query\":\"伏笔\"}"}
            ]}
            """.trimIndent()
        )
        val calls = OpenAiResponsesClient.extractToolCalls(response)
        assertEquals(listOf(ToolCall("call_1", "search_book", "{\"query\":\"伏笔\"}")), calls)
    }

    // ---- ChatOptions extraJson parsing ----

    @Test
    fun `chat options parse reasoning cache and passthrough keys`() {
        val options = ChatOptions.fromExtraJson(
            """
            {"temperature":0.6,"reasoning":"high","cache_prompt":false,
             "headers":{"X-Test":"1"},"body":{"enable_thinking":true}}
            """.trimIndent()
        )
        assertEquals(0.6f, options.temperature)
        assertEquals(ReasoningEffort.HIGH, options.reasoning)
        assertNull(options.cacheTtl)
        assertEquals(mapOf("X-Test" to "1"), options.extraHeaders)
        assertEquals(true, options.extraBody?.jsonObject?.containsKey("enable_thinking"))
    }

    @Test
    fun `chat options parse the one hour cache tier`() {
        assertEquals(
            PromptCacheTtl.ONE_HOUR,
            ChatOptions.fromExtraJson("""{"cache_ttl":"1h"}""").cacheTtl
        )
    }

    @Test
    fun `chat options default to a five minute cache and no reasoning`() {
        val options = ChatOptions.fromExtraJson("{}")
        assertNull(options.reasoning)
        assertEquals(PromptCacheTtl.FIVE_MINUTES, options.cacheTtl)
    }

    @Test
    fun `tool call arguments parse back to a json object with safe fallback`() {
        assertEquals(
            "主角",
            ToolCall("id", "search_book", "{\"query\":\"主角\"}").argumentsAsJson()
                .let { it["query"]?.toString()?.trim('"') }
        )
        assertTrue(ToolCall("id", "x", "not json").argumentsAsJson().isEmpty())
    }
}
