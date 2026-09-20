package com.mozhi.reader.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.feature.reader.AiRichText
import com.mozhi.reader.feature.reader.companionChatPalette
import com.mozhi.reader.ui.theme.LocalReadingReviewStyle
import com.mozhi.reader.ui.theme.isDarkTheme
import kotlin.math.abs

@Composable
internal fun ReviewNoteText(text: String) {
    val type = MaterialTheme.typography
    MaterialTheme(typography = type.copy(bodyMedium = type.bodyMedium.copy(fontSize = 16.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Medium))) {
        AiRichText(text, companionChatPalette(), parseSynchronously = true)
    }
}

/** Muted export-style inks are actual custom highlight colors; existing colors remain available. */
@Composable
internal fun ReviewDetailInkRow(currentTag: String, current: Color, style: AnnotationStyle, accent: Color, onSelect: (String) -> Unit) {
    val dark = isDarkTheme()
    val palette = listOf(accent, Color(if (dark) 0xFFB2C7B5 else 0xFF667F6D),
        Color(if (dark) 0xFFE0BCB9 else 0xFF95716F), Color(if (dark) 0xFFDED0A6 else 0xFF8C7D55))
    val near = palette.indexOfFirst { abs(it.red - current.red) + abs(it.green - current.green) + abs(it.blue - current.blue) < .10f }
    val swatches = if (near >= 0) palette.mapIndexed { i, color -> if (i == near) current else color }
        else palette + current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        swatches.forEachIndexed { index, color ->
            val selected = color == current
            val hex = "#%06X".format(color.toArgb() and 0xFFFFFF)
            Box(Modifier.size(38.dp).clip(CircleShape).clickable { onSelect(if (selected && currentTag.isNotBlank()) currentTag else hex) }
                .padding(2.dp).border(if (selected) 1.5.dp else 0.dp,
                    if (selected) LocalReadingReviewStyle.current.toolbarInk else Color.Transparent, CircleShape)
                .padding(if (selected) 3.dp else 4.dp).background(color, CircleShape)
                .semantics { contentDescription = if (selected) "当前划线颜色" else "划线配色 ${index + 1}" })
        }
        Spacer(Modifier.weight(1f))
        Text(when (style) { AnnotationStyle.HIGHLIGHT -> "荧光划线"; AnnotationStyle.UNDERLINE -> "直线划线"; AnnotationStyle.WAVY -> "波浪划线" },
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = LocalReadingReviewStyle.current.caption)
    }
}
