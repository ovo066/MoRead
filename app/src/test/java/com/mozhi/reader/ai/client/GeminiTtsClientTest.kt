package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GeminiTtsClientTest {
    @Test fun voiceDesignUsesStoredPromptedVoiceAndReturnsPreview() = runBlocking {
        val client = GeminiTtsClient(provider, model, "secret", http { request ->
            assertEquals("/v1beta/voices", request.url.encodedPath)
            assertEquals("POST", request.method)
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertTrue(body.getValue("store").jsonPrimitive.boolean)
            val voice = body.getValue("voice").jsonObject
            assertEquals("prompted", voice.getValue("type").jsonPrimitive.content)
            assertEquals("zh-CN", voice.getValue("language_code").jsonPrimitive.content)
            assertEquals("温暖低沉的男声", voice.getValue("prompted").jsonObject.getValue("input").jsonPrimitive.content)
            200 to """{"id":"voice_new","sample_audio":{"mime_type":"audio/wav","data":"${Base64.getEncoder().encodeToString(wav)}"}}"""
        })
        val voice = client.designVoice(GeminiVoiceDesignRequest("旁白", "温暖低沉的男声", "male"))
        assertEquals("voice_new", voice.id)
        assertArrayEquals(wav, voice.preview!!.bytes)
    }

    @Test fun missingPreviewCanBeFetchedWithoutCreatingAnotherVoiceAndDraftDeleteAccepts204() = runBlocking {
        val requests = mutableListOf<String>()
        val client = GeminiTtsClient(provider, model, "secret", http { request ->
            requests += request.method
            when (request.method) {
                "POST" -> 200 to """{"id":"voice_retry"}"""
                "GET" -> 200 to """{"id":"voice_retry","sample_audio":{"mime_type":"audio/wav","data":"${Base64.getEncoder().encodeToString(wav)}"}}"""
                else -> 204 to ""
            }
        })
        val voice = client.designVoice(GeminiVoiceDesignRequest("女声", "温柔清晰的女声"))
        assertNull(voice.preview)
        assertArrayEquals(wav, client.designedVoicePreview(voice.id).bytes)
        client.deleteDesignedVoice(voice.id)
        assertEquals(listOf("POST", "GET", "DELETE"), requests)
    }

    @Test fun designedVoiceIdCannotEscapeTheVoicesEndpoint() = runBlocking {
        val client = GeminiTtsClient(provider, model, "secret", http { error("No request should be sent") })
        assertTrue(runCatching { client.deleteDesignedVoice("voice_../../models") }.exceptionOrNull() is IllegalArgumentException)
    }
    private val wav = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(40); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(24000); putInt(48000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(4); putInt(0)
    }.array()
    private val provider = AiProviderEntity(id = 1, name = "Gemini", baseUrl = "https://example.test/v1beta/",
        apiKeyAlias = "test", type = AiProviderType.TTS, apiFormat = "GEMINI", adapter = AiProviderAdapter.GEMINI, createdAt = 0)
    private val model = AiModelEntity(id = 1, providerId = 1, modelName = "gemini-3.8-flash-tts", type = AiModelType.TTS, createdAt = 0)
    private fun http(reply: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).build()
    }.build()
    private fun audio(mime: String = "audio/wav", bytes: ByteArray = wav, status: String = "completed") =
        """{"id":"sample","status":"$status","steps":[{"type":"model_output","content":[{"type":"audio","mime_type":"$mime","data":"${Base64.getEncoder().encodeToString(bytes)}"}]}]}"""

    @Test fun nativeRouteKeepsTranscriptSeparateAndReadsRestStepsAsWav() = runBlocking {
        val client = OpenAiMediaClient(provider, model, "test-secret", http { request ->
            assertEquals("/v1beta/interactions", request.url.encodedPath)
            assertEquals("test-secret", request.header("x-goog-api-key"))
            assertNull(request.header("Authorization"))
            val payload = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            assertFalse(payload.getValue("store").jsonPrimitive.boolean)
            val content = payload.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray.single().jsonObject
            assertEquals("林砚翻开书。", content.getValue("text").jsonPrimitive.content)
            assertTrue(content.getValue("annotations").toString().contains("温柔"))
            assertTrue(payload.getValue("generation_config").toString().contains("Sulafat"))
            200 to audio()
        })
        val result = client.synthesizeSpeech("林砚翻开书。", instruction = "温柔", speed = 0.8f)
        assertArrayEquals(wav, result.bytes)
        assertEquals("audio/wav", result.mediaType)
    }

    @Test fun pcmResponseGetsPlayableWavHeader() = runBlocking {
        val pcm = byteArrayOf(0, 0, 4, 0)
        val result = GeminiTtsClient(provider, model, "key", http { 200 to audio("audio/L16;rate=24000", pcm) })
            .synthesizeSpeech("你好")
        assertEquals("WAVE", result.bytes.copyOfRange(8, 12).decodeToString())
        assertEquals(48, result.bytes.size)
        assertArrayEquals(pcm, result.bytes.copyOfRange(44, 48))
    }

    @Test fun unfinishedOrCorruptAudioIsNeverCachedAsSuccess() = runBlocking {
        for (body in listOf(audio(status = "failed"), audio(bytes = byteArrayOf(1, 2)), """{"status":"completed","steps":[]}""")) {
            val error = runCatching { GeminiTtsClient(provider, model, "key", http { 200 to body }).synthesizeSpeech("你好") }.exceptionOrNull()
            assertTrue(error is AiClientException.Malformed)
        }
    }

    @Test fun rateLimitDoesNotFallBackToAnotherPaidProtocol() = runBlocking {
        var requests = 0
        val error = runCatching {
            OpenAiMediaClient(provider, model, "key", http { requests++; 429 to """{"error":{"message":"quota"}}""" })
                .synthesizeSpeech("你好")
        }.exceptionOrNull()
        assertTrue(error is AiClientException.RateLimited)
        assertEquals(1, requests)
    }

    @Test fun voiceCatalogFollowsPaginationAndKeepsIds() = runBlocking {
        var requests = 0
        val voices = GeminiTtsClient(provider, model, "key", http { request ->
            assertNull(request.url.queryParameter("language_code"))
            requests++
            if (requests == 1) 200 to """{"voices":[{"id":"voice_one","display_name":"苏晚","gender":"female"}],"next_page_token":"next"}"""
            else {
                assertEquals("next", request.url.queryParameter("page_token"))
                200 to """{"voices":[{"id":"voice_two","display_name":"旁白","gender":"male"}]}"""
            }
        }).listVoices()
        assertEquals(listOf("voice_one", "voice_two"), voices.map { it.voiceId })
        assertEquals("FEMALE", voices.first().gender)
    }
}
