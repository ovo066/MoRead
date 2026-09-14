package com.mozhi.reader.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.dao.NoteDao
import com.mozhi.reader.core.database.dao.ShelfOrganizationDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.database.entity.ReadingHourlyEntity
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.tagList
import com.mozhi.reader.core.datastore.StatsSettingsStore
import com.mozhi.reader.core.datastore.StatsWidget
import com.mozhi.reader.core.datastore.StatsWidgets
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class StatsPeriod(
    val selectorLabel: String,
    val readingLabel: String,
    val previousLabel: String,
    val topBooksLabel: String
) {
    TOTAL("总", "累计阅读", "", "累计读得最多"),
    DAY("日", "当日阅读", "前一日", "当日读得最多"),
    WEEK("周", "本周阅读", "上周", "本周读得最多"),
    MONTH("月", "当月阅读", "上月", "当月读得最多"),
    YEAR("年", "当年阅读", "上年", "当年读得最多")
}

data class PeriodBookStat(
    val book: BookEntity,
    val durationMs: Long
)

data class StatsTimelineDay(val epochDay: Long, val books: List<PeriodBookStat>) {
    val durationMs: Long get() = books.sumOf { it.durationMs }
}

data class StatsTimelineBook(val book: BookEntity, val dailyDurations: List<Long>)
data class StatsTimelineWeek(val startEpochDay: Long, val books: List<StatsTimelineBook>)

data class StatsCloudItem(val label: String, val durationMs: Long, val bookCount: Int)
data class StatsBar(val label: String, val durationMs: Long)
data class StatsTimeBand(val label: String, val hours: String, val durationMs: Long)

data class StatsUiState(
    val period: StatsPeriod = StatsPeriod.MONTH,
    val anchorDate: LocalDate = LocalDate.now(),
    val periodLabel: String = "",
    val canGoNext: Boolean = false,
    val periodDurationMs: Long = 0,
    val previousPeriodDurationMs: Long = 0,
    val streakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val periodReadingDays: Int = 0,
    val finishedBooks: Int = 0,
    /** 笔记 + 段落批注的总量。 */
    val bookmarkNoteCount: Int = 0,
    /** 用户向 AI 发起过的消息总数（选段问答与伴读会话都算）。 */
    val aiChatCount: Int = 0,
    val durationsByEpochDay: Map<Long, Long> = emptyMap(),
    val topBooks: List<PeriodBookStat> = emptyList(),
    val periodBooks: List<PeriodBookStat> = emptyList(),
    val timeline: List<StatsTimelineDay> = emptyList(),
    val timelineWeeks: List<StatsTimelineWeek> = emptyList(),
    /** 月历始终取锚点所在的整月，不受日、周、年或总视图的聚合粒度影响。 */
    val monthTimeline: List<StatsTimelineDay> = emptyList(),
    val hourlyDurations: List<Long> = List(24) { 0L },
    val trend: List<StatsBar> = emptyList(),
    val timeBands: List<StatsTimeBand> = emptyList(),
    val tags: List<StatsCloudItem> = emptyList(),
    val authors: List<StatsCloudItem> = emptyList(),
    val widgets: StatsWidgets = StatsWidgets(),
    val today: LocalDate = LocalDate.now()
)

internal data class StatsPeriodRange(
    val startEpochDay: Long,
    val endEpochDayExclusive: Long,
    val previousStartEpochDay: Long
)

