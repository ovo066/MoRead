package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubBoxShadow
import com.mozhi.reader.core.library.EpubFloat
import com.mozhi.reader.core.library.EpubFontFace
import com.mozhi.reader.core.library.EpubResourcePath
import com.mozhi.reader.core.library.EpubTextAlign
import com.mozhi.reader.core.library.EpubVerticalAlign
import java.util.IdentityHashMap
import org.jsoup.nodes.Element

internal data class EpubStylesheetSource(
    val href: String,
    val css: String
)

internal class EpubCssStylesheetSet private constructor(
    private val rules: List<CssRule>,
    val fontFaces: List<EpubFontFace>,
    val unsupportedProperties: Set<String>
) {
    private val styleCache = IdentityHashMap<Element, EpubComputedStyle>()

    fun styleFor(element: Element): EpubComputedStyle = styleCache[element] ?: run {
        val inherited = element.parent()?.let(::styleFor) ?: EpubComputedStyle()
        val winners = LinkedHashMap<String, CascadeValue>()
        rules.forEach { rule ->
            if (!rule.selector.matches(element)) return@forEach
            rule.declarations.forEach { (property, declaration) ->
                val candidate = CascadeValue(
                    value = declaration.value,
                    important = declaration.important,
                    specificity = rule.selector.specificity,
                    order = rule.order,
                    sourceHref = rule.sourceHref
                )
                val current = winners[property]
                if (current == null || candidate.precedes(current)) winners[property] = candidate
            }
        }
        parseDeclarations(element.attr("style")).forEach { (property, declaration) ->
            val candidate = CascadeValue(
                value = declaration.value,
                important = declaration.important,
                specificity = INLINE_SPECIFICITY,
                order = Int.MAX_VALUE,
                sourceHref = null
            )
            val current = winners[property]
            if (current == null || candidate.precedes(current)) winners[property] = candidate
        }
        val resolved = resolveStyle(element, inherited, winners)
        styleCache[element] = resolved
        resolved
    }

    private fun resolveStyle(
        element: Element,
        inherited: EpubComputedStyle,
        values: Map<String, CascadeValue>
    ): EpubComputedStyle {
        val tag = element.normalName()
        val fontWeight = values["font-weight"]?.value?.toFontWeight()
            ?: when (tag) {
                "b", "strong", "h1", "h2", "h3", "h4", "h5", "h6" -> 700
                else -> inherited.fontWeight
            }
        val fontSize = values["font-size"]?.value?.toFontSizeEm(inherited.fontSizeEm ?: 1f)
            ?: when (tag) {
                "h1" -> 2f
                "h2" -> 1.5f
                "h3" -> 1.25f
                "h4" -> 1.1f
                "sup", "sub" -> (inherited.fontSizeEm ?: 1f) * 0.75f
                "small" -> 0.8f
                else -> inherited.fontSizeEm
            }
        val decoration = values["text-decoration"]?.value.orEmpty().lowercase()
        val background = values["background"]
        val border = values["border"]?.value
        val borderWidths = List(4) { side ->
            borderSideLength(values, side) ?: border?.firstLengthEm()
        }
        val borderColors = List(4) { side ->
            borderSideColor(values, side) ?: border?.firstColorArgb()
        }
        val borderWidth = borderWidths.filterNotNull().maxOrNull()
        val borderColor = borderColors.firstNotNullOfOrNull { it }
        return EpubComputedStyle(
            fontFamily = values["font-family"]?.value?.firstFontFamily() ?: inherited.fontFamily,
            fontSizeEm = fontSize ?: inherited.fontSizeEm,
            fontWeight = fontWeight,
            italic = values["font-style"]?.value?.contains("italic", true)
                ?: (tag in ITALIC_TAGS || inherited.italic),
            underline = decoration.contains("underline") || tag == "u" || inherited.underline,
            strikethrough = decoration.contains("line-through") || tag in STRIKE_TAGS || inherited.strikethrough,
            colorArgb = values["color"]?.value?.toColorArgb() ?: inherited.colorArgb,
            backgroundColorArgb = values["background-color"]?.value?.toColorArgb()
                ?: background?.value?.firstColorArgb(),
            backgroundImageHref = values["background-image"]?.resolveUrl()
                ?: background?.resolveUrl(),
            textAlign = values["text-align"]?.value?.toTextAlign() ?: inherited.textAlign,
            textIndentEm = values["text-indent"]?.value?.toEm()
                ?: values["duokan-text-indent"]?.value?.toEm()
                ?: inherited.textIndentEm,
            lineHeightEm = values["line-height"]?.value?.toLineHeightEm(fontSize ?: inherited.fontSizeEm)
                ?: inherited.lineHeightEm,
            letterSpacingEm = values["letter-spacing"]?.value?.toEm() ?: inherited.letterSpacingEm,
            marginTopEm = sideLength(values, "margin", "margin-top", 0),
            marginRightEm = sideLength(values, "margin", "margin-right", 1),
            marginBottomEm = sideLength(values, "margin", "margin-bottom", 2),
            marginLeftEm = sideLength(values, "margin", "margin-left", 3),
            paddingTopEm = sideLength(values, "padding", "padding-top", 0),
            paddingRightEm = sideLength(values, "padding", "padding-right", 1),
            paddingBottomEm = sideLength(values, "padding", "padding-bottom", 2),
            paddingLeftEm = sideLength(values, "padding", "padding-left", 3),
            borderWidthEm = borderWidth,
            borderColorArgb = borderColor,
            borderTopWidthEm = borderWidths[0],
            borderRightWidthEm = borderWidths[1],
            borderBottomWidthEm = borderWidths[2],
            borderLeftWidthEm = borderWidths[3],
            borderTopColorArgb = borderColors[0],
            borderRightColorArgb = borderColors[1],
            borderBottomColorArgb = borderColors[2],
            borderLeftColorArgb = borderColors[3],
            borderRadiusEm = values["border-radius"]?.value?.firstLengthEm(),
            boxShadows = values["box-shadow"]?.value?.toBoxShadows().orEmpty(),
            widthEm = values["width"]?.value?.absoluteLengthEm(),
            widthFraction = values["width"]?.value?.percentageFraction(),
            maxWidthEm = values["max-width"]?.value?.absoluteLengthEm(),
            maxWidthFraction = values["max-width"]?.value?.percentageFraction(),
            heightEm = values["height"]?.value?.absoluteLengthEm(),
            heightViewportFraction = values["height"]?.value?.viewportHeightFraction(),
            maxHeightEm = values["max-height"]?.value?.absoluteLengthEm(),
            maxHeightViewportFraction = values["max-height"]?.value?.viewportHeightFraction(),
            verticalAlign = values["vertical-align"]?.value?.toVerticalAlign()
                ?: when (tag) {
                    "sup" -> EpubVerticalAlign.SUPER
                    "sub" -> EpubVerticalAlign.SUB
                    else -> inherited.verticalAlign
                },
            float = values["float"]?.value?.toFloatSide() ?: EpubFloat.NONE,
            centerBlock = values["margin-left"]?.value?.trim()?.equals("auto", true) == true &&
                values["margin-right"]?.value?.trim()?.equals("auto", true) == true ||
                values["margin"]?.value?.hasAutoHorizontalMargins() == true,
            opacity = values["opacity"]?.value?.toFloatOrNull()?.coerceIn(0f, 1f) ?: inherited.opacity,
            breakBefore = values.breakValue("break-before", "page-break-before") == "always",
            breakAfter = values.breakValue("break-after", "page-break-after") == "always",
            avoidBreakInside = values.breakValue("break-inside", "page-break-inside") == "avoid",
            hidden = inherited.hidden || values["display"]?.value?.trim()?.equals("none", true) == true
        )
    }

    private fun borderSideLength(values: Map<String, CascadeValue>, side: Int): Float? {
        val name = BORDER_SIDES[side]
        return values["border-$name-width"]?.value?.firstLengthEm()
            ?: values["border-$name"]?.value?.firstLengthEm()
            ?: values["border-width"]?.value?.boxSide(side)?.toEm()
    }

    private fun borderSideColor(values: Map<String, CascadeValue>, side: Int): Int? {
        val name = BORDER_SIDES[side]
        return values["border-$name-color"]?.value?.firstColorArgb()
            ?: values["border-$name"]?.value?.firstColorArgb()
            ?: values["border-color"]?.value?.boxSide(side)?.toColorArgb()
    }

    private fun sideLength(
        values: Map<String, CascadeValue>,
        shorthand: String,
        explicit: String,
        side: Int
    ): Float? = values[explicit]?.value?.toEm()
        ?: values[shorthand]?.value?.boxSide(side)?.toEm()

    private fun CascadeValue.resolveUrl(): String? {
        val raw = URL_PATTERN.find(value)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trim('"', '\'')
            ?: return null
        return EpubResourcePath.normalize(raw, sourceHref)
    }

    companion object {
        fun parse(sources: List<EpubStylesheetSource>): EpubCssStylesheetSet {
            val rules = ArrayList<CssRule>()
            val fonts = ArrayList<EpubFontFace>()
            val unsupported = linkedSetOf<String>()
            var order = 0
            sources.forEach { source ->
                val css = COMMENTS.replace(source.css, "")
                FONT_FACE.findAll(css).forEach { match ->
                    val declarations = parseDeclarations(match.groupValues[1])
                    val family = declarations["font-family"]?.value?.firstFontFamily()
                    val src = declarations["src"]?.value
                        ?.let { value ->
                            URL_PATTERN.findAll(value)
                                .mapNotNull { match ->
                                    match.groupValues.getOrNull(1)
                                        ?.trim()
                                        ?.trim('"', '\'')
                                        ?.let { EpubResourcePath.normalize(it, source.href) }
                                }
                                .firstOrNull()
                        }
                    if (!family.isNullOrBlank() && src != null) {
                        fonts += EpubFontFace(
                            family = family,
                            resourceHref = src,
                            weight = declarations["font-weight"]?.value?.toFontWeight(),
                            italic = declarations["font-style"]?.value?.contains("italic", true) == true
                        )
                    }
                }
                RULE.findAll(FONT_FACE.replace(css, "")).forEach { match ->
                    val declarations = parseDeclarations(match.groupValues[2])
                    unsupported += declarations.keys.filterNot(SUPPORTED_PROPERTIES::contains)
                    match.groupValues[1].split(',').forEach selectorLoop@ { rawSelector ->
                        val selector = CssSelector.parse(rawSelector) ?: return@selectorLoop
                        rules += CssRule(selector, declarations, order++, source.href)
                    }
                }
            }
            return EpubCssStylesheetSet(rules, fonts.distinct(), unsupported)
        }

        private const val INLINE_SPECIFICITY = 1_000
        private val COMMENTS = Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL))
        private val FONT_FACE = Regex("@font-face\\s*\\{([^{}]*)\\}", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val RULE = Regex("([^{}]+)\\{([^{}]*)\\}", setOf(RegexOption.DOT_MATCHES_ALL))
        private val URL_PATTERN = Regex("url\\(([^)]+)\\)", RegexOption.IGNORE_CASE)
        private val ITALIC_TAGS = setOf("i", "em", "cite")
        private val STRIKE_TAGS = setOf("s", "strike", "del")
        private val SUPPORTED_PROPERTIES = setOf(
            "font-family", "font-size", "font-weight", "font-style", "line-height", "color",
            "background", "background-color", "background-image", "text-align", "text-indent",
            "duokan-text-indent", "text-decoration", "letter-spacing", "vertical-align",
            "margin", "margin-top", "margin-right",
            "margin-bottom", "margin-left", "padding", "padding-top", "padding-right",
            "padding-bottom", "padding-left", "border", "border-top", "border-right",
            "border-bottom", "border-left", "border-width", "border-color", "border-radius",
            "border-top-width", "border-right-width", "border-bottom-width", "border-left-width",
            "border-top-color", "border-right-color", "border-bottom-color", "border-left-color",
            "box-shadow", "width", "max-width", "height", "max-height", "float", "opacity", "display",
            "break-before", "break-after", "break-inside", "page-break-before", "page-break-after",
            "page-break-inside"
        )
        private val BORDER_SIDES = listOf("top", "right", "bottom", "left")
    }
}

