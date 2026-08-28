package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ReaderSyntaxFont

/**
 * Layout entities for the self-drawn text engine, ported from Legado's
 * `ui/book/read/page/entities`. Every coordinate is computed once at layout time in content-local
 * pixels — x in `0..visibleWidth`, y in `0..visibleHeight` — so drawing never measures. The painter
 * translates by the content origin (page padding plus header band) when it renders a page.
 *
 * Offsets are UTF-16 code-unit indices into the chapter body exactly as stored in `text.mz`;
 * synthetic lines (a title drawn from chapter metadata rather than body text) carry
 * [TextLine.charLength] = 0 so the mapping between characters and pages stays intact.
 */
class TextColumn(
    val start: Float,
    val end: Float,
    val charData: String,
    val syntaxColorArgb: Int? = null,
    val syntaxBackgroundArgb: Int? = null,
    val syntaxUnderline: Boolean = false,
    val syntaxFont: ReaderSyntaxFont = ReaderSyntaxFont.INHERIT,
    val syntaxFontAssetId: String? = null,
    val syntaxBold: Boolean = false,
    val syntaxItalic: Boolean = false,
    val syntaxStrikethrough: Boolean = false,
    val textSizeScale: Float = 1f,
    val fontFilePath: String? = null,
    val fontFamily: String? = null,
    val baselineShiftPx: Float = 0f,
    val opacity: Float = 1f,
    val sourceLength: Int = charData.length,
    val inlineMarkerKind: InlineMarkerKind? = null,
    val inlineMarkerOffset: Int? = null,
    /** EPUB 内部超链接；保留原 href（含 fragment），点击时再解析到章节坐标。 */
    val linkHref: String? = null
)

enum class InlineMarkerKind { ANNOTATION, ILLUSTRATION }

data class InlineMarkerReservation(
    val charOffset: Int,
    val kind: InlineMarkerKind
)

/** Chapter-level source metadata loaded from the EPUB media sidecar. */
data class InlineImageSource(
    val charOffset: Int,
    val imagePath: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val altText: String
)

/** Sized image block attached to a laid-out line; coordinates remain content-local pixels. */
data class InlineImagePlacement(
    val imagePath: String,
    val width: Float,
    val height: Float,
    val altText: String
)

enum class BackgroundSizeMode { AUTO, COVER, CONTAIN, STRETCH, EXPLICIT }

data class PositionedInlineImagePlacement(
    val imagePath: String,
    val left: Float,
    val topOffset: Float = 0f,
    val width: Float,
    val height: Float,
    val altText: String
)

data class TextBlockDecoration(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val backgroundColorArgb: Int? = null,
    val backgroundImagePath: String? = null,
    val backgroundSizeMode: BackgroundSizeMode = BackgroundSizeMode.COVER,
    val backgroundSizeWidth: Float = 0f,
    val backgroundSizeHeight: Float = 0f,
    val backgroundRepeatX: Boolean = false,
    val backgroundRepeatY: Boolean = false,
    val backgroundPositionX: Float = 0.5f,
    val backgroundPositionY: Float = 0.5f,
    val borderColorArgb: Int? = null,
    val borderWidth: Float = 0f,
    val borderTopColorArgb: Int? = null,
    val borderRightColorArgb: Int? = null,
    val borderBottomColorArgb: Int? = null,
    val borderLeftColorArgb: Int? = null,
    val borderTopWidth: Float = borderWidth,
    val borderRightWidth: Float = borderWidth,
    val borderBottomWidth: Float = borderWidth,
    val borderLeftWidth: Float = borderWidth,
    val borderRadius: Float = 0f,
    val borderTopLeftRadius: Float = borderRadius,
    val borderTopRightRadius: Float = borderRadius,
    val borderBottomRightRadius: Float = borderRadius,
    val borderBottomLeftRadius: Float = borderRadius,
    val boxShadows: List<TextBoxShadow> = emptyList(),
    val opacity: Float = 1f,
    val drawTopEdge: Boolean = true,
    val drawRightEdge: Boolean = true,
    val drawBottomEdge: Boolean = true,
    val drawLeftEdge: Boolean = true
)

