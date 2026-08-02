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
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.abs

private val AvatarGradients = listOf(
    Color(0xFF5E5E5E) to Color(0xFF262626),
    Color(0xFF767676) to Color(0xFF3A3A3A),
    Color(0xFF4A4A4A) to Color(0xFF1E1E1E),
    Color(0xFF8A8A8A) to Color(0xFF4A4A4A)
)

/** 角色头像：有自定义图用图，否则渐变底 + serif 首字（与占位版视觉一致）。 */
@Composable
fun PersonaAvatarImage(
    name: String,
    avatarPath: String?,
    modifier: Modifier = Modifier
) {
    val file = avatarPath?.takeIf(String::isNotBlank)?.let(::File)
    if (file != null && file.exists()) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape)
        )
    } else {
        val (start, end) = AvatarGradients[abs(name.hashCode()) % AvatarGradients.size]
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(start, end))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.firstOrNull()?.toString() ?: "角",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