private data class CssRule(
    val selector: CssSelector,
    val declarations: Map<String, CssDeclaration>,
    val order: Int,
    val sourceHref: String
)

private data class CssDeclaration(val value: String, val important: Boolean)

private data class CascadeValue(
    val value: String,
    val important: Boolean,
    val specificity: Int,
    val order: Int,
    val sourceHref: String?
) {
    fun precedes(other: CascadeValue): Boolean = when {
        important != other.important -> important
        specificity != other.specificity -> specificity > other.specificity
        else -> order >= other.order
    }
}

private data class CssSelector(
    val parts: List<SimpleSelector>,
    val specificity: Int
) {
    fun matches(element: Element): Boolean {
        var candidate: Element? = element
        for (index in parts.indices.reversed()) {
            val part = parts[index]
            if (index == parts.lastIndex) {
                if (candidate == null || !part.matches(candidate)) return false
                candidate = candidate.parent()
            } else {
                while (candidate != null && !part.matches(candidate)) candidate = candidate.parent()
                if (candidate == null) return false
                candidate = candidate.parent()
            }
        }
        return true
    }

    companion object {
        fun parse(raw: String): CssSelector? {
            val value = raw.trim()
            if (value.isEmpty() || value.startsWith('@') || value.any { it in ">+~[" } || ':' in value) {
                return null
            }
            val parts = value.split(Regex("\\s+")).mapNotNull(SimpleSelector::parse)
            if (parts.isEmpty()) return null
            return CssSelector(parts, parts.sumOf(SimpleSelector::specificity))
        }
    }
}

