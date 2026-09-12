package com.mozhi.reader.ai.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class RerankApiClientTest {
    @Test fun dedicatedEndpointUsesBoundedCanonicalDocumentsAndHonorsScores() = runBlocking {
        var sent: Request? = null
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            sent = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"results":[{"index":0,"relevance_score":0.1},{"index":1,"relevance_score":0.9}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val client = RerankApiClient("https://example.test/v1", "test-key", "bge-reranker", http, "/custom/rerank",
            """{"headers":{"X-Client":"reader"},"body":{"documents":["must not be sent"],"query":"override","top_n":99,"return_documents":true,"model":"other","custom":1}}""")
        assertEquals(listOf(1, 0), client.rank("人物线索", listOf("已读原文甲", "已读原文乙")))
        val request = requireNotNull(sent)
        assertEquals("/v1/custom/rerank", request.url.encodedPath)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals("reader", request.header("X-Client"))
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        val body = AiJson.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals(listOf("已读原文甲", "已读原文乙"), body.getValue("documents").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("人物线索", body.getValue("query").jsonPrimitive.content)
        assertEquals("2", body.getValue("top_n").jsonPrimitive.content)
        assertEquals("false", body.getValue("return_documents").jsonPrimitive.content)
        assertEquals("bge-reranker", body.getValue("model").jsonPrimitive.content)
        assertEquals("1", body.getValue("custom").jsonPrimitive.content)
    }

    @Test fun invalidRankingsAreRejectedInsteadOfLosingEvidence() {
        listOf(
            """{"results":[{"index":0,"relevance_score":1}]}""",
            """{"results":[{"index":0,"relevance_score":1},{"index":0,"relevance_score":0.5}]}""",
            """{"results":[{"index":2,"relevance_score":1},{"index":0,"relevance_score":0.5}]}""",
            """{"results":[{"index":1,"relevance_score":"NaN"},{"index":0,"relevance_score":0.5}]}""",
            """{"results":[{"index":1,"relevance_score":"Infinity"},{"index":0,"relevance_score":0.5}]}""",
            """{"data":[{"index":0}]}"""
        ).forEach { assertTrue(it, runCatching { RerankApiClient.parseRanking(it, 2) }.exceptionOrNull() is AiClientException.Malformed) }
    }
}
