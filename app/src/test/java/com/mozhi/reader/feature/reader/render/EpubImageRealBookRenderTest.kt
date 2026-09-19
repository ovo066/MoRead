package com.mozhi.reader.feature.reader.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.*
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.importer.EpubPackageInspector
import com.mozhi.reader.feature.reader.engine.*
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in local visual QA. The EPUB, fonts and images are never copied into source fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubImageRealBookRenderTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun renderSelectedChaptersWithRealImageDimensionsAndFonts() {
        val path = System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        val targets = System.getenv("MOREAD_IMAGE_QA_HREFS").orEmpty().split(',').filter { it.isNotBlank() }
        assumeTrue(path.isNotBlank() && targets.isNotEmpty() && File(path).isFile)
        val book = File(path)
        val inspector = EpubPackageInspector()
        val pkg = inspector.inspect(book)
        val sheets = inspector.readStylesheets(book, pkg)
        ZipFile(book).use { zip ->
            val resources = pkg.resources.associateBy { it.href }
            val paths = resources.mapValues { EpubArchiveAsset(book, it.value.archivePath).encode() }
            val faces = pkg.fontFaces.mapIndexedNotNull { index, face ->
                val resource = resources[face.resourceHref] ?: return@mapIndexedNotNull null
                val entry = zip.getEntry(resource.archivePath) ?: return@mapIndexedNotNull null
                val font = temporary.newFile("font-$index.ttf")
                zip.getInputStream(entry).use { input -> font.outputStream().use { input.copyTo(it) } }
                EpubResolvedFontFace(face.family, font.path, face.weight, face.italic)
            }
            val fonts = faces.groupBy { it.family.lowercase() }.mapValues { it.value.first().filePath }
            for (target in targets) {
                val spine = pkg.spine.first { it.href?.endsWith(target, true) == true }
                val href = requireNotNull(spine.href)
                val bytes = zip.getInputStream(zip.getEntry(resources.getValue(href).archivePath)).use { it.readBytes() }
                val parsed = EpubLayoutDocumentParser().parseWithText(bytes, spine.index, href, sheets)
                val images = parsed.images.map { image ->
                    val resource = resources.getValue(image.href)
                    val data = zip.getInputStream(zip.getEntry(resource.archivePath)).use { it.readBytes() }
                    val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, size)
                    InlineImageSource(image.charOffset, paths.getValue(image.href), size.outWidth, size.outHeight, image.altText)
                }
                val bundle = EpubLayoutChapterBundle(parsed.document, paths, fonts, faces, parsed.dom, pkg.stylesheets)
                for ((width, font) in listOf(411 to 26f, 320 to 28f)) {
                    val style = pageStyle(width, font)
                    val chapter = ChapterTypesetter(style.spec, style.measure).typeset(spine.index, "", parsed.text, inlineImages = images, epubLayout = bundle)
                    assertTrue(chapter.pages.isNotEmpty())
                    val widePage = chapter.pages.firstOrNull { page -> page.lines.any { line ->
                        (line.inlineImages + line.inlineGlyphImages).any { it.width / it.height > 9f }
                    } }
                    val renderer = PageBitmapRenderer(style)
                    try {
                        for (page in listOfNotNull(chapter.pages.first(), widePage).distinct()) {
                            for (line in page.lines) for (image in line.inlineImages + line.inlineGlyphImages) {
                                val original = images.first { it.imagePath == image.imagePath }
                                assertEquals(original.pixelWidth.toFloat() / original.pixelHeight, image.width / image.height, .01f)
                            }
                            val bitmap = renderer.render(RenderPage.Laid(spine.index, "", page.index, chapter.pageCount, page), null, 0f, "13:23", 100)
                            val output = File("build/reports/ui-qa/epub-real-${target.substringBeforeLast('.')}-${width}-${page.index}.png")
                            output.parentFile?.mkdirs()
                            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            bitmap.recycle()
                            println("$target width=$width page=${page.index} immersive=${page.immersive} decorations=${page.decorations.map { listOf(it.left, it.top, it.right, it.bottom) }}")
                        }
                    } finally { renderer.release() }
                }
            }
        }
    }
    private fun pageStyle(width: Int, fontSize: Float) = ReaderPageStyle(
        viewWidth = width, viewHeight = 891, paddingLeft = 24f, paddingRight = 24f,
        headerHeight = 28f, footerHeight = 24f, contentPaddingTop = 12f, contentPaddingBottom = 8f,
        immersiveContentTop = 12f, immersiveContentBottom = 867f, headerOffset = 0f, footerOffset = 0f,
        contentSizePx = fontSize, titleSizePx = fontSize * 1.2f, tipSizePx = 12f, lineStep = fontSize * 1.6f,
        backgroundColor = Color.WHITE, textColor = Color.BLACK, mutedColor = Color.GRAY,
        accentColor = Color.BLACK, isDark = false, grain = false, typeface = Typeface.DEFAULT,
        customFontPath = null, customFontPaths = emptyMap(), showHeader = true, showFooter = true,
        backgroundImagePath = null, backgroundImageOpacity = 1f, preferReaderBackground = false,
        publisherStyleMode = PublisherStyleMode.SMART, syntaxHighlightRules = emptyList(),
        titleTopSpacingLines = 0f, titleBottomSpacingLines = 0f, paragraphSpacingEm = .4f,
        firstLineIndentEm = 2f, textJustification = false, letterSpacingEm = 0f
    )
}
