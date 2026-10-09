package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.*
import org.junit.Test

class ReaderVisibleReadEndTest {
    private fun line(offset: Int, top: Float, length: Int = 4) = TextLine("正文内容",
        List(length) { TextColumn(it * 10f, (it + 1) * 10f, "字") },
        top, top + 8f, top + 10f, 0f, false, false, offset, length)

    @Test fun croppedBottomLineAndOffscreenPagesDoNotExpandReadScope() {
        val page = TextPage(0, listOf(line(0, 0f), line(4, 15f), line(8, 30f)), 0, 12, 50f)
        assertEquals(4, page.visibleReadEnd(0f, 24f))
        assertEquals(8, page.visibleReadEnd(0f, 25f))
        assertNull(page.visibleReadEnd(0f, -5f))
        assertNull(page.visibleReadEnd(50f, 100f))
    }

    @Test fun titleAndSyntheticTranslationRowsAreNotSourceProgress() {
        val title = TextLine("章节", emptyList(), 0f, 8f, 10f, 0f, true, false, 0, 0)
        val translation = TextLine("译文", emptyList(), 15f, 23f, 25f, 0f, false, false, 999, 0)
        assertNull(TextPage(0, listOf(title, translation), 0, 0, 40f).visibleReadEnd(0f, 40f))
    }

    @Test fun verticalWritingStopsAtFirstUnreadGlyphInsteadOfJumpingToNextColumn() {
        val page = TextPage(0, listOf(line(0, 0f), line(4, 15f, 2)), 0, 6, 50f,
            verticalFrameWidth = 100f)
        assertEquals(2, page.visibleReadEnd(0f, 25f))
        assertEquals(6, page.visibleReadEnd(0f, 40f))
    }

    @Test fun utf16ClustersAreCountedWhole() {
        val line = TextLine("字😀字", listOf(TextColumn(0f, 10f, "字"),
            TextColumn(10f, 20f, "😀"), TextColumn(20f, 30f, "字")),
            0f, 8f, 10f, 0f, false, false, 5, 4)
        val page = TextPage(0, listOf(line), 5, 4, 40f, verticalFrameWidth = 100f)
        assertEquals(6, page.visibleReadEnd(0f, 15f))
        assertEquals(8, page.visibleReadEnd(0f, 20f))
    }

    @Test fun verticalSyntheticGlyphsCannotAuthorizeUnreadBodyText() {
        val synthetic = TextLine("标题译文", List(4) { TextColumn(0f, 8f, "字") },
            0f, 8f, 10f, 0f, true, false, 999, 0)
        val page = TextPage(0, listOf(synthetic, line(0, 15f)), 0, 4, 60f, verticalFrameWidth = 100f)
        assertNull(page.visibleReadEnd(0f, 9f))
        assertEquals(4, page.visibleReadEnd(0f, 50f))
    }
}
