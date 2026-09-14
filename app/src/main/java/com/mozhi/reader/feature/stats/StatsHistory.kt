package com.mozhi.reader.feature.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.feature.bookdetail.formatDuration
import com.mozhi.reader.feature.bookshelf.coverColor
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.blockSheetDrag
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

/** 所有封面先占定尺寸；缺失、损坏或尚未解码时仍显示书名底图。 */
@Composable
internal fun StatsBookCover(book: BookEntity, modifier: Modifier = Modifier, compact: Boolean = false) {
    val file = remember(book.coverPath) { book.coverPath?.takeIf(String::isNotBlank)?.let(::File) }
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(coverColor(book.title))
        .testTag("stats-cover-${book.id}").clearAndSetSemantics {},
        contentAlignment = if (compact) Alignment.CenterStart else Alignment.Center) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.White.copy(alpha = .16f), Color.Transparent, Color.Black.copy(alpha = .15f)))))
        Text(if (compact) book.title.take(1) else book.title.take(4).chunked(2).joinToString("\n"),
            modifier = Modifier.padding(if (compact) 5.dp else 3.dp),
            fontSize = if (compact) 13.sp else 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = Color.White,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Clip)
        if (file != null) AsyncImage(file, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun StatsCalendar(state: StatsUiState, onDay: (LocalDate) -> Unit, onMonth: (LocalDate) -> Unit) {
    val month = YearMonth.from(state.anchorDate)
    val days = remember(state.monthTimeline) { state.monthTimeline.associateBy { it.epochDay } }
    val bookCount = remember(state.monthTimeline) { state.monthTimeline.flatMap { it.books }.map { it.book.id }.distinct().size }
    StatsCard("阅读月历", "", padding = 16.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(month.minusMonths(1).atDay(1)) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.ChevronLeft, "月历上一月")
            }
            Text("${month.year}年${month.monthValue}月", Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            IconButton(onClick = { onMonth(month.plusMonths(1).atDay(1)) }, enabled = month < YearMonth.from(state.today),
                modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ChevronRight, "月历下一月") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${days.size} 个阅读日 · $bookCount 本书", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(state.monthTimeline.sumOf { it.durationMs }), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cellHeight = ((maxWidth - 24.dp) / 7 / .68f).coerceIn(58.dp, 90.dp)
            val leading = month.atDay(1).dayOfWeek.value - 1
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    weekdays.forEach { Text(it, Modifier.weight(1f).padding(bottom = 3.dp), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                repeat((leading + month.lengthOfMonth() + 6) / 7) { week ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(7) { column ->
                            val number = week * 7 + column - leading + 1
                            if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(cellHeight))
                            else {
                                val date = month.atDay(number)
                                CalendarDay(date, days[date.toEpochDay()], date == state.today, date <= state.today,
                                    Modifier.weight(1f).height(cellHeight), onDay)
                            }
                        }
                    }
                }
            }
        }
        Text("点选日期查看当天的书 · 封面下方为阅读时长", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CalendarDay(date: LocalDate, day: StatsTimelineDay?, today: Boolean, enabled: Boolean,
    modifier: Modifier, onDay: (LocalDate) -> Unit) {
    val mainBook = day?.books?.maxWithOrNull(compareBy<PeriodBookStat> { it.durationMs }.thenByDescending { it.book.id })
    Surface(onClick = { onDay(date) }, enabled = enabled, modifier = modifier.testTag("calendar-$date").semantics {
        contentDescription = "${date.year}年${date.monthValue}月${date.dayOfMonth}日，" +
            if (day == null) "没有阅读记录" else "阅读${formatDuration(day.durationMs)}，" + day.books.joinToString("、") { it.book.title }
    }, shape = RoundedCornerShape(8.dp),
        border = if (today) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.fillMaxSize()) {
            if (mainBook != null) {
                StatsBookCover(mainBook.book, Modifier.fillMaxSize(), compact = true)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = .5f), .45f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))))
                Text(compactReadingDuration(day.durationMs), Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                    fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1)
                if (day.books.size > 1) Text("+${day.books.size - 1}", Modifier.align(Alignment.CenterEnd).padding(end = 2.dp)
                    .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(3.dp)).padding(horizontal = 2.dp),
                    fontSize = 8.sp, lineHeight = 11.sp, color = Color.White)
            }
            Text(date.dayOfMonth.toString(), Modifier.align(Alignment.TopStart).padding(start = 3.dp, top = 3.dp)
                .background(if (mainBook != null) Color.Black.copy(alpha = .72f) else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp, vertical = 1.dp),
                fontSize = 12.sp, lineHeight = 15.sp, fontWeight = if (mainBook != null || today) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    mainBook != null -> Color.White
                    !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .3f)
                    today -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
        }
    }
}

@Composable
internal fun StatsTimeline(state: StatsUiState, onShowAll: () -> Unit) {
    StatsCard("阅读时间线", if (state.timelineWeeks.size > 2) "最近两个阅读周 · 连线串起连续阅读日" else "圆点对应阅读日，连线串起连续阅读", padding = 16.dp) {
        if (state.timeline.isEmpty()) StatsEmpty("读过的书会按日期留在这里。")
        state.timelineWeeks.take(2).forEachIndexed { index, week ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
            TimelineWeek(week, state.today)
        }
        if (state.timeline.isNotEmpty()) TextButton(onClick = onShowAll, modifier = Modifier.align(Alignment.End),
            contentPadding = PaddingValues(horizontal = 0.dp)) { Text("查看全部 ${state.timeline.size} 天") }
    }
}

