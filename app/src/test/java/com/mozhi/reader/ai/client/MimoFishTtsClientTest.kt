package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.speech.TtsApiProvider
import com.mozhi.reader.core.speech.defaultBaseUrl
import com.mozhi.reader.core.speech.defaultModel
import java.util.Base64
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class MimoFishTtsClientTest {
    private val audio = byteArrayOf(1, 2, 3, 4)
    private val encoded = Base64.getEncoder().encodeToString(audio)

    @Test fun factoryRoutesSavedProvidersThroughTheirNativeProtocolOnCustomHosts() = runBlocking {
        for (provider in listOf(TtsApiProvider.XIAOMI_MIMO, TtsApiProvider.FISH_AUDIO)) {
            val settings = com.mozhi.reader.core.speech.TtsSettings(aiProvider = provider,
                aiBaseUrl = "https://minimax-proxy.test/v1", aiModel = provider.defaultModel())
            val store = mockk<com.mozhi.reader.core.speech.TtsSettingsStore>()
            coEvery { store.current() } returns settings
            val keyStore = mockk<com.mozhi.reader.core.security.ApiKeyStore>()
            every { keyStore.migrateAlias(any(), com.mozhi.reader.core.speech.TtsSettingsStore.apiKeyAlias(provider)) } returns "test-key"
            val http = OkHttpClient.Builder().addInterceptor {
                val request = it.request()
                if (provider == TtsApiProvider.XIAOMI_MIMO) {
                    assertEquals("/v1/chat/completions", request.url.encodedPath)
                    assertTrue(body(request).containsKey("messages"))
                    reply(request, """{"choices":[{"message":{"audio":{"data":"$encoded"}}}]}""")
                } else {
                    assertEquals("/v1/tts", request.url.encodedPath)
                    assertEquals(provider.defaultModel(), request.header("model"))
                    reply(request, audio, "audio/mpeg")
                }
            }.build()
            val factory = AiClientFactory(mockk(), mockk(), store, mockk(), keyStore, http, mockk())
            val resolved = factory.mediaForRole(ModelRole.TTS)
            assertArrayEquals(audio, resolved.client.synthesizeSpeech("你好").bytes)
            verify { keyStore.migrateAlias(any(), com.mozhi.reader.core.speech.TtsSettingsStore.apiKeyAlias(provider)) }
        }
    }

    @Test fun mimoKeepsNarrationSeparateFromStyleAndDecodesAudio() = runBlocking {
        var request: Request? = null
        val client = client(TtsApiProvider.XIAOMI_MIMO, baseUrl = "https://proxy.test/v1") {
            request = it
            reply(it, """{"id":"chat-1","choices":[{"finish_reason":"stop","message":{"audio":{"id":"audio-1","data":"$encoded"}}}]}""")
        }
        val result = client.synthesizeSpeech("林砚翻开书。", voice = "茉莉", speed = 1.4f,
            volume = 0.8f, pitch = -2, emotion = "开心", instruction = "轻柔自然")
        assertEquals("/v1/chat/completions", request!!.url.encodedPath)
        assertEquals("Bearer test-key", request!!.header("Authorization"))
        val body = body(request!!)
        assertEquals("mimo-v2.5-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals(false, body["stream"]!!.jsonPrimitive.boolean)
        val messages = body["messages"]!!.jsonArray
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("轻柔自然"))
        assertTrue(messages[0].jsonObject["content"]!!.jsonPrimitive.content.contains("1.4"))
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("林砚翻开书。", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("茉莉", body["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", body["audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("input"))
        assertFalse(body.containsKey("voice_prompt"))
        assertArrayEquals(audio, result.bytes)
        assertEquals("audio/wav", result.mediaType)
        assertEquals("audio-1", result.generationId)
    }

    @Test fun mimoHonorsNestedDefaultsAndExplicitMp3WithoutRewritingBookText() = runBlocking {
        val client = client(TtsApiProvider.XIAOMI_MIMO,
            extra = """{"body":{"stream":true,"audio":{"voice":"苏打","format":"wav","optimize_text_preview":true}}}""") {
            val body = body(it)
            assertEquals("苏打", body["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
            assertEquals("mp3", body["audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
            assertFalse(body["audio"]!!.jsonObject.containsKey("optimize_text_preview"))
            assertEquals(1, body["messages"]!!.jsonArray.size)
            assertFalse(body["stream"]!!.jsonPrimitive.boolean)
            reply(it, """{"choices":[{"message":{"audio":{"data":"$encoded"}}}]}""")
        }
        assertEquals("audio/mpeg", client.synthesizeSpeech("你好", responseFormat = "mp3").mediaType)
    }

    @Test fun mimoRejectsMissingInvalidAndTruncatedAudio() = runBlocking {
        listOf("{}", "not json", """{"choices":[{"message":{"audio":{"data":"!!!"}}}]}""",
            """{"choices":[{"finish_reason":"length","message":{"audio":{"data":"$encoded"}}}]}""").forEach { response ->
            val client = client(TtsApiProvider.XIAOMI_MIMO) { reply(it, response) }
            assertTrue(runCatching { client.synthesizeSpeech("你好") }.exceptionOrNull() is AiClientException.Malformed)
        }
    }

    @Test fun fishUsesModelHeaderReferenceIdAndDecibelProsody() = runBlocking {
        val client = client(TtsApiProvider.FISH_AUDIO, baseUrl = "https://proxy.test/v1",
            extra = """{"body":{"prosody":{"normalize_loudness":true}},"headers":{"model":"stale"}}""") {
            assertEquals("/v1/tts", it.url.encodedPath)
            assertEquals("s2.1-pro", it.header("model"))
            assertEquals("Bearer test-key", it.header("Authorization"))
            val body = body(it)
            assertEquals("fish-voice", body["reference_id"]!!.jsonPrimitive.content)
            assertEquals("[happy][轻柔自然]你好", body["text"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("model"))
            assertFalse(body.containsKey("input"))
            assertFalse(body.containsKey("voice"))
            assertFalse(body.containsKey("pitch"))
            val prosody = body["prosody"]!!.jsonObject
            assertEquals(1.5f, prosody["speed"]!!.jsonPrimitive.float, 0.001f)
            assertEquals(6.0206f, prosody["volume"]!!.jsonPrimitive.float, 0.001f)
            assertTrue(prosody["normalize_loudness"]!!.jsonPrimitive.boolean)
            reply(it, audio, "application/octet-stream")
        }
        val result = client.synthesizeSpeech("你好", " fish-voice ", speed = 1.5f, volume = 2f,
            pitch = 3, emotion = "开心", instruction = "轻柔自然")
        assertArrayEquals(audio, result.bytes)
        assertEquals("audio/mpeg", result.mediaType)
    }

    @Test fun fishS1UsesParenthesesAndPreservesDefaultVoiceAndProsody() = runBlocking {
        val client = client(TtsApiProvider.FISH_AUDIO, name = "s1",
            extra = """{"body":{"reference_id":"saved","prosody":{"speed":0.8,"volume":-3}}}""") {
            val body = body(it)
            assertEquals("saved", body["reference_id"]!!.jsonPrimitive.content)
            assertEquals("(sad)你好", body["text"]!!.jsonPrimitive.content)
            assertEquals(0.8f, body["prosody"]!!.jsonObject["speed"]!!.jsonPrimitive.float, 0.001f)
            assertEquals(-3, body["prosody"]!!.jsonObject["volume"]!!.jsonPrimitive.int)
            reply(it, audio, "audio/wav")
        }
        assertEquals("audio/wav", client.synthesizeSpeech("你好", responseFormat = "wav",
            emotion = "悲伤", instruction = "这段不要念出来").mediaType)
    }

    @Test fun fishBlankVoiceUsesServiceDefaultAndDoesNotInventOpenAiVoice() = runBlocking {
        val client = client(TtsApiProvider.FISH_AUDIO) {
            val body = body(it)
            assertFalse(body.containsKey("reference_id"))
            assertFalse(body.containsKey("voice"))
            assertEquals("你好", body["text"]!!.jsonPrimitive.content)
            reply(it, audio, "audio/mpeg")
        }
        assertArrayEquals(audio, client.synthesizeSpeech("你好", voice = "").bytes)
    }

    @Test fun httpErrorsAreNotCachedAsAudioAndKeysAreRedacted() = runBlocking {
        TtsApiProvider.entries.filter { it == TtsApiProvider.XIAOMI_MIMO || it == TtsApiProvider.FISH_AUDIO }.forEach { provider ->
            val client = client(provider) { reply(it, """{"error":{"message":"bad test-key"}}""", code = 401) }
            val error = runCatching { client.synthesizeSpeech("你好") }.exceptionOrNull()
            assertTrue(error is AiClientException)
            assertFalse(error!!.message.orEmpty().contains("test-key"))
        }
        val client = client(TtsApiProvider.FISH_AUDIO) { reply(it, """{"message":"failed"}""") }
        assertTrue(runCatching { client.synthesizeSpeech("你好") }.exceptionOrNull() is AiClientException.Malformed)
    }

    @Test fun fishEmptyAudioIsRejected() = runBlocking {
        val client = client(TtsApiProvider.FISH_AUDIO) { reply(it, byteArrayOf(), "audio/mpeg") }
        assertTrue(runCatching { client.synthesizeSpeech("你好") }.exceptionOrNull() is AiClientException.Empty)
    }

    private fun client(provider: TtsApiProvider, baseUrl: String = provider.defaultBaseUrl(),
        name: String = provider.defaultModel(), extra: String = "{}", respond: (Request) -> Response
    ): OpenAiMediaClient {
        val entity = AiProviderEntity(id = 1, name = "test", baseUrl = baseUrl, apiKeyAlias = "test",
            type = AiProviderType.TTS, createdAt = 0)
        val model = AiModelEntity(id = 1, providerId = 1, modelName = name, type = AiModelType.TTS,
            endpointPath = if (provider == TtsApiProvider.XIAOMI_MIMO) "/chat/completions" else "/tts",
            extraJson = extra, createdAt = 0)
        val http = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        return OpenAiMediaClient(entity, model, "test-key", http, provider)
    }

    private fun body(request: Request) = Json.parseToJsonElement(
        Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun reply(request: Request, body: String, code: Int = 200) =
        reply(request, body.toByteArray(), "application/json", code)
    private fun reply(request: Request, body: ByteArray, mime: String, code: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", mime)
            .body(body.toResponseBody(mime.toMediaType())).build()
}
