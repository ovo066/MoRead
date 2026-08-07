package com.mozhi.reader.feature.reader.engine

/** 「评」marker 与末字符右边界的间距系数（× markerRadius）；渲染与点击热区必须共用。0.35 会压到句号。 */
const val ANNOTATION_MARKER_GAP_RATIO = 0.72f

/** Render-layer copy of a Room annotation, kept free of database dependencies for geometry tests. */
data class ReaderAnnotationMark(
    val id: Long,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val hasComment: Boolean,
    /** AnnotationStyle wire 值；渲染层按它分笔画（荧光/直线/波浪）。 */
    val style: String = "HIGHLIGHT",
    /** AnnotationColors 色名；空串用阅读页强调色。 */
    val colorTag: String = ""
)

/** Render-layer copy of a generated illustration anchor. */
data class ReaderIllustrationMark(
    val id: Long,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int
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

data class IllustrationMarker(
    val illustrationIds: List<Long>,
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

/**
 * 选段插图的图片标记与批注「评」使用同一种末字符锚定语义。同行多张图合并成一个
 * 可点击标记，避免小图标互相覆盖。
 */
fun TextPage.illustrationMarkers(
    illustrations: List<ReaderIllustrationMark>,
    markerRadius: Float,
    markerGap: Float,
    maxRight: Float
): List<IllustrationMarker> {
    if (illustrations.isEmpty()) return emptyList()
    val idsByLine = linkedMapOf<Int, MutableList<Long>>()
    val endXByLine = mutableMapOf<Int, Float>()
    illustrations.forEach { illustration ->
        val start = illustration.startCharOffset.coerceAtLeast(0)
        val end = illustration.endCharOffset.coerceAtLeast(start + 1)
        val lineIndex = lines.indexOfFirst { line ->
            if (line.charLength <= 0 || line.columns.isEmpty()) return@indexOfFirst false
            val lineStart = line.chapterPosition
            val lineEnd = lineStart + line.charLength
            end > lineStart && end <= lineEnd
        }
        if (lineIndex < 0) return@forEach
        val line = lines[lineIndex]
        val right = line.xBoundaryAt(end, endBoundary = true)
        idsByLine.getOrPut(lineIndex) { mutableListOf() }.add(illustration.id)
        endXByLine[lineIndex] = maxOf(endXByLine[lineIndex] ?: 0f, right)
    }
    return idsByLine.mapNotNull { (lineIndex, ids) ->
        val line = lines.getOrNull(lineIndex) ?: return@mapNotNull null
        val endX = endXByLine[lineIndex] ?: line.columns.last().end
        IllustrationMarker(
            illustrationIds = ids.distinct(),
            centerX = (endX + markerGap + markerRadius).coerceAtMost(maxRight - markerRadius),
            centerY = (line.lineTop + line.lineBottom) / 2f
        )
    }
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
