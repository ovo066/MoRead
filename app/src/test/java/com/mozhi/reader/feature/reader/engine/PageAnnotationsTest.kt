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
        // 两条批注都终于第二行第 2 个字（offset 16 → 右边界 x=20），marker 紧跟其后
        assertEquals(20f + 4f + 8f, geometry.markers.single().centerX)
        assertEquals((24f + 46f) / 2f, geometry.markers.single().centerY)
        assertTrue(geometry.highlights.all { it.right > it.left })
    }

    @Test
    fun markerFollowsLastCharacterOfEachAnnotationLine() {
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
    fun markerClampsInsidePageEdge() {
        val first = line("天地玄黄", chapterPosition = 0, top = 0f)
        val page = TextPage(
            index = 0,
            lines = listOf(first),
            chapterPosition = 0,
            charLength = 4,
            height = 30f
        )
        // 批注直到行尾（右边界 x=40），maxRight 只有 44 → clamp 到 44-8
        val marks = listOf(ReaderAnnotationMark(1, 0, 0, 4, hasComment = true))

        val geometry = page.annotationGeometry(
            marks,
            markerRadius = 8f,
            markerGap = 4f,
            maxRight = 44f
        )

        assertEquals(44f - 8f, geometry.markers.single().centerX)
    }

    private fun line(text: String, chapterPosition: Int, top: Float): TextLine {
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
            isParagraphEnd = false,
            chapterPosition = chapterPosition,
            charLength = text.length
        )
    }
}
