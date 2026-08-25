package com.mozhi.reader.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mozhi.reader.ui.theme.isDarkTheme

/** 全屏聊天页不在阅读纸色语境里，按应用主题构造一份 ReaderPalette 复用弹层组件。 */
@Composable
internal fun companionChatPalette(): ReaderPalette {
    val scheme = MaterialTheme.colorScheme
    val dark = isDarkTheme()
    return ReaderPalette(
        background = scheme.background,
        onBackground = scheme.onBackground,
        muted = scheme.onSurfaceVariant,
        glass = scheme.surface.copy(alpha = if (dark) 0.72f else 0.85f),
        glassStrong = scheme.surface.copy(alpha = if (dark) 0.94f else 0.97f),
        glassBorder = scheme.outline.copy(alpha = 0.28f),
        accent = scheme.primary,
        accentContainer = scheme.primaryContainer,
        onAccent = scheme.onPrimary,
        scrim = Color.Black.copy(alpha = 0.42f),
        isDark = dark
    )
}
