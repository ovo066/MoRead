package com.mozhi.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import coil3.compose.AsyncImage
import com.mozhi.reader.ui.theme.ColorSchemePreset
import com.mozhi.reader.ui.theme.LocalMoReadColors
import com.mozhi.reader.ui.theme.SemanticHarmony
import com.mozhi.reader.ui.theme.onAccent
import java.io.File

/** 角色头像：有自定义图用图，否则渐变底 + serif 首字（与占位版视觉一致）。 */
@Composable
fun PersonaAvatarImage(
    name: String,
    avatarPath: String?,
    modifier: Modifier = Modifier,
    fallbackTextStyle: TextStyle? = null
) {
    val file = avatarPath?.takeIf(String::isNotBlank)?.let(::File)
    run {
        // 渐变底来自当前配色方案：中性方案仍是历史的四组灰，莫兰迪方案下按语义色上色。
        val appearance = LocalMoReadColors.current
        val gradients = appearance.avatarGradients
        val (start, end) = gradients[personaPaletteIndex(name, gradients.size)]
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(start, end))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.firstOrNull()?.toString() ?: "角",
                style = fallbackTextStyle ?: MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                color = end.onAccent()
            )
            // Keep an avatar visible while a local image loads, or when the image cannot decode.
            if (file != null && file.exists()) AsyncImage(
                model = file, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().clip(CircleShape)
            )
        }
    }
}
