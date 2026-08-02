package com.mozhi.reader.ai.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenAiResponsesPayloadTest {
    @Test
    fun functionToolAlwaysCarriesRequiredTypeAndStoreFalse() {
        val request = OpenAiResponsesClient.ResponsesRequest(
            model = "gpt-5-mini",
            input = listOf(
                OpenAiResponsesClient.InputItem(role = "user", content = JsonPrimitive("检索前文"))
            ),
            tools = listOf(
                OpenAiResponsesClient.WireTool(
                    type = "function",
                    name = "search_book",
                    description = "检索",
                    parameters = JsonObject(mapOf("type" to JsonPrimitive("object")))
                )
            ),
            stream = true,
            store = false
        )

        val payload = AiJson.encodeToJsonElement(
            OpenAiResponsesClient.ResponsesRequest.serializer(),
            request
        ).jsonObject
        val tool = payload.getValue("tools").jsonArray.single().jsonObject

        assertEquals("function", tool.getValue("type").jsonPrimitive.content)
        assertEquals("search_book", tool.getValue("name").jsonPrimitive.content)
        assertFalse(payload.getValue("store").jsonPrimitive.boolean)
    }
}
