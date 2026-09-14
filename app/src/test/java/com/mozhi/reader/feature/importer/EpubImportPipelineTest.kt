package com.mozhi.reader.feature.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.readium.ReadiumServices
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.readerPalette
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises the real import/storage/render pipeline; only the database repository is mocked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubImportPipelineTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun archiveBackedImportsRenderRasterAndSvgWithoutDuplicatingImagesOrFullBookDom() = runBlocking {
        val files = temporary.newFolder("archive-files")
        val caches = temporary.newFolder("archive-cache")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = caches
        }
        val cyan = 0xFF1AAEBE.toInt()
        val bitmap = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(cyan) }
        val png = ByteArrayOutputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); bitmap.recycle(); stream.toByteArray() }
        val source = temporary.newFile("archive-fixture.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            fun put(name: String, content: ByteArray) {
                val entry = ZipEntry(name)
                if (name == "mimetype") {
                    entry.method = ZipEntry.STORED; entry.size = content.size.toLong(); entry.compressedSize = entry.size
                    entry.crc = CRC32().apply { update(content) }.value
                }
                zip.putNextEntry(entry); zip.write(content); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            put("OPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">archive-regression</dc:identifier><dc:title>归档插图</dc:title><dc:language>zh-CN</dc:language><dc:creator>作者</dc:creator><meta property="dcterms:modified">2026-09-13T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/><item id="css" href="book.css" media-type="text/css"/><item id="png" href="art.png" media-type="image/png"/><item id="svg" href="art.svg" media-type="image/svg+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""".toByteArray())
            put("OPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="one.xhtml">第一章</a></li><li><a href="two.xhtml">第二章</a></li></ol></nav></body></html>""".toByteArray())
            put("OPS/book.css", "p { line-height: 1.6; } h1 { font-size: 1.3em; }".toByteArray())
            put("OPS/art.png", png)
            put("OPS/art.svg", """<svg xmlns="http://www.w3.org/2000/svg" width="120" height="60" viewBox="0 0 120 60"><rect width="120" height="60" fill="#1aaebe"/></svg>""".toByteArray())
            listOf("one" to "png", "two" to "svg").forEach { (name, extension) ->
                put("OPS/$name.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>章节</title><link rel="stylesheet" href="book.css"/></head><body><h1>章节</h1><p>这里是图片前的正文。🙂</p><img src="art.$extension" alt="青色插图"/><p>图片后的文字。</p></body></html>""".toByteArray())
            }
        }
        val layouts = BookLayoutStore(context)
        val media = BookMediaStore(context)
        val text = slot<List<ChapterTextInput>>()
        val library = mockk<LibraryRepository>(relaxed = true)
        coEvery { library.insertBook(any(), any(), any()) } returns 1L
        coEvery { library.materializeBookText(1, capture(text), false) } just Runs
        val importer = ImportCoordinator(context, mockk(), mockk(), mockk(), EpubTextExtractor(), EpubLayoutDocumentParser(),
            EpubPackageInspector(), layouts, media, mockk(), ReadiumServices(context), library)
        assertEquals(1L, importer.importDirectly(Uri.fromFile(source)))
        assertEquals(2, text.captured.size)
        assertTrue(layouts.hasCurrentLayout(1, text.captured.map { it.body.length }))
        assertTrue(File(files, "book-layout/1/chapters").exists().not())
        assertTrue(caches.walkTopDown().none { it.extension == "gz" })
        val images = media.read(1)
        assertEquals(2, images.size)
        assertTrue(images.all { com.mozhi.reader.core.library.EpubArchiveAsset.parse(it.imagePath) != null })
        assertTrue(File(files, "book-media/1").listFiles()!!.none { it.extension in listOf("png", "svg") })
        val style = ReaderPageStyle.resolve(ReaderSettings(theme = ReaderTheme.PAPER), readerPalette(ReaderTheme.PAPER, false, Color(0xFF728878)),
            Density(1.5f), 720, 1280, 36f, 36f)
        val renderer = PageBitmapRenderer(style)
        try {
            text.captured.forEach { chapter ->
                val layout = layouts.readChapter(1, chapter.index, chapter.body)!!
                val inline = images.filter { it.chapterIndex == chapter.index }.map { InlineImageSource(it.charOffset, it.imagePath, it.pixelWidth, it.pixelHeight, it.altText) }
                val pages = ChapterTypesetter(style.spec, style.measure).typeset(chapter.index, "章节", chapter.body, inlineImages = inline, epubLayout = layout)
                val rendered = renderer.render(RenderPage.Laid(chapter.index, "章节", 0, pages.pageCount, pages.pages.first()), null, 0f, "12:00", 100)
                try {
                    val pixels = IntArray(rendered.width * rendered.height)
                    rendered.getPixels(pixels, 0, rendered.width, 0, 0, rendered.width, rendered.height)
                    assertTrue("EPUB 内的 ${if (chapter.index == 0) "PNG" else "SVG"} 应真实绘制", pixels.count { it == cyan } > 100)
                } finally { rendered.recycle() }
            }
        } finally { renderer.release() }
    }

    @Test
    fun localBooksImportWithCompleteLayoutsAndAnchoredImages() = runBlocking {
        val paths = System.getenv("MOREAD_EPUB_FIXTURES") ?: System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        assumeTrue("Set MOREAD_EPUB_FIXTURE to run the import regression", paths.isNotBlank())
        val files = temporary.newFolder("files")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir(): File = files
        }
        val layouts = BookLayoutStore(context)
        val media = BookMediaStore(context)
        paths.split(File.pathSeparator).filter(String::isNotBlank).forEachIndexed { index, path ->
            val source = File(path)
            assertTrue("Configured EPUB fixture does not exist", source.isFile)
            val bookId = index + 1L
            val text = slot<List<ChapterTextInput>>()
            val library = mockk<LibraryRepository>(relaxed = true)
            coEvery { library.insertBook(any(), any(), any()) } returns bookId
            coEvery { library.materializeBookText(bookId, capture(text), false) } just Runs
            val importer = ImportCoordinator(
                context, mockk(), mockk(), mockk(), EpubTextExtractor(),
                EpubLayoutDocumentParser(), EpubPackageInspector(), layouts, media,
                mockk(), ReadiumServices(context), library
            )
            assertEquals(bookId, importer.importDirectly(Uri.fromFile(source)))
            coVerify(exactly = 1) { library.markTextReady(bookId) }
            val chapters = text.captured
            assertEquals(EpubPackageInspector().inspect(source).spine.count { it.linear }, chapters.size)
            assertTrue(layouts.hasCurrentLayout(bookId, chapters.map { it.body.length }))
            val images = media.read(bookId)
            images.forEach { image ->
                val body = chapters[image.chapterIndex].body
                assertTrue(image.charOffset in body.indices)
                assertTrue(body.substring(image.charOffset).startsWith("［图片］"))
                assertTrue(File(image.imagePath).isFile || com.mozhi.reader.core.library.EpubArchiveAsset.parse(image.imagePath)?.archive?.isFile == true)
            }
            chapters.forEach { chapter ->
                assertEquals(chapter.body.length, layouts.readChapter(bookId, chapter.index)!!.document.textLength)
            }
            println("EPUB pipeline: book=$index chapters=${chapters.size} images=${images.size}")

            // Optional native render artifacts for visual QA stay outside the repository fixtures.
            val renderDir = System.getenv("MOREAD_EPUB_RENDER_DIR")?.takeIf(String::isNotBlank)?.let(::File)
                ?: return@forEachIndexed
            renderDir.mkdirs()
            val selected = (chapters.take(9) + listOfNotNull(chapters.firstOrNull { it.body.length > 600 })).distinctBy { it.index }
            for (theme in listOf(ReaderTheme.PAPER, ReaderTheme.DARK)) {
                val style = ReaderPageStyle.resolve(
                    ReaderSettings(theme = theme), readerPalette(theme, false, Color(0xFF728878)),
                    Density(1.5f), 720, 1280, 36f, 36f
                )
                val renderer = PageBitmapRenderer(style)
                try {
                    selected.forEach { chapter ->
                        val bundle = layouts.readChapter(bookId, chapter.index)!!
                        val inlineImages = images.filter { it.chapterIndex == chapter.index }.map {
                            InlineImageSource(it.charOffset, it.imagePath, it.pixelWidth, it.pixelHeight, it.altText)
                        }
                        val laid = ChapterTypesetter(style.spec, style.measure).typeset(chapter.index, "", chapter.body,
                            inlineImages = inlineImages, epubLayout = bundle)
                        val bitmap = renderer.render(RenderPage.Laid(chapter.index, "", 0, laid.pageCount, laid.pages.first()),
                            null, 0f, "12:00", 100)
                        try {
                            File(renderDir, "book-$index-ch-${chapter.index}-${theme.name.lowercase()}.png")
                                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                } finally {
                    renderer.release()
                }
            }
        }
    }
}
