package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAnnotationsTest {
    @Test
    fun utf16RangeBecomesHighlightsAndGroupedCommentMarker() {
        val first = line("天地玄黄", chapterPosition = 10, top = 0f)
        val second = line("宇宙洪荒", chapterPosition = 14, top = 24f)
        val page = TextPage(
            index = 0,
            lines = listOf(first, second),
            chapterPosition = 10,
            charLength = 8,
            height = 60f
        )
        val marks = listOf(
            ReaderAnnotationMark(1, 0, 12, 16, hasComment = true),
            ReaderAnnotationMark(2, 0, 14, 16, hasComment = true)
        )

        val geometry = page.annotationGeometry(
            marks,
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 120f
        )

        assertEquals(3, geometry.highlights.size)
        assertEquals(1, geometry.markers.size)
        assertEquals(listOf(1L, 2L), geometry.markers.single().annotationIds)
        // 两条批注都在第二字后结束，marker 紧跟划线末尾（x=20）。
        assertEquals(20f + 4f + 8f, geometry.markers.single().centerX)
        assertEquals((24f + 46f) / 2f, geometry.markers.single().centerY)
        assertTrue(geometry.highlights.all { it.right > it.left })
    }

    @Test
    fun markerStaysAtUnderlineEndWhenAnnotationStopsMidParagraph() {
        val first = line("天地玄黄", chapterPosition = 0, top = 0f)
        val page = TextPage(
            index = 0,
            lines = listOf(first),
            chapterPosition = 0,
            charLength = 4,
            height = 30f
        )
        // 批注只到第 2 个字（右边界 x=20）
        val marks = listOf(ReaderAnnotationMark(1, 0, 0, 2, hasComment = true))

        val geometry = page.annotationGeometry(
            marks,
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 200f
        )

        assertEquals(20f + 4f + 8f, geometry.markers.single().centerX)
    }

    @Test
    fun markerFollowsTextWhenAnnotationEndsWithParagraph() {
        val first = line(
            text = "天地玄黄",
            chapterPosition = 0,
            top = 0f,
            isParagraphEnd = true
        )
        val page = TextPage(
            index = 0,
            lines = listOf(first),
            chapterPosition = 0,
            charLength = 4,
            height = 30f
        )

        val geometry = page.annotationGeometry(
            listOf(ReaderAnnotationMark(1, 0, 0, 4, hasComment = true)),
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 200f
        )

        assertEquals(40f + 4f + 8f, geometry.markers.single().centerX)
    }

    @Test
    fun markerFallbackStaysInsidePageEdge() {
        val first = line("天地玄黄", chapterPosition = 0, top = 0f)
        val page = TextPage(
            index = 0,
            lines = listOf(first),
            chapterPosition = 0,
            charLength = 4,
            height = 30f
        )
        // 排版占位尚未就绪时仍有兜底，marker 不越过右边界。
        val marks = listOf(ReaderAnnotationMark(1, 0, 0, 4, hasComment = true))

        val geometry = page.annotationGeometry(
            marks,
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 44f
        )

        assertTrue(geometry.markers.single().centerX + 8f <= 44f)
    }

    @Test
    fun illustrationMarkerUsesSelectionEndAndGroupsSameLine() {
        val page = TextPage(
            index = 0,
            lines = listOf(line("天地玄黄", chapterPosition = 0, top = 0f)),
            chapterPosition = 0,
            charLength = 4,
            height = 30f
        )

        val markers = page.illustrationMarkers(
            illustrations = listOf(
                ReaderIllustrationMark(8, 0, 0, 2),
                ReaderIllustrationMark(9, 0, 1, 2)
            ),
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 200f
        )

        assertEquals(1, markers.size)
        assertEquals(listOf(8L, 9L), markers.single().illustrationIds)
        assertEquals(20f + 4f + 8f, markers.single().centerX)
    }

    @Test
    fun inlineMarkerUsesReservedCharacterSlot() {
        val markerColumn = TextColumn(
            start = 20f,
            end = 30f,
            charData = "",
            sourceLength = 0,
            inlineMarkerKind = InlineMarkerKind.ANNOTATION,
            inlineMarkerOffset = 2
        )
        val page = TextPage(
            index = 0,
            lines = listOf(
                TextLine(
                    text = "天地玄黄",
                    columns = listOf(
                        TextColumn(0f, 10f, "天"),
                        TextColumn(10f, 20f, "地"),
                        markerColumn,
                        TextColumn(30f, 40f, "玄"),
                        TextColumn(40f, 50f, "黄")
                    ),
                    lineTop = 0f,
                    lineBase = 18f,
                    lineBottom = 22f,
                    startX = 0f,
                    isTitle = false,
                    isParagraphEnd = false,
                    chapterPosition = 0,
                    charLength = 4
                )
            ),
            chapterPosition = 0,
            charLength = 4,
            height = 30f
        )

        val layout = page.inlineMarkerLayout(
            annotations = listOf(ReaderAnnotationMark(1, 0, 0, 2, hasComment = true)),
            illustrations = emptyList(),
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 200f
        )

        assertEquals(1, layout.markers.size)
        assertEquals(25f, layout.markers.single().centerX)
        assertEquals(30f, page.lines.single().columns[3].start)
    }

    private fun line(
        text: String,
        chapterPosition: Int,
        top: Float,
        isParagraphEnd: Boolean = false
    ): TextLine {
        val columns = text.mapIndexed { index, char ->
            TextColumn(index * 10f, (index + 1) * 10f, char.toString())
        }
        return TextLine(
            text = text,
            columns = columns,
            lineTop = top,
            lineBase = top + 18f,
            lineBottom = top + 22f,
            startX = 0f,
            isTitle = false,
            isParagraphEnd = isParagraphEnd,
            chapterPosition = chapterPosition,
            charLength = text.length
        )
    }
}
