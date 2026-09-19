package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings

/**
 * 字体页只管「用哪套字」。字号、行距、字重这些数值项都在悬浮排版卡片里——
 * 它们要边调边看重排，二级页在半屏弹层里看不见正文。
 */
@Composable
internal fun FontPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderFontActions
) {
    Text("内置字体", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        ReaderFont.entries.filter { it != ReaderFont.CUSTOM }.forEach { font ->
            SegChip(
                text = font.shortLabel(),
                selected = settings.font == font,
                palette = palette,
                modifier = Modifier.width(76.dp)
            ) { actions.onFontChange(font) }
        }
    }
    if (settings.fontLibrary.isNotEmpty()) {
        Text("已导入字体 · 滑动预览", style = MaterialTheme.typography.labelMedium, color = palette.muted)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(settings.fontLibrary, key = { it.id }) { font ->
                com.mozhi.reader.ui.components.FontPreviewChoice(
                    font = font,
                    selected = settings.font == ReaderFont.CUSTOM &&
                        settings.selectedCustomFontId == font.id,
                    onClick = { actions.onCustomFontSelect(font.id) },
                    background = palette.background, foreground = palette.onBackground,
                    accent = palette.accent, outline = palette.glassBorder
                )
            }
        }
    }
    Surface(
        onClick = actions.onImportFont,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text("导入 TTF/OTF/TTC", style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun ReaderFont.shortLabel(): String = when (this) {
    ReaderFont.SYSTEM -> "默认"
    ReaderFont.SERIF -> "宋体"
    ReaderFont.SANS_SERIF -> "黑体"
    ReaderFont.MONOSPACE -> "等宽"
    ReaderFont.CUSTOM -> "自定义"
}
