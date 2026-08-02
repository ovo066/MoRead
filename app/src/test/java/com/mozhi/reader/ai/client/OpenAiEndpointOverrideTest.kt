package com.mozhi.reader.ai.client

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiEndpointOverrideTest {
    @Test
    fun `embedding uses model endpoint headers and body overrides`() = runBlocking {
        var path = ""
        var header = ""
        var body = ""
        val httpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    path = request.url.encodedPath
                    header = request.header("X-Model-Header").orEmpty()
                    body = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"data":[{"index":0,"embedding":[1.0,0.5]}]}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
            )
            .build()
        val client = OpenAiCompatClient(
            baseUrl = "https://example.test/api/v1",
            apiKey = "secret",
            model = "embedding/model",
            httpClient = httpClient,
            embeddingEndpointPath = "/vectors/embed",
            extraJson =
                """{"headers":{"X-Model-Header":"yes"},"body":{"dimensions":1024}}"""
        )

        val vectors = client.embed(listOf("测试"))

        assertEquals("/api/v1/vectors/embed", path)
        assertEquals("yes", header)
        assertTrue(body.contains("\"dimensions\":1024"))
        assertEquals(listOf(1.0f, 0.5f), vectors.single().toList())
    }
}
