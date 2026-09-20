package com.mozhi.reader.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mozhi.reader.feature.bookdetail.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun PackedStatsCloud(values: List<StatsCloudItem>) {
    val layout by produceState<CloudLayout?>(null, values) {
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            StatsCloudLayout.arrange(values) { context.ensureActive() }
        }
    }
    val colors = if (MaterialTheme.colorScheme.surface.luminance() < .5f) {
        listOf(Color(0xFF93BFEA), Color(0xFFC3B0E8), Color(0xFFE4BA85), Color(0xFF91CEC0), Color(0xFFE8AABB))
    } else {
        listOf(Color(0xFF346BA8), Color(0xFF79609A), Color(0xFFA3612F), Color(0xFF327D70), Color(0xFFA95165))
    }
    val cloud = layout
    if (cloud == null) {
        Box(Modifier.fillMaxWidth().height(200.dp))
        return
    }
    val paints = remember(cloud, colors) { cloud.words.map { word ->
        StatsCloudLayout.paint(word.fontSize, word.bold).apply { color = colors[Math.floorMod(word.item.label.hashCode(), colors.size)].toArgb() }
    } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height((maxWidth.value * cloud.height / cloud.width).dp)
            .testTag("packed-stats-cloud").semantics {
                contentDescription = values.joinToString("；") { "${it.label}，${it.bookCount}本书，阅读${formatDuration(it.durationMs)}" }
            }) {
            val canvas = drawContext.canvas.nativeCanvas
            val outer = canvas.save()
            canvas.scale(size.width / cloud.width, size.width / cloud.width)
            cloud.words.forEachIndexed { index, word ->
                val save = canvas.save()
                val paint = paints[index]
                canvas.translate(word.x + word.width / 2f, word.y + word.height / 2f)
                canvas.rotate(word.rotation)
                canvas.drawText(word.item.label, -paint.measureText(word.item.label) / 2f,
                    -(paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f, paint)
                canvas.restoreToCount(save)
            }
            canvas.restoreToCount(outer)
        }
    }
    if (cloud.omitted.isNotEmpty()) {
        var expanded by remember(values) { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起其余条目" else "另有 ${cloud.omitted.size} 项，展开查看") }
        if (expanded) cloud.omitted.forEach { Text("${it.label} · ${formatDuration(it.durationMs)}", style = MaterialTheme.typography.bodySmall) }
    }
}
