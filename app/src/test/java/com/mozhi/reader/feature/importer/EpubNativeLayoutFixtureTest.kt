package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.BookTextWriter
import com.mozhi.reader.core.library.EpubTextAlign
import java.io.File
import java.util.zip.ZipFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EpubNativeLayoutFixtureTest {
    @Test
    fun `configured epub fixture keeps native layout for every spine item`() {
        val fixturePath = System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        assumeTrue("Set MOREAD_EPUB_FIXTURE to run the real-book regression", fixturePath.isNotBlank())
        val epubFile = File(fixturePath)
        assumeTrue("Configured EPUB fixture does not exist", epubFile.isFile)

        val inspector = EpubPackageInspector()
        val layoutPackage = inspector.inspect(epubFile)
        val stylesheets = inspector.readStylesheets(epubFile, layoutPackage)
        val extractor = EpubTextExtractor()
        val parser = EpubLayoutDocumentParser()
        val failures = ArrayList<String>()
        var styledBlocks = 0
        var decoratedBlocks = 0
        var customFontSpans = 0
        var centeredBlocks = 0
        var normalizedTextMismatches = 0
        val json = Json { encodeDefaults = true }

        ZipFile(epubFile).use { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .associateBy { it.name.replace('\\', '/').lowercase() }
            layoutPackage.spine.filter { it.linear && it.href != null }.forEach { spineItem ->
                val href = requireNotNull(spineItem.href)
                val resource = layoutPackage.resources.firstOrNull { it.href.equals(href, true) }
                val entry = resource?.let { entries[it.archivePath.lowercase()] }
                if (entry == null) {
                    failures += "$href: missing archive entry"
                    return@forEach
                }
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val text = extractor.extractWithImages(bytes, href).text
                if (BookTextWriter().normalize(text) != text) normalizedTextMismatches++
                val chapter = runCatching {
                    parser.parse(bytes, spineItem.index, href, text, stylesheets)
                }.getOrElse { error ->
                    failures += "$href: ${error.message}"
                    return@forEach
                }
                runCatching { json.encodeToString(chapter) }.onFailure { error ->
                    failures += "$href: layout serialization failed: ${error.message}"
                    return@forEach
                }
                styledBlocks += chapter.blocks.count { block ->
                    block.kind != EpubLayoutBlockKind.CONTAINER && block.style != chapter.bodyStyle
                }
                decoratedBlocks += chapter.blocks.count { block ->
                    block.style.backgroundColorArgb != null || block.style.backgroundImageHref != null ||
                        block.style.borderWidthEm != null || block.style.borderLeftWidthEm != null ||
                        block.style.boxShadows.isNotEmpty()
                }
                centeredBlocks += chapter.blocks.count { block ->
                    block.style.textAlign == EpubTextAlign.CENTER
                }
                customFontSpans += chapter.blocks.sumOf { block ->
                    block.spans.count { span -> !span.style.fontFamily.isNullOrBlank() }
                }
            }
        }

        println(
            "native-layout fixture: spine=${layoutPackage.spine.size}, " +
                "fonts=${layoutPackage.fontFaces.size}, styledBlocks=$styledBlocks, " +
                "decoratedBlocks=$decoratedBlocks, centeredBlocks=$centeredBlocks, " +
                "customFontSpans=$customFontSpans, normalizedTextMismatches=$normalizedTextMismatches, " +
                "failures=${failures.size}"
        )
        failures.take(20).forEach(::println)

        assertTrue("No embedded font faces were discovered", layoutPackage.fontFaces.isNotEmpty())
        assertTrue("No styled blocks were produced", styledBlocks > 0)
        assertTrue("No decorated blocks were produced", decoratedBlocks > 0)
        assertTrue("No centered blocks were produced", centeredBlocks > 0)
        assertTrue("No custom-font spans were produced", customFontSpans > 0)
        assertEquals("Text writer changed EPUB layout coordinates", 0, normalizedTextMismatches)
        assertEquals("Some spine documents fell back to plain text", emptyList<String>(), failures)
    }
}
