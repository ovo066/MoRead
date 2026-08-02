package com.mozhi.reader.ai.client

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiImageClientTest {

    @Test
    fun v4ModelBuildsStructuredPrompt() {
        val client = client(model = "nai-diffusion-4-5-full", negative = "")
        val payload = client.buildPayload("星空下的小镇", "832x1216")

        assertEquals("星空下的小镇", payload.string("input"))
        assertEquals("nai-diffusion-4-5-full", payload.string("model"))
        assertEquals("generate", payload.string("action"))
        val parameters = payload.obj("parameters")
        assertEquals(832, parameters.int("width"))
        assertEquals(1216, parameters.int("height"))
        assertEquals(28, parameters.int("steps"))
        val v4Prompt = parameters.obj("v4_prompt")
        assertEquals("星空下的小镇", v4Prompt.obj("caption").string("base_caption"))
        assertTrue(
            parameters.obj("v4_negative_prompt").obj("caption").string("base_caption").isNotBlank()
        )
    }

    @Test
    fun v3ModelUsesFlatNegativePromptOnly() {
        val client = client(model = "nai-diffusion-3", negative = "低质量")
        val parameters = client.buildPayload("湖畔", "1216x832").obj("parameters")

        assertEquals(1216, parameters.int("width"))
        assertEquals(832, parameters.int("height"))
        assertEquals("低质量", parameters.string("negative_prompt"))
        assertFalse(parameters.containsKey("v4_prompt"))
    }

    @Test
    fun oddSizeFallsBackAndAlignsTo64() {
        val client = client(model = "nai-diffusion-4-5-full", negative = "")
        val aligned = client.buildPayload("x", "1000x700").obj("parameters")
        assertEquals(960, aligned.int("width"))
        assertEquals(640, aligned.int("height"))
        val fallback = client.buildPayload("x", "无效尺寸").obj("parameters")
        assertEquals(832, fallback.int("width"))
        assertEquals(1216, fallback.int("height"))
    }

    @Test
    fun zipResponseYieldsFirstImageBytes() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        var capturedUrl = ""
        var capturedAuth: String? = null
        var capturedBody = ""
        val http = fakeClient { request ->
            capturedUrl = request.url.toString()
            capturedAuth = request.header("Authorization")
            capturedBody = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            zipOf("metadata.json" to "{}".toByteArray(), "image_0.png" to png) to "application/x-zip-compressed"
        }
        val client = NovelAiImageClient(
            baseUrl = "https://image.novelai.net",
            apiKey = "pst-token",
            model = "nai-diffusion-4-5-full",
            defaultSize = "832x1216",
            negativePrompt = "",
            httpClient = http
        )

        val result = client.generateImages("月下古桥").single()

        assertEquals("https://image.novelai.net/ai/generate-image", capturedUrl)
        assertEquals("Bearer pst-token", capturedAuth)
        assertTrue(capturedBody.contains("\"action\":\"generate\""))
        assertArrayEquals(png, result.bytes)
        assertEquals("image/png", result.mediaType)
        assertArrayEquals(png, client.materializeImage(result))
    }

    @Test
    fun zipWithoutImageEntryReturnsNull() {
        assertNull(NovelAiZipParser.firstImage(zipOf("readme.txt" to "hi".toByteArray())))
    }

    @Test
    fun customSamplerStepsAndScaleReachPayload() {
        val client = NovelAiImageClient(
            baseUrl = "https://image.novelai.net",
            apiKey = "k",
            model = "nai-diffusion-4-5-full",
            defaultSize = "832x1216",
            negativePrompt = "",
            sampler = "k_dpmpp_2m",
            steps = 35,
            scale = 6.5f,
            httpClient = OkHttpClient()
        )
        val parameters = client.buildPayload("庭院", "832x1216").obj("parameters")
        assertEquals("k_dpmpp_2m", parameters.string("sampler"))
        assertEquals(35, parameters.int("steps"))
        assertEquals(6.5f, parameters.string("scale").toFloat(), 0.001f)
    }

    @Test
    fun blankSamplerFallsBackToDefaultAndStepsClamp() {
        val client = client(model = "nai-diffusion-3", negative = "")
        val parameters = client.buildPayload("x", "832x1216").obj("parameters")
        assertEquals("k_euler_ancestral", parameters.string("sampler"))
        assertEquals(28, parameters.int("steps"))
    }

    private fun client(model: String, negative: String) = NovelAiImageClient(
        baseUrl = "https://image.novelai.net",
        apiKey = "k",
        model = model,
        defaultSize = "832x1216",
        negativePrompt = negative,
        httpClient = OkHttpClient()
    )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.content.toInt()

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
