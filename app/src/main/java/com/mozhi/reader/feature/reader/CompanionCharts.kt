package com.mozhi.reader.feature.reader

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.InsertChart
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mozhi.reader.R
import com.mozhi.reader.ai.agent.ChartSpec
import com.mozhi.reader.ai.agent.ChartType
import com.mozhi.reader.ai.agent.RelationLayout
import com.mozhi.reader.ui.components.ImageExportActions
import com.mozhi.reader.ui.components.shelfTagPalette
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModel
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 伴读回复里的一张图。卡片高度只由规格决定（流式时不随解码或布局改变），
 * 点右上角放大后可以导出为图片。
 */
@Composable
internal fun CompanionChartCard(spec: ChartSpec, palette: ReaderPalette, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = palette.glass,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.InsertChart, null, Modifier.size(16.dp), tint = palette.accent)
                Text(
                    text = spec.title.ifBlank { stringResource(R.string.chart_untitled) },
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp)
                )
                IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.OpenInFull, stringResource(R.string.chart_expand), Modifier.size(16.dp), tint = palette.muted)
                }
            }
            Box(Modifier.padding(end = 8.dp)) { ChartBody(spec, palette, compactHeight(spec)) }
        }
    }
    if (expanded) CompanionChartDialog(spec, palette) { expanded = false }
}

@Composable
private fun CompanionChartDialog(spec: ChartSpec, palette: ReaderPalette, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var exportPath by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = palette.background, contentColor = palette.onBackground, shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.title.ifBlank { stringResource(R.string.chart_untitled) }, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, stringResource(R.string.chart_close)) }
                }
                Box(
                    Modifier
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            drawLayer(layer)
                        }
                        .background(palette.background)
                        .padding(vertical = 8.dp)
                ) { ChartBody(spec, palette, expandedHeight(spec)) }
                notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = palette.muted) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (exportPath == null) {
                        androidx.compose.material3.TextButton(onClick = {
                            scope.launch {
                                val bitmap = layer.toImageBitmap().asAndroidBitmap()
                                exportPath = withContext(Dispatchers.IO) {
                                    val dir = File(context.cacheDir, "chart-exports").apply { mkdirs() }
                                    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
                                    File(dir, "chart-${System.currentTimeMillis()}.png").also { file ->
                                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                    }.absolutePath
                                }
                            }
                        }) { Text(stringResource(R.string.chart_prepare_export), color = palette.accent) }
                    } else {
                        ImageExportActions(path = exportPath, onResult = { notice = it })
                    }
                }
            }
        }
    }
}

private fun compactHeight(spec: ChartSpec): Dp = when (spec.chartType) {
    ChartType.RELATION -> 260.dp
    ChartType.TIMELINE -> (spec.events.size * 46 + 8).coerceAtMost(300).dp
    ChartType.PIE, ChartType.RADAR -> 210.dp
    else -> 200.dp
}

private fun expandedHeight(spec: ChartSpec): Dp = when (spec.chartType) {
    ChartType.RELATION -> 420.dp
    ChartType.TIMELINE -> (spec.events.size * 56 + 8).coerceAtMost(520).dp
    else -> 320.dp
}

@Composable
private fun ChartBody(spec: ChartSpec, palette: ReaderPalette, height: Dp) {
    val colors = chartColors(palette)
    Column {
        Box(Modifier.fillMaxWidth().height(height)) {
            when (spec.chartType) {
                ChartType.BAR, ChartType.LINE -> CartesianChart(spec, palette, colors)
                ChartType.PIE -> PieChart(spec, palette, colors)
                ChartType.RADAR -> RadarChart(spec, palette, colors)
                ChartType.RELATION -> RelationChart(spec, palette, colors)
                ChartType.TIMELINE -> TimelineChart(spec, palette)
            }
        }
        val legend = when (spec.chartType) {
            ChartType.PIE -> spec.labels.mapIndexed { index, label ->
                val value = spec.series.single().values[index]
                "$label ${formatValue(value)}${spec.unit}" to colors[index % colors.size]
            }
            ChartType.BAR, ChartType.LINE, ChartType.RADAR -> if (spec.series.size > 1 || spec.series.single().name.isNotBlank()) {
                spec.series.mapIndexed { index, series -> series.name to colors[index % colors.size] }
            } else emptyList()
            ChartType.RELATION -> spec.nodes.map { it.group }.filter(String::isNotBlank).distinct().mapIndexed { index, group ->
                group to colors[index % colors.size]
            }
            ChartType.TIMELINE -> emptyList()
        }
        if (legend.isNotEmpty()) ChartLegend(legend, palette)
    }
}

