package com.mozhi.reader.feature.reader.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import android.text.TextPaint
import com.caverock.androidsvg.SVG
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.feature.reader.engine.BackgroundSizeMode
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.TextBlockDecoration
import com.mozhi.reader.feature.reader.engine.annotationGeometry
import com.mozhi.reader.feature.reader.engine.inlineMarkerLayout
import java.io.File
import java.util.Locale

/**
 * Renders one [RenderPage] into a bitmap: header (chapter title), the laid-out body, and the footer
 * (page number, whole-book progress, time, battery). Flat/no-animation modes use transparent page
 * bitmaps over a static background layer; simulation embeds the shared background to form a full
 * page snapshot for curl compositing.
 */
class PageBitmapRenderer(private val pageStyle: ReaderPageStyle) {

    private val tipPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = pageStyle.tipSizePx
        color = pageStyle.mutedColor
        typeface = pageStyle.typeface
    }
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = pageStyle.mutedColor
    }
    private val batteryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.mutedColor
    }
    private val placeholderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = pageStyle.contentSizePx
        color = pageStyle.mutedColor
        typeface = pageStyle.typeface
        textAlign = Paint.Align.CENTER
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backgroundProvider = ReaderBackgroundProvider(pageStyle)

    /** 后台合成背景图；已就绪（无背景图或缓存命中）时不会回调。 */
    fun prepareBackground(onReady: () -> Unit) = backgroundProvider.prepare(onReady)

    private val syntaxPaints = HashMap<String, TextPaint>()
    private val syntaxBackgroundPaints = HashMap<Int, Paint>()
    private val embeddedTypefaces = HashMap<String, Typeface?>()
    private val epubDecorationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val epubShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val epubBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val epubRulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    /** (style|colorTag) → Paint；样式实例随 pageStyle 重建，缓存不会跨主题存活。 */
    private val annotationInkPaints = HashMap<String, Paint>()
    private val transientHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.accentColor
        alpha = if (pageStyle.isDark) 30 else 38
    }
    private val annotationMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.accentColor
        style = Paint.Style.STROKE
        strokeWidth = (pageStyle.tipSizePx * 0.11f).coerceAtLeast(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val annotationMarkerTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = pageStyle.tipSizePx * 0.82f
        color = pageStyle.accentColor
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val illustrationMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.accentColor
        style = Paint.Style.STROKE
        strokeWidth = (pageStyle.tipSizePx * 0.11f).coerceAtLeast(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val illustrationGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.accentColor
        style = Paint.Style.STROKE
        strokeWidth = (pageStyle.tipSizePx * 0.1f).coerceAtLeast(1.4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val imageFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pageStyle.mutedColor
        alpha = 24
    }
    private val imageCache = object : LruCache<String, Bitmap>(IMAGE_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue && !oldValue.isRecycled) oldValue.recycle()
        }
    }

    fun obtainBitmap(recycled: Bitmap?): Bitmap {
        val existing = recycled
            ?.takeIf { !it.isRecycled && it.width == pageStyle.viewWidth && it.height == pageStyle.viewHeight }
        return existing ?: Bitmap.createBitmap(
            pageStyle.viewWidth.coerceAtLeast(1),
            pageStyle.viewHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
    }

    fun render(
        page: RenderPage,
        into: Bitmap?,
        bookProgress: Float,
        timeText: String,
        batteryPercent: Int,
        annotations: List<ReaderAnnotationMark> = emptyList(),
        illustrations: List<ReaderIllustrationMark> = emptyList(),
        transientHighlight: TransientHighlightSpan? = null,
        includeBackground: Boolean = true
    ): Bitmap {
        val bitmap = obtainBitmap(into)
        // `into` 会在三页窗口中循环复用。无论纸面是否嵌入，都先彻底清掉上一页；
        // 不能只依赖背景绘制覆盖，因为半透明背景/图片边缘会把旧字留在缓冲里，
        // 页面第二次进入该缓冲后就会形成重影。
        bitmap.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bitmap)
        if (includeBackground) {
            drawBackdrop(canvas, bitmap.width.toFloat(), bitmap.height.toFloat())
        }
        drawHeader(canvas, page)
        when (page) {
            is RenderPage.Laid -> drawBody(canvas, page, annotations, illustrations, transientHighlight)
            is RenderPage.Placeholder -> drawPlaceholder(canvas, page)
        }
        drawFooter(canvas, page, bookProgress, timeText, batteryPercent)
        return bitmap
    }

    /** 纸面底色 + 纸纹；滚动模式在实时画布上也走这一笔。 */
    internal fun drawBackdrop(canvas: Canvas, width: Float, height: Float) {
        backgroundProvider.draw(canvas, width, height)    }

    internal fun drawHeader(canvas: Canvas, page: RenderPage) {
        val laidPage = (page as? RenderPage.Laid)?.page
        if (!pageStyle.showHeader || laidPage?.immersive == true || laidPage?.hideHeader == true) return
        val title = page.chapterTitle.ifBlank { return }
        val maxWidth = pageStyle.contentWidth
        val text = ellipsize(title, tipPaint, maxWidth)
        val y = pageStyle.headerBaseline
        canvas.drawText(text, pageStyle.paddingLeft, y, tipPaint)
    }

    private fun drawBody(
        canvas: Canvas,
        page: RenderPage.Laid,
        annotations: List<ReaderAnnotationMark>,
        illustrations: List<ReaderIllustrationMark>,
        transientHighlight: TransientHighlightSpan?
    ) {
        val artwork = page.page.lines.singleOrNull()?.inlineImage
        if (page.page.immersive && artwork != null) {
            val availableWidth = pageStyle.viewWidth.toFloat()
            val availableHeight = pageStyle.immersiveContentBottom
            val scale = kotlin.math.min(
                availableWidth / artwork.width.coerceAtLeast(1f),
                availableHeight / artwork.height.coerceAtLeast(1f)
            )
            val width = artwork.width * scale
            val height = artwork.height * scale
            drawInlineImage(
                canvas = canvas,
                path = artwork.imagePath,
                altText = artwork.altText,
                destination = RectF(
                    (availableWidth - width) / 2f,
                    (availableHeight - height) / 2f,
                    (availableWidth + width) / 2f,
                    (availableHeight + height) / 2f
                )
            )
            return
        }
        val immersiveBackground = page.page.immersive &&
            (page.page.backgroundImagePath != null || page.page.backgroundColorArgb != null)
        if (immersiveBackground) {
            val viewport = RectF(
                0f,
                0f,
                pageStyle.viewWidth.toFloat(),
                pageStyle.immersiveContentBottom
            )
            page.page.backgroundColorArgb?.let { color ->
                epubDecorationPaint.style = Paint.Style.FILL
                epubDecorationPaint.color = color.withOpacity(page.page.backgroundOpacity)
                canvas.drawRect(viewport, epubDecorationPaint)
            }
            page.page.backgroundImagePath?.let { path ->
                drawCoverImage(canvas, path, viewport, page.page.backgroundOpacity)
            }
        }
        canvas.save()
        canvas.translate(
            pageStyle.paddingLeft,
            if (page.page.immersive || page.page.hideHeader) {
                pageStyle.immersiveContentTop
            } else {
                pageStyle.contentTop
            }
        )
        drawContent(
            canvas,
            page,
            annotations,
            illustrations,
            transientHighlight,
            drawPageBackground = !immersiveBackground
        )
        canvas.restore()
    }

    /**
     * 内容层（听书底色 → 批注墨迹 → 正文 → 「评」标记），坐标为内容局部系，画布由调用方
     * 平移定位；翻页位图与滚动条带共用这一份代码。[clipTop]/[clipBottom] 是页内可见窗，
     * 滚动模式用它跳过视口外的行，避免逐帧画整页。
     */
    internal fun drawContent(
        canvas: Canvas,
        page: RenderPage.Laid,
        annotations: List<ReaderAnnotationMark>,
        illustrations: List<ReaderIllustrationMark> = emptyList(),
        transientHighlight: TransientHighlightSpan? = null,
        clipTop: Float = Float.NEGATIVE_INFINITY,
        clipBottom: Float = Float.POSITIVE_INFINITY,
        drawPageBackground: Boolean = true
    ) {
        val markerRadius = (pageStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        if (drawPageBackground) drawEpubPageBackground(canvas, page, clipTop, clipBottom)
        drawEpubDecorations(canvas, page, clipTop, clipBottom)
        // 听书当前句底色画在批注高亮之下，两者重叠时批注色仍占主导。
        if (transientHighlight != null && transientHighlight.chapterIndex == page.chapterIndex) {
            val listenGeometry = page.page.annotationGeometry(
                annotations = listOf(
                    ReaderAnnotationMark(
                        id = LISTEN_HIGHLIGHT_ID,
                        chapterIndex = transientHighlight.chapterIndex,
                        startCharOffset = transientHighlight.startCharOffset,
                        endCharOffset = transientHighlight.endCharOffset,
                        hasComment = false
                    )
                ),
                markerRadius = markerRadius,
                markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.ANNOTATION_MARKER_GAP_RATIO,
                maxRight = pageStyle.contentWidth
            )
            listenGeometry.highlights.forEach { rect ->
                if (rect.bottom < clipTop || rect.top > clipBottom) return@forEach
                canvas.drawRoundRect(
                    RectF(rect.left, rect.top, rect.right, rect.bottom),
                    ANNOTATION_HIGHLIGHT_RADIUS,
                    ANNOTATION_HIGHLIGHT_RADIUS,
                    transientHighlightPaint
                )
            }
        }
        val geometry = page.page.inlineMarkerLayout(
            annotations = annotations,
            illustrations = illustrations,
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.ANNOTATION_MARKER_GAP_RATIO,
            maxRight = pageStyle.contentWidth
        )
        val markById = annotations.associateBy(ReaderAnnotationMark::id)
        // 荧光垫在正文之下；直线/波浪画在字形基线下沿，也一并先画（都在文字层之下不糊字形）
        geometry.highlights.forEach { rect ->
            if (rect.bottom < clipTop || rect.top > clipBottom) return@forEach
            val mark = markById[rect.annotationId]
            drawAnnotationInk(canvas, rect, mark)
        }
        val contentPaint = pageStyle.measure.contentPaint
        val titlePaint = pageStyle.measure.titlePaint
        contentPaint.color = pageStyle.textColor
        titlePaint.color = pageStyle.textColor
        contentPaint.alpha = 255
        titlePaint.alpha = 255
        for ((lineIndex, line) in page.page.lines.withIndex()) {
            if (line.lineBottom < clipTop || line.lineTop > clipBottom) continue
            val rule = line.rule
            if (rule != null) {
                epubRulePaint.color = rule.colorArgb
                canvas.drawRect(
                    line.startX,
                    line.lineTop,
                    line.startX + rule.width,
                    line.lineTop + rule.height,
                    epubRulePaint
                )
                continue
            }
            if (line.inlineImages.isNotEmpty()) {
                line.inlineImages.forEach { image ->
                    drawInlineImage(
                        canvas = canvas,
                        path = image.imagePath,
                        altText = image.altText,
                        destination = RectF(
                            image.left,
                            line.lineTop + image.topOffset,
                            image.left + image.width,
                            line.lineTop + image.topOffset + image.height
                        )
                    )
                }
                continue
            }
            val inlineImage = line.inlineImage
            if (inlineImage != null) {
                drawInlineImage(
                    canvas = canvas,
                    path = inlineImage.imagePath,
                    altText = inlineImage.altText,
                    destination = RectF(
                        line.startX,
                        line.lineTop,
                        line.startX + inlineImage.width,
                        line.lineTop + inlineImage.height
                    )
                )
                continue
            }
            line.inlineDecorations.forEach { decoration ->
                drawEpubDecoration(canvas, decoration)
            }
            line.inlineGlyphImages.forEach { image ->
                drawInlineImage(
                    canvas = canvas,
                    path = image.imagePath,
                    altText = image.altText,
                    destination = RectF(
                        image.left,
                        line.lineTop + image.topOffset,
                        image.left + image.width,
                        line.lineTop + image.topOffset + image.height
                    )
                )
            }
            val paint = if (line.isTitle) titlePaint else contentPaint
            line.rubyPlacements.forEach { ruby ->
                val rubyPaint = syntaxPaint(
                    base = paint,
                    color = ruby.colorArgb ?: pageStyle.textColor,
                    underline = false,
                    title = line.isTitle,
                    font = ReaderSyntaxFont.INHERIT,
                    fontAssetId = null,
                    bold = ruby.bold,
                    italic = ruby.italic,
                    strikethrough = false,
                    textSizeScale = ruby.textSizeScale,
                    fontFilePath = ruby.fontFilePath,
                    fontFamily = ruby.fontFamily,
                    opacity = ruby.opacity
                )
                val rubyX = ruby.left + (ruby.right - ruby.left - rubyPaint.measureText(ruby.text)) / 2f
                canvas.drawText(ruby.text, rubyX, ruby.baseline, rubyPaint)
            }
            for ((columnIndex, column) in line.columns.withIndex()) {
                if (column.inlineMarkerKind != null) continue
                val columnStart = geometry.startFor(lineIndex, columnIndex, column)
                val columnEnd = geometry.endFor(lineIndex, columnIndex, column)
                column.syntaxBackgroundArgb?.let { color ->
                    val resolvedColor = color.withOpacity(column.opacity)
                    val backgroundPaint = syntaxBackgroundPaints.getOrPut(resolvedColor) {
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = resolvedColor }
                    }
                    canvas.drawRect(
                        columnStart,
                        line.lineTop,
                        columnEnd,
                        line.lineBottom,
                        backgroundPaint
                    )
                }
                val resolvedPaint = if (
                    column.syntaxColorArgb != null ||
                    column.syntaxUnderline ||
                    column.linkHref != null ||
                    column.syntaxFont != ReaderSyntaxFont.INHERIT ||
                    column.syntaxBold ||
                    column.syntaxItalic ||
                    column.syntaxStrikethrough ||
                    column.textSizeScale != 1f ||
                    column.fontFilePath != null ||
                    column.fontFamily != null ||
                    column.opacity < 1f
                ) {
                    syntaxPaint(
                        base = paint,
                        color = if (column.linkHref != null) pageStyle.accentColor
                            else column.syntaxColorArgb ?: pageStyle.textColor,
                        underline = column.syntaxUnderline || column.linkHref != null,
                        title = line.isTitle,
                        font = column.syntaxFont,
                        fontAssetId = column.syntaxFontAssetId,
                        bold = column.syntaxBold,
                        italic = column.syntaxItalic,
                        strikethrough = column.syntaxStrikethrough,
                        textSizeScale = column.textSizeScale,
                        fontFilePath = column.fontFilePath,
                        fontFamily = column.fontFamily,
                        opacity = column.opacity
                    )
                } else {
                    paint
                }
                canvas.drawText(
                    column.charData,
                    columnStart,
                    line.lineBase + column.baselineShiftPx,
                    resolvedPaint
                )
            }
        }
        geometry.markers.forEach { marker ->
            if (marker.centerY + markerRadius < clipTop || marker.centerY - markerRadius > clipBottom) {
                return@forEach
            }
            when {
                marker.annotationIds.isNotEmpty() -> drawAnnotationMarker(
                    canvas,
                    marker.centerX,
                    marker.centerY,
                    markerRadius,
                    marker.annotationIds.size
                )
                marker.illustrationIds.isNotEmpty() -> drawIllustrationMarker(
                    canvas,
                    marker.centerX,
                    marker.centerY,
                    markerRadius
                )
            }
        }
    }

    private fun drawAnnotationMarker(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        count: Int
    ) {
        val bubble = RectF(
            centerX - radius * 0.78f,
            centerY - radius * 0.62f,
            centerX + radius * 0.78f,
            centerY + radius * 0.48f
        )
        canvas.drawRoundRect(bubble, radius * 0.28f, radius * 0.28f, annotationMarkerPaint)
        val tail = Path().apply {
            moveTo(centerX - radius * 0.22f, bubble.bottom)
            lineTo(centerX - radius * 0.34f, centerY + radius * 0.78f)
            lineTo(centerX + radius * 0.05f, bubble.bottom)
        }
        canvas.drawPath(tail, annotationMarkerPaint)
        if (count > 1) {
            canvas.drawText(
                count.toString(),
                centerX,
                centerY - (annotationMarkerTextPaint.ascent() + annotationMarkerTextPaint.descent()) / 2f,
                annotationMarkerTextPaint
            )
        } else {
            val dotRadius = radius * 0.075f
            val y = centerY - radius * 0.04f
            canvas.drawCircle(centerX - radius * 0.25f, y, dotRadius, illustrationGlyphPaint)
            canvas.drawCircle(centerX, y, dotRadius, illustrationGlyphPaint)
            canvas.drawCircle(centerX + radius * 0.25f, y, dotRadius, illustrationGlyphPaint)
        }
    }

    /** 小图片符号：圆角相框 + 山景与太阳，保持位图渲染器不依赖 Compose 图标。 */
    private fun drawIllustrationMarker(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawRoundRect(rect, radius * 0.28f, radius * 0.28f, illustrationMarkerPaint)
        val inset = radius * 0.26f
        val frame = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        canvas.drawRoundRect(frame, radius * 0.12f, radius * 0.12f, illustrationGlyphPaint)
        val mountain = Path().apply {
            moveTo(frame.left + radius * 0.12f, frame.bottom - radius * 0.12f)
            lineTo(frame.centerX() - radius * 0.08f, frame.centerY())
            lineTo(frame.centerX() + radius * 0.12f, frame.bottom - radius * 0.22f)
            lineTo(frame.right - radius * 0.08f, frame.bottom - radius * 0.08f)
        }
        canvas.drawPath(mountain, illustrationGlyphPaint)
        canvas.drawCircle(
            frame.right - radius * 0.28f,
            frame.top + radius * 0.28f,
            radius * 0.09f,
            illustrationGlyphPaint
        )
    }

    /** 按批注样式分笔画：荧光=填充矩形，直线=底沿实线，波浪=底沿正弦 Path。 */
    private fun drawAnnotationInk(
        canvas: Canvas,
        rect: com.mozhi.reader.feature.reader.engine.AnnotationHighlightRect,
        mark: ReaderAnnotationMark?
    ) {
        val style = mark?.style?.uppercase() ?: "HIGHLIGHT"
        val colorTag = mark?.colorTag.orEmpty()
        val stroke = (pageStyle.contentSizePx * 0.055f).coerceAtLeast(2.5f)
        when (style) {
            "UNDERLINE" -> {
                val paint = inkPaint("UNDERLINE", colorTag) {
                    color = AnnotationInk.lineColor(colorTag, pageStyle.isDark, pageStyle.accentColor)
                    strokeWidth = stroke
                    strokeCap = Paint.Cap.ROUND
                }
                val y = rect.bottom - stroke * 0.75f
                canvas.drawLine(rect.left + 1f, y, rect.right - 1f, y, paint)
            }
            "WAVY" -> {
                val paint = inkPaint("WAVY", colorTag) {
                    color = AnnotationInk.lineColor(colorTag, pageStyle.isDark, pageStyle.accentColor)
                    this.style = Paint.Style.STROKE
                    strokeWidth = stroke * 0.9f
                    strokeCap = Paint.Cap.ROUND
                }
                val amplitude = stroke * 1.1f
                val period = (pageStyle.contentSizePx * 0.42f).coerceAtLeast(8f)
                val baseY = rect.bottom - amplitude - stroke * 0.35f
                val width = rect.right - rect.left - 2f
                val segments = AnnotationInk.wavySegments(width, period)
                if (segments > 0) {
                    val path = Path()
                    val step = width / segments
                    path.moveTo(rect.left + 1f, baseY)
                    var up = true
                    var x = rect.left + 1f
                    repeat(segments) {
                        val controlY = if (up) baseY - amplitude else baseY + amplitude
                        path.quadTo(x + step / 2f, controlY, x + step, baseY)
                        x += step
                        up = !up
                    }
                    canvas.drawPath(path, paint)
                }
            }
            else -> {
                val paint = inkPaint("HIGHLIGHT", colorTag) {
                    color = AnnotationInk.highlightFillColor(
                        colorTag,
                        pageStyle.isDark,
                        pageStyle.accentColor
                    )
                }
                canvas.drawRoundRect(
                    RectF(rect.left, rect.top, rect.right, rect.bottom),
                    ANNOTATION_HIGHLIGHT_RADIUS,
                    ANNOTATION_HIGHLIGHT_RADIUS,
                    paint
                )
            }
        }
    }

    private inline fun inkPaint(style: String, colorTag: String, configure: Paint.() -> Unit): Paint =
        annotationInkPaints.getOrPut("$style|$colorTag") {
            Paint(Paint.ANTI_ALIAS_FLAG).apply(configure)
        }

    private fun syntaxPaint(
        base: TextPaint,
        color: Int,
        underline: Boolean,
        title: Boolean,
        font: ReaderSyntaxFont,
        fontAssetId: String?,
        bold: Boolean,
        italic: Boolean,
        strikethrough: Boolean,
        textSizeScale: Float,
        fontFilePath: String?,
        fontFamily: String?,
        opacity: Float
    ): TextPaint {
        val key = "$title|$color|$underline|$font|$fontAssetId|$bold|$italic|$strikethrough|" +
            "$textSizeScale|$fontFilePath|$fontFamily|$opacity"
        return syntaxPaints.getOrPut(key) {
            TextPaint(base).apply {
                this.color = color.withOpacity(opacity)
                textSize = base.textSize * textSizeScale
                isUnderlineText = underline
                isStrikeThruText = strikethrough
                val family = when (font) {
                    ReaderSyntaxFont.INHERIT -> fontFilePath
                        ?.let { path ->
                            embeddedTypefaces.getOrPut(path) {
                                runCatching { Typeface.createFromFile(File(path)) }.getOrNull()
                            }
                        }
                        ?: fontFamily.toSystemTypeface()
                        ?: base.typeface
                    ReaderSyntaxFont.SYSTEM -> Typeface.DEFAULT
                    ReaderSyntaxFont.SERIF -> Typeface.SERIF
                    ReaderSyntaxFont.SANS_SERIF -> Typeface.SANS_SERIF
                    ReaderSyntaxFont.MONOSPACE -> Typeface.MONOSPACE
                    ReaderSyntaxFont.CUSTOM -> (fontAssetId
                        ?.let(pageStyle.customFontPaths::get) ?: pageStyle.customFontPath)
                        ?.let { path -> runCatching { Typeface.createFromFile(path) }.getOrNull() }
                        ?: base.typeface
                }
                val style = (if (bold) Typeface.BOLD else Typeface.NORMAL) or
                    (if (italic) Typeface.ITALIC else Typeface.NORMAL)
                typeface = if (style == Typeface.NORMAL) family else Typeface.create(family, style)
            }
        }
    }

    private fun drawEpubPageBackground(
        canvas: Canvas,
        page: RenderPage.Laid,
        clipTop: Float,
        clipBottom: Float
    ) {
        if (page.page.backgroundColorArgb == null && page.page.backgroundImagePath == null) return
        val destination = RectF(0f, 0f, pageStyle.contentWidth, pageStyle.contentHeight)
        if (destination.bottom < clipTop || destination.top > clipBottom) return
        page.page.backgroundColorArgb?.let { color ->
            epubDecorationPaint.style = Paint.Style.FILL
            epubDecorationPaint.color = color.withOpacity(page.page.backgroundOpacity)
            canvas.drawRect(destination, epubDecorationPaint)
        }
        page.page.backgroundImagePath?.let { path ->
            drawCoverImage(canvas, path, destination, page.page.backgroundOpacity)
        }
    }

    private fun drawEpubDecorations(
        canvas: Canvas,
        page: RenderPage.Laid,
        clipTop: Float,
        clipBottom: Float
    ) {
        page.page.decorations.forEach { decoration ->
            if (decoration.bottom < clipTop || decoration.top > clipBottom) return@forEach
            drawEpubDecoration(canvas, decoration)
        }
    }

    private fun drawEpubDecoration(canvas: Canvas, decoration: TextBlockDecoration) {
        val rect = RectF(decoration.left, decoration.top, decoration.right, decoration.bottom)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val shape = decorationPath(decoration, rect)
        drawDecorationShadows(canvas, decoration, rect, shape, inset = false)
        decoration.backgroundColorArgb?.let { color ->
            epubDecorationPaint.style = Paint.Style.FILL
            epubDecorationPaint.color = color.withOpacity(decoration.opacity)
            canvas.drawPath(shape, epubDecorationPaint)
        }
        decoration.backgroundImagePath?.let { path ->
            drawDecorationBackgroundImage(canvas, path, rect, decoration, shape)
        }
        drawDecorationShadows(canvas, decoration, rect, shape, inset = true)
        drawDecorationBorders(canvas, decoration, rect, shape)
    }

    private fun drawDecorationShadows(
        canvas: Canvas,
        decoration: TextBlockDecoration,
        rect: RectF,
        shape: Path,
        inset: Boolean
    ) {
        decoration.boxShadows.asReversed().filter { it.inset == inset }.forEach { shadow ->
            epubShadowPaint.color = shadow.colorArgb.withOpacity(decoration.opacity)
            epubShadowPaint.maskFilter = shadow.blurRadius.takeIf { it > 0f }
                ?.let { BlurMaskFilter(it, BlurMaskFilter.Blur.NORMAL) }
            if (inset) {
                epubShadowPaint.style = Paint.Style.STROKE
                epubShadowPaint.strokeWidth = (
                    shadow.blurRadius * 2f + shadow.spreadRadius * 2f
                    ).coerceAtLeast(1f)
                canvas.save()
                canvas.clipPath(shape)
                canvas.translate(shadow.offsetX, shadow.offsetY)
                canvas.drawPath(shape, epubShadowPaint)
                canvas.restore()
            } else {
                val shadowRect = RectF(rect).apply {
                    inset(-shadow.spreadRadius, -shadow.spreadRadius)
                    offset(shadow.offsetX, shadow.offsetY)
                }
                if (shadowRect.width() > 0f && shadowRect.height() > 0f) {
                    val shadowDecoration = decoration.copy(
                        borderRadius = (decoration.borderRadius + shadow.spreadRadius).coerceAtLeast(0f),
                        borderTopLeftRadius = (decoration.borderTopLeftRadius + shadow.spreadRadius).coerceAtLeast(0f),
                        borderTopRightRadius = (decoration.borderTopRightRadius + shadow.spreadRadius).coerceAtLeast(0f),
                        borderBottomRightRadius = (decoration.borderBottomRightRadius + shadow.spreadRadius).coerceAtLeast(0f),
                        borderBottomLeftRadius = (decoration.borderBottomLeftRadius + shadow.spreadRadius).coerceAtLeast(0f)
                    )
                    epubShadowPaint.style = Paint.Style.FILL
                    canvas.save()
                    canvas.clipOutPath(shape)
                    canvas.drawPath(decorationPath(shadowDecoration, shadowRect), epubShadowPaint)
                    canvas.restore()
                }
            }
            epubShadowPaint.maskFilter = null
        }
    }

    private fun decorationPath(decoration: TextBlockDecoration, rect: RectF): Path {
        val topLeftRadius = if (decoration.drawTopEdge && decoration.drawLeftEdge) {
            decoration.borderTopLeftRadius
        } else {
            0f
        }
        val topRightRadius = if (decoration.drawTopEdge && decoration.drawRightEdge) {
            decoration.borderTopRightRadius
        } else {
            0f
        }
        val bottomRightRadius = if (decoration.drawBottomEdge && decoration.drawRightEdge) {
            decoration.borderBottomRightRadius
        } else {
            0f
        }
        val bottomLeftRadius = if (decoration.drawBottomEdge && decoration.drawLeftEdge) {
            decoration.borderBottomLeftRadius
        } else {
            0f
        }
        return Path().apply {
            addRoundRect(
                rect,
                floatArrayOf(
                    topLeftRadius, topLeftRadius,
                    topRightRadius, topRightRadius,
                    bottomRightRadius, bottomRightRadius,
                    bottomLeftRadius, bottomLeftRadius
                ),
                Path.Direction.CW
            )
        }
    }

    private fun drawDecorationBorders(
        canvas: Canvas,
        decoration: TextBlockDecoration,
        rect: RectF,
        shape: Path
    ) {
        val fallbackColor = decoration.borderColorArgb ?: pageStyle.textColor
        val widths = floatArrayOf(
            decoration.borderTopWidth,
            decoration.borderRightWidth,
            decoration.borderBottomWidth,
            decoration.borderLeftWidth
        )
        val colors = intArrayOf(
            decoration.borderTopColorArgb ?: fallbackColor,
            decoration.borderRightColorArgb ?: fallbackColor,
            decoration.borderBottomColorArgb ?: fallbackColor,
            decoration.borderLeftColorArgb ?: fallbackColor
        )
        val uniform = decoration.drawTopEdge && decoration.drawRightEdge &&
            decoration.drawBottomEdge && decoration.drawLeftEdge &&
            widths.all { kotlin.math.abs(it - widths[0]) < 0.01f } && colors.all { it == colors[0] }
        if (uniform && widths[0] > 0f) {
            epubBorderPaint.strokeWidth = widths[0]
            epubBorderPaint.color = colors[0].withOpacity(decoration.opacity)
            canvas.drawPath(shape, epubBorderPaint)
            return
        }

        val enabled = booleanArrayOf(
            decoration.drawTopEdge,
            decoration.drawRightEdge,
            decoration.drawBottomEdge,
            decoration.drawLeftEdge
        )
        val topRadius = maxOf(decoration.borderTopLeftRadius, decoration.borderTopRightRadius)
        val rightRadius = maxOf(decoration.borderTopRightRadius, decoration.borderBottomRightRadius)
        val bottomRadius = maxOf(decoration.borderBottomLeftRadius, decoration.borderBottomRightRadius)
        val leftRadius = maxOf(decoration.borderTopLeftRadius, decoration.borderBottomLeftRadius)
        val maxWidth = widths.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val clips = arrayOf(
            RectF(rect.left - maxWidth, rect.top - maxWidth, rect.right + maxWidth, rect.top + topRadius + maxWidth),
            RectF(rect.right - rightRadius - maxWidth, rect.top - maxWidth, rect.right + maxWidth, rect.bottom + maxWidth),
            RectF(rect.left - maxWidth, rect.bottom - bottomRadius - maxWidth, rect.right + maxWidth, rect.bottom + maxWidth),
            RectF(rect.left - maxWidth, rect.top - maxWidth, rect.left + leftRadius + maxWidth, rect.bottom + maxWidth)
        )
        // 非对称边框也沿同一条圆角轮廓绘制，再按四边裁切。旧实现只画四条直线，
        // 遇到 border-left/right 不同色或不同宽时会丢掉圆角弧线，看起来像完全方框。
        widths.indices.forEach { side ->
            if (!enabled[side] || widths[side] <= 0f) return@forEach
            epubBorderPaint.strokeWidth = widths[side]
            epubBorderPaint.color = colors[side].withOpacity(decoration.opacity)
            canvas.save()
            canvas.clipRect(clips[side])
            canvas.drawPath(shape, epubBorderPaint)
            canvas.restore()
        }
    }

    private fun drawDecorationBackgroundImage(
        canvas: Canvas,
        path: String,
        destination: RectF,
        decoration: TextBlockDecoration,
        clipPath: Path
    ) {
        if (decoration.backgroundSizeMode == BackgroundSizeMode.COVER &&
            !decoration.backgroundRepeatX && !decoration.backgroundRepeatY
        ) {
            drawCoverImage(canvas, path, destination, decoration.opacity, clipPath)
            return
        }
        val bitmap = loadImage(path, destination.width().toInt(), destination.height().toInt()) ?: return
        val intrinsicW = bitmap.width.toFloat().coerceAtLeast(1f)
        val intrinsicH = bitmap.height.toFloat().coerceAtLeast(1f)
        val (drawW, drawH) = when (decoration.backgroundSizeMode) {
            BackgroundSizeMode.STRETCH -> destination.width() to destination.height()
            BackgroundSizeMode.EXPLICIT ->
                decoration.backgroundSizeWidth.coerceAtLeast(1f) to decoration.backgroundSizeHeight.coerceAtLeast(1f)
            BackgroundSizeMode.CONTAIN -> {
                val scale = minOf(destination.width() / intrinsicW, destination.height() / intrinsicH)
                intrinsicW * scale to intrinsicH * scale
            }
            BackgroundSizeMode.COVER -> {
                val scale = maxOf(destination.width() / intrinsicW, destination.height() / intrinsicH)
                intrinsicW * scale to intrinsicH * scale
            }
            BackgroundSizeMode.AUTO -> intrinsicW to intrinsicH
        }
        val left = destination.left + (destination.width() - drawW) * decoration.backgroundPositionX.coerceIn(0f, 1f)
        val top = destination.top + (destination.height() - drawH) * decoration.backgroundPositionY.coerceIn(0f, 1f)
        canvas.save()
        canvas.clipPath(clipPath)
        imagePaint.alpha = (decoration.opacity.coerceIn(0f, 1f) * 255f).toInt()
        if (decoration.backgroundRepeatX || decoration.backgroundRepeatY) {
            val shader = BitmapShader(
                bitmap,
                if (decoration.backgroundRepeatX) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP,
                if (decoration.backgroundRepeatY) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP
            )
            shader.setLocalMatrix(Matrix().apply {
                setScale(drawW / intrinsicW, drawH / intrinsicH)
                postTranslate(left, top)
            })
            imagePaint.shader = shader
            canvas.drawRect(destination, imagePaint)
            imagePaint.shader = null
        } else {
            canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), imagePaint)
        }
        imagePaint.alpha = 255
        canvas.restore()
    }

    private fun drawCoverImage(
        canvas: Canvas,
        path: String,
        destination: RectF,
        opacity: Float,
        clipPath: Path? = null
    ) {
        val bitmap = loadImage(path, destination.width().toInt(), destination.height().toInt()) ?: return
        val sourceAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        val destinationAspect = destination.width() / destination.height().coerceAtLeast(1f)
        val source = if (sourceAspect > destinationAspect) {
            val width = (bitmap.height * destinationAspect).toInt().coerceAtLeast(1)
            val left = (bitmap.width - width) / 2
            Rect(left, 0, left + width, bitmap.height)
        } else {
            val height = (bitmap.width / destinationAspect).toInt().coerceAtLeast(1)
            val top = (bitmap.height - height) / 2
            Rect(0, top, bitmap.width, top + height)
        }
        canvas.save()
        if (clipPath == null) canvas.clipRect(destination) else canvas.clipPath(clipPath)
        imagePaint.alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawBitmap(bitmap, source, destination, imagePaint)
        imagePaint.alpha = 255
        canvas.restore()
    }

    private fun drawInlineImage(
        canvas: Canvas,
        path: String,
        altText: String,
        destination: RectF
    ) {
        val bitmap = loadImage(path, destination.width().toInt(), destination.height().toInt())
        if (bitmap != null) {
            canvas.save()
            canvas.clipPath(Path().apply {
                addRoundRect(
                    destination,
                    IMAGE_CORNER_RADIUS,
                    IMAGE_CORNER_RADIUS,
                    Path.Direction.CW
                )
            })
            canvas.drawBitmap(bitmap, null, destination, imagePaint)
            canvas.restore()
            return
        }
        canvas.drawRoundRect(destination, IMAGE_CORNER_RADIUS, IMAGE_CORNER_RADIUS, imageFramePaint)
        val label = altText.ifBlank { "图片" }
        val display = ellipsize("［$label］", placeholderPaint, destination.width() * 0.86f)
        canvas.drawText(
            display,
            destination.centerX(),
            destination.centerY() - (placeholderPaint.ascent() + placeholderPaint.descent()) / 2f,
            placeholderPaint
        )
    }

    private fun loadImage(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val cacheKey = if (path.endsWith(".svg", true)) {
            "$path|${targetWidth.coerceAtLeast(1)}x${targetHeight.coerceAtLeast(1)}"
        } else {
            path
        }
        imageCache.get(cacheKey)?.takeIf { !it.isRecycled }?.let { return it }
        val bitmap = decodeImage(path, targetWidth, targetHeight) ?: return null
        imageCache.put(cacheKey, bitmap)
        return bitmap
    }

    /** 普通正文插图按目标尺寸采样，缓存由 renderer 生命周期托管。 */
    private fun decodeImage(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        if (path.endsWith(".svg", true)) return decodeSvg(path, targetWidth, targetHeight)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val readBounds = runCatching {
            BitmapFactory.decodeFile(path, bounds)
            true
        }.getOrDefault(false)
        if (!readBounds || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return decodeSvg(path, targetWidth, targetHeight)
        }
        var sample = 1
        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        while (bounds.outWidth / (sample * 2) >= safeWidth &&
            bounds.outHeight / (sample * 2) >= safeHeight
        ) {
            sample *= 2
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            )
        }.getOrNull() ?: return null
        return bitmap
    }

    private fun decodeSvg(path: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val svg = runCatching {
            File(path).inputStream().buffered().use { input -> SVG.getFromInputStream(input) }
        }.getOrNull() ?: return null
        val viewBox = svg.documentViewBox
        val intrinsicWidth = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
            ?: viewBox?.width()?.takeIf { it.isFinite() && it > 0f }
            ?: targetWidth.coerceAtLeast(1).toFloat()
        val intrinsicHeight = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
            ?: viewBox?.height()?.takeIf { it.isFinite() && it > 0f }
            ?: targetHeight.coerceAtLeast(1).toFloat()
        val requestedScale = kotlin.math.max(
            targetWidth.coerceAtLeast(1) / intrinsicWidth,
            targetHeight.coerceAtLeast(1) / intrinsicHeight
        )
        val dimensionScale = kotlin.math.min(
            MAX_SVG_DIMENSION / intrinsicWidth,
            MAX_SVG_DIMENSION / intrinsicHeight
        )
        val scale = kotlin.math.min(requestedScale, dimensionScale).coerceAtLeast(MIN_SVG_SCALE)
        val pixelWidth = (intrinsicWidth * scale).toInt().coerceIn(1, MAX_SVG_DIMENSION)
        val pixelHeight = (intrinsicHeight * scale).toInt().coerceIn(1, MAX_SVG_DIMENSION)
        val bitmap = runCatching {
            Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        return runCatching {
            svg.setDocumentWidth(pixelWidth.toFloat())
            svg.setDocumentHeight(pixelHeight.toFloat())
            svg.renderToCanvas(Canvas(bitmap))
            bitmap
        }.getOrElse {
            bitmap.recycle()
            null
        }
    }

    fun release() {
        imageCache.evictAll()
        backgroundProvider.release()
    }

    private fun drawPlaceholder(canvas: Canvas, page: RenderPage.Placeholder) {
        val centerX = pageStyle.viewWidth / 2f
        val centerY = pageStyle.contentTop + pageStyle.contentHeight / 2f
        if (page.chapterTitle.isNotBlank()) {
            val titleText = ellipsize(page.chapterTitle, placeholderPaint, pageStyle.contentWidth)
            canvas.drawText(titleText, centerX, centerY - pageStyle.lineStep, placeholderPaint)
        }
        canvas.drawText(page.message, centerX, centerY + pageStyle.lineStep * 0.5f, placeholderPaint)
    }

    /** 滚动模式的未排版邻章占位块：在条带上画一屏高的「章名 + 加载中」。 */
    internal fun drawScrollPlaceholder(
        canvas: Canvas,
        title: String,
        message: String,
        top: Float,
        height: Float
    ) {
        val centerX = pageStyle.viewWidth / 2f
        val centerY = top + height / 2f
        if (title.isNotBlank()) {
            val titleText = ellipsize(title, placeholderPaint, pageStyle.contentWidth)
            canvas.drawText(titleText, centerX, centerY - pageStyle.lineStep, placeholderPaint)
        }
        canvas.drawText(message, centerX, centerY + pageStyle.lineStep * 0.5f, placeholderPaint)
    }

    internal fun drawFooter(
        canvas: Canvas,
        page: RenderPage,
        bookProgress: Float,
        timeText: String,
        batteryPercent: Int
    ) {
        if (!pageStyle.showFooter || (page as? RenderPage.Laid)?.page?.immersive == true) return
        val baseline = pageStyle.footerBaseline
        val pageText = "${page.pageIndex + 1} / ${page.pageCount} 页"
        canvas.drawText(pageText, pageStyle.paddingLeft, baseline, tipPaint)

        val progressText = String.format(Locale.ROOT, "%.1f%%", bookProgress * 100)
        val rightText = "$progressText · $timeText"
        val batteryWidth = pageStyle.tipSizePx * 1.7f
        val textWidth = tipPaint.measureText(rightText)
        val rightEdge = pageStyle.viewWidth - pageStyle.paddingRight
        canvas.drawText(rightText, rightEdge - batteryWidth - textWidth, baseline, tipPaint)
        drawBattery(
            canvas,
            left = rightEdge - batteryWidth + pageStyle.tipSizePx * 0.35f,
            centerY = baseline - pageStyle.tipSizePx * 0.32f,
            percent = batteryPercent
        )
    }

    private fun drawBattery(canvas: Canvas, left: Float, centerY: Float, percent: Int) {
        val width = pageStyle.tipSizePx * 1.25f
        val height = pageStyle.tipSizePx * 0.62f
        val body = RectF(left, centerY - height / 2f, left + width, centerY + height / 2f)
        val radius = height * 0.22f
        canvas.drawRoundRect(body, radius, radius, batteryPaint)
        val capWidth = pageStyle.tipSizePx * 0.16f
        canvas.drawRect(
            body.right + 1f,
            centerY - height * 0.22f,
            body.right + 1f + capWidth,
            centerY + height * 0.22f,
            batteryFillPaint
        )
        val inset = 2f
        val level = (percent.coerceIn(0, 100) / 100f)
        canvas.drawRect(
            body.left + inset,
            body.top + inset,
            body.left + inset + (body.width() - inset * 2) * level,
            body.bottom - inset,
            batteryFillPaint
        )
    }

    private companion object {
        const val IMAGE_CACHE_KB = 32 * 1024
        const val IMAGE_CORNER_RADIUS = 12f
        const val MAX_SVG_DIMENSION = 4096
        const val MIN_SVG_SCALE = 0.01f
        const val ANNOTATION_HIGHLIGHT_RADIUS = 4f
        const val LISTEN_HIGHLIGHT_ID = -1L
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}

private fun Int.withOpacity(opacity: Float): Int {
    val alpha = (Color.alpha(this) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    return Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
}

private fun String?.toSystemTypeface(): Typeface? = when {
    this == null -> null
    contains("mono", true) -> Typeface.MONOSPACE
    contains("sans", true) || contains("黑体") -> Typeface.SANS_SERIF
    contains("serif", true) || contains("宋体") || contains("明朝") -> Typeface.SERIF
    else -> null
}
