package com.mozhi.reader.feature.reader.engine

/** Render-layer copy of a Room annotation, kept free of database dependencies for geometry tests. */
data class ReaderAnnotationMark(
    val id: Long,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val hasComment: Boolean
)

/** 听书当前句（章内 UTF-16 区间）；render 层据此画一条跟随朗读进度的柔和底色。 */
data class ListenHighlightSpan(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int
)

data class AnnotationHighlightRect(
    val annotationId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

data class AnnotationMarker(
    val annotationIds: List<Long>,
    val centerX: Float,
    val centerY: Float
)

data class PageAnnotationGeometry(
    val highlights: List<AnnotationHighlightRect>,
    val markers: List<AnnotationMarker>
)

/**
 * Maps chapter UTF-16 ranges to laid-out cluster rectangles and one comment marker per line.
 * Marker 紧跟该行批注末字符的右边界（常见小说 App 手感），超出右界时 clamp 回页内。
 */
fun TextPage.annotationGeometry(
    annotations: List<ReaderAnnotationMark>,
    markerRadius: Float,
    markerGap: Float,
    maxRight: Float
): PageAnnotationGeometry {
    if (annotations.isEmpty()) return PageAnnotationGeometry(emptyList(), emptyList())
    val highlights = mutableListOf<AnnotationHighlightRect>()
    val markerIdsByLine = linkedMapOf<Int, MutableList<Long>>()
    val markerEndXByLine = mutableMapOf<Int, Float>()
    annotations.forEach { annotation ->
        if (annotation.endCharOffset <= annotation.startCharOffset) return@forEach
        var lastVisibleLine = -1
        var lastVisibleRight = 0f
        lines.forEachIndexed { lineIndex, line ->
            if (line.charLength <= 0 || line.columns.isEmpty()) return@forEachIndexed
            val lineStart = line.chapterPosition
            val lineEnd = lineStart + line.charLength
            val from = maxOf(annotation.startCharOffset, lineStart)
            val to = minOf(annotation.endCharOffset, lineEnd)
            if (from >= to) return@forEachIndexed
            val left = line.xBoundaryAt(from, endBoundary = false)
            val right = line.xBoundaryAt(to, endBoundary = true)
            if (right > left) {
                highlights += AnnotationHighlightRect(
                    annotationId = annotation.id,
                    left = left,
                    top = line.lineTop,
                    right = right,
                    bottom = line.lineBottom
                )
                lastVisibleLine = lineIndex
                lastVisibleRight = right
            }
        }
        if (annotation.hasComment && lastVisibleLine >= 0) {
            markerIdsByLine.getOrPut(lastVisibleLine) { mutableListOf() }.add(annotation.id)
            // 同行多批注共用一个带数字的 marker，取最靠右的末字符边界
            markerEndXByLine[lastVisibleLine] =
                maxOf(markerEndXByLine[lastVisibleLine] ?: 0f, lastVisibleRight)
        }
    }
    val markers = markerIdsByLine.mapNotNull { (lineIndex, ids) ->
        val line = lines.getOrNull(lineIndex) ?: return@mapNotNull null
        val endX = markerEndXByLine[lineIndex] ?: line.columns.last().end
        val centerX = (endX + markerGap + markerRadius)
            .coerceAtMost(maxRight - markerRadius)
        AnnotationMarker(ids.distinct(), centerX, (line.lineTop + line.lineBottom) / 2f)
    }
    return PageAnnotationGeometry(highlights, markers)
}

private fun TextLine.xBoundaryAt(offset: Int, endBoundary: Boolean): Float {
    var cursor = chapterPosition
    columns.forEach { column ->
        val next = cursor + column.charData.length
        when {
            offset <= cursor -> return column.start
            offset < next -> return if (endBoundary) column.end else column.start
            offset == next -> return column.end
        }
        cursor = next
    }
    return columns.lastOrNull()?.end ?: startX
}
