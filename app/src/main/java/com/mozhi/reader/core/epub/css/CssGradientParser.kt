package com.mozhi.reader.core.epub.css

/**
 * CSS Images 3 `linear-gradient()` / `radial-gradient()` and their `repeating-` forms, parsed into
 * [CssValue.Gradient]. The subset covers what publishers write for cards, ruled notebook paper and
 * title banners: angles or `to <side>`, radial shape and `at <position>`, and colour stops with
 * optional (double) positions. Anything else makes the whole value invalid, as in a browser.
 */
internal object CssGradientParser {
    private val FUNCTION = Regex("(?is)^\\s*(repeating-)?(linear|radial)-gradient\\((.*)\\)\\s*$")

    /** Start/end of the first top-level gradient function in [source], parentheses balanced. */
    fun find(source: String): IntRange? {
        val match = Regex("(?i)(repeating-)?(linear|radial)-gradient\\(").find(source) ?: return null
        var depth = 0
        for (index in match.range.last until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return match.range.first..index
            }
        }
        return null
    }

    fun parse(raw: String): CssValue.Gradient? {
        val match = FUNCTION.matchEntire(raw) ?: return null
        val repeating = match.groupValues[1].isNotEmpty()
        val radial = match.groupValues[2].equals("radial", true)
        val arguments = splitTopLevelCommas(match.groupValues[3]).map(String::trim)
        if (arguments.isEmpty()) return null
        var first = 0
        var angle = 180f
        var circle = false
        var centerX = .5f
        var centerY = .5f
        val lead = arguments[0].lowercase()
        if (!radial) {
            if (lead.startsWith("to ")) {
                angle = sideAngle(lead.removePrefix("to ").trim()) ?: return null
                first = 1
            } else {
                parseAngle(lead)?.let { angle = it; first = 1 }
            }
        } else if (colorPrefix(arguments[0]) == null) {
            val (shape, position) = lead.split(" at ", limit = 2).let { it[0].trim() to it.getOrNull(1)?.trim() }
            if (lead.startsWith("at ")) {
                positionFractions(lead.removePrefix("at ").trim())?.let { (x, y) -> centerX = x; centerY = y }
            } else {
                circle = shape.split(' ').any { it == "circle" }
                position?.let(::positionFractions)?.let { (x, y) -> centerX = x; centerY = y }
            }
            first = 1
        }
        val stops = ArrayList<CssValue.GradientStop>()
        for (argument in arguments.drop(first)) {
            stops += parseStop(argument) ?: return null
        }
        if (stops.isEmpty()) return null
        if (stops.size == 1) stops += stops.single()
        return CssValue.Gradient(
            radial = radial, repeating = repeating, angleDeg = angle, circle = circle,
            centerX = centerX, centerY = centerY, stops = stops
        )
    }

    /** `red`, `rgba(0,0,0,.3) 28px`, `#fff 10% 20%` (a double position repeats the colour). */
    private fun parseStop(raw: String): List<CssValue.GradientStop>? {
        val colorText = colorPrefix(raw) ?: return null
        val color = CssColor.parse(colorText) ?: return null
        val positions = raw.substring(colorText.length).trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (positions.size > 2) return null
        if (positions.isEmpty()) return listOf(CssValue.GradientStop(color, null))
        return positions.map { position -> CssValue.GradientStop(color, parseLength(position) ?: return null) }
    }

    /** The colour at the start of a stop, including functional notation with inner spaces. */
    private fun colorPrefix(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val open = text.indexOf('(')
        val space = text.indexOfFirst(Char::isWhitespace).let { if (it < 0) text.length else it }
        val end = if (open in 1 until space) {
            text.indexOf(')', open).takeIf { it > 0 }?.plus(1) ?: return null
        } else space
        val candidate = text.substring(0, end)
        return candidate.takeIf { CssColor.parse(it) != null }
    }

    private fun parseLength(raw: String): CssValue.Length? {
        val token = CssTokenizer.tokenize(raw).firstOrNull { it.type != CssTokenType.WHITESPACE } ?: return null
        val number = token.number ?: return null
        return when (token.type) {
            CssTokenType.PERCENTAGE -> CssValue.Length(number, CssUnit.PERCENT)
            CssTokenType.NUMBER -> if (number == 0f) CssValue.Length(0f, CssUnit.PX) else null
            CssTokenType.DIMENSION -> when (token.unit?.lowercase()) {
                "px" -> CssValue.Length(number, CssUnit.PX)
                "em" -> CssValue.Length(number, CssUnit.EM)
                "rem" -> CssValue.Length(number, CssUnit.REM)
                "pt" -> CssValue.Length(number, CssUnit.PT)
                "vw" -> CssValue.Length(number, CssUnit.VW)
                "vh" -> CssValue.Length(number, CssUnit.VH)
                else -> null
            }
            else -> null
        }
    }

    private fun parseAngle(raw: String): Float? {
        val match = Regex("^(-?[0-9.]+)(deg|grad|rad|turn)$").matchEntire(raw.trim()) ?: return null
        val value = match.groupValues[1].toFloatOrNull() ?: return null
        return when (match.groupValues[2]) {
            "deg" -> value
            "grad" -> value * 0.9f
            "rad" -> Math.toDegrees(value.toDouble()).toFloat()
            else -> value * 360f
        }
    }

    /** `to <side-or-corner>`; corners use 45° steps, which matches square boxes exactly. */
    private fun sideAngle(raw: String): Float? {
        val parts = raw.split(Regex("\\s+")).toSet()
        val vertical = when {
            "top" in parts -> 0f
            "bottom" in parts -> 180f
            else -> null
        }
        val horizontal = when {
            "right" in parts -> 90f
            "left" in parts -> 270f
            else -> null
        }
        return when {
            vertical != null && horizontal != null -> when {
                vertical == 0f && horizontal == 90f -> 45f
                vertical == 180f && horizontal == 90f -> 135f
                vertical == 180f && horizontal == 270f -> 225f
                else -> 315f
            }
            else -> vertical ?: horizontal
        }
    }

    private fun positionFractions(raw: String): Pair<Float, Float>? {
        var x = .5f
        var y = .5f
        val parts = raw.split(Regex("\\s+")).filter(String::isNotEmpty)
        parts.forEachIndexed { index, part ->
            when (part) {
                "left" -> x = 0f
                "right" -> x = 1f
                "top" -> y = 0f
                "bottom" -> y = 1f
                "center" -> Unit
                else -> {
                    val percent = part.removeSuffix("%").toFloatOrNull()?.takeIf { part.endsWith('%') } ?: return null
                    if (index == 0) x = percent / 100f else y = percent / 100f
                }
            }
        }
        return x to y
    }

    private fun splitTopLevelCommas(value: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var start = 0
        value.forEachIndexed { index, char ->
            when (char) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 0) {
                    result += value.substring(start, index)
                    start = index + 1
                }
            }
        }
        result += value.substring(start)
        return result.filter(String::isNotBlank)
    }
}
