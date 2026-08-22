package com.mozhi.reader.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.feature.bookdetail.formatDuration
import com.mozhi.reader.feature.bookshelf.coverColor
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.ReadingHeatmap
import com.mozhi.reader.ui.components.SectionLabel
import com.mozhi.reader.ui.components.StatCell
import com.mozhi.reader.ui.theme.sealColor
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 全局统计页（design/ui-adaptation-plan.md §4）。 */
@Composable
fun StatsScreen(
    contentPadding: PaddingValues,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("统计", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = viewModel::previousPeriod) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "上一周期")
                    }
                    Surface(
                        onClick = { showDatePicker = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.CalendarMonth,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                            Text(
                                text = state.periodLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = viewModel::nextPeriod,
                        enabled = state.canGoNext
                    ) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "下一周期")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatsPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = state.period == period,
                            onClick = { viewModel.setPeriod(period) },
                            label = { Text(period.selectorLabel) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        item {
            PeriodHeroCard(state = state)
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCell("${state.periodReadingDays}", "阅读天数", Modifier.weight(1f))
                StatCell("${state.finishedBooks}", "读完的书", Modifier.weight(1f))
                StatCell("${state.bookmarkNoteCount}", "笔记", Modifier.weight(1f))
                StatCell("${state.aiChatCount}", "AI 对话", Modifier.weight(1f))
            }
        }
        item {
            FrostedSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "阅读热力",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    ReadingHeatmap(
                        durationsByEpochDay = state.durationsByEpochDay,
                        todayEpochDay = LocalDate.now().toEpochDay(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            }
        }
        if (state.topBooks.isNotEmpty()) {
            item {
                SectionLabel(title = state.period.topBooksLabel)
            }
            item {
                FrostedSurface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    shadowElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val maxDuration = state.topBooks.maxOf(PeriodBookStat::durationMs)
                            .coerceAtLeast(1L)
                        state.topBooks.forEach { stat ->
                            TopBookRow(stat = stat, maxDurationMs = maxDuration)
                        }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = state.anchorDate
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            viewModel.selectDate(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                            )
                        }
                        showDatePicker = false
                    }
                ) { Text("跳转") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** 双栏 hero 卡：所选周期阅读时长 ｜ 连续阅读天数。 */
@Composable
private fun PeriodHeroCard(state: StatsUiState) {
    val seal = sealColor()
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 10.dp
    ) {
        Row(modifier = Modifier.padding(vertical = 20.dp)) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = formatDuration(state.periodDurationMs),
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = state.period.readingLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = periodComparison(
                        state.periodDurationMs,
                        state.previousPeriodDurationMs,
                        state.period
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            VerticalDivider(
                modifier = Modifier.height(72.dp).align(Alignment.CenterVertically),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${state.streakDays}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    color = seal
                )
                Text(
                    text = "连续阅读天数",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    text = "最长纪录 ${state.longestStreakDays} 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun TopBookRow(stat: PeriodBookStat, maxDurationMs: Long) {
    val book = stat.book
    val coverFile = androidx.compose.runtime.remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(width = 34.dp, height = 48.dp),
            shape = RoundedCornerShape(6.dp),
            color = coverColor(book.title)
        ) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(
                            (stat.durationMs.toFloat() / maxDurationMs).coerceIn(0.04f, 1f)
                        )
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
        Text(
            text = formatDuration(stat.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun periodComparison(current: Long, previous: Long, period: StatsPeriod): String = when {
    previous <= 0 && current > 0 -> "${period.previousLabel}无记录"
    previous <= 0 -> "该周期暂无阅读"
    current >= previous -> "较${period.previousLabel} +${formatDuration(current - previous)}"
    else -> "较${period.previousLabel} -${formatDuration(previous - current)}"
}
