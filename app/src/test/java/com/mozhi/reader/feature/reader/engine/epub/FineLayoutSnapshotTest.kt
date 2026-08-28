package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.TextBlockDecoration
import com.mozhi.reader.feature.reader.engine.TextChapter
import com.mozhi.reader.feature.reader.engine.TextLine
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FineLayoutSnapshotTest {
    @Test
    fun `fine-layout fixtures match deterministic geometry snapshots`() {
        FineLayoutFixtures.names.forEach { name ->
            val parsed = FineLayoutFixtures.parse(name)
            val resourcePaths = RESOURCE_HREFS.associateWith { "fixture://$it" }
            val inlineImages = parsed.images.map { image ->
                val fullPage = image.href.endsWith("fullpage.jpg") || image.href.endsWith("header.png")
                InlineImageSource(
                    charOffset = image.charOffset,
                    imagePath = "fixture://${image.href}",
                    pixelWidth = if (fullPage) 800 else 64,
                    pixelHeight = if (fullPage) 450 else 64,
                    altText = image.altText
                )
            }
            val chapter = ChapterTypesetter(SPEC, FakeMeasure()).typeset(
                chapterIndex = parsed.document.chapterIndex,
                title = "",
                body = parsed.text,
                inlineImages = inlineImages,
                epubLayout = EpubLayoutChapterBundle(
                    document = parsed.document,
                    resourcePaths = resourcePaths,
                    fontPaths = emptyMap(),
                    dom = parsed.dom,
                    stylesheets = FineLayoutFixtures.stylesheets(name).map { (href, css) ->
                        EpubStylesheetText(href, css)
                    }
                )
            )
            assertMonotonic(name, chapter)
            val actual = JSON.encodeToString(chapter.toSnapshot(name))
            val file = FineLayoutFixtures.snapshotFile("$name.json")
            if (System.getenv("REGEN_SNAPSHOTS") == "1") {
                file.parentFile.mkdirs()
                file.writeText(actual + "\n")
            } else {
                val expectedTree = JSON.parseToJsonElement(file.readText())
                val actualTree = JSON.parseToJsonElement(actual)
                assertEquals("layout snapshot changed for $name", expectedTree, actualTree)
            }
        }
    }

    private fun assertMonotonic(name: String, chapter: TextChapter) {
        val sourcedLines = chapter.pages.flatMap { it.lines }.filter { it.charLength > 0 }
        sourcedLines.zipWithNext().forEach { (left, right) ->
            assertTrue(
                "$name has overlapping or decreasing text coordinates",
                left.chapterPosition + left.charLength <= right.chapterPosition
            )
        }
    }

    private fun TextChapter.toSnapshot(fixture: String) = ChapterSnapshot(
        fixture = fixture,
        bodyLength = bodyLength,
        pages = pages.map { page ->
            PageSnapshot(
                index = page.index,
                chapterPosition = page.chapterPosition,
                charLength = page.charLength,
                height = page.height,
                backgroundColorArgb = page.backgroundColorArgb,
                backgroundImagePath = page.backgroundImagePath,
                immersive = page.immersive,
                hideHeader = page.hideHeader,
                decorations = page.decorations.map { it.toSnapshot("block") },
                lines = page.lines.map { it.toSnapshot() }
            )
        }
    )

    private fun TextLine.toSnapshot() = LineSnapshot(
        chapterPosition = chapterPosition,
        charLength = charLength,
        lineTop = lineTop,
        lineBottom = lineBottom,
        startX = startX,
        image = inlineImage?.let {
            ImageSnapshot(it.imagePath, startX, lineTop, it.width, it.height)
        },
        images = inlineImages.map {
            ImageSnapshot(it.imagePath, it.left, lineTop + it.topOffset, it.width, it.height)
        },
        glyphImages = inlineGlyphImages.map {
            ImageSnapshot(it.imagePath, it.left, lineTop + it.topOffset, it.width, it.height)
        },
        decorations = inlineDecorations.map { it.toSnapshot("inline") }
    )

    private fun TextBlockDecoration.toSnapshot(kind: String) = DecorationSnapshot(
        kind = kind,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        backgroundColorArgb = backgroundColorArgb,
        backgroundImagePath = backgroundImagePath,
        backgroundSizeMode = backgroundSizeMode.name,
        backgroundSize = listOf(backgroundSizeWidth, backgroundSizeHeight),
        backgroundRepeat = listOf(backgroundRepeatX, backgroundRepeatY),
        backgroundPosition = listOf(backgroundPositionX, backgroundPositionY),
        borderWidths = listOf(borderTopWidth, borderRightWidth, borderBottomWidth, borderLeftWidth),
        borderColors = listOf(borderTopColorArgb, borderRightColorArgb, borderBottomColorArgb, borderLeftColorArgb),
        radii = listOf(borderTopLeftRadius, borderTopRightRadius, borderBottomRightRadius, borderBottomLeftRadius),
        shadowCount = boxShadows.size,
        edges = listOf(drawTopEdge, drawRightEdge, drawBottomEdge, drawLeftEdge)
    )

    private companion object {
        val SPEC = TypesetSpec(
            visibleWidth = 240f,
            visibleHeight = 320f,
            contentLineStep = 24f,
            titleLineStep = 32f,
            paragraphSpacing = 8f,
            blankLineSpacing = 8f,
            titleTopSpacing = 0f,
            titleBottomSpacing = 8f,
            indentCharCount = 0f,
            justifyContent = false,
            bottomAlign = false,
            contentFontSizePx = 20f,
            titleFontSizePx = 27f
        )
        val RESOURCE_HREFS = listOf(
            "OEBPS/Images/frame.png",
            "OEBPS/Images/ornament.png",
            "OEBPS/Images/texture.png",
            "OEBPS/Images/paper.jpg"
        )
        val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = true
        }
    }
}

@Serializable
private data class ChapterSnapshot(
    val fixture: String,
    val bodyLength: Int,
    val pages: List<PageSnapshot>
)

@Serializable
private data class PageSnapshot(
    val index: Int,
    val chapterPosition: Int,
    val charLength: Int,
    val height: Float,
    val backgroundColorArgb: Int?,
    val backgroundImagePath: String?,
    val immersive: Boolean,
    val hideHeader: Boolean,
    val decorations: List<DecorationSnapshot>,
    val lines: List<LineSnapshot>
)

@Serializable
private data class LineSnapshot(
    val chapterPosition: Int,
    val charLength: Int,
    val lineTop: Float,
    val lineBottom: Float,
    val startX: Float,
    val image: ImageSnapshot?,
    val images: List<ImageSnapshot>,
    val glyphImages: List<ImageSnapshot> = emptyList(),
    val decorations: List<DecorationSnapshot>
)

@Serializable
private data class ImageSnapshot(
    val path: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

@Serializable
private data class DecorationSnapshot(
    val kind: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val backgroundColorArgb: Int?,
    val backgroundImagePath: String?,
    val backgroundSizeMode: String,
    val backgroundSize: List<Float>,
    val backgroundRepeat: List<Boolean>,
    val backgroundPosition: List<Float>,
    val borderWidths: List<Float>,
    val borderColors: List<Int?>,
    val radii: List<Float>,
    val shadowCount: Int,
    val edges: List<Boolean>
)
