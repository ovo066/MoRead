package com.mozhi.reader.core.epub.style

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.epub.css.CssCascade
import com.mozhi.reader.core.epub.css.CssColor
import com.mozhi.reader.core.epub.css.CssDeclaration
import com.mozhi.reader.core.epub.css.CssElementNode
import com.mozhi.reader.core.epub.css.CssParser
import com.mozhi.reader.core.epub.css.CssRule
import com.mozhi.reader.core.epub.css.CssRuleIndex
import com.mozhi.reader.core.epub.css.CssUnit
import com.mozhi.reader.core.epub.css.CssValue
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubStylesheetText

sealed interface ResolvedLength {
    data class Px(val value: Float) : ResolvedLength
    data class Percent(val value: Float) : ResolvedLength
    data object Auto : ResolvedLength
}

fun ResolvedLength.resolve(percentBase: Float): Float? = when (this) {
    is ResolvedLength.Px -> value
    is ResolvedLength.Percent -> value * percentBase / 100f
    ResolvedLength.Auto -> null
}

enum class EpubDisplay { BLOCK, INLINE, INLINE_BLOCK, FLEX, GRID, TABLE, TABLE_ROW, TABLE_CELL, LIST_ITEM, NONE }
enum class EpubFloatValue { NONE, LEFT, RIGHT }
enum class EpubClearValue { NONE, LEFT, RIGHT, BOTH }
enum class EpubTextAlignValue { START, CENTER, END, JUSTIFY }

sealed interface EpubVerticalAlignment {
    data object Baseline : EpubVerticalAlignment
    data object Sub : EpubVerticalAlignment
    data object Super : EpubVerticalAlignment
    data object Middle : EpubVerticalAlignment
    data object TextTop : EpubVerticalAlignment
    data object TextBottom : EpubVerticalAlignment
    data class Shift(val px: Float) : EpubVerticalAlignment
}

data class EpubBackgroundStyle(
    val colorArgb: Int? = null,
    val imageHref: String? = null,
    val sizeMode: String = "auto",
    val size: List<ResolvedLength> = emptyList(),
    /** 0..1 fractions along each axis, defaulting to the CSS initial 0%/0%. */
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val repeat: String = "repeat"
)

data class EpubShadow(
    val offsetXPx: Float,
    val offsetYPx: Float,
    val blurPx: Float,
    val spreadPx: Float,
    val colorArgb: Int,
    val inset: Boolean
)

