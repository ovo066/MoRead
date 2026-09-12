package com.mozhi.reader.ui.theme

import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Font validation/loading never blocks the first frame; missing restored files use the default. */
@Composable
fun rememberAppFontFamily(path: String?): FontFamily? {
    val loaded by produceState<Pair<String, FontFamily>?>(null, path) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                require(file.isFile)
                Typeface.createFromFile(file)
                path to FontFamily(Font(file))
            }.getOrNull()
        }
    }
    return loaded?.takeIf { it.first == path }?.second
}

internal fun Typography.withAppFont(family: FontFamily?): Typography {
    if (family == null) return this
    return copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family)
    )
}