private data class SimpleSelector(
    val tag: String?,
    val id: String?,
    val classes: Set<String>
) {
    val specificity: Int get() = (if (id == null) 0 else 100) + classes.size * 10 + if (tag == null) 0 else 1

    fun matches(element: Element): Boolean =
        (tag == null || element.normalName().equals(tag, true)) &&
            (id == null || element.id() == id) &&
            classes.all(element.classNames()::contains)

    companion object {
        fun parse(raw: String): SimpleSelector? {
            val value = raw.trim()
            if (value.isEmpty() || value == "*") return SimpleSelector(null, null, emptySet())
            val tag = TAG.find(value)?.value?.takeUnless { it == "*" }?.lowercase()
            val id = ID.find(value)?.groupValues?.getOrNull(1)
            val classes = CLASS.findAll(value).map { it.groupValues[1] }.toSet()
            if (tag == null && id == null && classes.isEmpty()) return null
            return SimpleSelector(tag, id, classes)
        }

        private val TAG = Regex("^[A-Za-z][A-Za-z0-9_-]*|^\\*")
        private val ID = Regex("#([A-Za-z0-9_-]+)")
        private val CLASS = Regex("\\.([A-Za-z0-9_-]+)")
    }
}

private fun parseDeclarations(raw: String): Map<String, CssDeclaration> = buildMap {
    raw.split(';').forEach { declaration ->
        val separator = declaration.indexOf(':')
        if (separator <= 0) return@forEach
        val property = declaration.substring(0, separator).trim().lowercase()
        var value = declaration.substring(separator + 1).trim()
        if (property.isEmpty() || value.isEmpty()) return@forEach
        val important = value.endsWith("!important", true)
        if (important) value = value.dropLast("!important".length).trim()
        put(property, CssDeclaration(value, important))
    }
}

