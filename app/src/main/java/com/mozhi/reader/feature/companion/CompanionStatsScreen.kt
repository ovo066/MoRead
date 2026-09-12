package com.mozhi.reader.feature.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

@Composable
fun CompanionStatsScreen(onBack: () -> Unit, viewModel: CompanionStatsViewModel = hiltViewModel()) {
    val selected by viewModel.selection.collectAsStateWithLifecycle()
    val stats by viewModel.statistics.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf(false) }
    var scopeMenu by remember { mutableStateOf(false) }
    MoReadSecondaryPage(
        title = "陪伴足迹", onBack = onBack,
        actions = { IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, "统计说明") } }
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompanionStatsPeriod.entries.forEach { period ->
                        FilterChip(selected.period == period, { viewModel.selectPeriod(period) }, label = { Text(period.label) })
                    }
                }
                Box {
                    TextButton(onClick = { scopeMenu = true }) { Text(when (selected.scope) {
                        CompanionStatsScope.ALL -> "全部 ▾"
                        CompanionStatsScope.BOOK -> "书内 ▾"
                        CompanionStatsScope.LIBRARY -> "书库 ▾"
                    }) }
                    DropdownMenu(scopeMenu, { scopeMenu = false }) {
                        CompanionStatsScope.entries.forEach { scope ->
                            DropdownMenuItem(text = { Text(scope.label) }, onClick = { viewModel.selectScope(scope); scopeMenu = false })
                        }
                    }
                }
            }
        }
        item { CompanionStatsOverview(stats) }
        item {
            FrostedSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("聊书的日子", style = MaterialTheme.typography.titleSmall)
                        Text(stats.activeDays.toString() + " 天有过交流", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CompanionDayDots(stats, selected.period)
                }
            }
        }
        item {
            Text(
                if (stats.rounds == 0) "下一页，也一起读。" else "交换了 " + stats.rounds + " 次想法，留下 " + stats.conversations + " 段对话。",
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (info) AlertDialog(
        onDismissRequest = { info = false }, title = { Text("这些数字") },
        text = { Text("书籍：保留记录且有过伴读交流的书。\n时长：这些书的阅读记录，非同步在线计时。\n字数：你和伴读的消息正文。\n旧记录按现存记录统计，删除后同步变化。") },
        confirmButton = { TextButton(onClick = { info = false }) { Text("知道了") } }
    )
}

@Composable
internal fun CompanionStatsOverview(stats: CompanionStatistics) {
    val first = stats.firstChatDate
    val days = first?.let { ChronoUnit.DAYS.between(it, LocalDate.now()).coerceAtLeast(0) + 1 }
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FrostedSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(days?.let { "相伴第 " + it + " 天" } ?: "从第一句话开始", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(28.dp), tint = accent.copy(alpha = 0.65f))
                }
                Text("一起读过", Modifier.padding(top = 26.dp), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stats.books.toString(), fontSize = 64.sp, fontWeight = FontWeight.Light, color = accent)
                    Text("本书", Modifier.padding(bottom = 14.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                first?.let { Text(it.toString().replace('-', '.') + " — 今天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CompanionLifeMetric("读了", formatCompanionReadingDuration(stats.readingDurationMs), Modifier.weight(1f))
            val words = if (stats.chatCharacters >= 10_000) String.format(Locale.ROOT, "%.1f 万字", stats.chatCharacters / 10_000.0) else stats.chatCharacters.toString() + " 字"
            CompanionLifeMetric("聊了", words, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompanionLifeMetric(label: String, value: String, modifier: Modifier) {
    FrostedSurface(modifier, shape = RoundedCornerShape(22.dp), shadowElevation = 4.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CompanionDayDots(stats: CompanionStatistics, period: CompanionStatsPeriod) {
    val count = period.days?.toInt() ?: 35
    val rows = (count + 6) / 7
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val today = LocalDate.now()
    val from = today.minusDays((count - 1).toLong())
    val daysWithChats = stats.roundsByDay.count { (day, _) -> !day.isBefore(from) && !day.isAfter(today) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height((rows * 21).dp).semantics {
            contentDescription = "最近 $count 天，$daysWithChats 天有过交流"
        }) {
            val gap = 7.dp.toPx()
            val width = (size.width - 6 * gap) / 7f
            val height = 13.dp.toPx()
            for (index in 0 until count) {
                val day = today.minusDays((count - index - 1).toLong())
                val exchanges = stats.roundsByDay[day] ?: 0
                drawRoundRect(
                    if (exchanges == 0) track else accent.copy(alpha = (0.25f + exchanges * 0.12f).coerceAtMost(0.9f)),
                    Offset(index % 7 * (width + gap), index / 7 * 21.dp.toPx()), Size(width, height), CornerRadius(4.dp.toPx())
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${from.monthValue}.${from.dayOfMonth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("今天", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
