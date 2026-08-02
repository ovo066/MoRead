package com.mozhi.reader.ai.provider

import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun openRouterAggregatesDedicatedCatalogsAndInfersCapabilities() = runBlocking {
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val client = fakeClient { path ->
            paths += path
            when {
                path.startsWith("/api/v1/models?") ->
                    """{"data":[
                        {"id":"anthropic/claude-sonnet","architecture":{"output_modalities":["text"]}},
                        {"id":"openai/voice","architecture":{"output_modalities":["speech"]}},
                        {"id":"vendor/painter","architecture":{"output_modalities":["image"]}}
                    ]}"""
                path == "/api/v1/embeddings/models" ->
                    """{"data":[{"id":"openai/text-embedding-3-large"}]}"""
                path == "/api/v1/images/models" ->
                    """{"data":[{"id":"vendor/painter"}]}"""
                else -> error("Unexpected path $path")
            }
        }

        val result = ModelCatalogFetcher(client).fetch(
            provider(adapter = AiProviderAdapter.OPENROUTER),
            "secret"
        ) as ModelCatalogResult.Success

        assertEquals(4, result.models.size)
        assertEquals(
            AiModelType.EMBEDDING,
            result.models.single { it.modelName.contains("embedding") }.type
        )
        assertEquals(AiModelType.TTS, result.models.single { it.modelName == "openai/voice" }.type)
        assertEquals(AiModelType.IMAGE, result.models.single { it.modelName == "vendor/painter" }.type)
        assertEquals("/images", result.models.single { it.type == AiModelType.IMAGE }.endpointPath)
        assertTrue(paths.any { it.startsWith("/api/v1/models?output_modalities=all") })
        assertTrue(paths.contains("/api/v1/embeddings/models"))
        assertTrue(paths.contains("/api/v1/images/models"))
    }

    @Test
    fun genericOpenAiUsesOnlyStandardCatalogAndLeavesTypeManual() = runBlocking {
        val paths = mutableListOf<String>()
        val client = fakeClient { path ->
            paths += path
            """{"data":[{"id":"custom-model","architecture":{"output_modalities":["image"]}}]}"""
        }

        val result = ModelCatalogFetcher(client).fetch(
            provider(adapter = AiProviderAdapter.CUSTOM),
            "secret"
        ) as ModelCatalogResult.Success

        assertEquals(listOf("/api/v1/models"), paths)
        assertEquals(AiModelType.CHAT, result.models.single().type)
        assertEquals("", result.models.single().endpointPath)
    }

    private fun provider(adapter: AiProviderAdapter) = AiProviderEntity(
        id = 1,
        name = adapter.name,
        baseUrl = "https://example.test/api/v1",
        apiKeyAlias = "alias",
        type = AiProviderType.CHAT,
        apiFormat = "OPENAI",
        adapter = adapter,
        createdAt = 1
    )

    private fun fakeClient(bodyForPath: (String) -> String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val request = chain.request()
                    val path = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bodyForPath(path).toResponseBody("application/json".toMediaType()))
                        .build()
                }
            )
            .build()
}
