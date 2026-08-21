package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiMediaClientTest {
    @Test
    fun openRouterImageUsesDedicatedEndpointAndModelBody() = runBlocking {
        var capturedPath = ""
        var capturedBody = ""
        val expected = byteArrayOf(1, 2, 3, 4)
        val client = fakeClient { request ->
            capturedPath = request.url.encodedPath
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val json = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(expected)}","media_type":"image/png"}]}"""
            json.toByteArray() to "application/json"
        }
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.OPENROUTER),
            model = model(
                AiModelType.IMAGE,
                extraJson = """{"body":{"aspect_ratio":"16:9","resolution":"2K"}}"""
            ),
            apiKey = "secret",
            httpClient = client
        )

        val result = media.generateImages("水墨山川").single()

        assertEquals("/api/v1/images", capturedPath)
        assertTrue(capturedBody.contains("\"aspect_ratio\":\"16:9\""))
        assertArrayEquals(expected, result.bytes)
        assertEquals("image/png", result.mediaType)
    }

    @Test
    fun temporaryImageUrlIsDownloadedImmediately() = runBlocking {
        val expected = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        var capturedPath = ""
        var capturedAuthorization: String? = null
        val client = fakeClient { request ->
            capturedPath = request.url.encodedPath
            capturedAuthorization = request.header("Authorization")
            expected to "image/png"
        }
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.CUSTOM),
            model = model(AiModelType.IMAGE),
            apiKey = "secret",
            httpClient = client
        )

        val bytes = media.materializeImage(
            GeneratedImage(bytes = null, url = "https://example.test/generated/1.png", mediaType = null)
        )

        assertEquals("/generated/1.png", capturedPath)
        assertEquals("Bearer secret", capturedAuthorization)
        assertArrayEquals(expected, bytes)
    }

    @Test
    fun nonImageTemporaryUrlResponseIsRejected() = runBlocking {
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.CUSTOM),
            model = model(AiModelType.IMAGE),
            apiKey = "secret",
            httpClient = fakeClient { "not an image".toByteArray() to "text/plain" }
        )

        val error = runCatching {
            media.materializeImage(
                GeneratedImage(
                    bytes = null,
                    url = "https://example.test/generated/result",
                    mediaType = null
                )
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("不是可识别的图片"))
    }

    @Test
    fun externalPlainHttpImageUrlIsRejected() = runBlocking {
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.CUSTOM),
            model = model(AiModelType.IMAGE),
            apiKey = "secret",
            httpClient = fakeClient { byteArrayOf(1) to "image/png" }
        )

        val error = runCatching {
            media.materializeImage(
                GeneratedImage(bytes = null, url = "http://example.test/image.png", mediaType = null)
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun speechUsesOpenAiCompatibleEndpointAndReturnsBinaryBody() = runBlocking {
        var capturedPath = ""
        var capturedBody = ""
        val expected = byteArrayOf(9, 8, 7)
        val client = fakeClient { request ->
            capturedPath = request.url.encodedPath
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            expected to "audio/mpeg"
        }
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.CUSTOM),
            model = model(AiModelType.TTS),
            apiKey = "secret",
            httpClient = client
        )

        val result = media.synthesizeSpeech("你好", voice = "nova", speed = 1.5f)

        assertEquals("/api/v1/audio/speech", capturedPath)
        assertTrue(capturedBody.contains("\"voice\":\"nova\""))
        assertTrue(capturedBody.contains("\"speed\":1.5"))
        assertArrayEquals(expected, result.bytes)
        assertEquals("audio/mpeg", result.mediaType)
    }

    @Test
    fun miniMaxSpeechUsesVoiceIdSpeedAndDecodesHexAudio() = runBlocking {
        var capturedPath = ""
        var capturedGroupId: String? = null
        var capturedBody = ""
        val expected = byteArrayOf(0x01, 0x2A, 0x7F)
        val client = fakeClient { request ->
            capturedPath = request.url.encodedPath
            capturedGroupId = request.url.queryParameter("GroupId")
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            """{"data":{"audio":"012a7f"},"trace_id":"trace-1","base_resp":{"status_code":0}}"""
                .toByteArray() to "application/json"
        }
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.MINIMAX),
            model = model(
                AiModelType.TTS,
                extraJson = """{"body":{"group_id":"group-9","voice_setting":{"voice_id":"default"}}}"""
            ),
            apiKey = "secret",
            httpClient = client
        )

        val result = media.synthesizeSpeech(
            text = "你好",
            voice = "voice-clone-42",
            speed = 1.25f,
            volume = 2f,
            pitch = -2
        )

        assertEquals("/api/v1/t2a_v2", capturedPath)
        assertEquals("group-9", capturedGroupId)
        assertTrue(capturedBody.contains("\"voice_id\":\"voice-clone-42\""))
        assertTrue(capturedBody.contains("\"speed\":1.25"))
        assertTrue(capturedBody.contains("\"vol\":2.0"))
        assertTrue(capturedBody.contains("\"pitch\":-2"))
        assertArrayEquals(expected, result.bytes)
        assertEquals("audio/mpeg", result.mediaType)
    }

    @Test
    fun miniMaxSpeechAppliesEmotionPerformanceAndPauseMarker() = runBlocking {
        var capturedBody = ""
        val client = fakeClient { request ->
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            """{"data":{"audio":"01"},"base_resp":{"status_code":0}}"""
                .toByteArray() to "application/json"
        }
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.MINIMAX),
            model = model(AiModelType.TTS),
            apiKey = "secret",
            httpClient = client
        )

        media.synthesizeSpeech(
            text = "“别怕。”",
            voice = "voice-1",
            speed = 1f,
            volume = 1f,
            pitch = 0,
            emotion = "悲伤",
            instruction = "轻声，句末短停"
        )

        val payload = Json.parseToJsonElement(capturedBody).jsonObject
        val voiceSetting = payload.getValue("voice_setting").jsonObject
        assertEquals("sad", voiceSetting.getValue("emotion").jsonPrimitive.contentOrNull)
        assertEquals(0.846f, voiceSetting.getValue("speed").jsonPrimitive.float, 0.001f)
        assertEquals(0.7544f, voiceSetting.getValue("vol").jsonPrimitive.float, 0.001f)
        assertEquals(-2, voiceSetting.getValue("pitch").jsonPrimitive.int)
        assertEquals("“别怕。<#0.28#>”", payload.getValue("text").jsonPrimitive.contentOrNull)
    }

    @Test
    fun chatCompletionEndpointExtractsDataUriImages() = runBlocking {
        var capturedPath = ""
        var capturedBody = ""
        val expected = byteArrayOf(11, 22, 33)
        val encoded = Base64.getEncoder().encodeToString(expected)
        val client = fakeClient { request ->
            capturedPath = request.url.encodedPath
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val json = """{"choices":[{"message":{"images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,$encoded"}}]}}]}"""
            json.toByteArray() to "application/json"
        }
        val media = OpenAiMediaClient(
            provider = provider(AiProviderAdapter.CUSTOM),
            model = model(AiModelType.IMAGE).copy(endpointPath = "/chat/completions"),
            apiKey = "secret",
            httpClient = client
        )

        val result = media.generateImages("画一只窗台上晒太阳的猫").single()

        assertEquals("/api/v1/chat/completions", capturedPath)
        assertTrue(capturedBody.contains("\"messages\""))
        assertTrue(capturedBody.contains("\"modalities\""))
        assertArrayEquals(expected, result.bytes)
        assertEquals("image/png", result.mediaType)
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

    private fun model(type: AiModelType, extraJson: String = "{}") = AiModelEntity(
        id = 1,
        providerId = 1,
        modelName = "test/model",
        type = type,
        extraJson = extraJson,
        createdAt = 1
    )

    private fun fakeClient(
        response: (okhttp3.Request) -> Pair<ByteArray, String>
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val (bytes, contentType) = response(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", contentType)
                    .body(bytes.toResponseBody(contentType.toMediaType()))
                    .build()
            }
        )
        .build()
}
