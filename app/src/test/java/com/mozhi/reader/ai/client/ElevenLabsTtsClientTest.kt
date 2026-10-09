package com.mozhi.reader.ai.client

import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.security.ApiKeyStore
import com.mozhi.reader.core.speech.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ElevenLabsTtsClientTest {
    private val audio = byteArrayOf(1, 2, 3, 4)

    @Test fun factoryUsesNativeAuthenticationOnCustomHostAndProviderScopedKey() = runBlocking {
        val store = mockk<TtsSettingsStore>()
        coEvery { store.current() } returns TtsSettings(aiProvider = TtsApiProvider.ELEVENLABS,
            aiBaseUrl = "https://minimax-proxy.test/v1", aiModel = TtsApiProvider.ELEVENLABS.defaultModel(), aiVoiceId = " voice-123 ", aiSpeed = 1.1f)
        val keys = mockk<ApiKeyStore>()
        every { keys.migrateAlias(TtsSettingsStore.API_KEY_ALIAS, TtsSettingsStore.apiKeyAlias(TtsApiProvider.ELEVENLABS)) } returns "test-key"
        val http = OkHttpClient.Builder().addInterceptor {
            val request = it.request()
            assertEquals("/v1/text-to-speech/voice-123", request.url.encodedPath)
            assertEquals("test-key", request.header("xi-api-key"))
            assertNull(request.header("Authorization"))
            assertEquals("mp3_44100_128", request.url.queryParameter("output_format"))
            val body = body(request)
            assertEquals("你好", body["text"]!!.jsonPrimitive.content)
            assertEquals("eleven_multilingual_v2", body["model_id"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("model"))
            assertFalse(body.containsKey("input"))
            assertFalse(body.containsKey("voice_id"))
            assertEquals(1.1f, body["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.float, 0.001f)
            reply(request)
        }.build()
        val factory = AiClientFactory(mockk(), mockk(), store, mockk(), keys, http, mockk())
        val result = factory.mediaForRole(ModelRole.TTS).client.synthesizeSpeech("你好")
        assertArrayEquals(audio, result.bytes)
        assertEquals("eleven-request", result.generationId)
    }

    @Test fun assignedModelOnOfficialHostIsDetectedWithoutPreset() = runBlocking {
        val client = client(preset = null) {
            assertEquals("/v1/text-to-speech/voice-123", it.url.encodedPath)
            assertEquals("test-key", it.header("xi-api-key"))
            reply(it)
        }
        assertArrayEquals(audio, client.synthesizeSpeech("你好", voice = "voice-123").bytes)
    }

    @Test fun explicitSettingsOverrideDefaultsAndPreserveVoiceTuning() = runBlocking {
        val client = client(extra = """{"headers":{"xi-api-key":"stale","X-Proxy":"custom"},"body":{"voice_id":"saved","model_id":"stale","output_format":"wav_24000","voice_settings":{"stability":0.4,"similarity_boost":0.8,"speed":0.9},"seed":12}}""") {
            assertEquals("test-key", it.header("xi-api-key"))
            assertEquals("custom", it.header("X-Proxy"))
            assertEquals("opus_48000_128", it.url.queryParameter("output_format"))
            assertEquals("/v1/text-to-speech/chosen", it.url.encodedPath)
            val fields = body(it)
            assertEquals("eleven_multilingual_v2", fields["model_id"]!!.jsonPrimitive.content)
            assertEquals("林砚翻开书。", fields["text"]!!.jsonPrimitive.content)
            val settings = fields["voice_settings"]!!.jsonObject
            assertEquals(1.2f, settings["speed"]!!.jsonPrimitive.float, 0.001f)
            assertEquals(0.4f, settings["stability"]!!.jsonPrimitive.float, 0.001f)
            assertEquals(0.8f, settings["similarity_boost"]!!.jsonPrimitive.float, 0.001f)
            assertEquals(12, fields["seed"]!!.jsonPrimitive.int)
            assertFalse(fields.containsKey("output_format"))
            assertFalse(fields.containsKey("volume"))
            assertFalse(fields.containsKey("pitch"))
            assertFalse(fields.containsKey("instructions"))
            reply(it, mime = "application/octet-stream")
        }
        val result = client.synthesizeSpeech("林砚翻开书。", voice = "chosen", responseFormat = "opus", speed = 2f,
            volume = 2f, pitch = 3, emotion = "开心", instruction = "这句指令不能念出来")
        assertEquals("audio/ogg", result.mediaType)
        assertArrayEquals(audio, result.bytes)
    }

    @Test fun nullArgumentsUseSavedVoiceFormatAndSpeed() = runBlocking {
        val client = client(extra = """{"body":{"voice_id":"saved","output_format":"wav_24000","voice_settings":{"speed":0.9}}}""") {
            assertEquals("/v1/text-to-speech/saved", it.url.encodedPath)
            assertEquals("wav_24000", it.url.queryParameter("output_format"))
            assertEquals(0.9f, body(it)["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.float, 0.001f)
            reply(it)
        }
        assertEquals("audio/wav", client.synthesizeSpeech("你好").mediaType)
    }

    @Test fun formatsProducePlayableMimeAndSpeedHasLowerBound() = runBlocking {
        for ((format, output, mime) in listOf(Triple("mp3", "mp3_44100_128", "audio/mpeg"),
            Triple("wav", "wav_24000", "audio/wav"), Triple("opus", "opus_48000_128", "audio/ogg"))) {
            val client = client {
                assertEquals(output, it.url.queryParameter("output_format"))
                assertEquals(0.7f, body(it)["voice_settings"]!!.jsonObject["speed"]!!.jsonPrimitive.float, 0.001f)
                reply(it)
            }
            assertEquals(mime, client.synthesizeSpeech("你好", voice = "saved", responseFormat = format, speed = 0.5f).mediaType)
        }
    }

    @Test fun v3OmitsUnsupportedSpeedAndKeepsStability() = runBlocking {
        val client = client(name = "eleven_v3", extra = """{"body":{"voice_settings":{"speed":0.9,"stability":0.5}}}""") {
            val settings = body(it)["voice_settings"]!!.jsonObject
            assertFalse(settings.containsKey("speed"))
            assertEquals(0.5f, settings["stability"]!!.jsonPrimitive.float, 0.001f)
            reply(it)
        }
        assertArrayEquals(audio, client.synthesizeSpeech("你好", voice = "saved", speed = 1.1f).bytes)
    }

    @Test fun voiceIdCannotChangePathOrQuery() = runBlocking {
        val client = client {
            assertEquals(listOf("v1", "text-to-speech", "custom/voice?key=value"), it.url.pathSegments)
            assertNull(it.url.queryParameter("key"))
            reply(it)
        }
        assertArrayEquals(audio, client.synthesizeSpeech("你好", voice = "custom/voice?key=value").bytes)
    }

    @Test fun missingOrExplicitlyClearedVoiceAndUnsupportedFormatNeverSendRequest() = runBlocking {
        val client = client(extra = """{"body":{"voice_id":"saved"}}""") { error("Request must not be sent") }
        for (voice in listOf("", " ", ".", "..")) {
            assertTrue(runCatching { client.synthesizeSpeech("你好", voice = voice) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { client.synthesizeSpeech("你好", responseFormat = "pcm") }.exceptionOrNull() is IllegalArgumentException)
        val empty = client { error("Request must not be sent") }
        assertTrue(runCatching { empty.synthesizeSpeech("你好") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun httpErrorsRedactKeysAndNonAudioOrEmptyResponsesAreRejected() = runBlocking {
        for (code in listOf(401, 429, 422, 500)) {
            val client = client { reply(it, """{"detail":{"message":"bad test-key"}}""".toByteArray(), "application/json", code) }
            val error = runCatching { client.synthesizeSpeech("你好", voice = "saved") }.exceptionOrNull()
            assertTrue(error is AiClientException)
            assertFalse(error!!.message.orEmpty().contains("test-key"))
        }
        val json = client { reply(it, "{}".toByteArray(), "application/json") }
        assertTrue(runCatching { json.synthesizeSpeech("你好", voice = "saved") }.exceptionOrNull() is AiClientException.Malformed)
        val empty = client { reply(it, byteArrayOf()) }
        assertTrue(runCatching { empty.synthesizeSpeech("你好", voice = "saved") }.exceptionOrNull() is AiClientException.Empty)
    }

    private fun client(name: String = "eleven_multilingual_v2", extra: String = "{}",
        preset: TtsApiProvider? = TtsApiProvider.ELEVENLABS, respond: (Request) -> Response
    ): OpenAiMediaClient {
        val provider = AiProviderEntity(id = 1, name = "test", baseUrl = "https://api.elevenlabs.io/v1",
            apiKeyAlias = "test", type = AiProviderType.TTS, createdAt = 0)
        val model = AiModelEntity(id = 1, providerId = 1, modelName = name, type = AiModelType.TTS,
            endpointPath = "", extraJson = extra, createdAt = 0)
        val http = OkHttpClient.Builder().addInterceptor { respond(it.request()) }.build()
        return OpenAiMediaClient(provider, model, "test-key", http, preset)
    }

    private fun body(request: Request) = Json.parseToJsonElement(
        Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
    private fun reply(request: Request, bytes: ByteArray = audio, mime: String = "audio/mpeg", code: Int = 200) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", mime).header("request-id", "eleven-request")
            .body(bytes.toResponseBody(mime.toMediaType())).build()
}
