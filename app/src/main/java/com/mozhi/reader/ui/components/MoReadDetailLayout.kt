package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A summary stays visible alongside independently scrollable material when both panes fit. */
@Composable
internal fun MoReadDetailLayout(
    header: @Composable () -> Unit,
    summary: @Composable () -> Unit,
    centerSummary: Boolean = false,
    content: @Composable (split: Boolean) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val split = maxWidth >= 840.dp
        val summaryWidth = if (split) minOf(380.dp, maxWidth * .36f) else 0.dp
        Column(Modifier.fillMaxSize()) {
            if (split) header()
            Row(Modifier.weight(1f).widthIn(max = 1320.dp).fillMaxWidth().align(Alignment.CenterHorizontally)
                .padding(horizontal = if (split) 32.dp else 0.dp)) {
                BoxWithConstraints(Modifier.width(summaryWidth).fillMaxHeight()) {
                    val paneHeight = maxHeight
                    if (split) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .heightIn(min = if (centerSummary) paneHeight else 0.dp).testTag("detail-summary"),
                        verticalArrangement = if (centerSummary) Arrangement.Center else Arrangement.Top) { summary() }
                }
                Box(Modifier.weight(1f).fillMaxHeight().testTag("detail-content")) { content(split) }
            }
        }
    }
}
