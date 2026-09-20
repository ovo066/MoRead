package com.mozhi.reader.core.datastore

/** Native paints parsed from the saved CSS; no separate persisted representation. */
data class ReaderLinearGradient(val angle: Float, val colors: List<Int>, val stops: List<Float>) {
    companion object {
        fun parse(value: String): ReaderLinearGradient {
            require(value.startsWith("linear-gradient(", true) && value.endsWith(')')) { "请使用 linear-gradient(...)" }
            val parts = value.substringAfter('(').dropLast(1).split(Regex(",(?=[^()]*($|\\())"))
                .map(String::trim).toMutableList()
            val direction = parts.firstOrNull().orEmpty().lowercase()
            val angle = when {
                direction.endsWith("deg") -> direction.removeSuffix("deg").toFloatOrNull()
                    ?.takeIf { it.isFinite() } ?: error("渐变角度无效")
                direction.startsWith("to ") -> when (direction) {
                    "to top" -> 0f; "to right" -> 90f; "to bottom" -> 180f; "to left" -> 270f
                    "to top right", "to right top" -> 45f
                    "to bottom right", "to right bottom" -> 135f
                    "to bottom left", "to left bottom" -> 225f
                    "to top left", "to left top" -> 315f
                    else -> error("渐变方向无效")
                }
                else -> 180f
            }
            if (direction.endsWith("deg") || direction.startsWith("to ")) parts.removeAt(0)
            require(parts.size in 2..16) { "渐变需有 2～16 个色标" }
            val colors = mutableListOf<Int>()
            val stops = mutableListOf<Float?>()
            parts.forEach { part ->
                val match = Regex("^(.*?)(?:\\s+([+-]?[\\d.]+)%)?$").matchEntire(part)
                    ?: error("色标格式无效")
                colors += ReaderStyleCss.color(match.groupValues[1].trim()) ?: error("渐变颜色无效")
                stops += match.groupValues[2].takeIf(String::isNotEmpty)?.let { position ->
                    (position.toFloatOrNull() ?: error("色标位置无效")) / 100f
                }?.also {
                    require(it.isFinite() && it in 0f..1f) { "色标位置需在 0%～100%" }
                }
            }
            if (stops.first() == null) stops[0] = 0f
            if (stops.last() == null) stops[stops.lastIndex] = 1f
            var previous = 0
            for (i in 1..stops.lastIndex) if (stops[i] != null) {
                // CSS fixes decreasing explicit stops at the preceding stop.
                stops[i] = maxOf(stops[previous]!!, stops[i]!!)
                for (j in previous + 1 until i) stops[j] = stops[previous]!! +
                    (stops[i]!! - stops[previous]!!) * (j - previous) / (i - previous)
                previous = i
            }
            return ReaderLinearGradient(((angle % 360f) + 360f) % 360f, colors, stops.map { it!! })
        }
    }
}

data class ReaderStylePaint(
    val textGradient: ReaderLinearGradient? = null,
    val backgroundGradient: ReaderLinearGradient? = null,
    val backgroundImageId: String? = null,
    val backgroundImageSpecified: Boolean = false
) {
    fun respectingPublisher(text: Boolean, background: Boolean) = copy(
        textGradient = textGradient.takeUnless { text },
        backgroundGradient = backgroundGradient.takeUnless { background },
        backgroundImageId = backgroundImageId.takeUnless { background }
    )
}

/** Keep the original match identity when English overlays split a syntax span. */
data class ReaderPaintSpan(val paint: ReaderStylePaint, val ruleId: Long, val start: Int, val end: Int)
