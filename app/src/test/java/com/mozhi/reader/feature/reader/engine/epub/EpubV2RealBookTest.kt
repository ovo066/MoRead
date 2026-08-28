package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.importer.EpubPackageInspector
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs every spine document of a real fine-layout EPUB through the v2 box engine. Gated behind
 * MOREAD_EPUB_FIXTURE so no copyrighted content enters the repository.
 */
class EpubV2RealBookTest {
    @Test
    fun `real book lays out every chapter with monotonic coordinates`() {
        val fixturePath = System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        assumeTrue("Set MOREAD_EPUB_FIXTURE to run the real-book regression", fixturePath.isNotBlank())
        val epubFile = File(fixturePath)
        assumeTrue("Configured EPUB fixture does not exist", epubFile.isFile)

        val inspector = EpubPackageInspector()
        val layoutPackage = inspector.inspect(epubFile)
        val stylesheets = inspector.readStylesheets(epubFile, layoutPackage)
        val parser = EpubLayoutDocumentParser()
        val failures = ArrayList<String>()
        var pages = 0
        var decorated = 0
        var glyphImages = 0
        var floatsSeen = 0

        PublisherStyleMode.entries.forEach { mode ->
            ZipFile(epubFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .associateBy { it.name.replace('\\', '/').lowercase() }
                layoutPackage.spine.filter { it.linear && it.href != null }.forEach { spineItem ->
                    val href = requireNotNull(spineItem.href)
                    val resource = layoutPackage.resources.firstOrNull { it.href.equals(href, true) }
                    val entry = resource?.let { entries[it.archivePath.lowercase()] } ?: return@forEach
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val parsed = runCatching {
                        parser.parseWithText(bytes, spineItem.index, href, stylesheets)
                    }.getOrElse { error ->
                        failures += "$href: parse: ${error.message}"
                        return@forEach
                    }
                    val bundle = EpubLayoutChapterBundle(
                        document = parsed.document,
                        resourcePaths = emptyMap(),
                        fontPaths = emptyMap(),
                        dom = parsed.dom,
                        stylesheets = layoutPackage.stylesheets.ifEmpty {
                            stylesheets.map { (sheetHref, css) -> EpubStylesheetText(sheetHref, css) }
                        }
                    )
                    val chapter = runCatching {
                        ChapterTypesetter(spec(mode), FakeMeasure()).typeset(
                            chapterIndex = spineItem.index,
                            title = "",
                            body = parsed.text,
                            inlineImages = parsed.images.map { image ->
                                InlineImageSource(image.charOffset, "mem://${image.href}", 600, 800, image.altText)
                            },
                            epubLayout = bundle
                        )
                    }.getOrElse { error ->
                        failures += "$href($mode): layout: $error"
                        return@forEach
                    }
                    pages += chapter.pageCount
                    chapter.pages.forEach { page ->
                        decorated += page.decorations.size
                        page.lines.forEach { line ->
                            glyphImages += line.inlineGlyphImages.size
                            if (line.startX > 1f && line.columns.isNotEmpty()) floatsSeen++
                        }
                        val sourced = page.lines.filter { it.charLength > 0 }
                        sourced.zipWithNext().forEach { (left, right) ->
                            if (left.chapterPosition + left.charLength > right.chapterPosition) {
                                failures += "$href($mode): non-monotonic at ${right.chapterPosition}"
                            }
                        }
                    }
                }
            }
        }

        println("v2 real book: pages=$pages decorations=$decorated glyphImages=$glyphImages indentedLines=$floatsSeen")
        failures.take(20).forEach(::println)
        assertEquals(emptyList<String>(), failures.take(20))
        assertTrue("no pages laid out", pages > 0)
    }

    private fun spec(mode: PublisherStyleMode) = TypesetSpec(
        visibleWidth = 1000f,
        visibleHeight = 1900f,
        contentLineStep = 70f,
        titleLineStep = 88f,
        paragraphSpacing = 18f,
        blankLineSpacing = 18f,
        titleTopSpacing = 20f,
        titleBottomSpacing = 20f,
        contentFontSizePx = 44f,
        titleFontSizePx = 58f,
        publisherStyleMode = mode,
        immersiveExtraTopPx = 90f,
        immersiveExtraBottomPx = 60f
    )
}