@Composable
private fun TimelineWeek(week: StatsTimelineWeek, today: LocalDate) {
    val start = LocalDate.ofEpochDay(week.startEpochDay)
    Column(Modifier.fillMaxWidth().testTag("timeline-week-$start"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(statsPeriodLabel(StatsPeriod.WEEK, start), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val labelWidth = if (maxWidth < 280.dp) 78.dp else 94.dp
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(labelWidth))
                    repeat(7) { offset ->
                        val date = start.plusDays(offset.toLong())
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(weekdays[offset], fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(date.dayOfMonth.toString(), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                week.books.take(4).forEach { reading ->
                    val color = statsHistoryColor(reading.book.id)
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        contentDescription = reading.book.title + "，" + reading.dailyDurations.mapIndexedNotNull { offset, duration ->
                            if (duration > 0) "${start.plusDays(offset.toLong())} 阅读${formatDuration(duration)}" else null
                        }.joinToString("；")
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(labelWidth).padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            StatsBookCover(reading.book, Modifier.size(25.dp, 36.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(reading.book.title, fontSize = 10.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(compactReadingDuration(reading.dailyDurations.sum()), fontSize = 9.sp, lineHeight = 11.sp, color = color)
                            }
                        }
                        BoxWithConstraints(Modifier.weight(1f).height(42.dp)) {
                            val cell = maxWidth / 7
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                repeat(7) { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(3.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f), CircleShape))
                                } }
                            }
                            readingSpans(reading.dailyDurations).forEach { span ->
                                Box(Modifier.offset(x = cell * span.first + 2.dp).width(cell * span.count() - 4.dp).height(20.dp)
                                    .align(Alignment.CenterStart).background(color.copy(alpha = .18f), RoundedCornerShape(10.dp))
                                    .testTag("timeline-span-${week.startEpochDay}-${reading.book.id}-${span.first}-${span.last}"))
                            }
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                                reading.dailyDurations.forEach { duration ->
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                        if (duration > 0) Box(Modifier.size(7.dp).background(color, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (week.books.size > 4) Text("本周另有 ${week.books.size - 4} 本书，展开查看完整记录", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StatsTimelineSheet(state: StatsUiState, selectedDay: Long? = null, onDismiss: () -> Unit) {
    val days = if (selectedDay == null) state.timeline else state.monthTimeline.filter { it.epochDay == selectedDay }
    val title = if (selectedDay == null) "阅读时间线" else "当日阅读"
    val label = if (selectedDay == null) state.periodLabel else statsPeriodLabel(StatsPeriod.DAY, LocalDate.ofEpochDay(selectedDay))
    NavigationSheet(onDismiss, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.scrim.copy(alpha = .45f)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(76.dp).padding(start = 20.dp, end = 8.dp).testTag("stats-history-header"),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭阅读时间线") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            val list = rememberLazyListState()
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(list).testTag("stats-timeline-list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
                if (days.isEmpty()) item { StatsEmpty("这一天还没有阅读记录。") }
                itemsIndexed(days, key = { _, day -> day.epochDay }) { index, day ->
                    TimelineDay(day, index < days.lastIndex)
                }
            }
        }
    }
}

@Composable
private fun TimelineDay(day: StatsTimelineDay, continues: Boolean) {
    val date = LocalDate.ofEpochDay(day.epochDay)
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)
    Row(Modifier.fillMaxWidth().drawBehind {
        val x = 51.dp.toPx()
        if (continues) drawLine(line, Offset(x, 16.dp.toPx()), Offset(x, size.height + 12.dp.toPx()), 1.5.dp.toPx())
        drawCircle(accent, 3.5.dp.toPx(), Offset(x, 12.dp.toPx()))
    }.padding(bottom = 20.dp).testTag("timeline-day-${day.epochDay}"), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
        Column(Modifier.width(43.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.dayOfMonth.toString().padStart(2, '0'), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium, color = accent)
            Text("${date.year}.${date.monthValue.toString().padStart(2, '0')}", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("周${weekdays[date.dayOfWeek.value - 1]} · ${day.books.size} 本", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDuration(day.durationMs), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
                }
                day.books.forEach { stat ->
                    Row(Modifier.fillMaxWidth().testTag("timeline-book-${day.epochDay}-${stat.book.id}"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatsBookCover(stat.book, Modifier.size(44.dp, 64.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stat.book.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatDuration(stat.durationMs), style = MaterialTheme.typography.labelMedium,
                                color = statsHistoryColor(stat.book.id), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun statsHistoryColor(id: Long): Color {
    val colors = if (MaterialTheme.colorScheme.surface.luminance() < .5f)
        listOf(Color(0xFF93BFEA), Color(0xFFC3B0E8), Color(0xFFE4BA85), Color(0xFF91CEC0), Color(0xFFE8AABB))
    else listOf(Color(0xFF346BA8), Color(0xFF79609A), Color(0xFFA3612F), Color(0xFF327D70), Color(0xFFA95165))
    return colors[Math.floorMod(id.hashCode(), colors.size)]
}

private fun compactReadingDuration(durationMs: Long): String {
    val minutes = durationMs / 60_000
    return when {
        durationMs <= 0 -> "0分"
        minutes == 0L -> "${(durationMs / 1000).coerceAtLeast(1)}秒"
        minutes < 60 -> "${minutes}分"
        minutes % 60 == 0L -> "${minutes / 60}时"
        else -> "${minutes / 60}h${(minutes % 60).toString().padStart(2, '0')}"
    }
}
