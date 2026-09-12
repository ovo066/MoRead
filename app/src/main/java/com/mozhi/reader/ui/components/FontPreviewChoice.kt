package com.mozhi.reader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.ui.theme.rememberAppFontFamily

/** Font names remain readable UI text; the same sample is rendered in each candidate font. */
@Composable
fun FontPreviewChoice(
    font: ReaderFontAsset,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    accent: Color = MaterialTheme.colorScheme.primary,
    outline: Color = MaterialTheme.colorScheme.outlineVariant,
    fontFamily: FontFamily? = rememberAppFontFamily(font.filePath)
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(160.dp).semantics { this.selected = selected; role = Role.RadioButton },
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = .12f).compositeOver(background) else background,
        contentColor = foreground,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else outline)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(font.displayName, style = MaterialTheme.typography.labelSmall,
                color = if (selected) accent else foreground.copy(alpha = .75f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("阅读 Aa 123", fontFamily = fontFamily, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