internal data class StatsSelection(
    val period: StatsPeriod,
    val anchorDate: LocalDate
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    chatDao: ChatDao,
    noteDao: NoteDao,
    annotationDao: AnnotationDao,
    shelfOrganizationDao: ShelfOrganizationDao,
    private val statsSettings: StatsSettingsStore
) : ViewModel() {
    private val selection = MutableStateFlow(StatsSelection(StatsPeriod.MONTH, LocalDate.now()))
    private val library = combine(libraryRepository.observeAllReadingDays(),
        libraryRepository.observeBooksIncludingRemoved()) { days, books -> days to books }
    private val counts = combine(chatDao.observeUserMessageCount(), noteDao.observeCount(), annotationDao.observeCount()) {
        chats, notes, annotations -> chats to notes + annotations
    }
    private val tags = combine(shelfOrganizationDao.observeTags(), shelfOrganizationDao.observeTagRefs()) { tags, refs -> tags to refs }
    private val hours = selection.flatMapLatest { selected ->
        val range = statsPeriodRange(selected.period, selected.anchorDate)
        libraryRepository.observeReadingHours(range.startEpochDay, range.endEpochDayExclusive).map { selected to it }
    }

    val uiState = combine(library, counts, tags, hours, statsSettings.widgets) { library, counts, tags, selected, widgets ->
        buildStatsState(library.first, library.second, counts.first, counts.second, selected.first,
            hours = selected.second, tags = tags.first, tagRefs = tags.second).copy(widgets = widgets)
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState(periodLabel = statsPeriodLabel(StatsPeriod.MONTH, LocalDate.now()))
    )

    fun setPeriod(period: StatsPeriod) {
        selection.update { it.copy(period = period, anchorDate = if (period == StatsPeriod.TOTAL) LocalDate.now() else it.anchorDate) }
    }

    fun selectDate(date: LocalDate) {
        selection.update { it.copy(anchorDate = date.coerceAtMost(LocalDate.now())) }
    }

    fun previousPeriod() {
        if (selection.value.period == StatsPeriod.TOTAL) return
        selection.update { it.copy(anchorDate = shiftPeriod(it.anchorDate, it.period, -1)) }
    }

    fun nextPeriod() {
        if (selection.value.period == StatsPeriod.TOTAL) return
        selection.update { current ->
            val candidate = shiftPeriod(current.anchorDate, current.period, 1)
            val candidateStart = statsPeriodRange(current.period, candidate).startEpochDay
            val currentPeriodStart = statsPeriodRange(current.period, LocalDate.now()).startEpochDay
            if (candidateStart <= currentPeriodStart) current.copy(anchorDate = candidate.coerceAtMost(LocalDate.now())) else current
        }
    }

    fun selectDay(date: LocalDate) { selection.value = StatsSelection(StatsPeriod.DAY, date.coerceAtMost(LocalDate.now())) }
    fun setWidgetVisible(widget: StatsWidget, visible: Boolean) { viewModelScope.launch { statsSettings.setVisible(widget, visible) } }
    fun moveWidget(widget: StatsWidget, direction: Int) { viewModelScope.launch { statsSettings.move(widget, direction) } }
    fun resetWidgets() { viewModelScope.launch { statsSettings.reset() } }
}

