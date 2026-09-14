package com.mozhi.reader.feature.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.mozhi.reader.feature.bookdetail.formatDuration

@Composable
internal fun StatsCard(title: String, subtitle: String, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().testTag("stats-card-$title"), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .28f))) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
internal fun StatsOverview(state: StatsUiState) {
    Surface(modifier = Modifier.testTag("stats-overview"), shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .36f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.period.readingLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(formatDuration(state.periodDurationMs), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(periodComparison(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OverviewMetric(state.periodReadingDays.toString(), "阅读天数", Modifier.weight(1f))
                OverviewMetric(state.periodBooks.size.toString(), "读过的书", Modifier.weight(1f))
                OverviewMetric((if (state.periodReadingDays == 0) 0 else state.periodDurationMs / state.periodReadingDays / 60_000).toString(), "日均分钟", Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = .13f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("连续阅读 ${state.streakDays} 天", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("最长 ${state.longestStreakDays} 天", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("累计读完 ${state.finishedBooks} 本  ·  笔记 ${state.bookmarkNoteCount}  ·  AI 对话 ${state.aiChatCount}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverviewMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun periodComparison(state: StatsUiState): String = when {
    state.period == StatsPeriod.TOTAL -> "共记录 ${state.periodReadingDays} 个阅读日"
    state.previousPeriodDurationMs <= 0 && state.periodDurationMs > 0 -> "${state.period.previousLabel}暂无记录，继续积累阅读时光"
    state.previousPeriodDurationMs <= 0 -> "这个周期还没有阅读记录"
    state.periodDurationMs >= state.previousPeriodDurationMs -> "比${state.period.previousLabel}多读 ${formatDuration(state.periodDurationMs - state.previousPeriodDurationMs)}"
    else -> "比${state.period.previousLabel}少读 ${formatDuration(state.previousPeriodDurationMs - state.periodDurationMs)}"
}

@Composable
internal fun StatsEmpty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
}

@Composable
internal fun StatsBooks(state: StatsUiState) {
    StatsCard("阅读排行", state.period.topBooksLabel) {
        if (state.topBooks.isEmpty()) StatsEmpty("这个周期还没有读过的书。")
        val maximum = state.topBooks.maxOfOrNull { it.durationMs }?.coerceAtLeast(1L) ?: 1L
        state.topBooks.forEachIndexed { index, stat ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text((index + 1).toString().padStart(2, '0'), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                StatsBookCover(stat.book, Modifier.size(34.dp, 48.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stat.book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
                        Box(Modifier.fillMaxWidth((stat.durationMs.toFloat() / maximum).coerceIn(.01f, 1f)).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary))
                    }
                    Text(formatDuration(stat.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
