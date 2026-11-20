package com.mozhi.reader.feature.reader.engine

/**
 * 排进正文的插图：读者自己生成的、伴读随段评配的。
 *
 * 它不是原文——text.mz 里没有它，也不占任何原文坐标。排版时在锚点所在段落之后插入一块
 * 「图 + 图注」，两行的原文长度都是 0，选区、书签、听书和阅读进度照旧按原文计算。
 */
data class EmbeddedArt(
    /** 显示正文里的锚点（UTF-16）：图排在包含它的那个段落之后。 */
    val anchor: Int,
    val imagePath: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** 图注：说明这是谁配的图，不让它冒充原书插图。 */
    val caption: String
)

/** 这一段（显示正文区间 [[start], [end])）后面该排哪些图；按锚点先后。 */
internal fun List<EmbeddedArt>.anchoredIn(start: Int, end: Int): List<EmbeddedArt> =
    if (isEmpty()) emptyList() else filter { it.anchor in start until maxOf(end, start + 1) }.sortedBy { it.anchor }

/** 图上方留的空：比段距宽一些，图才像一幅插图而不是挤在两段字中间。 */
internal fun embeddedArtGap(spec: TypesetSpec): Float = maxOf(spec.paragraphSpacing, spec.contentLineStep * 0.6f)

/** 一块插图占的总高度；整块留在同一页，图和图注不分家。 */
internal fun List<TextLine>.blockHeight(): Float = if (isEmpty()) 0f else last().lineBottom - first().lineTop

/**
 * 一幅插图排成的两行：图本身、图注。坐标从 0 起算，调用方整体平移到落位处。
 *
 * 图按内容宽度铺开、保持比例；高度封顶在半页上下（竖图稍高），页面上总还留得下文字，
 * 不会一幅图独占一整页把阅读节奏打断。图注行的下沿多留了一截，当作图块与下一段之间的空。
 */
internal fun embeddedArtLines(
    art: EmbeddedArt, left: Float, width: Float, spec: TypesetSpec, measure: TextMeasure, paragraphEnd: Int
): List<TextLine> {
    val aspect = if (art.pixelWidth > 0 && art.pixelHeight > 0) {
        (art.pixelWidth.toFloat() / art.pixelHeight).coerceIn(EMBEDDED_ART_MIN_ASPECT, EMBEDDED_ART_MAX_ASPECT)
    } else 1f
    var imageWidth = width
    var imageHeight = imageWidth / aspect
    val maxHeight = spec.visibleHeight * if (aspect < 0.9f) EMBEDDED_ART_PORTRAIT_HEIGHT else EMBEDDED_ART_LANDSCAPE_HEIGHT
    if (imageHeight > maxHeight) {
        imageHeight = maxHeight
        imageWidth = imageHeight * aspect
    }
    val image = TextLine(
        text = "", columns = emptyList(), lineTop = 0f, lineBase = imageHeight, lineBottom = imageHeight,
        startX = left + (width - imageWidth) / 2f, isTitle = false, isParagraphEnd = true,
        chapterPosition = paragraphEnd, charLength = 0,
        inlineImage = InlineImagePlacement(art.imagePath, imageWidth, imageHeight, art.caption)
    )
    val caption = art.caption.trim().take(EMBEDDED_ART_CAPTION_CHARS)
    if (caption.isEmpty()) return listOf(image)
    val style = MeasuredTextStyle(false, textSizeScale = EMBEDDED_ART_CAPTION_SCALE)
    val metrics = measure.metrics(style)
    val widths = measure.charWidths(caption, style)
    val clusters = mutableListOf<String>()
    val advances = mutableListOf<Float>()
    clusterText(caption, widths, 0, caption.length, clusters, advances)
    // 图注放不下就从尾部截掉：它只是一行说明，不值得为它再占一行。
    var total = advances.sum()
    while (total > width && clusters.size > 1) {
        total -= advances.removeAt(advances.lastIndex)
        clusters.removeAt(clusters.lastIndex)
    }
    var x = left + (width - total) / 2f
    val columns = clusters.mapIndexed { index, text ->
        TextColumn(x, x + advances[index], text, syntaxColorArgb = spec.glossColorArgb,
            textSizeScale = EMBEDDED_ART_CAPTION_SCALE, sourceLength = 0).also { x += advances[index] }
    }
    val top = imageHeight + spec.contentLineStep * 0.3f
    val captionLine = TextLine(
        text = "", columns = columns, lineTop = top, lineBase = top + metrics.textHeight - metrics.descent,
        lineBottom = top + metrics.textHeight + embeddedArtGap(spec), startX = columns.firstOrNull()?.start ?: left,
        isTitle = false, isParagraphEnd = true, chapterPosition = paragraphEnd, charLength = 0
    )
    return listOf(image, captionLine)
}

private const val EMBEDDED_ART_MIN_ASPECT = 0.45f
private const val EMBEDDED_ART_MAX_ASPECT = 2.6f
private const val EMBEDDED_ART_LANDSCAPE_HEIGHT = 0.48f
private const val EMBEDDED_ART_PORTRAIT_HEIGHT = 0.6f
private const val EMBEDDED_ART_CAPTION_SCALE = 0.72f
private const val EMBEDDED_ART_CAPTION_CHARS = 40
