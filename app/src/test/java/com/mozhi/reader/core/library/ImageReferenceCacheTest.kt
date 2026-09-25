package com.mozhi.reader.core.library

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.datastore.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageReferenceCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = temporary.root
    }
    private fun picture(): File {
        val file = File(temporary.root, "reference.png")
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
    @Test fun vibeIsChargedOncePerImageModelAndExtractionAndSurvivesRepositoryRecreation() = runBlocking {
        val source = picture()
        val settings = mockk<ReaderSettingsRepository> {
            every { settings } returns flowOf(ReaderSettings(imageLibrary = listOf(ReaderImageAsset("ref", "reference", source.path))))
        }
        var encodes = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/ai/encode-vibe", chain.request().url.encodedPath)
            val payload = Json.parseToJsonElement(Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()).jsonObject
            assertTrue(payload.containsKey("information_extracted"))
            encodes++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                .body(byteArrayOf(1, 2, 3).toResponseBody("application/binary".toMediaType())).build()
        }.build()
        val client = NovelAiImageClient("https://image.novelai.net", "test", "nai-diffusion-4-5-full", "832x1216", negativePrompt = "", httpClient = http)
        fun repository() = ImageConsistencyRepository(mockk(), mockk(), mockk(), settings, mockk(), context)
        val recipe = ImageRecipe(backend = "nai-diffusion-4-5-full", shot = ShotSpec(action = "snow"), references = listOf(ReferenceSpec("ref", ReferenceKind.STYLE)))
        assertTrue(repository().request(recipe, client).references.single().encodedVibe)
        repository().request(recipe, client)
        assertEquals(1, encodes)
        repository().request(recipe.copy(references = listOf(recipe.references.single().copy(informationExtracted = .5f))), client)
        assertEquals(2, encodes)
        repository().request(recipe.copy(backend = "nai-diffusion-5-full"), client)
        assertEquals(3, encodes)
    }
    @Test fun preciseReferenceUsesOfficialCanvasAndBlackPadding() = runBlocking {
        val source = picture()
        val settings = mockk<ReaderSettingsRepository> {
            every { settings } returns flowOf(ReaderSettings(imageLibrary = listOf(ReaderImageAsset("ref", "reference", source.path))))
        }
        val repository = ImageConsistencyRepository(mockk(), mockk(), mockk(), settings, mockk(), context)
        val client = NovelAiImageClient("https://image.novelai.net", "test", "nai-diffusion-4-5-full", "832x1216", negativePrompt = "", httpClient = OkHttpClient())
        val request = repository.request(ImageRecipe(references = listOf(ReferenceSpec("ref", ReferenceKind.CHARACTER))), client)
        val bytes = request.references.single().bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1024, bitmap.width)
        assertEquals(1536, bitmap.height)
        assertEquals(android.graphics.Color.BLACK, bitmap.getPixel(0, 0))
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(512, 768))
        bitmap.recycle()
    }

    @Test fun uploadedPhotoOrientationIsAppliedBeforeChoosingReferenceCanvas() = runBlocking {
        val source = File(temporary.root, "rotated.jpg")
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        android.media.ExifInterface(source.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val settings = mockk<ReaderSettingsRepository> {
            every { settings } returns flowOf(ReaderSettings(imageLibrary = listOf(ReaderImageAsset("ref", "reference", source.path))))
        }
        val repository = ImageConsistencyRepository(mockk(), mockk(), mockk(), settings, mockk(), context)
        val client = NovelAiImageClient("https://image.novelai.net", "test", "nai-diffusion-4-5-full", "832x1216", negativePrompt = "", httpClient = OkHttpClient())
        val bytes = repository.request(ImageRecipe(references = listOf(ReferenceSpec("ref", ReferenceKind.CHARACTER))), client).references.single().bytes
        val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(1536, result.width)
        assertEquals(1024, result.height)
        result.recycle()
    }
}
