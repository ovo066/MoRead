package com.mozhi.reader.feature.reader.engine

/** 插图按钮与锚点右边界的间距系数；渲染与点击热区共用。段评小点不占位。 */
const val INLINE_MARKER_GAP_RATIO = 0.72f

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

/**
 * 一段临时高亮（章内 UTF-16 区间），render 层据此画一条柔和底色，画在批注色之下。
 * 两个来源共用它：听书当前句（跟随朗读推进）与聊天引文定位（跳过来后短暂点亮）。
 */
data class TransientHighlightSpan(
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
    val centerY: Float,
    val radius: Float
)

data class IllustrationMarker(
    val illustrationIds: List<Long>,
    val centerX: Float,
    val centerY: Float
)

data class InlineMarker(
    val lineIndex: Int,
    val afterColumnIndex: Int,
    val annotationIds: List<Long> = emptyList(),
    val illustrationIds: List<Long> = emptyList(),
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val occupiedWidth: Float
)

data class PageInlineMarkerLayout(
    val highlights: List<AnnotationHighlightRect>,
    val markers: List<InlineMarker>,
    private val columnShifts: Map<Int, FloatArray>,
    private val lineScales: Map<Int, Float>
) {
    fun shiftFor(lineIndex: Int, columnIndex: Int): Float =
        columnShifts[lineIndex]?.getOrNull(columnIndex) ?: 0f

    fun scaleFor(lineIndex: Int): Float = lineScales[lineIndex] ?: 1f

    fun startFor(lineIndex: Int, columnIndex: Int, column: TextColumn): Float =
        column.start + shiftFor(lineIndex, columnIndex)

    fun endFor(lineIndex: Int, columnIndex: Int, column: TextColumn): Float =
        startFor(lineIndex, columnIndex, column) + (column.end - column.start) * scaleFor(lineIndex)

    /** 整个划线文字区域都是入口；不把小点热区扩张到未标注的后续文字。 */
    fun annotationIdsAt(x: Float, y: Float): List<Long> = highlights
        .filter { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
        .map(AnnotationHighlightRect::annotationId)
        .distinct()
}

data class PageAnnotationGeometry(
    val highlights: List<AnnotationHighlightRect>,
    val markers: List<AnnotationMarker>
)

/**
 * Maps chapter UTF-16 ranges to existing text rectangles and comment dots.
 * 小点画在末字符的下沿以内，不改变字距、行高、换行或分页。
 */
fun TextPage.annotationGeometry(
    annotations: List<ReaderAnnotationMark>,
    markerRadius: Float,
    markerGap: Float,
    maxRight: Float
): PageAnnotationGeometry {
    val layout = inlineMarkerLayout(annotations, emptyList(), markerRadius, markerGap, maxRight)
    return PageAnnotationGeometry(
        highlights = layout.highlights,
        markers = layout.markers.mapNotNull { marker ->
            marker.annotationIds.takeIf { it.isNotEmpty() }?.let {
                AnnotationMarker(it, marker.centerX, marker.centerY, marker.radius)
            }
        }
    )
}

/**
 * 选段插图保留末字符之后的占位按钮；同一锚点的多张图合并成一个可点击标记。
 */
fun TextPage.illustrationMarkers(
    illustrations: List<ReaderIllustrationMark>,
    markerRadius: Float,
    markerGap: Float,
    maxRight: Float
): List<IllustrationMarker> {
    return inlineMarkerLayout(emptyList(), illustrations, markerRadius, markerGap, maxRight)
        .markers.mapNotNull { marker ->
            marker.illustrationIds.takeIf { it.isNotEmpty() }?.let {
                IllustrationMarker(it, marker.centerX, marker.centerY)
            }
        }
}

fun TextPage.inlineMarkerLayout(
    annotations: List<ReaderAnnotationMark>,
    illustrations: List<ReaderIllustrationMark>,
    markerRadius: Float,
    markerGap: Float,
    maxRight: Float
): PageInlineMarkerLayout {
    val requests = linkedMapOf<MarkerKey, MutableList<Long>>()
    annotations.forEach { annotation ->
        if (annotation.endCharOffset <= annotation.startCharOffset) return@forEach
        if (annotation.hasComment) {
            markerAnchor(annotation.endCharOffset)?.let { anchor ->
                requests.getOrPut(MarkerKey(anchor.lineIndex, anchor.columnIndex, MarkerKind.ANNOTATION)) {
                    mutableListOf()
                }.add(annotation.id)
            }
        }
    }
    illustrations.forEach { illustration ->
        markerAnchor(
            illustration.endCharOffset.coerceAtLeast(illustration.startCharOffset + 1),
            InlineMarkerKind.ILLUSTRATION
        )
            ?.let { anchor ->
                requests.getOrPut(MarkerKey(anchor.lineIndex, anchor.columnIndex, MarkerKind.ILLUSTRATION)) {
                    mutableListOf()
                }.add(illustration.id)
            }
    }

    val shifts = mutableMapOf<Int, FloatArray>()
    val scales = mutableMapOf<Int, Float>()
    val markers = mutableListOf<InlineMarker>()
    requests.entries
        .sortedWith(compareBy({ it.key.lineIndex }, { it.key.columnIndex }, { it.key.kind.ordinal }))
        .groupBy { it.key.lineIndex }
        .forEach { (lineIndex, entries) ->
            val line = lines[lineIndex]
            entries.forEach { entry ->
                val anchor = line.columns[entry.key.columnIndex]
                val isComment = entry.key.kind == MarkerKind.ANNOTATION
                val radius = if (isComment) {
                    minOf(markerRadius * 0.3f, (anchor.end - anchor.start) / 2f,
                        (line.lineBottom - line.lineTop) / 2f).coerceAtLeast(0f)
                } else markerRadius
                val isReservedSlot = anchor.inlineMarkerKind == InlineMarkerKind.ILLUSTRATION
                val centerX = if (isComment) {
                    // 末字以内的小点，不挤到下一个字，也不需要右侧留白。
                    minOf(anchor.end, maxRight) - radius
                } else if (isReservedSlot) {
                    (anchor.start + anchor.end) / 2f
                } else {
                    (anchor.end + markerGap + markerRadius).coerceAtMost(maxRight - markerRadius)
                }
                markers += InlineMarker(
                    lineIndex = lineIndex,
                    afterColumnIndex = entry.key.columnIndex,
                    annotationIds = if (entry.key.kind == MarkerKind.ANNOTATION) entry.value.distinct() else emptyList(),
                    illustrationIds = if (entry.key.kind == MarkerKind.ILLUSTRATION) entry.value.distinct() else emptyList(),
                    centerX = centerX,
                    centerY = if (isComment) line.lineBottom - radius else (line.lineTop + line.lineBottom) / 2f,
                    radius = radius,
                    occupiedWidth = if (isComment) 0f else anchor.end - anchor.start
                )
            }
            shifts[lineIndex] = FloatArray(line.columns.size)
            scales[lineIndex] = 1f
        }

    val layout = PageInlineMarkerLayout(emptyList(), markers, shifts, scales)
    val highlights = buildList {
        annotations.forEach { annotation ->
            if (annotation.endCharOffset <= annotation.startCharOffset) return@forEach
            lines.forEachIndexed { lineIndex, line ->
                if (line.charLength <= 0 || line.columns.isEmpty()) return@forEachIndexed
                val lineStart = line.chapterPosition
                val lineEnd = lineStart + line.charLength
                val from = maxOf(annotation.startCharOffset, lineStart)
                val to = minOf(annotation.endCharOffset, lineEnd)
                if (from >= to) return@forEachIndexed
                val left = line.xBoundaryAt(
                    offset = from,
                    endBoundary = false,
                    lineIndex = lineIndex,
                    layout = layout
                )
                val right = line.xBoundaryAt(
                    offset = to,
                    endBoundary = true,
                    lineIndex = lineIndex,
                    layout = layout
                )
                if (right > left) {
                    add(AnnotationHighlightRect(annotation.id, left, line.lineTop, right, line.lineBottom))
                }
            }
        }
    }
    return PageInlineMarkerLayout(highlights, markers, shifts, scales)
}

private enum class MarkerKind { ANNOTATION, ILLUSTRATION }

private data class MarkerKey(
    val lineIndex: Int,
    val columnIndex: Int,
    val kind: MarkerKind
)

private data class MarkerAnchor(val lineIndex: Int, val columnIndex: Int)

private fun TextPage.markerAnchor(endOffset: Int, kind: InlineMarkerKind? = null): MarkerAnchor? {
    if (kind != null) {
        lines.forEachIndexed { lineIndex, line ->
            line.columns.forEachIndexed { columnIndex, column ->
                if (column.inlineMarkerOffset == endOffset && column.inlineMarkerKind == kind) {
                    return MarkerAnchor(lineIndex, columnIndex)
                }
            }
        }
    }
    lines.forEachIndexed { lineIndex, line ->
        if (line.charLength <= 0 || line.columns.isEmpty()) return@forEachIndexed
        val lineStart = line.chapterPosition
        val lineEnd = lineStart + line.charLength
        if (endOffset <= lineStart || endOffset > lineEnd) return@forEachIndexed
        var cursor = lineStart
        line.columns.forEachIndexed { columnIndex, column ->
            if (column.sourceLength == 0) return@forEachIndexed
            cursor += column.sourceLength
            if (endOffset <= cursor) return MarkerAnchor(lineIndex, columnIndex)
        }
    }
    return null
}

private fun TextLine.xBoundaryAt(
    offset: Int,
    endBoundary: Boolean,
    lineIndex: Int,
    layout: PageInlineMarkerLayout
): Float {
    var cursor = chapterPosition
    columns.forEachIndexed { columnIndex, column ->
        if (column.sourceLength == 0) return@forEachIndexed
        val next = cursor + column.sourceLength
        when {
            offset <= cursor -> return layout.startFor(lineIndex, columnIndex, column)
            offset < next -> return if (endBoundary) {
                layout.endFor(lineIndex, columnIndex, column)
            } else {
                layout.startFor(lineIndex, columnIndex, column)
            }
            offset == next && endBoundary -> return layout.endFor(lineIndex, columnIndex, column)
        }
        cursor = next
    }
    val lastIndex = columns.indexOfLast { it.sourceLength > 0 }
    return columns.getOrNull(lastIndex)?.let { layout.endFor(lineIndex, lastIndex, it) } ?: startX
}
