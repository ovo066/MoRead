package com.mozhi.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.GenericShape
import kotlin.math.cos
import kotlin.math.sin

internal data class ReadingReviewStyle(val paper: Color, val accent: Color, val accentText: Color, val caption: Color, val toolbarInk: Color)
internal val LocalReadingReviewStyle = staticCompositionLocalOf {
    ReadingReviewStyle(Color(0xFFF5F2EA), Color(0xFF88AED0), Color(0xFF658EB0), Color(0xFF8D9AA4), Color(0xFF7A8995))
}

/** The prototype's softly scalloped location action, kept as a native shape. */
internal val ReviewLocationShape = GenericShape { size, _ ->
    val unit = minOf(size.width, size.height)
    for (step in 0..256) {
        val angle = step * 2.0 * Math.PI / 256 - Math.PI / 2
        val radius = unit * (.445 + .05 * cos(angle * 8))
        val x = (size.width / 2 + radius * cos(angle)).toFloat()
        val y = (size.height / 2 + radius * sin(angle)).toFloat()
        if (step == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private val LocalReadingReviewTheme = staticCompositionLocalOf { false }

/** A quiet paper-and-pastel variant of the existing theme, scoped to the reading notebook. */
@Composable
internal fun ReadingReviewTheme(content: @Composable () -> Unit) {
    if (LocalReadingReviewTheme.current) { content(); return }
    val dark = isDarkTheme()
    val app = MaterialTheme.colorScheme
    val accent = app.primary
    fun tint(base: Color, amount: Float) = accent.copy(alpha = amount).compositeOver(base)
    val paper = tint(if (dark) Color(0xFF293035) else Color(0xFFFFFDFA), if (dark) .035f else .012f)
    val canvas = if (dark) tint(Color(0xFF20262A), .025f) else Color(0xFFF5F4F1)
    val ink = lerp(if (dark) Color(0xFFD5DFE6) else Color(0xFF495963), accent, .045f)
    val muted = lerp(if (dark) Color(0xFFACBAC4) else Color(0xFF63717B), accent, .06f)
    val primaryContainer = tint(paper, if (dark) .19f else .18f)
    val colors = app.copy(
        background = canvas, onBackground = ink, surface = paper, onSurface = ink, onSurfaceVariant = muted,
        surfaceContainerLowest = paper, surfaceContainerLow = lerp(canvas, paper, .45f),
        surfaceContainer = tint(if (dark) Color(0xFF293137) else Color(0xFFECEDE7), .025f),
        surfaceContainerHigh = tint(if (dark) Color(0xFF34414A) else Color(0xFFE4E9E8), .035f),
        primary = readableOn(paper, lerp(ink, accent, .78f)),
        primaryContainer = primaryContainer, onPrimaryContainer = readableOn(primaryContainer, lerp(ink, accent, .25f)),
        secondaryContainer = app.secondary.copy(alpha = if (dark) .18f else .13f).compositeOver(paper),
        tertiaryContainer = app.tertiary.copy(alpha = if (dark) .18f else .13f).compositeOver(paper),
        outlineVariant = lerp(canvas, muted, if (dark) .20f else .22f)
    )
    val existing = LocalMoReadColors.current
    fun soft(tone: MoReadTone): MoReadTone {
        val container = tone.accent.copy(alpha = if (dark) .19f else .14f).compositeOver(paper)
        return tone.copy(container = container, onContainer = readableOn(container, tone.accent))
    }
    val semantic = existing.semantic.let { it.copy(reading = soft(it.reading), knowledge = soft(it.knowledge), ai = soft(it.ai), caution = soft(it.caution)) }
    val appearance = existing.copy(accent = colors.primary, canvas = colors.background, surfaceStyle = SurfaceStyle.FLAT,
        semantic = semantic, avatarGradients = semantic.all.map { it.container to it.container })
    val type = MaterialTheme.typography
    val expressiveAccent = if (dark) lerp(accent, paper, .08f) else lerp(paper, accent, .52f)
    val reviewStyle = ReadingReviewStyle(
        paper = if (dark) Color(0xFF20272D) else Color(0xFFF5F2EA), accent = expressiveAccent,
        accentText = if (dark) accent else lerp(ink, expressiveAccent, .78f),
        caption = lerp(canvas, muted, if (dark) .80f else .62f), toolbarInk = lerp(ink, canvas, .28f)
    )
    CompositionLocalProvider(LocalReadingReviewTheme provides true, LocalMoReadColors provides appearance,
        LocalReadingReviewStyle provides reviewStyle) {
        MaterialTheme(colorScheme = colors, typography = type.copy(
            titleMedium = type.titleMedium.copy(fontWeight = FontWeight.Medium),
            titleSmall = type.titleSmall.copy(fontWeight = FontWeight.Medium)
        ), content = content)
    }
}