private fun Map<String, CascadeValue>.breakValue(primary: String, legacy: String): String? =
    (this[primary] ?: this[legacy])?.value?.trim()?.lowercase()

private fun String.firstFontFamily(): String? = split(',')
    .asSequence()
    .map { it.trim().trim('"', '\'') }
    .firstOrNull(String::isNotEmpty)

private fun String.toFontWeight(): Int? = when (val value = trim().lowercase()) {
    "normal" -> 400
    "bold", "bolder" -> 700
    "lighter" -> 300
    else -> value.toIntOrNull()?.coerceIn(100, 900)
}

private fun String.toTextAlign(): EpubTextAlign? = when (trim().lowercase()) {
    "left", "start" -> EpubTextAlign.START
    "center" -> EpubTextAlign.CENTER
    "right", "end" -> EpubTextAlign.END
    "justify", "justify-all" -> EpubTextAlign.JUSTIFY
    else -> null
}

private fun String.toLineHeightEm(fontSizeEm: Float?): Float? {
    val value = trim().lowercase()
    if (value == "normal") return null
    if (value.endsWith('%')) return value.dropLast(1).toFloatOrNull()?.div(100f)
    if (value.endsWith("em")) return value.dropLast(2).toFloatOrNull()
    if (value.endsWith("rem")) return value.dropLast(3).toFloatOrNull()
    if (value.endsWith("px")) {
        val size = value.dropLast(2).toFloatOrNull() ?: return null
        return size / (16f * (fontSizeEm ?: 1f))
    }
    return value.toFloatOrNull()
}

