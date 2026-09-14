package com.mozhi.reader.core.library

import android.app.Application
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
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
class BookMediaArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy { object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(temporary.root, "files").apply { mkdirs() }
    } }
    private val entry = "OPS/Images/插图 !+%.png"
    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray() }
    }
    private fun archive(bytes: ByteArray) = File(context.filesDir, "books/book.epub").apply {
        parentFile.mkdirs()
        ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry(entry)); it.write(bytes); it.closeEntry() }
    }

    @Test fun inlineImagesReferenceTheZipWithoutCopyingTheImage() = runTest {
        val bytes = png(Color.CYAN)
        val epub = archive(bytes)
        val store = BookMediaStore(context)
        store.replace(1, listOf(BookImageInput(0, 3, entry, "插图", archivePath = entry)), epub)
        val image = store.read(1).single()
        val asset = EpubArchiveAsset.parse(image.imagePath)!!
        assertEquals(entry, asset.entry)
        assertEquals(epub.canonicalFile, asset.archive.canonicalFile)
        assertEquals(80, image.pixelWidth)
        assertEquals(40, image.pixelHeight)
        assertTrue(File(context.filesDir, "book-media/1").listFiles()!!.none { it.extension == "png" })
        EpubArchivePool().use { pool ->
            assertArrayEquals(bytes, pool.read(asset))
            assertArrayEquals(bytes, pool.read(asset))
            assertNull(pool.read(asset, maxBytes = 5))
        }
    }

    @Test fun migrationDeletesOnlyByteIdenticalCopiesAndKeepsExceptionsReadable() = runTest {
        val bytes = png(Color.CYAN)
        val different = png(Color.RED)
        val epub = archive(bytes)
        val store = BookMediaStore(context)
        store.replace(1, listOf(BookImageInput(0, 0, "copy.png", "匹配", bytes), BookImageInput(0, 4, "converted.png", "转换图", different)))
        val before = store.read(1)
        assertEquals(bytes.size.toLong(), store.adoptArchive(1, epub))
        assertFalse(File(before[0].imagePath).exists())
        assertTrue(File(before[1].imagePath).isFile)
        val after = store.read(1)
        assertNotNull(EpubArchiveAsset.parse(after[0].imagePath))
        assertEquals(before[1], after[1])
        assertEquals(0L, store.adoptArchive(1, epub))
    }

    @Test fun archiveReferenceRoundTripsUnicodeAndReservedCharacters() {
        val value = EpubArchiveAsset(File(temporary.root, "书 !+%.epub"), entry)
        assertEquals(value, EpubArchiveAsset.parse(value.encode()))
        assertNull(EpubArchiveAsset.parse("/a/local/image.png"))
    }
}
