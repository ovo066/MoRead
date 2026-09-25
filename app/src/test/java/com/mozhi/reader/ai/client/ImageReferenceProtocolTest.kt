package com.mozhi.reader.ai.client

import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.entity.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ImageReferenceProtocolTest {
    private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1)
    private fun http(reply: (Request) -> Pair<ByteArray, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (bytes, type) = reply(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody(type.toMediaType())).build()
    }.build()
    private fun novel(model: String = "nai-diffusion-4-5-full", http: OkHttpClient = OkHttpClient()) = NovelAiImageClient(
        "https://image.novelai.net", "fake", model, "832x1216", negativePrompt = "bad quality", httpClient = http)
    private fun open(modelName: String, path: String, http: OkHttpClient, extra: String = "{}") = OpenAiMediaClient(
        AiProviderEntity(name = "test", baseUrl = "https://example.test/v1", apiKeyAlias = "fake", type = AiProviderType.IMAGE, createdAt = 0),
        AiModelEntity(providerId = 0, modelName = modelName, type = AiModelType.IMAGE, endpointPath = path, extraJson = extra, createdAt = 0), "fake", http)
    private fun ref(kind: ReferenceKind = ReferenceKind.CHARACTER, encoded: Boolean = false) =
        ImageReferenceInput(ReferenceSpec("ref", kind, "shen", .6f, .8f, .9f), image, encodedVibe = encoded)

    @Test fun novelAiUsesOfficialPreciseFieldsCharacterSlotsAndSeed() {
        val payload = novel().buildPayload(ImageRequest("snow", listOf("black_hair", "red_scarf"), "text", listOf(ref()), 42), "832x1216")
        val parameters = payload["parameters"]!!.jsonObject
        val chars = parameters["v4_prompt"]!!.jsonObject["caption"]!!.jsonObject["char_captions"]!!.jsonArray
        assertEquals(listOf("black_hair", "red_scarf"), chars.map { it.jsonObject["char_caption"]!!.jsonPrimitive.content })
        assertEquals(42, parameters["seed"]!!.jsonPrimitive.int)
        assertEquals("character", parameters["director_reference_descriptions"]!!.jsonArray[0].jsonObject["caption"]!!.jsonObject["base_caption"]!!.jsonPrimitive.content)
        assertEquals(.9f, parameters["director_reference_secondary_strength_values"]!!.jsonArray[0].jsonPrimitive.float, .001f)
        assertFalse(parameters.containsKey("reference_image_multiple"))
    }
    @Test fun vibePayloadUsesCachedBinaryAndV5DoesNotClaimPreciseReference() {
        val params = novel().buildPayload(ImageRequest("snow", references = listOf(ref(ReferenceKind.STYLE, true))), "832x1216")["parameters"]!!.jsonObject
        assertTrue(params.containsKey("reference_image_multiple"))
        assertFalse(params.containsKey("director_reference_images"))
        val v5 = novel("nai-diffusion-5-full")
        assertEquals(0, v5.capabilities.maxCharacterReferences)
        assertEquals(22, v5.capabilities.maxCharacters)
        assertTrue(v5.buildPayload("snow", "832x1216")["parameters"]!!.jsonObject.containsKey("v4_prompt"))
    }
    @Test fun novelAiGeneratesRequestedCandidatesWithDistinctSeeds() = runBlocking {
        val seeds = mutableListOf<Long>()
        val zip = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry("image.png")); it.write(image); it.closeEntry() } }.toByteArray()
        val client = novel(http = http { request ->
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            seeds += Json.parseToJsonElement(body).jsonObject["parameters"]!!.jsonObject["seed"]!!.jsonPrimitive.long
            zip to "application/zip"
        })
        assertEquals(4, client.generateImages(ImageRequest("snow", count = 4, seed = 10)).size)
        assertEquals(listOf(10L, 11L, 12L, 13L), seeds)
    }
    @Test fun openAiEditsUsesMultipartImagesAndOmitsUnsupportedFidelity() = runBlocking {
        for (model in listOf("gpt-image-1", "gpt-image-2")) {
            var body = ""
            val client = open(model, "/images/generations", http { request ->
                assertEquals("/v1/images/edits", request.url.encodedPath)
                assertTrue(request.body is MultipartBody)
                body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                """{"data":[{"b64_json":"AQID"}]}""".toByteArray() to "application/json"
            }, """{"body":{"size":"1024x1024","input_fidelity":"low","response_format":"url"}}""")
            client.generateImages(ImageRequest("exact prompt", references = listOf(ref()), size = "1024x1536"))
            assertTrue(body.contains("name=\"image[]\""))
            assertTrue(body.contains("1024x1536"))
            assertFalse(body.contains("name=\"response_format\""))
            assertEquals(model == "gpt-image-1", body.contains("name=\"input_fidelity\""))
        }
    }
    @Test fun chatImagesCarryLabeledMultimodalReferencesAndHonorCandidateCount() = runBlocking {
        var requests = 0
        val client = open("gemini-3-pro-image-preview", "/chat/completions", http { request ->
            requests++
            val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
            val parts = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
            assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.startsWith("data:image/png;base64,"))
            """{"choices":[{"message":{"content":"![image](data:image/png;base64,AQID)"}}]}""".toByteArray() to "application/json"
        })
        assertEquals(2, client.generateImages(ImageRequest("reference 1 is Shen", references = listOf(ref()), count = 2)).size)
        assertEquals(2, requests)
    }
}
