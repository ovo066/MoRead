package com.mozhi.reader.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.feature.bookdetail.formatDuration
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.sqrt

@Composable
internal fun StatsTrend(state: StatsUiState) {
    val peak = state.trend.maxByOrNull { it.durationMs }
    StatsCard("阅读趋势", when (state.period) {
        StatsPeriod.TOTAL -> "历年阅读时长"; StatsPeriod.YEAR -> "每月阅读时长"; StatsPeriod.DAY -> "每小时阅读时长"; else -> "每日阅读时长"
    }) {
        if (state.period == StatsPeriod.DAY && state.hourlyDurations.sum() == 0L) {
            StatsEmpty(if (state.periodDurationMs > 0) "这一天只有按天汇总的记录，尚无具体时段。" else "这一天还没有阅读记录。")
        } else {
            StatsBars(state.trend)
            Text(if (peak == null || peak.durationMs <= 0) "这个周期还没有阅读记录" else "最高 ${formatDuration(peak.durationMs)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatsBars(bars: List<StatsBar>, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)
    val maximum = bars.maxOf { it.durationMs }.coerceAtLeast(1L)
    val step = when { bars.size <= 12 -> 1; bars.size <= 24 -> 4; else -> 5 }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(132.dp).semantics {
            contentDescription = bars.joinToString("；") { "${it.label}，${formatDuration(it.durationMs)}" }
        }) {
            val width = size.width / bars.size
            val gap = minOf(5.dp.toPx(), width * .35f)
            repeat(4) { i ->
                val y = size.height * i / 3f
                drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            bars.forEachIndexed { i, bar ->
                if (bar.durationMs <= 0) return@forEachIndexed
                val height = (bar.durationMs.toFloat() / maximum * (size.height - 6.dp.toPx())).coerceAtLeast(2.dp.toPx())
                drawRoundRect(accent.copy(alpha = if (bar.durationMs == maximum) 1f else .58f),
                    topLeft = Offset(i * width + gap / 2, size.height - height),
                    size = Size(width - gap, height), cornerRadius = CornerRadius(3.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEachIndexed { index, bar ->
                Text(if (index % step == 0 || index == bars.lastIndex) bar.label else "", modifier = Modifier.weight(1f).wrapContentWidth(unbounded = true),
                    textAlign = TextAlign.Center, maxLines = 1, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun StatsHours(state: StatsUiState) {
    val tracked = state.hourlyDurations.sum()
    val peak = state.timeBands.maxByOrNull { it.durationMs }
    StatsCard("阅读时间段", if (tracked > 0 && peak != null) "${peak.label}读得最多 · ${peak.hours} 时" else "发现一天中的阅读习惯") {
        if (tracked == 0L) {
            StatsEmpty("开始阅读后，这里会呈现真实的时段分布。早期按天汇总的记录仍保留在月历与时间线中。")
        } else {
            StatsBars(state.hourlyDurations.mapIndexed { index, duration -> StatsBar(index.toString(), duration) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                state.timeBands.forEach { band ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(band.label, style = MaterialTheme.typography.labelLarge,
                            color = if (band == peak) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${(band.durationMs.toDouble() / tracked * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                        Text(band.hours, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val withoutHours = (state.periodDurationMs - tracked).coerceAtLeast(0L)
            Text(if (withoutHours > 0) "已记录具体时段 ${formatDuration(tracked)}；另有 ${formatDuration(withoutHours)} 早期记录。"
                else "按实际阅读时长统计，横轴为一天中的小时", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatsCloud(title: String, subtitle: String, values: List<StatsCloudItem>, empty: String) {
    StatsCard(title, subtitle) {
        if (values.isEmpty()) StatsEmpty(empty)
        else {
            PackedStatsCloud(values)
        }
    }
}

@Composable
internal fun StatsHeatmap(state: StatsUiState, onDay: (LocalDate) -> Unit = {}) {
    val year = state.anchorDate.year
    val month = YearMonth.from(state.anchorDate)
    val all = state.durationsByEpochDay.filterValues { it > 0L }
    val monthly = state.period != StatsPeriod.YEAR && state.period != StatsPeriod.TOTAL
    val days = when {
        state.period == StatsPeriod.TOTAL -> all
        monthly -> all.filterKeys { YearMonth.from(LocalDate.ofEpochDay(it)) == month }
        else -> all.filterKeys { LocalDate.ofEpochDay(it).year == year }
    }
    val subtitle = when {
        state.period == StatsPeriod.TOTAL -> "全部记录 · 阅读 " + days.size + " 天"
        monthly -> year.toString() + "年" + month.monthValue + "月 · 阅读 " + days.size + " 天"
        else -> year.toString() + "年 · 阅读 " + days.size + " 天"
    }
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainer
    StatsCard("阅读热力", subtitle) {
        if (monthly) {
            val maximum = days.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            val leading = month.atDay(1).dayOfWeek.value - 1
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                repeat((leading + month.lengthOfMonth() + 6) / 7) { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(7) { column ->
                            val day = week * 7 + column - leading + 1
                            if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f))
                            else {
                                val date = month.atDay(day)
                                val duration = days[date.toEpochDay()] ?: 0L
                                val color = if (duration <= 0) track else accent.copy(alpha = .22f + .78f * sqrt(duration.toFloat() / maximum))
                                Box(Modifier.weight(1f).aspectRatio(1f).background(color, RoundedCornerShape(7.dp))
                                    .clickable(enabled = date <= state.today) { onDay(date) }
                                    .testTag("heatmap-day-" + date).semantics {
                                        contentDescription = date.toString() + "，阅读" + formatDuration(duration)
                                    })
                            }
                        }
                    }
                }
            }
        } else {
            val tiles = if (state.period == StatsPeriod.YEAR) {
                (1..12).map { number ->
                    StatsBar(number.toString() + "月", days.filterKeys { LocalDate.ofEpochDay(it).monthValue == number }.values.sum())
                }
            } else {
                days.entries.groupBy { LocalDate.ofEpochDay(it.key).year }.toSortedMap().map { (number, entries) ->
                    StatsBar(number.toString() + "年", entries.sumOf { it.value })
                }
            }
            if (tiles.isEmpty()) StatsEmpty("开始阅读后，这里会留下阅读的足迹。")
            else {
                val maximum = tiles.maxOf { it.durationMs }.coerceAtLeast(1L)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { tile ->
                                val strength = if (tile.durationMs <= 0) 0f else .25f + .75f * sqrt(tile.durationMs.toFloat() / maximum)
                                Surface(Modifier.weight(1f).height(68.dp), shape = RoundedCornerShape(13.dp),
                                    color = if (strength == 0f) track else MaterialTheme.colorScheme.primaryContainer.copy(alpha = strength),
                                    contentColor = MaterialTheme.colorScheme.onSurface) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.Center) {
                                        Text(tile.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                        Text(formatDuration(tile.durationMs), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("少", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(0f, .25f, .5f, .75f, 1f).forEach { alpha ->
                Box(Modifier.padding(start = 4.dp).size(9.dp).background(if (alpha == 0f) track else accent.copy(alpha = alpha), RoundedCornerShape(2.dp)))
            }
            Text("多", modifier = Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
