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
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Debug-only geometry dump for one chapter of a real book. Not an assertion test. */
class EpubV2ChapterDumpTest {
    @Test
    fun `dump chapter geometry`() {
        val fixturePath = System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        val target = System.getenv("MOREAD_DUMP_HREF").orEmpty()
        assumeTrue(fixturePath.isNotBlank() && target.isNotBlank())
        val epubFile = File(fixturePath)
        assumeTrue(epubFile.isFile)

        val inspector = EpubPackageInspector()
        val layoutPackage = inspector.inspect(epubFile)
        val stylesheets = inspector.readStylesheets(epubFile, layoutPackage)
        val parser = EpubLayoutDocumentParser()
        ZipFile(epubFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }
                .associateBy { it.name.replace('\\', '/').lowercase() }
            val spineItem = layoutPackage.spine.first { it.href?.contains(target, true) == true }
            val href = requireNotNull(spineItem.href)
            val resource = layoutPackage.resources.first { it.href.equals(href, true) }
            val bytes = zip.getInputStream(entries.getValue(resource.archivePath.lowercase())).use { it.readBytes() }
            val parsed = parser.parseWithText(bytes, spineItem.index, href, stylesheets)
            println("== text ==")
            println(parsed.text.replace('\n', '|'))
            println("immersive=${parsed.document.immersivePage} links=${parsed.document.stylesheetHrefs}")
            val bundle = EpubLayoutChapterBundle(
                document = parsed.document,
                resourcePaths = layoutPackage.resources.associate { it.href to "mem://${it.href}" },
                fontPaths = emptyMap(),
                dom = parsed.dom,
                stylesheets = layoutPackage.stylesheets.ifEmpty {
                    stylesheets.map { (sheetHref, css) -> EpubStylesheetText(sheetHref, css) }
                }
            )
            println("package stylesheets=${layoutPackage.stylesheets.map { it.href }}")
            val spec = TypesetSpec(
                visibleWidth = 900f,
                visibleHeight = 1800f,
                contentLineStep = 66f,
                titleLineStep = 84f,
                paragraphSpacing = 18f,
                blankLineSpacing = 18f,
                titleTopSpacing = 20f,
                titleBottomSpacing = 20f,
                contentFontSizePx = 42f,
                titleFontSizePx = 56f,
                publisherStyleMode = PublisherStyleMode.SMART,
                immersiveExtraTopPx = 90f,
                immersiveExtraBottomPx = 60f
            )
            val chapter = ChapterTypesetter(spec, FakeMeasure()).typeset(
                chapterIndex = spineItem.index,
                title = "",
                body = parsed.text,
                inlineImages = parsed.images.map { image ->
                    InlineImageSource(image.charOffset, "mem://${image.href}", 1000, 1414, image.altText)
                },
                epubLayout = bundle
            )
            chapter.pages.forEach { page ->
                println("page ${page.index} h=${page.height} bg=${page.backgroundColorArgb} img=${page.backgroundImagePath} imm=${page.immersive}")
                page.decorations.forEach { dec ->
                    println(
                        "  DEC l=${dec.left} t=${dec.top} r=${dec.right} b=${dec.bottom} " +
                            "bg=${dec.backgroundColorArgb} img=${dec.backgroundImagePath} bw=${listOf(dec.borderTopWidth, dec.borderRightWidth, dec.borderBottomWidth, dec.borderLeftWidth)}"
                    )
                }
                page.lines.forEach { line ->
                    println(
                        "  line pos=${line.chapterPosition} len=${line.charLength} top=${line.lineTop} bot=${line.lineBottom} x=${line.startX} " +
                            "img=${line.inlineImage} glyphs=${line.inlineGlyphImages.map { listOf(it.left, it.topOffset, it.width, it.height) }} text='${line.text.take(18)}'"
                    )
                }
            }
        }
    }
}
