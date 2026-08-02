package com.mozhi.reader.ai.provider

import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConnectionTesterTest {
    @Test
    fun `openRouter connection test ignores Claude chat default`() = runBlocking {
        var path = ""
        var authorization: String? = null
        var anthropicKey: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    path = request.url.encodedPath + "?" + request.url.encodedQuery
                    authorization = request.header("Authorization")
                    anthropicKey = request.header("x-api-key")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            )
            .build()
        val provider = AiProviderEntity(
            id = 1,
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            apiKeyAlias = "alias",
            type = AiProviderType.CHAT,
            apiFormat = ApiDialect.CLAUDE.name,
            adapter = AiProviderAdapter.OPENROUTER,
            createdAt = 1
        )

        val result = ProviderConnectionTester(client).test(provider, "secret")

        assertTrue(result is ConnectionTestResult.Success)
        assertEquals("/api/v1/models?output_modalities=all&limit=1", path)
        assertEquals("Bearer secret", authorization)
        assertNull(anthropicKey)
    }
}
