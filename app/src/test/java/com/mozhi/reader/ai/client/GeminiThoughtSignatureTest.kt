package com.mozhi.reader.ai.client

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiThoughtSignatureTest {
    @Test
    fun `native signatures survive parallel calls persistence and sequential tool rounds`() = runBlocking {
        SseFixture(
            listOf(
                """{"candidates":[{"content":{"parts":[{"thought":true,"text":"先查前文"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"我来查一下。"},{"functionCall":{"name":"search_book","args":{"query":"主角"}},"thoughtSignature":"c2lnL0E="},{"functionCall":{"name":"search_book","args":{"query":"配角"}}}]}}]}"""
            ),
            listOf(
                """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_book","args":{"query":"结局"}},"thoughtSignature":"c2lnL0I="}]}}]}"""
            ),
            listOf("""{"candidates":[{"content":{"parts":[{"text":"查到了"}]}}]}""")
        ).use { fixture ->
            val client = GeminiClient("https://example.test", "key", "gemini-3-pro", fixture.http)
            val history = mutableListOf(ChatMessage(ChatRole.USER, "查找人物"))
            client.toolRound(history)
            client.toolRound(history)
            val reply = withTimeout(5_000) { client.chatStream(history, emptyList()).toList() }

            assertEquals(listOf(ChatDelta.Text("查到了")), reply)
            val secondContents = fixture.requests[1].getValue("contents").jsonArray
            val firstCalls = secondContents[1].jsonObject.getValue("parts").jsonArray
                .map { it.jsonObject }.filter { "functionCall" in it }
            assertEquals(2, firstCalls.size)
            assertEquals("c2lnL0E=", firstCalls[0].getValue("thoughtSignature").jsonPrimitive.content)
            assertFalse("Unsigned parallel calls must stay unsigned", "thoughtSignature" in firstCalls[1])
            assertEquals(
                listOf("主角", "配角"),
                firstCalls.map { it.getValue("functionCall").jsonObject.getValue("args")
                    .jsonObject.getValue("query").jsonPrimitive.content }
            )
            val finalCalls = fixture.requests[2].getValue("contents").jsonArray
                .flatMap { it.jsonObject.getValue("parts").jsonArray }
                .map { it.jsonObject }.filter { "functionCall" in it }
            assertEquals(
                listOf("c2lnL0E=", null, "c2lnL0I="),
                finalCalls.map { it["thoughtSignature"]?.jsonPrimitive?.content }
            )
        }
    }

    @Test
    fun `openai compatible signatures arriving in later fragments stay with their call`() = runBlocking {
        SseFixture(
            listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":1,"extra_content":{"google":{"thought_signature":"c2lnL0I="}}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"search_book","arguments":"{\"query\":"}},{"index":1,"id":"call_b","function":{"name":"search_book","arguments":"{}"}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"extra_content":{"google":{"thought_signature":"c2lnL0E="},"provider_flag":true}}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"主角\"}"},"extra_content":{"google":{"trace":"kept"}}},{"index":1,"extra_content":{}}]}}]}""",
                "[DONE]"
            ),
            listOf("""{"choices":[{"delta":{"content":"查到了"}}]}""", "[DONE]")
        ).use { fixture ->
            val client = OpenAiCompatClient("https://example.test/v1", "key", "gemini-3-pro", fixture.http)
            val history = mutableListOf(ChatMessage(ChatRole.USER, "查找人物"))
            client.toolRound(history)
            withTimeout(5_000) { client.chatStream(history, emptyList()).toList() }

            val calls = fixture.requests[1].getValue("messages").jsonArray[1]
                .jsonObject.getValue("tool_calls").jsonArray.map { it.jsonObject }
            assertEquals(listOf("call_a", "call_b"), calls.map { it.getValue("id").jsonPrimitive.content })
            assertEquals(
                listOf("c2lnL0E=", "c2lnL0I="),
                calls.map { it.getValue("extra_content").jsonObject.getValue("google")
                    .jsonObject.getValue("thought_signature").jsonPrimitive.content }
            )
            assertEquals(
                """{"google":{"thought_signature":"c2lnL0E=","trace":"kept"},"provider_flag":true}""".asObject(),
                calls[0].getValue("extra_content")
            )
            assertEquals("{\"query\":\"主角\"}", calls[0].getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content)
        }
    }

    @Test
    fun `openrouter reasoning details survive chunks and replay once on the assistant message`() = runBlocking {
        val details = listOf(
            """{"type":"reasoning.text","text":"先查前文","format":"google-gemini-v1","index":0}""".asObject(),
            """{"type":"reasoning.encrypted","data":"c2lnL0E=","id":"call_a","format":"google-gemini-v1","index":1}""".asObject(),
            """{"type":"reasoning.encrypted","data":"c2lnL0I=","id":"call_b","format":"google-gemini-v1","index":2}""".asObject()
        )
        SseFixture(
            listOf(
                """{"choices":[{"delta":{"reasoning_details":[${details[0]}]}}]}""",
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"search_book","arguments":"{}"}},{"index":1,"id":"call_b","function":{"name":"search_book","arguments":"{}"}}],"reasoning_details":[${details[1]}]}}]}""",
                """{"choices":[{"delta":{"reasoning_details":[${details[2]}]},"finish_reason":"tool_calls"}]}""",
                "[DONE]"
            ),
            listOf("""{"choices":[{"delta":{"content":"查到了"}}]}""", "[DONE]")
        ).use { fixture ->
            val client = OpenAiCompatClient("https://example.test/v1", "key", "google/gemini-3-pro", fixture.http)
            val history = mutableListOf(ChatMessage(ChatRole.USER, "查找人物"))
            client.toolRound(history)
            withTimeout(5_000) { client.chatStream(history, emptyList()).toList() }

            val messages = fixture.requests[1].getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(details, messages[1].getValue("reasoning_details").jsonArray)
            assertTrue(messages.filterIndexed { index, _ -> index != 1 }.none { "reasoning_details" in it })
            assertTrue(messages[1].getValue("tool_calls").jsonArray.none { "reasoning_details" in it.jsonObject })
        }
    }

    @Test
    fun `old tool history remains readable without adding signature fields`() {
        val legacy = """[{"id":"call_1","name":"search_book","arguments":"{}"}]"""
        val serializer = ListSerializer(ToolCall.serializer())
        val calls = AiJson.decodeFromString(serializer, legacy)
        assertEquals(listOf(ToolCall("call_1", "search_book", "{}")), calls)
        assertEquals(legacy, AiJson.encodeToString(serializer, calls))
    }

    @Test
    fun `native snake case signatures are read and replayed using the rest field name`() = runBlocking {
        SseFixture(
            listOf("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_book","args":{}},"thought_signature":"c2lnL0E="}]}}]}"""),
            listOf("""{"candidates":[{"content":{"parts":[{"text":"查到了"}]}}]}""")
        ).use { fixture ->
            val client = GeminiClient("https://example.test", "key", "gemini-3-pro", fixture.http)
            val history = mutableListOf(ChatMessage(ChatRole.USER, "查找人物"))
            client.toolRound(history)
            withTimeout(5_000) { client.chatStream(history, emptyList()).toList() }
            val part = fixture.requests[1].getValue("contents").jsonArray[1]
                .jsonObject.getValue("parts").jsonArray.single().jsonObject
            assertEquals("c2lnL0E=", part.getValue("thoughtSignature").jsonPrimitive.content)
            assertFalse("thought_signature" in part)
        }
    }

    @Test
    fun `unsigned tool history omits protocol metadata in both request dialects`() = runBlocking {
        val history = listOf(
            ChatMessage(ChatRole.USER, "查找人物"),
            ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall("g0_search_book", "search_book", "{}"))),
            ChatMessage(ChatRole.TOOL, "找到结果", toolCallId = "g0_search_book")
        )
        val reply = listOf("""{"candidates":[{"content":{"parts":[{"text":"查到了"}]}}],"choices":[{"delta":{"content":"查到了"}}]}""")
        listOf<(OkHttpClient) -> ChatApiClient>(
            { GeminiClient("https://example.test", "key", "gemini-2.5-pro", it) },
            { OpenAiCompatClient("https://example.test/v1", "key", "other-model", it) }
        ).forEach { createClient ->
            SseFixture(reply).use { fixture ->
                assertEquals(
                    listOf(ChatDelta.Text("查到了")),
                    withTimeout(5_000) { createClient(fixture.http).chatStream(history, emptyList()).toList() }
                )
                val request = fixture.requests.single().toString()
                listOf("thoughtSignature", "extra_content", "reasoning_details").forEach {
                    assertFalse("Unsigned calls must not add $it", request.contains(it))
                }
            }
        }
    }

    private suspend fun ChatApiClient.toolRound(history: MutableList<ChatMessage>) {
        val deltas = withTimeout(5_000) {
            chatStream(history.toList(), listOf(ToolSpec("search_book", "搜索正文", """{"type":"object"}""".asObject()))).toList()
        }
        val calls = deltas.filterIsInstance<ChatDelta.ToolCalls>().single().calls
        // AgentLoop persists this exact JSON representation before a later request reloads it.
        val serializer = ListSerializer(ToolCall.serializer())
        val restoredCalls = AiJson.decodeFromString(serializer, AiJson.encodeToString(serializer, calls))
        val visible = deltas.mapNotNull {
            when (it) {
                is ChatDelta.Text -> it.text
                is ChatDelta.Reasoning -> it.text
                is ChatDelta.ToolCalls -> null
                is ChatDelta.Usage -> null
            }
        }.joinToString("")
        assertFalse("Signatures are protocol data, not visible reasoning", visible.contains("c2lnL0"))
        history += ChatMessage(
            ChatRole.ASSISTANT,
            deltas.filterIsInstance<ChatDelta.Text>().joinToString("") { it.text },
            toolCalls = restoredCalls
        )
        restoredCalls.forEach { history += ChatMessage(ChatRole.TOOL, "找到结果", toolCallId = it.id) }
    }

    private class SseFixture(vararg responses: List<String>) : Closeable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val index = requests.size
            requests += AiJson.parseToJsonElement(body).jsonObject
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responses[index].joinToString("") { "data: $it\n\n" }
                    .toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()

        override fun close() {
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
        }
    }

    private fun String.asObject(): JsonObject = AiJson.parseToJsonElement(this).jsonObject
}
