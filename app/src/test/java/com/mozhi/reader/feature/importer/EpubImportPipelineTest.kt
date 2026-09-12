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
                context, mockk(), mockk(), mockk(), mockk(), EpubTextExtractor(),
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
                assertTrue(File(image.imagePath).isFile)
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
