package com.mozhi.reader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** UI sheets and menus use a clear sans-serif hierarchy, independent of reading/book fonts. */
@Composable
internal fun MoReadOverlayTheme(content: @Composable () -> Unit) {
    val type = MaterialTheme.typography
    val family = FontFamily.SansSerif
    MaterialTheme(typography = type.copy(
        displayLarge = type.displayLarge.copy(fontFamily = family),
        displayMedium = type.displayMedium.copy(fontFamily = family),
        displaySmall = type.displaySmall.copy(fontFamily = family),
        headlineLarge = type.headlineLarge.copy(fontFamily = family),
        headlineMedium = type.headlineMedium.copy(fontFamily = family),
        headlineSmall = type.headlineSmall.copy(fontFamily = family, fontSize = 22.sp, lineHeight = 30.sp),
        titleLarge = type.titleLarge.copy(fontFamily = family, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = type.titleMedium.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        titleSmall = type.titleSmall.copy(fontFamily = family, fontWeight = FontWeight.Medium),
        bodyLarge = type.bodyLarge.copy(fontFamily = family),
        bodyMedium = type.bodyMedium.copy(fontFamily = family),
        bodySmall = type.bodySmall.copy(fontFamily = family),
        labelLarge = type.labelLarge.copy(fontFamily = family),
        labelMedium = type.labelMedium.copy(fontFamily = family),
        labelSmall = type.labelSmall.copy(fontFamily = family)
    ), content = content)
}
