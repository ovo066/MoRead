package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.epub.css.CssColor
import com.mozhi.reader.feature.reader.engine.EpubThemeColors

/** Adapt individual solid colors while retaining the dictionary's color hierarchy. */
internal fun dictionaryCssForTheme(css: String, dark: Boolean): String {
    val normalized = Regex("url\\(([^)]*)\\)", RegexOption.IGNORE_CASE).replace(css) { match ->
        if (':' in match.groupValues[1]) match.value else "url(${match.groupValues[1].replace('\\', '/')})"
    }
    if (!dark) return normalized
    return Regex("(^|[;{])([\\s]*)(color|background-color|background|border(?:-(?:top|right|bottom|left))?-color)(\\s*:\\s*)([^;{}]+)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)).replace(normalized) { match ->
        val value = match.groupValues[5]
        val raw = value.replace(Regex("\\s*!important\\s*$", RegexOption.IGNORE_CASE), "").trim()
        val color = CssColor.parse(raw)
        if (color == null || color == CssColor.CURRENT_COLOR || color ushr 24 == 0) match.value else {
            val property = match.groupValues[3].lowercase()
            val adapted = if (property.startsWith("background")) EpubThemeColors.background(color, true) ?: color
                // Mapped light panels can reach 30% lightness; text must remain readable there.
                else EpubThemeColors.foreground(color, 0xff4d4d4d.toInt(), 0xffdedede.toInt())
            val hex = "#%08x".format(java.util.Locale.ROOT, adapted)
            // CSS uses RRGGBBAA, while Android stores AARRGGBB.
            val cssColor = if (adapted ushr 24 == 255) "#%06x".format(java.util.Locale.ROOT, adapted and 0xffffff)
                else "#${hex.substring(3)}${hex.substring(1, 3)}"
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + match.groupValues[4] +
                cssColor + if (value.contains("!important", true)) "!important" else ""
        }
    }
}
