package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep the reader in the same composition slot when opening or closing companion chat. */
@Composable
internal fun ReaderCompanionLayout(
    companionVisible: Boolean,
    modifier: Modifier = Modifier,
    companion: @Composable () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Row(modifier = modifier) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight(), content = content)
        if (companionVisible) {
            VerticalDivider()
            Box(modifier = Modifier.width(com.mozhi.reader.ui.MoReadLayoutPolicy.CompanionPaneWidthDp.dp).fillMaxSize()) { companion() }
        }
    }
}
