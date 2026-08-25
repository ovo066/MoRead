package com.mozhi.reader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CompanionVoiceBubble(
    text: String,
    clip: VoiceClipState?,
    palette: ReaderPalette,
    onPrepare: () -> Unit,
    onRegenerate: () -> Unit,
    onPlay: (String) -> Unit
) {
    var expanded by remember(text) { mutableStateOf(false) }
    LaunchedEffect(text) { onPrepare() }
    Column(modifier = Modifier.width(230.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                clip?.loading == true -> CircularProgressIndicator(
                    modifier = Modifier.size(32.dp).padding(6.dp),
                    strokeWidth = 2.dp
                )
                clip?.path != null -> Surface(
                    shape = CircleShape,
                    color = palette.accent.copy(alpha = 0.18f),
                    modifier = Modifier.size(32.dp).clickable { onPlay(clip.path) }
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "播放语音",
                        modifier = Modifier.padding(6.dp)
                    )
                }
                else -> IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "重新合成语音")
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                deterministicWaveform(text).forEach { height ->
                    Surface(
                        color = palette.accent.copy(alpha = 0.78f),
                        shape = CircleShape,
                        modifier = Modifier.width(2.dp).height(height.dp)
                    ) {}
                }
            }
            Text(
                "${(text.length / 4).coerceAtLeast(1)}″",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted
            )
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起文字" else "展开文字"
                )
            }
        }
        if (clip?.missing == true) {
            Text(
                "点击左侧重新合成",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted
            )
        }
        if (expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }
    }
}

internal fun deterministicWaveform(text: String): List<Int> {
    var seed = text.hashCode().toLong() and 0xffffffffL
    return List(24) {
        seed = (seed * 1664525L + 1013904223L) and 0xffffffffL
        6 + (seed % 19).toInt()
    }
}
