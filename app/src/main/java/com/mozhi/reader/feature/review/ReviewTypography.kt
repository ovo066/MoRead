package com.mozhi.reader.feature.review

import android.graphics.Typeface
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.activeThemeSlot
import com.mozhi.reader.core.datastore.resolveForBook
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.rememberAppFontFamily

internal val LocalReviewReaderSettings = staticCompositionLocalOf { ReaderSettings() }

internal data class ReviewFontSpec(val font: ReaderFont = ReaderFont.SYSTEM, val path: String? = null, val weight: Int = 400) {
    fun typeface(): Typeface {
        val base = when (font) {
            ReaderFont.SERIF -> Typeface.SERIF
            ReaderFont.SANS_SERIF -> Typeface.SANS_SERIF
            ReaderFont.MONOSPACE -> Typeface.MONOSPACE
            ReaderFont.CUSTOM -> path?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT
            ReaderFont.SYSTEM -> Typeface.DEFAULT
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(base, weight.coerceIn(300, 700), false)
        else Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
    }
}

internal fun reviewFontSpec(settings: ReaderSettings, bookId: Long, dark: Boolean): ReviewFontSpec {
    val choice = settings.reviewFont
    ReaderFont.entries.firstOrNull { it != ReaderFont.CUSTOM && it.name == choice }?.let { return ReviewFontSpec(it) }
    if (choice.startsWith("font:")) settings.fontLibrary.firstOrNull { it.id == choice.removePrefix("font:") }
        ?.let { return ReviewFontSpec(ReaderFont.CUSTOM, it.filePath) }
    val resolved = settings.resolveForBook(bookId, settings.activeThemeSlot(dark))
    return ReviewFontSpec(resolved.font, resolved.customFontPath, resolved.fontWeight)
}

@Composable
internal fun reviewQuoteStyle(bookId: Long, base: TextStyle): TextStyle {
    val spec = reviewFontSpec(LocalReviewReaderSettings.current, bookId, isDarkTheme())
    val custom = rememberAppFontFamily(spec.path.takeIf { spec.font == ReaderFont.CUSTOM })
    val family = when (spec.font) {
        ReaderFont.SERIF -> FontFamily.Serif
        ReaderFont.SANS_SERIF -> FontFamily.SansSerif
        ReaderFont.MONOSPACE -> FontFamily.Monospace
        ReaderFont.CUSTOM -> custom ?: FontFamily.Default
        ReaderFont.SYSTEM -> FontFamily.Default
    }
    return base.copy(fontFamily = family, fontWeight = FontWeight(spec.weight))
}

internal fun reviewFontChoices(settings: ReaderSettings): List<Pair<String, String>> = listOf(
    "" to "跟随书籍主题 / 默认正文", ReaderFont.SYSTEM.name to "系统字体", ReaderFont.SERIF.name to "衬线体",
    ReaderFont.SANS_SERIF.name to "无衬线体", ReaderFont.MONOSPACE.name to "等宽体"
) + settings.fontLibrary.map { "font:${it.id}" to it.displayName }
