package com.mozhi.reader.core.media

import android.app.Application
import android.content.ContentResolver
import android.content.ContextWrapper
import android.net.Uri
import android.provider.MediaStore
import com.mozhi.reader.ui.components.ImageDocumentContract
import com.mozhi.reader.ui.components.ImageDocumentRequest
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalImageExporterTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun context(resolver: ContentResolver? = null) = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(temporary.root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(temporary.root, "cache").apply { mkdirs() }
        override fun getContentResolver(): ContentResolver = resolver ?: super.getContentResolver()
    }
    private fun png(context: ContextWrapper) = File(context.filesDir, "image.wrong").apply {
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", this)
    }

    @Test fun exportsDetectedMimeAndDoesNotReadOrDownloadOutsideFiles() {
        val context = context()
        val file = png(context)
        val source = LocalImageExporter.source(context, file.path)
        assertEquals("image/png", source.mimeType)
        assertEquals("png", source.extension)
        assertThrows(IllegalArgumentException::class.java) { LocalImageExporter.source(context, "https://example.invalid/image.png") }
        assertThrows(IllegalArgumentException::class.java) { LocalImageExporter.source(context, File(temporary.root, "outside.png").path) }
        val intent = ImageDocumentContract().createIntent(context, ImageDocumentRequest("MoRead.jpg", "image/jpeg"))
        assertEquals("image/jpeg", intent.type)
        assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, intent.action)
    }

    @Test fun failedGalleryWriteRemovesPendingRowButNeverDeletesOriginal() = runBlocking {
        val resolver = mockk<ContentResolver>()
        val context = context(resolver)
        val file = png(context)
        val uri = Uri.parse("content://media/external/images/media/123")
        every { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, any()) } returns uri
        every { resolver.openOutputStream(uri, "w") } throws java.io.IOException("write failed")
        every { resolver.delete(uri, null, null) } returns 1
        val failure = runCatching { LocalImageExporter.saveToGallery(context, file.path) }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        verify(exactly = 1) { resolver.delete(uri, null, null) }
        assertTrue(file.exists())
    }
}
