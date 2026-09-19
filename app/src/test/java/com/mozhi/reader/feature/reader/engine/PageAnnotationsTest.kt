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
        // 两条批注共用末字下沿的小点，始终留在划线范围内。
        val dot = geometry.markers.single()
        assertEquals(20f, dot.centerX + dot.radius, 0.001f)
        assertEquals(46f, dot.centerY + dot.radius, 0.001f)
        assertTrue(dot.centerX - dot.radius >= 10f)
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

        val dot = geometry.markers.single()
        assertEquals(20f, dot.centerX + dot.radius, 0.001f)
        assertEquals(22f, dot.centerY + dot.radius, 0.001f)
    }

    @Test
    fun paragraphEndDotStaysInsideTheLastCharacter() {
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

        val dot = geometry.markers.single()
        assertEquals(40f, dot.centerX + dot.radius, 0.001f)
    }

    @Test
    fun fullLineDotNeedsNoSpaceBeyondThePageEdge() {
        val first = line("天地玄黄", chapterPosition = 0, top = 0f)
        val page = TextPage(
            index = 0,
            lines = listOf(first),
            chapterPosition = 0,
            charLength = 4,
            height = 30f
        )
        // 正文铺满整行时也不需要挤字或另开一行。
        val marks = listOf(ReaderAnnotationMark(1, 0, 0, 4, hasComment = true))

        val geometry = page.annotationGeometry(
            marks,
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 40f
        )

        val dot = geometry.markers.single()
        assertTrue(dot.centerX + dot.radius <= 40f)
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
    fun commentDotAndIllustrationAtTheSameAnchorUseDifferentGeometry() {
        val markerColumn = TextColumn(
            start = 20f,
            end = 30f,
            charData = "",
            sourceLength = 0,
            inlineMarkerKind = InlineMarkerKind.ILLUSTRATION,
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
            illustrations = listOf(ReaderIllustrationMark(8, 0, 0, 2)),
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 200f
        )

        assertEquals(2, layout.markers.size)
        val dot = layout.markers.single { it.annotationIds.isNotEmpty() }
        val image = layout.markers.single { it.illustrationIds.isNotEmpty() }
        assertEquals(20f, dot.centerX + dot.radius, 0.001f)
        assertEquals(0f, dot.occupiedWidth, 0f)
        assertEquals(25f, image.centerX, 0f)
        assertEquals(30f, page.lines.single().columns[3].start)
    }

    @Test
    fun highlightAreaIsClickableWithoutStealingTheNextCharacterOrLine() {
        val page = TextPage(0, listOf(line("天地玄黄", 0, 0f)), 0, 4, 30f)
        val marks = listOf(
            ReaderAnnotationMark(1, 0, 0, 2, hasComment = true),
            ReaderAnnotationMark(2, 0, 1, 2, hasComment = false)
        )
        val geometry = page.inlineMarkerLayout(marks, emptyList(), 8f, 4f, 40f)
        assertEquals(listOf(1L), geometry.annotationIdsAt(5f, 10f))
        assertEquals(listOf(1L, 2L), geometry.annotationIdsAt(15f, 10f))
        val dot = geometry.markers.single()
        assertEquals(listOf(1L, 2L), geometry.annotationIdsAt(dot.centerX, dot.centerY))
        assertTrue(geometry.annotationIdsAt(21f, 10f).isEmpty())
        assertTrue(geometry.annotationIdsAt(18f, 25f).isEmpty())
    }

    @Test
    fun crossPageCommentOnlyMarksItsActualEndAndPureHighlightsHaveNoDot() {
        val first = TextPage(0, listOf(line("天地玄黄", 0, 0f)), 0, 4, 30f)
        val next = TextPage(1, listOf(line("宇宙洪荒", 4, 0f)), 4, 4, 30f)
        val marks = listOf(ReaderAnnotationMark(1, 0, 2, 6, hasComment = true))
        val head = first.annotationGeometry(marks, 8f, 4f, 40f)
        val tail = next.annotationGeometry(marks, 8f, 4f, 40f)
        assertEquals(1, head.highlights.size)
        assertTrue(head.markers.isEmpty())
        assertEquals(1, tail.highlights.size)
        assertEquals(20f, tail.markers.single().let { it.centerX + it.radius }, 0.001f)
        assertTrue(next.annotationGeometry(marks.map { it.copy(hasComment = false) }, 8f, 4f, 40f).markers.isEmpty())
    }

    @Test
    fun repeatedCommentsNeverMoveTextSelectionOrPageBoundaries() {
        val body = "天地玄黄宇宙洪荒日月盈昃".repeat(35)
        val spec = TypesetSpec(visibleWidth = 100f, visibleHeight = 100f,
            contentLineStep = 25f, titleLineStep = 34f, paragraphSpacing = 0f,
            blankLineSpacing = 0f, titleTopSpacing = 0f, titleBottomSpacing = 0f)
        val chapter = ChapterTypesetter(spec, FakeMeasure())
            .typeset(0, "", body)
        val pages = chapter.pages.toList()
        val first = pages.first()
        val selection = first.selectionRects(1..4)
        repeat(20) { update ->
            val marks = (1..update + 1).map { end -> ReaderAnnotationMark(end.toLong(), 0, 0, end, true) }
            pages.forEach { page ->
                val geometry = page.inlineMarkerLayout(marks, emptyList(), 8f, 4f, 100f)
                assertTrue(geometry.markers.all { it.occupiedWidth == 0f })
                page.lines.forEachIndexed { lineIndex, line ->
                    line.columns.forEachIndexed { columnIndex, column ->
                        assertEquals(column.start, geometry.startFor(lineIndex, columnIndex, column), 0f)
                        assertEquals(column.end, geometry.endFor(lineIndex, columnIndex, column), 0f)
                    }
                }
            }
            assertEquals(selection, first.selectionRects(1..4))
            assertEquals(pages, chapter.pages)
        }
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