data class TextBoxShadow(
    val offsetX: Float,
    val offsetY: Float,
    val blurRadius: Float,
    val spreadRadius: Float,
    val colorArgb: Int,
    val inset: Boolean
)

data class TextRubyPlacement(
    val text: String,
    val left: Float,
    val right: Float,
    val baseline: Float,
    val textSizeScale: Float,
    val fontFilePath: String? = null,
    val fontFamily: String? = null,
    val colorArgb: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val opacity: Float = 1f
)

data class TextRulePlacement(
    val width: Float,
    val height: Float,
    val colorArgb: Int
)

class TextLine(
    val text: String,
    val columns: List<TextColumn>,
    var lineTop: Float,
    var lineBase: Float,
    var lineBottom: Float,
    val startX: Float,
    val isTitle: Boolean,
    val isParagraphEnd: Boolean,
    /** Offset of the first body character this line covers. */
    val chapterPosition: Int,
    /** Body characters covered, 0 for synthetic title lines. */
    val charLength: Int,
    /** Extra width distributed to every cluster gap by justification, for diagnostics/tests. */
    val justifyGapExtra: Float = 0f,
    /** Non-null when the source paragraph token is replaced by an EPUB image block. */
    val inlineImage: InlineImagePlacement? = null,
    /** flex/grid 图片组，同一行可包含多张图片。 */
    val inlineImages: List<PositionedInlineImagePlacement> = emptyList(),
    /** Replaced elements participating in the inline formatting context. */
    val inlineGlyphImages: List<PositionedInlineImagePlacement> = emptyList(),
    /** Synthetic horizontal separator; it carries no text offset. */
    val rule: TextRulePlacement? = null,
    /** CSS inline boxes such as badges, danmaku pills, and chat labels. */
    var inlineDecorations: List<TextBlockDecoration> = emptyList(),
    /** Ruby annotations drawn in a compact baseline above their source text. */
    var rubyPlacements: List<TextRubyPlacement> = emptyList()
)

class TextPage(
    val index: Int,
    val lines: List<TextLine>,
    /** Body offset of the first body character on this page. */
    val chapterPosition: Int,
    val charLength: Int,
    val height: Float,
    val decorations: List<TextBlockDecoration> = emptyList(),
    val backgroundColorArgb: Int? = null,
    val backgroundImagePath: String? = null,
    val backgroundOpacity: Float = 1f,
    /** 特殊封面/卷首页隐藏阅读器页眉页脚。 */
    val immersive: Boolean = false,
    /** 正文自带章标题时只隐藏阅读器页眉，并回收顶部标题栏空间。 */
    val hideHeader: Boolean = false,
    /**
     * 本页最后一行之后已经应用、但被页切吞掉的纵向间隙（段距，或空行贡献的更大间隙）。
     * 滚动模式拼接条带时必须补回来，否则接缝会比页内段距紧。排版器写入，绘制不读。
     */
    val trailingGap: Float = 0f
)

/** A fully laid out chapter. Layout is atomic: once published, all pages exist. */
class TextChapter(
    val chapterIndex: Int,
    val title: String,
    val pages: List<TextPage>,
    val bodyLength: Int
) {
    val pageCount: Int get() = pages.size

    fun page(index: Int): TextPage? = pages.getOrNull(index)

    val lastPage: TextPage? get() = pages.lastOrNull()

    fun isLastPage(index: Int): Boolean = index >= pages.size - 1

    /** Body offset of the given page's first character, clamped to valid pages. */
    fun pageStartOffset(pageIndex: Int): Int =
        pages.getOrNull(pageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0)))
            ?.chapterPosition ?: 0

    /**
     * The page containing [charOffset]: last page whose start is <= offset, except that among
     * pages with equal starts the first wins. Equal starts happen when a synthetic title (zero
     * body length) fills a whole page; picking the first page makes opening a chapter land on the
     * title page. Navigation across an equal-start group cannot rely on this lookup at all — the
     * controller keeps an explicit page index for that.
     */
    fun pageIndexAt(charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        var low = 0
        var high = pages.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (pages[mid].chapterPosition <= charOffset) low = mid else high = mid - 1
        }
        while (low > 0 && pages[low - 1].chapterPosition == pages[low].chapterPosition) {
            low--
        }
        return low
    }
}
