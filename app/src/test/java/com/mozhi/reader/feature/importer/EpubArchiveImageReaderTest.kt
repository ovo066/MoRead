package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubLayoutPackage
import com.mozhi.reader.core.library.EpubLayoutResource
import com.mozhi.reader.core.library.EpubLayoutResourceKind
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubArchiveImageReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun unusedFallbackNeverOpensTheArchive() {
        var opens = 0
        EpubArchiveImageReader(archive("image.png" to byteArrayOf(1)), null) {
            opens++
            ZipFile(it)
        }.use { }
        assertEquals(0, opens)
    }

    @Test
    fun manifestAliasesAndEncodedCaseInsensitivePathsShareOneArchive() {
        val cover = byteArrayOf(1, 2, 3)
        val figure = byteArrayOf(4, 5)
        val file = archive("OPS/Images/Cover One.PNG" to cover, "OPS/Images/Figure.png" to figure)
        val layout = EpubLayoutPackage(
            packageDocumentPath = "OPS/content.opf",
            resources = listOf(EpubLayoutResource(
                id = "cover", href = "OPS/Images/Cover%20One.PNG",
                archivePath = "OPS/Images/Cover One.PNG", mediaType = "image/png",
                kind = EpubLayoutResourceKind.IMAGE, sizeBytes = cover.size.toLong()
            ))
        )
        var opens = 0
        EpubArchiveImageReader(file, layout) { opens++; ZipFile(it) }.use { reader ->
            assertArrayEquals(cover, reader.read("images/cover%20one.png#artwork", 100))
            assertArrayEquals(cover, reader.read("OPS/IMAGES/COVER ONE.PNG?size=large", 100))
            assertArrayEquals(figure, reader.read("/OPS/Images/Figure.png", 100))
            assertNull(reader.read("OPS/Images/missing.png", 100))
            assertEquals(1, opens)
        }
    }

    @Test
    fun byteLimitRejectsOversizedImagesIncludingHighlyCompressedEntries() {
        val bytes = ByteArray(128 * 1024) { 42 }
        EpubArchiveImageReader(archive("image.png" to bytes), null).use { reader ->
            assertNull(reader.read("image.png", bytes.size - 1))
            assertNull(reader.read("image.png", 0))
            assertNull(reader.read("image.png", -1))
            assertArrayEquals(bytes, reader.read("image.png", bytes.size))
        }
    }

    @Test
    fun unsafeOrExternalPathsDoNotOpenTheArchive() {
        var opens = 0
        EpubArchiveImageReader(archive("image.png" to byteArrayOf(1)), null) {
            opens++; ZipFile(it)
        }.use { reader ->
            listOf("", "../image.png", "%2e%2e/image.png", "https://example.org/image.png",
                "file:///image.png", "//server/image.png", "image%00.png").forEach { href ->
                assertNull(href, reader.read(href, 100))
            }
            assertEquals(0, opens)
        }
    }

    @Test
    fun duplicateCaseInsensitiveEntriesPreserveFirstMatchSemantics() {
        EpubArchiveImageReader(archive(
            "OPS/Cover.png" to byteArrayOf(1), "ops/cover.PNG" to byteArrayOf(2)
        ), null).use { reader ->
            assertArrayEquals(byteArrayOf(1), reader.read("ops/cover.png", 100))
        }
    }

    @Test
    fun lookupDoesNotDependOnTurkishDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            EpubArchiveImageReader(archive("OPS/IMAGE.PNG" to byteArrayOf(1)), null).use { reader ->
                assertArrayEquals(byteArrayOf(1), reader.read("ops/image.png", 100))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun closeReleasesTheZipAndCannotReopenIt() {
        var opens = 0
        lateinit var opened: ZipFile
        val reader = EpubArchiveImageReader(archive("image.png" to byteArrayOf(1)), null) {
            opens++
            ZipFile(it).also { zip -> opened = zip }
        }
        assertNotNull(reader.read("image.png", 100))
        reader.close()
        assertNull(reader.read("image.png", 100))
        reader.close()
        assertEquals(1, opens)
        assertThrows(IllegalStateException::class.java) { opened.getEntry("image.png") }
    }

    @Test
    fun missingAndMalformedArchivesFailWithoutCrashingImport() {
        EpubArchiveImageReader(null, null).use { assertNull(it.read("image.png", 100)) }
        EpubArchiveImageReader(File(temporary.root, "missing.epub"), null).use {
            assertNull(it.read("image.png", 100))
        }
        val broken = temporary.newFile("broken.epub").apply { writeText("not a ZIP") }
        EpubArchiveImageReader(broken, null).use { assertNull(it.read("image.png", 100)) }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): File =
        temporary.newFile("book-${System.nanoTime()}.epub").apply {
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
}