private fun String.toEm(): Float? {
    val value = trim().lowercase()
    return when {
        value == "0" -> 0f
        value.endsWith("rem") -> value.dropLast(3).toFloatOrNull()
        value.endsWith("em") -> value.dropLast(2).toFloatOrNull()
        value.endsWith('%') -> value.dropLast(1).toFloatOrNull()?.div(100f)
        value.endsWith("px") -> value.dropLast(2).toFloatOrNull()?.div(16f)
        value.endsWith("pt") -> value.dropLast(2).toFloatOrNull()?.div(12f)
        value == "xx-small" -> 0.6f
        value == "x-small" -> 0.75f
        value == "small" -> 0.875f
        value == "medium" -> 1f
        value == "large" -> 1.125f
        value == "x-large" -> 1.5f
        value == "xx-large" -> 2f
        else -> null
    }
}

private fun String.toFontSizeEm(parentSizeEm: Float): Float? {
    val value = trim().lowercase()
    return when {
        value.endsWith("rem") -> value.dropLast(3).toFloatOrNull()
        value.endsWith("em") -> value.dropLast(2).toFloatOrNull()?.times(parentSizeEm)
        value.endsWith('%') -> value.dropLast(1).toFloatOrNull()?.div(100f)?.times(parentSizeEm)
        else -> value.toEm()
    }
}

private fun String.absoluteLengthEm(): Float? {
    val value = trim().lowercase()
    if (value.endsWith('%') || value.endsWith("vh") || value.endsWith("vw") || value == "auto") return null
    return value.toEm()
}

private fun String.percentageFraction(): Float? {
    val value = trim().lowercase()
    return value.takeIf { it.endsWith('%') }
        ?.dropLast(1)
        ?.toFloatOrNull()
        ?.div(100f)
        ?.coerceAtLeast(0f)
}

private fun String.viewportHeightFraction(): Float? {
    val value = trim().lowercase()
    return value.takeIf { it.endsWith("vh") }
        ?.dropLast(2)
        ?.toFloatOrNull()
        ?.div(100f)
        ?.coerceAtLeast(0f)
}

private fun String.toVerticalAlign(): EpubVerticalAlign? = when (trim().lowercase()) {
    "super", "text-top" -> EpubVerticalAlign.SUPER
    "sub", "text-bottom" -> EpubVerticalAlign.SUB
    "baseline", "middle" -> EpubVerticalAlign.BASELINE
    else -> null
}

private fun String.toFloatSide(): EpubFloat? = when (trim().lowercase()) {
    "left", "inline-start" -> EpubFloat.START
    "right", "inline-end" -> EpubFloat.END
    "none" -> EpubFloat.NONE
    else -> null
}

private fun String.hasAutoHorizontalMargins(): Boolean {
    val values = trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return when (values.size) {
        2 -> values[1].equals("auto", true)
        3 -> values[1].equals("auto", true)
        4 -> values[1].equals("auto", true) && values[3].equals("auto", true)
        else -> false
    }
}

private fun String.boxSide(side: Int): String? {
    val values = trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (values.isEmpty()) return null
    return when (values.size) {
        1 -> values[0]
        2 -> if (side == 0 || side == 2) values[0] else values[1]
        3 -> when (side) {
            0 -> values[0]
            2 -> values[2]
            else -> values[1]
        }
        else -> values[side.coerceIn(0, 3)]
    }
}

private fun String.firstLengthEm(): Float? = split(Regex("\\s+")).firstNotNullOfOrNull { it.toEm() }