internal fun buildStatsState(
    days: List<ReadingDailyEntity>,
    books: List<BookEntity>,
    aiChatCount: Int,
    noteCount: Int,
    selection: StatsSelection,
    today: LocalDate = LocalDate.now(),
    hours: List<ReadingHourlyEntity> = emptyList(),
    tags: List<BookTagEntity> = emptyList(),
    tagRefs: List<BookTagRefEntity> = emptyList()
): StatsUiState {
    val range = statsPeriodRange(selection.period, selection.anchorDate)
    val byDay = days
        .groupBy(ReadingDailyEntity::epochDay)
        .mapValues { (_, list) -> list.sumOf(ReadingDailyEntity::durationMs) }
    val periodDays = byDay.filterKeys { it in range.startEpochDay until range.endEpochDayExclusive }
    val previousDays = byDay.filterKeys {
        it in range.previousStartEpochDay until range.startEpochDay
    }

    // 连续阅读始终以今天为锚点，不随统计周期翻页而改变。
    var cursor = if ((byDay[today.toEpochDay()] ?: 0) > 0) {
        today.toEpochDay()
    } else {
        today.toEpochDay() - 1
    }
    var streak = 0
    while ((byDay[cursor] ?: 0) > 0) {
        streak += 1
        cursor -= 1
    }

    var longest = 0
    var run = 0
    var previousDay: Long? = null
    byDay.keys.filter { (byDay[it] ?: 0) > 0 }.sorted().forEach { day ->
        run = if (previousDay != null && day == previousDay + 1) run + 1 else 1
        if (run > longest) longest = run
        previousDay = day
    }

    val periodByBook = days
        .filter {
            it.epochDay in range.startEpochDay until range.endEpochDayExclusive &&
                it.durationMs > 0
        }
        .groupBy(ReadingDailyEntity::bookId)
        .mapValues { (_, list) -> list.sumOf(ReadingDailyEntity::durationMs) }
    val booksById = books.associateBy(BookEntity::id)
    val periodBooks = periodByBook.entries
        .sortedByDescending { it.value }
        .mapNotNull { (bookId, duration) ->
            booksById[bookId]?.let { PeriodBookStat(it, duration) }
        }

    val allTimeline = days.filter { it.durationMs > 0 }
        .groupBy { it.epochDay }.toSortedMap(reverseOrder()).mapNotNull { (day, rows) ->
            val entries = rows.groupBy { it.bookId }.mapNotNull { (id, values) ->
                booksById[id]?.let { PeriodBookStat(it, values.sumOf { it.durationMs }) }
            }.sortedBy { it.book.id }
            entries.takeIf { it.isNotEmpty() }?.let { StatsTimelineDay(day, it) }
        }
    val timeline = allTimeline.filter { it.epochDay in range.startEpochDay until range.endEpochDayExclusive }
    val monthStart = selection.anchorDate.withDayOfMonth(1)
    val monthTimeline = allTimeline.filter { it.epochDay in monthStart.toEpochDay() until monthStart.plusMonths(1).toEpochDay() }
    val byHour = hours.filter { it.epochDay in range.startEpochDay until range.endEpochDayExclusive && it.hour in 0..23 && it.durationMs > 0 }
        .groupBy { it.hour }.mapValues { (_, rows) -> rows.sumOf { it.durationMs } }
    val hourly = List(24) { byHour[it] ?: 0L }
    val bandLabels = listOf("凌晨" to "00–06", "上午" to "06–12", "下午" to "12–18", "夜晚" to "18–24")
    val timeBands = bandLabels.mapIndexed { index, (label, labelHours) ->
        StatsTimeBand(label, labelHours, hourly.subList(index * 6, index * 6 + 6).sum())
    }
    val tagNames = tags.associate { it.id to it.name }
    val refs = tagRefs.groupBy { it.bookId }
    fun cloud(labels: (BookEntity) -> List<String>): List<StatsCloudItem> = periodBooks
        .flatMap { stat -> labels(stat.book).map(String::trim).filter(String::isNotBlank).distinct().map { it to stat } }
        .groupBy({ it.first }, { it.second }).map { (label, values) ->
            StatsCloudItem(label, values.sumOf { it.durationMs }, values.map { it.book.id }.distinct().size)
        }.sortedWith(compareByDescending<StatsCloudItem> { it.durationMs }.thenBy { it.label }).take(40)
    val cloudTags = cloud { book -> refs[book.id]?.mapNotNull { tagNames[it.tagId] }?.takeIf { it.isNotEmpty() } ?: book.tagList() }
    val authors = cloud { listOf(it.author).filter { author -> author.isNotBlank() && author != "未知作者" } }
    val trend = when (selection.period) {
        StatsPeriod.TOTAL -> periodDays.entries.groupBy { LocalDate.ofEpochDay(it.key).year }.toSortedMap()
            .map { (year, entries) -> StatsBar(year.toString(), entries.sumOf { it.value }) }
        StatsPeriod.DAY -> hourly.mapIndexed { hour, duration -> StatsBar(hour.toString(), duration) }
        StatsPeriod.YEAR -> (1..12).map { month ->
            val duration = periodDays.filterKeys { LocalDate.ofEpochDay(it).monthValue == month }.values.sum()
            StatsBar("${month}月", duration)
        }
        else -> (range.startEpochDay until range.endEpochDayExclusive).map { day ->
            val date = LocalDate.ofEpochDay(day)
            val label = if (selection.period == StatsPeriod.WEEK) listOf("一", "二", "三", "四", "五", "六", "日")[date.dayOfWeek.value - 1]
                else date.dayOfMonth.toString()
            StatsBar(label, periodDays[day] ?: 0L)
        }
    }

    val finished = books.count { it.readState() == BookReadState.FINISHED }
    val currentRangeStart = statsPeriodRange(selection.period, today).startEpochDay

    return StatsUiState(
        period = selection.period,
        anchorDate = selection.anchorDate,
        periodLabel = statsPeriodLabel(selection.period, selection.anchorDate),
        canGoNext = range.startEpochDay < currentRangeStart,
        periodDurationMs = periodDays.values.sum(),
        previousPeriodDurationMs = previousDays.values.sum(),
        streakDays = streak,
        longestStreakDays = longest,
        periodReadingDays = periodDays.count { it.value > 0 },
        finishedBooks = finished,
        bookmarkNoteCount = noteCount,
        aiChatCount = aiChatCount,
        durationsByEpochDay = byDay,
        topBooks = periodBooks.take(5),
        periodBooks = periodBooks,
        timeline = timeline,
        timelineWeeks = timelineWeeks(timeline),
        monthTimeline = monthTimeline,
        hourlyDurations = hourly,
        trend = trend,
        timeBands = timeBands,
        tags = cloudTags,
        authors = authors,
        today = today
    )
}

