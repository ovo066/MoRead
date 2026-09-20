package com.mozhi.reader.ai.client

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ResponsesParameterCompatibilityTest {
    private val messages = listOf(ChatMessage(ChatRole.SYSTEM, "保持原提示词"), ChatMessage(ChatRole.USER, "解释故字"))
    private val success = """{"output":[{"type":"message","content":[{"type":"output_text","text":"旧的"}]}]}"""
    private fun error(parameter: String, code: String = "unsupported_parameter") = """{"error":{"message":"Unsupported parameter: '$parameter' is not supported with this model.","type":"invalid_request_error","param":"$parameter","code":"$code"}}"""

    @Test fun plainRequestsRemoveRejectedSamplingOverridesOneAtATimeWithoutChangingSettings() = runBlocking {
        Fixture { index, _ -> when (index) {
            0 -> Reply(400, error("temperature"))
            1 -> Reply(400, error("top_p"))
            else -> Reply(200, success)
        } }.use { fixture ->
            val client = OpenAiResponsesClient("https://api.openai.com/v1", "test-key", "chosen-model", fixture.http)
            val options = ChatOptions(temperature = .7f, topP = .8f, maxTokens = 256,
                extraBody = buildJsonObject { put("temperature", .6); putJsonObject("text") { put("verbosity", "low") } })
            assertEquals("旧的", client.chat(messages, options))
            assertEquals(3, fixture.requests.size)
            val first = fixture.requests.first()
            assertEquals(JsonPrimitive(.6), first["temperature"])
            assertEquals(JsonObject(first - "temperature"), fixture.requests[1])
            assertEquals(JsonObject(first - setOf("temperature", "top_p")), fixture.requests[2])
            assertEquals(JsonPrimitive(false), fixture.requests[2]["store"])
            assertEquals("旧的", client.chat(messages, options))
            assertEquals(first, fixture.requests[3]) // Different calls/models/settings must not inherit rejection state.
            assertEquals(.7f, options.temperature)
        }
    }

    @Test fun streamRetriesBeforeOutputAndPreservesToolsUsageAndRequestHeaders() = runBlocking {
        Fixture { index, _ -> if (index == 0) Reply(400, error("temperature", "unsupported_value")) else Reply(200,
            "event: response.output_text.delta\ndata: {\"delta\":\"旧的\"}\n\n" +
            "event: response.completed\ndata: {\"response\":{\"usage\":{\"input_tokens\":15,\"output_tokens\":4},\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"search_book\",\"arguments\":\"{}\"}]}}\n\n", "text/event-stream")
        }.use { fixture ->
            val client = OpenAiResponsesClient("https://api.openai.com/v1", "test-key", "chosen-model", fixture.http)
            val tools = listOf(ToolSpec("search_book", "查询", buildJsonObject { put("type", "object") }))
            val deltas = withTimeout(5_000) { client.chatStream(messages, tools, ChatOptions(temperature = .7f, extraHeaders = mapOf("X-Test" to "preserved"))).toList() }
            assertEquals(listOf("旧的"), deltas.filterIsInstance<ChatDelta.Text>().map { it.text })
            assertEquals(4L, deltas.filterIsInstance<ChatDelta.Usage>().single().outputTokens)
            assertEquals("call_1", deltas.filterIsInstance<ChatDelta.ToolCalls>().single().calls.single().id)
            assertEquals(JsonObject(fixture.requests[0] - "temperature"), fixture.requests[1])
            assertTrue(fixture.headers.all { it["X-Test"] == "preserved" })
        }
    }

    @Test fun removesNestedTuningButPreservesSchemaAndNeverStripsRequiredOrPrivacyFields() {
        val payload = AiJson.parseToJsonElement("""{"temperature":0.7,"store":false,"tools":[],"input":[],"model":"m","text":{"verbosity":"low","format":{"type":"json_object"}}}""").jsonObject
        val adjusted = removeRejectedResponsesParameter(payload, error("text.verbosity"))!!
        assertFalse("verbosity" in adjusted["text"]!!.jsonObject)
        assertEquals(payload["text"]!!.jsonObject["format"], adjusted["text"]!!.jsonObject["format"])
        listOf("store", "tools", "input", "model", "text.format", "absent", "top_p").forEach { assertNull(removeRejectedResponsesParameter(payload, error(it))) }
        assertNull(removeRejectedResponsesParameter(payload, """{"error":{"message":"temperature must be between 0 and 2","param":"temperature","code":"invalid_value"}}"""))
        assertEquals(JsonObject(payload - "temperature"), removeRejectedResponsesParameter(payload,
            """{"error":{"message":"Unrecognized request argument: 'temperature'"}}"""))
    }

    @Test fun neverRetriesAuthRateLimitServerErrorsOrStreamFailuresAfterText() = runBlocking {
        for (status in listOf(401, 429, 500)) Fixture { _, _ -> Reply(status, error("temperature")) }.use { fixture ->
            val client = OpenAiResponsesClient("https://example.invalid", "test", "m", fixture.http)
            assertTrue(runCatching { client.chat(messages, ChatOptions(temperature = .7f)) }.isFailure)
            assertEquals(1, fixture.requests.size)
        }
        Fixture { _, _ -> Reply(200,
            "event: response.output_text.delta\ndata: {\"delta\":\"已经输出\"}\n\n" +
            "event: error\ndata: ${error("temperature")}\n\n", "text/event-stream") }.use { fixture ->
            val client = OpenAiResponsesClient("https://example.invalid", "test", "m", fixture.http)
            assertTrue(runCatching { withTimeout(5_000) { client.chatStream(messages, options = ChatOptions(temperature = .7f)).toList() } }.isFailure)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test fun repeatedRejectionStopsWhenParameterAlreadyRemovedAndRetriesAreBounded() = runBlocking {
        Fixture { _, _ -> Reply(400, error("temperature")) }.use { fixture ->
            val client = OpenAiResponsesClient("https://example.invalid", "test", "m", fixture.http)
            assertTrue(runCatching { client.chat(messages, ChatOptions(temperature = .7f)) }.isFailure)
            assertEquals(2, fixture.requests.size)
        }
        val fields = listOf("temperature", "top_p", "top_logprobs", "logprobs", "seed", "presence_penalty", "frequency_penalty")
        Fixture { index, _ -> Reply(422, error(fields[index])) }.use { fixture ->
            val client = OpenAiResponsesClient("https://example.invalid", "test", "m", fixture.http)
            assertTrue(runCatching { client.chat(messages, ChatOptions(extraBody = JsonObject(fields.associateWith { JsonPrimitive(1) }))) }.isFailure)
            assertEquals(7, fixture.requests.size)
        }
    }

    private data class Reply(val status: Int, val body: String, val type: String = "application/json")
    private class Fixture(responder: (Int, JsonObject) -> Reply) : Closeable {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val headers = CopyOnWriteArrayList<Headers>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val payload = AiJson.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val reply = responder(requests.size, payload)
            requests += payload; headers += request.headers
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(reply.status).message("Test")
                .body(reply.body.toResponseBody(reply.type.toMediaType())).build()
        }.build()
        override fun close() { http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }
}