private fun String.firstColorArgb(): Int? = toColorArgb()
    ?: COLOR_TOKEN.findAll(this).firstNotNullOfOrNull { it.value.toColorArgb() }

private fun String.toBoxShadows(): List<EpubBoxShadow> {
    if (trim().equals("none", true)) return emptyList()
    return splitTopLevelCommas().mapNotNull(String::toBoxShadow)
}

private fun String.toBoxShadow(): EpubBoxShadow? {
    var value = trim()
    if (value.isEmpty()) return null
    val inset = INSET_TOKEN.containsMatchIn(value)
    value = INSET_TOKEN.replace(value, " ")
    val colorMatch = COLOR_TOKEN.findAll(value).lastOrNull { it.value.toColorArgb() != null }
    val color = colorMatch?.value?.toColorArgb() ?: 0xFF000000.toInt()
    if (colorMatch != null) {
        value = value.removeRange(colorMatch.range)
    }
    val tokens = value.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (tokens.size !in 2..4) return null
    val lengths = ArrayList<Float>(tokens.size)
    tokens.forEach { token ->
        lengths += token.absoluteLengthEm() ?: return null
    }
    val blur = lengths.getOrElse(2) { 0f }
    if (blur < 0f) return null
    return EpubBoxShadow(
        offsetXEm = lengths[0],
        offsetYEm = lengths[1],
        blurRadiusEm = blur,
        spreadRadiusEm = lengths.getOrElse(3) { 0f },
        colorArgb = color,
        inset = inset
    )
}

private fun String.splitTopLevelCommas(): List<String> {
    val values = ArrayList<String>()
    var start = 0
    var depth = 0
    forEachIndexed { index, char ->
        when (char) {
            '(' -> depth++
            ')' -> depth = (depth - 1).coerceAtLeast(0)
            ',' -> if (depth == 0) {
                values += substring(start, index).trim()
                start = index + 1
            }
        }
    }
    values += substring(start).trim()
    return values.filter(String::isNotEmpty)
}

private fun String.toColorArgb(): Int? {
    val value = trim().lowercase().trimEnd(';')
    if (value == "transparent") return 0
    NAMED_COLORS[value]?.let { return it }
    if (value.startsWith('#')) {
        val hex = value.drop(1)
        return runCatching {
            when (hex.length) {
                3 -> (0xFF000000L or
                    ((hex[0].digitToInt(16) * 17L) shl 16) or
                    ((hex[1].digitToInt(16) * 17L) shl 8) or
                    (hex[2].digitToInt(16) * 17L)).toInt()
                4 -> (((hex[3].digitToInt(16) * 17L) shl 24) or
                    ((hex[0].digitToInt(16) * 17L) shl 16) or
                    ((hex[1].digitToInt(16) * 17L) shl 8) or
                    (hex[2].digitToInt(16) * 17L)).toInt()
                6 -> (0xFF000000L or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> return null
            }
        }.getOrNull()
    }
    val rgb = RGB.matchEntire(value) ?: return null
    val red = rgb.groupValues[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
    val green = rgb.groupValues[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
    val blue = rgb.groupValues[3].toIntOrNull()?.coerceIn(0, 255) ?: return null
    val alpha = rgb.groupValues[4].takeIf(String::isNotEmpty)
        ?.toFloatOrNull()
        ?.times(255f)
        ?.toInt()
        ?.coerceIn(0, 255)
        ?: 255
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

private val RGB = Regex("rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*([0-9.]+))?\\s*\\)")
private val COLOR_TOKEN = Regex("#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)|[A-Za-z]+")
private val INSET_TOKEN = Regex("(?i)(?:^|\\s)inset(?=\\s|$)")
private val NAMED_COLORS = mapOf(
    "black" to 0xFF000000.toInt(),
    "white" to 0xFFFFFFFF.toInt(),
    "red" to 0xFFFF0000.toInt(),
    "green" to 0xFF008000.toInt(),
    "blue" to 0xFF0000FF.toInt(),
    "gray" to 0xFF808080.toInt(),
    "grey" to 0xFF808080.toInt(),
    "yellow" to 0xFFFFFF00.toInt()
)
