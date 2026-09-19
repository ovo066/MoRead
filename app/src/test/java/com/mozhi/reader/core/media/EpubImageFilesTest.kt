package com.mozhi.reader.core.media

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.mozhi.reader.core.library.EpubArchiveAsset
import com.mozhi.reader.core.library.EpubArchivePool
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
class EpubImageFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun image(): File = temporary.newFile("source.png").also { file ->
        val bitmap = Bitmap.createBitmap(12, 6, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        for (y in 0 until 6) for (x in 0 until 6) bitmap.setPixel(x, y, Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun unicodeArchiveImageIsMaterializedAndUneditedExportPreservesBytes() = runBlocking {
        val original = image().readBytes()
        val archive = temporary.newFile("带图片的书.epub")
        val entry = "OEBPS/Images/星 星.png"
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entry)); zip.write(original); zip.closeEntry()
        }
        val source = EpubImageFiles.materialize(context, EpubArchiveAsset(archive, entry).encode())
        assertTrue(source.canonicalPath.startsWith(context.cacheDir.canonicalPath))
        assertArrayEquals(original, EpubImageFiles.export(source, 0).readBytes())
        assertArrayEquals(original, EpubImageFiles.export(source, 4).readBytes())
        val preview = EpubImageFiles.preview(source)
        assertEquals(12, preview.width); assertEquals(6, preview.height); preview.recycle()
        assertEquals("image/png", LocalImageExporter.source(context, source.path).mimeType)
    }
    @Test fun clockwiseExportRotatesPixelsWithoutChangingSource() = runBlocking {
        val input = image()
        val bytes = input.readBytes()
        val source = EpubImageFiles.materialize(context, input.path)
        val rotated = BitmapFactory.decodeFile(EpubImageFiles.export(source, 1).path)
        assertEquals(6, rotated.width); assertEquals(12, rotated.height)
        assertEquals(Color.RED, rotated.getPixel(3, 2))
        assertEquals(Color.BLUE, rotated.getPixel(3, 9))
        rotated.recycle()
        assertArrayEquals(bytes, source.readBytes()); assertArrayEquals(bytes, input.readBytes())
    }
    @Test fun svgGetsBoundedPreviewAndPngExport() = runBlocking {
        val input = temporary.newFile("art.svg").apply {
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="16000" height="8000" viewBox="0 0 16 8"><rect width="16" height="8" fill="red"/></svg>""")
        }
        val source = EpubImageFiles.materialize(context, input.path)
        val preview = EpubImageFiles.preview(source)
        assertTrue(preview.width.toLong() * preview.height <= 4_000_000)
        assertEquals(2f, preview.width.toFloat() / preview.height, .002f)
        assertEquals(Color.RED, preview.getPixel(10, 10)); preview.recycle()
        val output = EpubImageFiles.export(source, 0)
        assertEquals("png", output.extension)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.path, bounds)
        assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000)
    }
    @Test fun rejectsMissingCorruptAndOversizedSources() = runBlocking {
        assertTrue(runCatching { EpubImageFiles.materialize(context, File(temporary.root, "missing").path) }.isFailure)
        val invalid = temporary.newFile("bad.png").apply { writeText("not an image") }
        assertTrue(runCatching { EpubImageFiles.preview(EpubImageFiles.materialize(context, invalid.path)) }.isFailure)
        val huge = temporary.newFile("huge.png")
        RandomAccessFile(huge, "rw").use { it.setLength(EpubArchivePool.MAX_IMAGE_BYTES.toLong() + 1) }
        assertTrue(runCatching { EpubImageFiles.materialize(context, huge.path) }.isFailure)
    }
}