/** 系列配色：先用强调色，再接主题里的标签色板，深浅纸上都看得清。 */
@Composable
private fun chartColors(palette: ReaderPalette): List<Color> =
    (listOf(palette.accent) + shelfTagPalette()).distinct()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(items: List<Pair<String, Color>>, palette: ReaderPalette) {
    FlowRow(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(label, style = MaterialTheme.typography.labelSmall, color = palette.muted,
                    modifier = Modifier.padding(start = 4.dp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun CartesianChart(spec: ChartSpec, palette: ReaderPalette, colors: List<Color>) {
    val labels = spec.labels
    val model = remember(spec) {
        val values = spec.series.map { series -> series.values }
        CartesianChartModel(
            if (spec.chartType == ChartType.BAR) {
                ColumnCartesianLayerModel.build { values.forEach { series(it) } }
            } else {
                LineCartesianLayerModel.build { values.forEach { series(it) } }
            }
        )
    }
    val bottomFormatter = remember(labels) {
        object : CartesianValueFormatter {
            override fun format(context: CartesianMeasuringContext, value: Double, verticalAxisPosition: Axis.Position.Vertical?): CharSequence =
                labels.getOrNull(value.toInt()).orEmpty()
        }
    }
    ProvideVicoTheme(
        rememberM3VicoTheme(
            columnCartesianLayerColors = colors,
            lineCartesianLayerColors = colors,
            lineColor = palette.glassBorder,
            textColor = palette.muted
        )
    ) {
        CartesianChartHost(
            rememberCartesianChart(
                if (spec.chartType == ChartType.BAR) rememberColumnCartesianLayer() else rememberLineCartesianLayer(),
                startAxis = VerticalAxis.rememberStart(),
                bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = bottomFormatter)
            ),
            model,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PieChart(spec: ChartSpec, palette: ReaderPalette, colors: List<Color>) {
    val values = spec.series.single().values
    val total = values.sum()
    Canvas(Modifier.fillMaxSize()) {
        val diameter = min(size.width, size.height) * 0.92f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val ring = diameter * 0.22f
        var start = -90f
        values.forEachIndexed { index, value ->
            val sweep = (value / total * 360.0).toFloat()
            if (sweep > 0f) {
                drawArc(
                    color = colors[index % colors.size],
                    startAngle = start,
                    sweepAngle = (sweep - 1.2f).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = Offset(topLeft.x + ring / 2, topLeft.y + ring / 2),
                    size = Size(diameter - ring, diameter - ring),
                    style = Stroke(width = ring)
                )
            }
            start += sweep
        }
        drawCircle(palette.glassBorder, radius = diameter / 2f - ring - 2f, center = center, style = Stroke(1f))
    }
}

@Composable
private fun RadarChart(spec: ChartSpec, palette: ReaderPalette, colors: List<Color>) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = palette.muted)
    val max = spec.series.flatMap { it.values }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Canvas(Modifier.fillMaxSize()) {
        val count = spec.labels.size
        val radius = min(size.width, size.height) / 2f * 0.72f
        fun point(index: Int, fraction: Float): Offset {
            val angle = -PI / 2 + 2 * PI * index / count
            return Offset(center.x + (radius * fraction * cos(angle)).toFloat(), center.y + (radius * fraction * sin(angle)).toFloat())
        }
        for (ring in 1..4) {
            val path = Path()
            for (i in 0 until count) {
                val p = point(i, ring / 4f)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            drawPath(path, palette.glassBorder, style = Stroke(1f))
        }
        for (i in 0 until count) {
            drawLine(palette.glassBorder, center, point(i, 1f), 1f)
            val layout = measurer.measure(spec.labels[i], labelStyle, maxLines = 1)
            val at = point(i, 1.16f)
            drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
        }
        spec.series.forEachIndexed { index, series ->
            val path = Path()
            series.values.forEachIndexed { i, value ->
                val p = point(i, (value / max).toFloat().coerceIn(0f, 1f))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            path.close()
            val color = colors[index % colors.size]
            drawPath(path, color.copy(alpha = 0.18f))
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun RelationChart(spec: ChartSpec, palette: ReaderPalette, colors: List<Color>) {
    val measurer = rememberTextMeasurer()
    val positions = remember(spec) { RelationLayout.layout(spec.nodes.map { it.id }, spec.edges.map { it.from to it.to }) }
    val groups = remember(spec) { spec.nodes.map { it.group }.filter(String::isNotBlank).distinct() }
    val nameStyle = TextStyle(fontSize = 11.sp, color = palette.onBackground, fontWeight = FontWeight.Medium)
    val edgeStyle = TextStyle(fontSize = 9.sp, color = palette.muted)
    Canvas(Modifier.fillMaxSize()) {
        val pad = 34.dp.toPx()
        fun at(id: String): Offset {
            val p = positions.getValue(id)
            return Offset(pad + p.first * (size.width - 2 * pad), pad + p.second * (size.height - 2 * pad))
        }
        spec.edges.forEach { edge ->
            val a = at(edge.from)
            val b = at(edge.to)
            drawLine(palette.muted.copy(alpha = 0.45f), a, b, 1.2.dp.toPx())
            if (edge.label.isNotBlank()) {
                val layout = measurer.measure(edge.label, edgeStyle, maxLines = 1)
                val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                drawRect(palette.background, Offset(mid.x - layout.size.width / 2f - 2f, mid.y - layout.size.height / 2f),
                    Size(layout.size.width + 4f, layout.size.height.toFloat()))
                drawText(layout, topLeft = Offset(mid.x - layout.size.width / 2f, mid.y - layout.size.height / 2f))
            }
        }
        spec.nodes.forEach { node ->
            val p = at(node.id)
            val groupIndex = groups.indexOf(node.group)
            val color = if (groupIndex >= 0) colors[groupIndex % colors.size] else palette.accent
            drawCircle(color, radius = 7.dp.toPx(), center = p)
            drawCircle(palette.background, radius = 7.dp.toPx(), center = p, style = Stroke(2.dp.toPx()))
            val layout = measurer.measure(node.label, nameStyle, maxLines = 1)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y + 9.dp.toPx()))
        }
    }
}

@Composable
private fun TimelineChart(spec: ChartSpec, palette: ReaderPalette) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        spec.events.forEachIndexed { index, event ->
            Row(Modifier.fillMaxWidth().height(46.dp)) {
                Text(event.time, style = MaterialTheme.typography.labelSmall, color = palette.muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(64.dp).padding(top = 2.dp))
                Canvas(Modifier.width(18.dp).fillMaxSize()) {
                    val x = size.width / 2f
                    if (index > 0) drawLine(palette.glassBorder, Offset(x, 0f), Offset(x, 8.dp.toPx()), 2f)
                    if (index < spec.events.lastIndex) drawLine(palette.glassBorder, Offset(x, 8.dp.toPx()), Offset(x, size.height), 2f)
                    drawCircle(palette.accent, 4.dp.toPx(), Offset(x, 8.dp.toPx()))
                }
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(event.title, style = MaterialTheme.typography.labelMedium, color = palette.onBackground, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (event.detail.isNotBlank()) Text(event.detail, style = MaterialTheme.typography.labelSmall, color = palette.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun formatValue(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