internal fun timelineWeeks(days: List<StatsTimelineDay>): List<StatsTimelineWeek> = days.groupBy {
    val date = LocalDate.ofEpochDay(it.epochDay)
    date.minusDays((date.dayOfWeek.value - 1).toLong()).toEpochDay()
}.toSortedMap(reverseOrder()).map { (start, week) ->
    val byDay = week.associateBy { it.epochDay }
    val books = week.flatMap { it.books }.map { it.book }.distinctBy { it.id }.sortedBy { it.id }
    StatsTimelineWeek(start, books.map { book ->
        StatsTimelineBook(book, List(7) { offset ->
            byDay[start + offset]?.books?.firstOrNull { it.book.id == book.id }?.durationMs ?: 0L
        })
    })
}

/** 只连接确有阅读记录的相邻日期，不把未阅读的间隔补成连续阅读。 */
internal fun readingSpans(durations: List<Long>): List<IntRange> {
    val spans = mutableListOf<IntRange>()
    var start = -1
    durations.forEachIndexed { index, duration ->
        if (duration > 0 && start < 0) start = index
        if (duration <= 0 && start >= 0) { spans += start until index; start = -1 }
    }
    if (start >= 0) spans += start..durations.lastIndex
    return spans
}

internal fun statsPeriodRange(period: StatsPeriod, anchor: LocalDate): StatsPeriodRange {
    if (period == StatsPeriod.TOTAL) return StatsPeriodRange(LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay() + 1, LocalDate.MIN.toEpochDay())
    val start = when (period) {
        StatsPeriod.TOTAL -> anchor
        StatsPeriod.DAY -> anchor
        StatsPeriod.WEEK -> anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        StatsPeriod.MONTH -> anchor.withDayOfMonth(1)
        StatsPeriod.YEAR -> anchor.withDayOfYear(1)
    }
    val end = shiftPeriod(start, period, 1)
    val previousStart = shiftPeriod(start, period, -1)
    return StatsPeriodRange(
        startEpochDay = start.toEpochDay(),
        endEpochDayExclusive = end.toEpochDay(),
        previousStartEpochDay = previousStart.toEpochDay()
    )
}

internal fun statsPeriodLabel(period: StatsPeriod, anchor: LocalDate): String = when (period) {
    StatsPeriod.TOTAL -> "全部阅读记录"
    StatsPeriod.DAY -> "${anchor.year}年${anchor.monthValue}月${anchor.dayOfMonth}日"
    StatsPeriod.WEEK -> {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        val end = start.plusDays(6)
        if (start.year == end.year && start.monthValue == end.monthValue) {
            "${start.year}年${start.monthValue}月${start.dayOfMonth}—${end.dayOfMonth}日"
        } else {
            if (start.year != end.year) "${start.year}.${start.monthValue}.${start.dayOfMonth}—${end.year}.${end.monthValue}.${end.dayOfMonth}"
            else "${start.year}年${start.monthValue}月${start.dayOfMonth}日—${end.monthValue}月${end.dayOfMonth}日"
        }
    }
    StatsPeriod.MONTH -> "${anchor.year}年${anchor.monthValue}月"
    StatsPeriod.YEAR -> "${anchor.year}年"
}

private fun shiftPeriod(date: LocalDate, period: StatsPeriod, amount: Long): LocalDate = when (period) {
    StatsPeriod.TOTAL -> date
    StatsPeriod.DAY -> date.plusDays(amount)
    StatsPeriod.WEEK -> date.plusWeeks(amount)
    StatsPeriod.MONTH -> date.plusMonths(amount)
    StatsPeriod.YEAR -> date.plusYears(amount)
}