data class EpubStyle(
    val fontSizePx: Float,
    /** Properties actually supplied by CSS/UA or inherited from such a declaration. */
    val appliedProperties: Set<String> = emptySet(),
    val fontFamilies: List<String> = emptyList(),
    val fontWeight: Int = 400,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val letterSpacingPx: Float = 0f,
    val colorArgb: Int,
    val lineHeight: Float? = null,
    val lineHeightNumber: Float? = null,
    val display: EpubDisplay = EpubDisplay.INLINE,
    val float: EpubFloatValue = EpubFloatValue.NONE,
    val clear: EpubClearValue = EpubClearValue.NONE,
    val textAlign: EpubTextAlignValue = EpubTextAlignValue.START,
    val verticalAlign: EpubVerticalAlignment = EpubVerticalAlignment.Baseline,
    val marginTop: ResolvedLength = ResolvedLength.Px(0f),
    val marginRight: ResolvedLength = ResolvedLength.Px(0f),
    val marginBottom: ResolvedLength = ResolvedLength.Px(0f),
    val marginLeft: ResolvedLength = ResolvedLength.Px(0f),
    val paddingTop: ResolvedLength = ResolvedLength.Px(0f),
    val paddingRight: ResolvedLength = ResolvedLength.Px(0f),
    val paddingBottom: ResolvedLength = ResolvedLength.Px(0f),
    val paddingLeft: ResolvedLength = ResolvedLength.Px(0f),
    val width: ResolvedLength = ResolvedLength.Auto,
    val height: ResolvedLength = ResolvedLength.Auto,
    val minWidth: ResolvedLength = ResolvedLength.Auto,
    val minHeight: ResolvedLength = ResolvedLength.Auto,
    val maxWidth: ResolvedLength = ResolvedLength.Auto,
    val maxHeight: ResolvedLength = ResolvedLength.Auto,
    val boxSizingBorderBox: Boolean = false,
    val textIndent: ResolvedLength = ResolvedLength.Px(0f),
    val background: EpubBackgroundStyle = EpubBackgroundStyle(),
    /** Effective border widths in px (style:none already collapsed to 0), order top/right/bottom/left. */
    val borderWidths: List<Float> = ZERO_SIDES,
    val borderColors: List<Int?> = List(4) { null },
    /** Corner order: top-left, top-right, bottom-right, bottom-left. */
    val borderRadii: List<ResolvedLength> = List(4) { ResolvedLength.Px(0f) },
    val boxShadows: List<EpubShadow> = emptyList(),
    val opacity: Float = 1f,
    val breakBefore: Boolean = false,
    val breakAfter: Boolean = false,
    val breakInsideAvoid: Boolean = false,
    val breakAfterAvoid: Boolean = false,
    val orphans: Int = 2,
    val widows: Int = 2
) {
    fun hasBorder(): Boolean = borderWidths.any { it > 0f }

    fun hasDecoration(): Boolean =
        background.colorArgb != null || background.imageHref != null || hasBorder() || boxShadows.isNotEmpty()

    private companion object {
        val ZERO_SIDES = listOf(0f, 0f, 0f, 0f)
    }
}

data class StyledDomNode(
    val node: EpubDomNode,
    val style: EpubStyle,
    val children: List<StyledDomNode>
)

