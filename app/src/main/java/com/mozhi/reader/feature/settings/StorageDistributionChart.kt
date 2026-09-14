package com.mozhi.reader.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.storage.StorageCategory
import com.mozhi.reader.core.storage.StorageCategoryUsage
import kotlin.math.atan2
import kotlin.math.hypot

internal data class StorageChartSlice(
    val key: String, val title: String, val bytes: Long, val categories: List<StorageCategoryUsage>
)

internal fun storageChartSlices(categories: List<StorageCategoryUsage>, expanded: Boolean = false): List<StorageChartSlice> {
    val sorted = categories.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    fun slice(row: StorageCategoryUsage) = StorageChartSlice(row.category.name, row.category.title, row.bytes, listOf(row))
    if (expanded || sorted.size <= 6) return sorted.map(::slice)
    val remainder = sorted.drop(5)
    return sorted.take(5).map(::slice) +
        StorageChartSlice("remainder", "其余 ${remainder.size} 项", remainder.sumOf { it.bytes }, remainder)
}

internal fun storageSliceAt(slices: List<StorageChartSlice>, turn: Double): StorageChartSlice? {
    val total = slices.sumOf { it.bytes }.toDouble()
    if (total <= 0 || !turn.isFinite() || turn < 0 || turn >= 1) return null
    var end = 0.0
    return slices.firstOrNull { end += it.bytes / total; turn < end } ?: slices.lastOrNull()
}

private fun StorageChartSlice.color(): Color = when (categories.singleOrNull()?.category) {
    StorageCategory.VECTOR -> Color(0xFF8872D9)
    StorageCategory.SPEECH -> Color(0xFFE6A54A)
    StorageCategory.TEXT -> Color(0xFF29A59C)
    StorageCategory.ORIGINALS -> Color(0xFF5B8DEF)
    StorageCategory.MEDIA -> Color(0xFFD977A2)
    StorageCategory.LAYOUT -> Color(0xFF53A8C3)
    StorageCategory.ILLUSTRATIONS -> Color(0xFFB780D6)
    StorageCategory.ATTACHMENTS -> Color(0xFFDC8165)
    StorageCategory.COVERS -> Color(0xFF5DA587)
    StorageCategory.CUSTOM -> Color(0xFF8A9E64)
    StorageCategory.DATABASE -> Color(0xFF788B9F)
    StorageCategory.BACKUP -> Color(0xFFC38869)
    StorageCategory.CACHE -> Color(0xFFA1ACB9)
    else -> Color(0xFF9B9EA6)
}

internal fun storagePercentage(bytes: Long, total: Long): String {
    if (bytes <= 0 || total <= 0) return "0%"
    val percent = bytes.toDouble() / total * 100
    return if (percent < 0.1) "<0.1%" else String.format(java.util.Locale.ROOT, "%.1f%%", percent)
}

@Composable
internal fun StorageDistributionChart(categories: List<StorageCategoryUsage>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val slices = remember(categories, expanded) { storageChartSlices(categories, expanded) }
    val total = slices.sumOf { it.bytes }
    val selected = slices.firstOrNull { it.key == selectedKey }
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    fun select(slice: StorageChartSlice) { selectedKey = slice.key.takeUnless { it == selectedKey } }

    Column(modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (slices.isEmpty()) {
            Text("暂无存储数据", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(184.dp).testTag("storage-donut")
                .semantics { contentDescription = "存储占用环形图，总量 ${formatBytes(total)}，可点击下方分类查看占比" }
                .pointerInput(slices) {
                    detectTapGestures { position ->
                        val dx = position.x - size.width / 2f
                        val dy = position.y - size.height / 2f
                        val radius = minOf(size.width, size.height) / 2f
                        if (hypot(dx, dy) in radius * 0.58f..radius) {
                            val turn = (atan2(dy.toDouble(), dx.toDouble()) / (2 * Math.PI) + 1.25) % 1
                            storageSliceAt(slices, turn)?.let(::select)
                        }
                    }
                }) {
                val inset = 18.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(trackColor, -90f, 360f, false, topLeft, arcSize, style = Stroke(26.dp.toPx()))
                var angle = -90f
                slices.forEach { slice ->
                    val sweep = (slice.bytes.toDouble() / total * 360).toFloat()
                    val gap = minOf(2.2f, sweep * 0.3f)
                    val focused = selected?.key == slice.key
                    drawArc(slice.color().copy(alpha = if (selected == null || focused) 1f else 0.3f),
                        angle + gap / 2, sweep - gap, false, topLeft, arcSize,
                        style = Stroke(if (focused) 31.dp.toPx() else 26.dp.toPx()))
                    angle += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(selected?.let { storagePercentage(it.bytes, total) } ?: formatBytes(total),
                    style = MaterialTheme.typography.headlineSmall)
                Text(selected?.title ?: "总占用", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        slices.forEach { slice ->
            val color = slice.color()
            Surface(shape = RoundedCornerShape(12.dp),
                color = if (selected?.key == slice.key) color.copy(alpha = 0.10f) else Color.Transparent,
                modifier = Modifier.fillMaxWidth().clickable(onClickLabel = "查看${slice.title}占用") { select(slice) }) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).background(color, CircleShape))
                        Text(slice.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(slice.bytes), style = MaterialTheme.typography.labelLarge)
                        Text(storagePercentage(slice.bytes, total), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(trackColor)) {
                        Box(Modifier.fillMaxWidth((slice.bytes.toDouble() / total).toFloat()).fillMaxHeight()
                            .clip(CircleShape).background(color))
                    }
                }
            }
        }
        if (categories.count { it.bytes > 0 } > 6) {
            TextButton(onClick = { expanded = !expanded; selectedKey = null }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (expanded) "收起小项" else "展开全部分类")
            }
        }
        val detail = selected?.categories?.singleOrNull()?.category?.description
            ?: selected?.categories?.joinToString("、") { it.category.title }
        Text(detail ?: "点击色块或条形图，可查看该类数据的占比与说明。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
