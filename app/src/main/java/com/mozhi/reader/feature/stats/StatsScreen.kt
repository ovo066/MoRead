package com.mozhi.reader.feature.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.datastore.StatsWidget
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun StatsScreen(contentPadding: PaddingValues, viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StatsDashboard(state, contentPadding, viewModel::setPeriod, viewModel::selectDate, viewModel::selectDay,
        viewModel::previousPeriod, viewModel::nextPeriod, viewModel::setWidgetVisible, viewModel::moveWidget, viewModel::resetWidgets)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsDashboard(
    state: StatsUiState,
    contentPadding: PaddingValues = PaddingValues(),
    onPeriod: (StatsPeriod) -> Unit = {},
    onDate: (LocalDate) -> Unit = {},
    onDay: (LocalDate) -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onWidgetVisible: (StatsWidget, Boolean) -> Unit = { _, _ -> },
    onMoveWidget: (StatsWidget, Int) -> Unit = { _, _ -> },
    onResetWidgets: () -> Unit = {}
) {
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var fullTimeline by rememberSaveable { mutableStateOf(false) }
    var calendarDay by rememberSaveable { mutableStateOf<Long?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val columns = if (maxWidth >= 880.dp) 2 else 1
    val tablet = com.mozhi.reader.ui.rememberMoReadWindowWidth() == com.mozhi.reader.ui.MoReadWindowWidth.EXPANDED
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(contentPadding).testTag("stats-list"),
        contentPadding = PaddingValues(start = if (tablet) 40.dp else 20.dp, top = if (tablet) 28.dp else 18.dp,
            end = if (tablet) 40.dp else 20.dp, bottom = if (tablet) 32.dp else 124.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("统计", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        IconButton(onClick = { settings = true }) { Icon(Icons.Outlined.Tune, "调整统计组件") }
                    }
                }
                Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(4.dp).selectableGroup().testTag("stats-periods")) {
                    listOf(StatsPeriod.TOTAL, StatsPeriod.YEAR, StatsPeriod.MONTH, StatsPeriod.WEEK, StatsPeriod.DAY).forEach { period ->
                        val selected = state.period == period
                        val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
                            label = "stats-period")
                        Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(color)
                            .selectable(selected, role = Role.Tab, onClick = { onPeriod(period) }), contentAlignment = Alignment.Center) {
                            Text(period.selectorLabel, style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().testTag("stats-date-range"), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious, enabled = state.period != StatsPeriod.TOTAL) { Icon(Icons.Outlined.ChevronLeft, "上一周期") }
                    Surface(onClick = { datePicker = true }, enabled = state.period != StatsPeriod.TOTAL,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.background) {
                        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(state.periodLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    IconButton(onClick = onNext, enabled = state.canGoNext) { Icon(Icons.Outlined.ChevronRight, "下一周期") }
                }
            }
        }
        item(key = "overview", span = { GridItemSpan(maxLineSpan) }) { StatsOverview(state) }
        state.widgets.visible.forEach { widget ->
            item(key = widget.name, contentType = widget) {
                when (widget) {
                    StatsWidget.CALENDAR -> StatsCalendar(state, onDay = { calendarDay = it.toEpochDay() }, onMonth = onDate)
                    StatsWidget.TREND -> StatsTrend(state)
                    StatsWidget.HOURS -> StatsHours(state)
                    StatsWidget.TIMELINE -> StatsTimeline(state, onShowAll = { fullTimeline = true })
                    StatsWidget.BOOKS -> StatsBooks(state)
                    StatsWidget.TAGS -> StatsCloud("标签云", "字号按本期阅读时长呈现", state.tags, "给读过的书添加标签，阅读偏好就会出现在这里。")
                    StatsWidget.AUTHORS -> StatsCloud("作者云", "本期与你相伴的作者", state.authors, "阅读后，这里会汇集书籍的作者。")
                    StatsWidget.HEATMAP -> StatsHeatmap(state, onDay)
                }
            }
        }
    }
    }
    }
    if (settings) StatsWidgetsSheet(state.widgets, onWidgetVisible, onMoveWidget, onResetWidgets) { settings = false }
    if (fullTimeline) StatsTimelineSheet(state) { fullTimeline = false }
    calendarDay?.let { day -> StatsTimelineSheet(state, selectedDay = day) { calendarDay = null } }
    if (datePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.anchorDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember(state.today) { object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= state.today
                override fun isSelectableYear(year: Int) = year <= state.today.year
            } }
        )
        DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                datePicker = false
            }, enabled = pickerState.selectedDateMillis != null) { Text("跳转") }
        }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text("取消") } }) { DatePicker(pickerState) }
    }
}