class EpubStyleResolver(
    stylesheets: List<EpubStylesheetText>,
    private val viewportWidthPx: Float,
    private val viewportHeightPx: Float,
    private val rootFontSizePx: Float,
    private val themeTextArgb: Int,
    publisherStyleMode: PublisherStyleMode = PublisherStyleMode.RESPECT,
    preParsedPublisherRules: List<CssRule>? = null,
    documentHref: String = "inline"
) {
    // style="...url(...)" is declared by the XHTML document, not by a synthetic "inline" file.
    // Resolve relative resources against the chapter href just like a browser does.
    private val parserForInline = CssParser(documentHref)
    private val takeOver = publisherStyleMode == PublisherStyleMode.TAKE_OVER
    private val index: CssRuleIndex

    /** CSS px 以 16px 根字号为基准换算，跟随用户正文字号缩放（沿用 v9 的既有语义）。 */
    private val pxScale = rootFontSizePx / CSS_ROOT_FONT_PX

    init {
        val ua = CssParser("ua.css", -10_000).parse(UA_STYLES).stylesheet.rules
        var order = 0
        val publisher = preParsedPublisherRules ?: stylesheets.flatMap { sheet ->
            val parsed = CssParser(sheet.href, order).parse(sheet.css).stylesheet.rules
            order += parsed.size
            parsed
        }
        // 接管模式仍保留出版结构（display/float/宽高/分页），只丢外观声明；
        // 否则聊天气泡、表格等布局骨架会在接管模式下散架。
        val effective = if (takeOver) {
            publisher.mapNotNull { rule ->
                val structural = rule.declarations.filter { it.property in STRUCTURAL_PROPERTIES }
                if (structural.isEmpty()) null else rule.copy(declarations = structural)
            }
        } else {
            publisher
        }
        index = CssRuleIndex(ua + effective)
    }

    fun resolve(body: EpubDomNode): StyledDomNode {
        val root = NodeView(body, null)
        return resolveNode(root, null, rootFontSizePx)
    }

    private fun resolveNode(view: NodeView, parent: EpubStyle?, rootSize: Float): StyledDomNode {
        val inline = view.node.attributes["style"]
            ?.let(parserForInline::parseDeclarations)
            .orEmpty()
            .let { declarations ->
                if (takeOver) declarations.filter { it.property in STRUCTURAL_PROPERTIES } else declarations
            }
        val specified = CssCascade.resolve(
            element = view,
            index = index,
            inlineDeclarations = inline,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx
        )
        fun inherited(name: String): CssValue? = specified[name].let { value ->
            if (value == CssValue.Keyword("inherit")) null else value
        }
        val parentSize = parent?.fontSizePx ?: rootSize
        val fontSize = fontSizePx(inherited("font-size"), parentSize, rootSize) ?: parentSize
        val color = when (val value = inherited("color")) {
            is CssValue.Color -> value.argb
            CssValue.Keyword("currentcolor"), null -> parent?.colorArgb ?: themeTextArgb
            else -> parent?.colorArgb ?: themeTextArgb
        }
        val applied = specified.keys + parent?.appliedProperties.orEmpty().filter { it in INHERITED_PROPERTIES }
        val decoration = textDecoration(inherited("text-decoration") ?: inherited("text-decoration-line"))
        val borderStyles = SIDES.map { side -> (specified["border-$side-style"] as? CssValue.Keyword)?.name }
        val style = EpubStyle(
            fontSizePx = fontSize,
            appliedProperties = applied,
            fontFamilies = families(inherited("font-family")) ?: parent?.fontFamilies.orEmpty(),
            fontWeight = number(inherited("font-weight")) ?: keywordWeight(inherited("font-weight")) ?: parent?.fontWeight ?: 400,
            italic = (inherited("font-style") as? CssValue.Keyword)?.name in setOf("italic", "oblique") ||
                inherited("font-style") == null && parent?.italic == true,
            underline = decoration?.first ?: parent?.underline ?: false,
            strikethrough = decoration?.second ?: parent?.strikethrough ?: false,
            letterSpacingPx = lengthPx(inherited("letter-spacing"), fontSize, rootSize, fontSize)
                ?: parent?.letterSpacingPx ?: 0f,
            colorArgb = color,
            lineHeight = lineHeight(inherited("line-height"), fontSize, parent),
            lineHeightNumber = when (val line = inherited("line-height")) {
                is CssValue.Number -> line.value
                null -> parent?.lineHeightNumber
                else -> null
            },
            display = display(inherited("display"), view.node.tag),
            float = when ((inherited("float") as? CssValue.Keyword)?.name) {
                "left" -> EpubFloatValue.LEFT
                "right" -> EpubFloatValue.RIGHT
                else -> EpubFloatValue.NONE
            },
            clear = when ((inherited("clear") as? CssValue.Keyword)?.name) {
                "left" -> EpubClearValue.LEFT
                "right" -> EpubClearValue.RIGHT
                "both" -> EpubClearValue.BOTH
                else -> EpubClearValue.NONE
            },
            textAlign = textAlign(inherited("text-align"), parent),
            verticalAlign = verticalAlign(inherited("vertical-align"), fontSize, rootSize),
            marginTop = resolved(inherited("margin-top"), fontSize, rootSize),
            marginRight = resolved(inherited("margin-right"), fontSize, rootSize),
            marginBottom = resolved(inherited("margin-bottom"), fontSize, rootSize),
            marginLeft = resolved(inherited("margin-left"), fontSize, rootSize),
            paddingTop = resolved(inherited("padding-top"), fontSize, rootSize, auto = false),
            paddingRight = resolved(inherited("padding-right"), fontSize, rootSize, auto = false),
            paddingBottom = resolved(inherited("padding-bottom"), fontSize, rootSize, auto = false),
            paddingLeft = resolved(inherited("padding-left"), fontSize, rootSize, auto = false),
            width = resolved(inherited("width"), fontSize, rootSize),
            height = resolved(inherited("height"), fontSize, rootSize),
            minWidth = resolved(inherited("min-width"), fontSize, rootSize),
            minHeight = resolved(inherited("min-height"), fontSize, rootSize),
            maxWidth = resolved(inherited("max-width"), fontSize, rootSize),
            maxHeight = resolved(inherited("max-height"), fontSize, rootSize),
            boxSizingBorderBox = (specified["box-sizing"] as? CssValue.Keyword)?.name == "border-box",
            textIndent = resolved(inherited("text-indent") ?: inherited("duokan-text-indent"), fontSize, rootSize, auto = false),
            background = background(specified, color, fontSize, rootSize),
            borderWidths = SIDES.mapIndexed { sideIndex, side ->
                borderWidthPx(specified["border-$side-width"], borderStyles[sideIndex], fontSize, rootSize)
            },
            borderColors = SIDES.map { edge -> color(specified["border-$edge-color"], color) },
            borderRadii = CORNERS.map { resolved(specified["border-$it-radius"], fontSize, rootSize, false) },
            boxShadows = shadows(specified["box-shadow"], fontSize, rootSize, color),
            opacity = (specified["opacity"] as? CssValue.Number)?.value?.coerceIn(0f, 1f) ?: 1f,
            breakBefore = breakAlways(specified["break-before"] ?: specified["page-break-before"]),
            breakAfter = breakAlways(specified["break-after"] ?: specified["page-break-after"]),
            breakInsideAvoid = (specified["break-inside"] ?: specified["page-break-inside"]) == CssValue.Keyword("avoid"),
            breakAfterAvoid = (specified["break-after"] ?: specified["page-break-after"]) == CssValue.Keyword("avoid"),
            orphans = number(specified["orphans"]) ?: parent?.orphans ?: 2,
            widows = number(specified["widows"]) ?: parent?.widows ?: 2
        )
        val children = view.childViews.map { child -> resolveNode(child, style, rootSize) }
        return StyledDomNode(view.node, style, children)
    }

    private fun background(values: Map<String, CssValue>, currentColor: Int, fontSize: Float, root: Float): EpubBackgroundStyle {
        val color = color(values["background-color"], currentColor)
        val image = (values["background-image"] as? CssValue.Url)?.href
        val sizeValue = values["background-size"]
        val size = when (sizeValue) {
            is CssValue.Tuple -> sizeValue.items.map { resolved(it, fontSize, root, false) }
            is CssValue.Length -> listOf(resolved(sizeValue, fontSize, root, false))
            else -> emptyList()
        }
        val sizeMode = (sizeValue as? CssValue.Keyword)?.name ?: if (size.isNotEmpty()) "explicit" else "auto"
        val position = when (val value = values["background-position"]) {
            is CssValue.Tuple -> value.items
            null -> emptyList()
            else -> listOf(value)
        }
        val (positionX, positionY) = positionFractions(position)
        val repeat = (values["background-repeat"] as? CssValue.Keyword)?.name ?: "repeat"
        return EpubBackgroundStyle(color, image, sizeMode, size, positionX, positionY, repeat)
    }

    /** CSS background-position resolved to 0..1 fractions; px offsets are approximated by 0. */
    private fun positionFractions(values: List<CssValue>): Pair<Float, Float> {
        if (values.isEmpty()) return 0f to 0f
        var x: Float? = null
        var y: Float? = null
        values.forEach { value ->
            when (value) {
                is CssValue.Keyword -> when (value.name) {
                    "left" -> x = 0f
                    "right" -> x = 1f
                    "top" -> y = 0f
                    "bottom" -> y = 1f
                    "center" -> if (x == null) x = 0.5f else y = 0.5f
                }
                is CssValue.Length -> {
                    val fraction = if (value.unit == CssUnit.PERCENT) (value.value / 100f).coerceIn(0f, 1f) else 0f
                    if (x == null) x = fraction else y = fraction
                }
                else -> Unit
            }
        }
        return (x ?: 0.5f) to (y ?: (if (values.size == 1) 0.5f else 0f))
    }

    private fun shadows(value: CssValue?, fontSize: Float, root: Float, currentColor: Int): List<EpubShadow> {
        val groups = when (value) {
            null -> return emptyList()
            is CssValue.CommaList -> value.items
            else -> listOf(value)
        }
        return groups.mapNotNull { group ->
            val items = when (group) {
                is CssValue.Tuple -> group.items
                else -> listOf(group)
            }
            if (items.any { it == CssValue.Keyword("none") }) return@mapNotNull null
            val inset = items.any { it == CssValue.Keyword("inset") }
            val color = items.firstNotNullOfOrNull { (it as? CssValue.Color)?.argb }
                ?: items.firstNotNullOfOrNull { item ->
                    (item as? CssValue.Ident)?.name?.let(CssColor::parse)
                }
                ?: if (items.any { it == CssValue.Keyword("currentcolor") }) currentColor else 0xFF000000.toInt()
            val lengths = items.mapNotNull { item -> lengthPx(item, fontSize, root, fontSize) }
            if (lengths.size < 2) return@mapNotNull null
            EpubShadow(
                offsetXPx = lengths[0],
                offsetYPx = lengths[1],
                blurPx = lengths.getOrElse(2) { 0f }.coerceAtLeast(0f),
                spreadPx = lengths.getOrElse(3) { 0f },
                colorArgb = color,
                inset = inset
            )
        }
    }

    private fun borderWidthPx(width: CssValue?, style: String?, fontSize: Float, root: Float): Float {
        if (style == null || style == "none" || style == "hidden") return 0f
        return when (width) {
            is CssValue.Length -> lengthPx(width, fontSize, root, fontSize) ?: 0f
            is CssValue.Number -> width.value * pxScale
            is CssValue.Keyword -> when (width.name) {
                "thin" -> 1f * pxScale
                "thick" -> 4f * pxScale
                else -> MEDIUM_BORDER_PX * pxScale
            }
            null -> MEDIUM_BORDER_PX * pxScale
            else -> 0f
        }.coerceAtLeast(0f)
    }

    private fun textDecoration(value: CssValue?): Pair<Boolean, Boolean>? {
        val names = when (value) {
            null -> return null
            is CssValue.Keyword -> listOf(value.name)
            is CssValue.Ident -> listOf(value.name.lowercase())
            is CssValue.Tuple -> value.items.mapNotNull {
                (it as? CssValue.Keyword)?.name ?: (it as? CssValue.Ident)?.name?.lowercase()
            }
            else -> return null
        }
        return names.contains("underline") to names.contains("line-through")
    }

    private fun verticalAlign(value: CssValue?, fontSize: Float, root: Float): EpubVerticalAlignment = when (value) {
        is CssValue.Length -> {
            // CSS 正值抬高基线；渲染坐标向下为正，因此取负。百分比按行高近似字号。
            val px = lengthPx(value, fontSize, root, fontSize) ?: 0f
            if (px == 0f) EpubVerticalAlignment.Baseline else EpubVerticalAlignment.Shift(-px)
        }
        is CssValue.Keyword -> when (value.name) {
            "sub" -> EpubVerticalAlignment.Sub
            "super" -> EpubVerticalAlignment.Super
            "middle" -> EpubVerticalAlignment.Middle
            "text-top", "top" -> EpubVerticalAlignment.TextTop
            "text-bottom", "bottom" -> EpubVerticalAlignment.TextBottom
            else -> EpubVerticalAlignment.Baseline
        }
        else -> EpubVerticalAlignment.Baseline
    }

    private fun resolved(value: CssValue?, fontSize: Float, root: Float, auto: Boolean = true): ResolvedLength = when (value) {
        CssValue.Keyword("auto") -> if (auto) ResolvedLength.Auto else ResolvedLength.Px(0f)
        is CssValue.Length -> when (value.unit) {
            CssUnit.PERCENT -> ResolvedLength.Percent(value.value)
            else -> ResolvedLength.Px(lengthPx(value, fontSize, root, fontSize) ?: 0f)
        }
        is CssValue.Number -> ResolvedLength.Px(value.value)
        else -> if (auto) ResolvedLength.Auto else ResolvedLength.Px(0f)
    }

    private fun lengthPx(value: CssValue?, em: Float, root: Float, percentBase: Float): Float? = when (value) {
        is CssValue.Length -> when (value.unit) {
            CssUnit.PX -> value.value * pxScale
            CssUnit.EM -> value.value * em
            CssUnit.REM -> value.value * root
            CssUnit.PERCENT -> value.value * percentBase / 100f
            CssUnit.VW -> value.value * viewportWidthPx / 100f
            CssUnit.VH -> value.value * viewportHeightPx / 100f
            CssUnit.PT -> value.value * pxScale * 96f / 72f
            CssUnit.EX -> value.value * em * .5f
            CssUnit.CH -> value.value * em * .5f
        }
        is CssValue.Number -> if (value.value == 0f) 0f else null
        else -> null
    }

    private fun fontSizePx(value: CssValue?, parentSize: Float, root: Float): Float? = when (value) {
        is CssValue.Keyword -> FONT_SIZE_KEYWORDS[value.name]?.times(root)
            ?: when (value.name) {
                "smaller" -> parentSize / 1.2f
                "larger" -> parentSize * 1.2f
                else -> null
            }
        is CssValue.Ident -> FONT_SIZE_KEYWORDS[value.name.lowercase()]?.times(root)
        else -> lengthPx(value, parentSize, root, parentSize)
    }

    private fun lineHeight(value: CssValue?, fontSize: Float, parent: EpubStyle?): Float? = when (value) {
        is CssValue.Number -> value.value * fontSize
        is CssValue.Length -> lengthPx(value, fontSize, rootFontSizePx, fontSize)
        CssValue.Keyword("normal") -> null
        null -> parent?.lineHeightNumber?.times(fontSize) ?: parent?.lineHeight
        else -> parent?.lineHeight
    }

    private fun families(value: CssValue?): List<String>? = when (value) {
        is CssValue.CommaList -> value.items.mapNotNull { item ->
            (item as? CssValue.Ident)?.name ?: (item as? CssValue.Keyword)?.name
        }
        is CssValue.Ident -> listOf(value.name)
        is CssValue.Keyword -> listOf(value.name)
        else -> null
    }

    private fun number(value: CssValue?): Int? = (value as? CssValue.Number)?.value?.toInt()
    private fun keywordWeight(value: CssValue?): Int? = when ((value as? CssValue.Keyword)?.name) { "bold", "bolder" -> 700; "normal" -> 400; else -> null }
    private fun color(value: CssValue?, current: Int): Int? = when (value) {
        is CssValue.Color -> value.argb
        CssValue.Keyword("currentcolor") -> current
        else -> null
    }
    private fun breakAlways(value: CssValue?) = (value as? CssValue.Keyword)?.name in setOf("always", "page", "left", "right")

    private fun textAlign(value: CssValue?, parent: EpubStyle?) = when ((value as? CssValue.Keyword)?.name) {
        "center" -> EpubTextAlignValue.CENTER
        "right", "end" -> EpubTextAlignValue.END
        "justify" -> EpubTextAlignValue.JUSTIFY
        "left", "start" -> EpubTextAlignValue.START
        else -> parent?.textAlign ?: EpubTextAlignValue.START
    }

    private fun display(value: CssValue?, tag: String): EpubDisplay = when ((value as? CssValue.Keyword)?.name) {
        "none" -> EpubDisplay.NONE
        "block" -> EpubDisplay.BLOCK
        "inline" -> EpubDisplay.INLINE
        "inline-block" -> EpubDisplay.INLINE_BLOCK
        "flex" -> EpubDisplay.FLEX
        "grid" -> EpubDisplay.GRID
        "table" -> EpubDisplay.TABLE
        "table-row" -> EpubDisplay.TABLE_ROW
        "table-cell" -> EpubDisplay.TABLE_CELL
        "list-item" -> EpubDisplay.LIST_ITEM
        else -> when (tag) {
            "div", "p", "section", "article", "aside", "blockquote", "figure", "figcaption", "h1", "h2", "h3", "h4", "h5", "h6", "ol", "ul", "body", "hr", "pre", "dl", "dd", "dt", "nav", "header", "footer", "main" -> EpubDisplay.BLOCK
            "li" -> EpubDisplay.LIST_ITEM
            "table" -> EpubDisplay.TABLE
            "tr" -> EpubDisplay.TABLE_ROW
            "td", "th" -> EpubDisplay.TABLE_CELL
            else -> EpubDisplay.INLINE
        }
    }

    private class NodeView(val node: EpubDomNode, override val parent: NodeView?) : CssElementNode {
        override val tag get() = node.tag
        override val id get() = node.id
        override val classes get() = node.classes.toSet()
        override val attributes get() = node.attributes
        override val childIndex get() = node.childIndex
        override val childIndexOfType get() = node.childIndexOfType
        val childViews by lazy { node.children.filter { it.tag != "#text" }.map { NodeView(it, this) } }
        override val elementChildren: List<CssElementNode> get() = childViews
    }

    private companion object {
        const val MEDIUM_BORDER_PX = 3f
        const val CSS_ROOT_FONT_PX = 16f
        val SIDES = listOf("top", "right", "bottom", "left")
        val CORNERS = listOf("top-left", "top-right", "bottom-right", "bottom-left")
        val INHERITED_PROPERTIES = setOf(
            "color", "font-family", "font-size", "font-weight", "font-style", "line-height",
            "letter-spacing", "text-align", "text-indent", "white-space", "visibility", "orphans", "widows"
        )
        val FONT_SIZE_KEYWORDS = mapOf(
            "xx-small" to 0.6f, "x-small" to 0.75f, "small" to 0.875f, "medium" to 1f,
            "large" to 1.125f, "x-large" to 1.5f, "xx-large" to 2f
        )

        /** Declarations that survive publisher TAKE_OVER: structure, not looks. */
        val STRUCTURAL_PROPERTIES = setOf(
            "display", "float", "clear", "width", "height", "min-width", "min-height", "max-width", "max-height",
            "text-align", "text-indent", "duokan-text-indent", "vertical-align", "box-sizing",
            "break-before", "break-after", "break-inside", "page-break-before", "page-break-after",
            "page-break-inside", "orphans", "widows", "border-collapse", "flex-direction",
            "grid-template-columns", "column-gap", "row-gap", "gap", "align-items", "justify-content",
            "list-style-type", "list-style-position"
        )
        const val UA_STYLES = """
            html, body, div, p, section, article, aside, blockquote, figure, figcaption, h1, h2, h3, h4, h5, h6, ol, ul, hr, pre, dl, dd, dt { display:block; }
            span, a, b, strong, i, em, cite, u, s, del, ruby, rt, img { display:inline; }
            table { display:table; border-collapse:separate; } tr { display:table-row; } td, th { display:table-cell; }
            li { display:list-item; } h1 { font-size:2em; font-weight:bold; } h2 { font-size:1.5em; font-weight:bold; }
            h3 { font-size:1.17em; font-weight:bold; } h4, h5, h6, b, strong { font-weight:bold; }
            i, em, cite { font-style:italic; } u { text-decoration:underline; } s, del { text-decoration:line-through; }
            sup { vertical-align:super; font-size:0.75em; } sub { vertical-align:sub; font-size:0.75em; }
            small { font-size:0.8em; } blockquote { margin:1em 40px; }
        """
    }
}
