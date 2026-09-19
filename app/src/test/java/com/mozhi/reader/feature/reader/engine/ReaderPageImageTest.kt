package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.*
import org.junit.Test

class ReaderPageImageTest {
    private val line = TextLine("正文［图片］［图片］", listOf(TextColumn(0f, 40f, "正文")), 20f, 50f, 60f,
        0f, false, true, 10, 10, inlineGlyphImages = listOf(
            PositionedInlineImagePlacement("same.png", 40f, 4f, 20f, 20f, "first", 12),
            PositionedInlineImagePlacement("same.png", 80f, 8f, 20f, 20f, "second", 16)))
    private fun page() = TextPage(0, listOf(line), 10, 10, 100f, backgroundImagePath = "wallpaper.png")
    @Test fun repeatedInlineAssetsHaveDistinctPreciseAnchors() {
        assertEquals(12, page().imageAt(50f, 30f, 4)!!.charOffset)
        assertEquals(16, page().imageAt(90f, 30f, 4)!!.charOffset)
        assertEquals(4, page().imageAt(90f, 30f, 4)!!.chapterIndex)
        assertEquals("wallpaper.png", page().imageAt(90f, 21f, 4)!!.imagePath)
        assertEquals("wallpaper.png", page().imageAt(60f, 30f, 4)!!.imagePath)
    }
    @Test fun textSelectionWinsOverPageArtwork() {
        assertNull(page().imageAt(20f, 30f, 0))
        assertEquals("wallpaper.png", page().imageAt(150f, 80f, 0)!!.imagePath)
    }
    @Test fun opaquePanelsDoNotExposeTheWallpaperBehindThem() {
        val page = TextPage(0, emptyList(), 4, 0, 100f, decorations = listOf(
            TextBlockDecoration(0f, 0f, 100f, 100f, backgroundColorArgb = -1)), backgroundImagePath = "hidden.png")
        assertNull(page.imageAt(50f, 50f, 0))
    }
}
