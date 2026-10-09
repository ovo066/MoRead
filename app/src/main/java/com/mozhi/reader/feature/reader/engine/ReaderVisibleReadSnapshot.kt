package com.mozhi.reader.feature.reader.engine

/** Immutable provenance of a rendered bitmap or scroll frame, never inferred from prefetch. */
data class ReaderVisibleReadSnapshot(
    val layoutGeneration: Int,
    val chapterIndex: Int,
    val pages: List<RenderPage.Laid>,
    val displayEnd: Int,
    val source: ReaderChapterSource
)

/** Only fully drawn source lines/glyphs may extend the scroll viewport's spoiler boundary. */
internal fun TextPage.visibleReadEnd(clipTop: Float, clipBottom: Float): Int? {
    var end: Int? = null
    if (!isVertical) {
        for (line in lines) {
            if (line.charLength > 0 && line.lineBottom > clipTop && line.lineBottom <= clipBottom) {
                end = maxOf(end ?: 0, line.chapterPosition + line.charLength)
            }
        }
    } else {
        // In vertical writing frame x is physical y. Do not leap past an unread tail of
        // the first column just because a later, shorter column also fits on screen.
        reading@ for (line in lines) {
            if (line.charLength <= 0) continue
            var offset = line.chapterPosition
            for (column in line.columns) {
                if (column.sourceLength <= 0) continue
                if (column.end > clipBottom) break@reading
                offset += column.sourceLength
                if (column.end > clipTop) end = maxOf(end ?: 0, offset)
            }
        }
    }
    return end
}
