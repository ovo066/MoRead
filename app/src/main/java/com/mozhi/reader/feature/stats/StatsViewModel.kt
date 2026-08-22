package com.mozhi.reader.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.dao.NoteDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class StatsPeriod(
    val selectorLabel: String,
    val readingLabel: String,
    val previousLabel: String,
    val topBooksLabel: String
) {
    DAY("日", "当日阅读", "前一日", "当日读得最多"),
    WEEK("周", "本周阅读", "上周", "本周读得最多"),
    MONTH("月", "当月阅读", "上月", "当月读得最多"),
    YEAR("年", "当年阅读", "上年", "当年读得最多")
}

data class PeriodBookStat(
    val book: BookEntity,
    val durationMs: Long
)

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
    val topBooks: List<PeriodBookStat> = emptyList()
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
    annotationDao: AnnotationDao
) : ViewModel() {
    private val selectedPeriod = MutableStateFlow(StatsPeriod.MONTH)
    private val anchorDate = MutableStateFlow(LocalDate.now())
    private val selection = combine(selectedPeriod, anchorDate, ::StatsSelection)
    private val noteCount = combine(noteDao.observeCount(), annotationDao.observeCount(), Int::plus)

    val uiState = combine(
        libraryRepository.observeAllReadingDays(),
        libraryRepository.observeBooks(),
        chatDao.observeUserMessageCount(),
        noteCount,
        selection
    ) { days, books, aiChats, notes, currentSelection ->
        buildStatsState(days, books, aiChats, notes, currentSelection)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = StatsUiState(periodLabel = statsPeriodLabel(StatsPeriod.MONTH, LocalDate.now()))
    )

    fun setPeriod(period: StatsPeriod) {
        selectedPeriod.value = period
    }

    fun selectDate(date: LocalDate) {
        anchorDate.value = date.coerceAtMost(LocalDate.now())
    }

    fun previousPeriod() {
        anchorDate.update { shiftPeriod(it, selectedPeriod.value, -1) }
    }

    fun nextPeriod() {
        val period = selectedPeriod.value
        anchorDate.update { current ->
            val candidate = shiftPeriod(current, period, 1)
            val candidateStart = statsPeriodRange(period, candidate).startEpochDay
            val currentPeriodStart = statsPeriodRange(period, LocalDate.now()).startEpochDay
            if (candidateStart <= currentPeriodStart) candidate else current
        }
    }
}

internal fun buildStatsState(
    days: List<ReadingDailyEntity>,
    books: List<BookEntity>,
    aiChatCount: Int,
    noteCount: Int,
    selection: StatsSelection,
    today: LocalDate = LocalDate.now()
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
    val topBooks = periodByBook.entries
        .sortedByDescending { it.value }
        .mapNotNull { (bookId, duration) ->
            booksById[bookId]?.let { PeriodBookStat(it, duration) }
        }
        .take(5)

    val finished = books.count { book ->
        book.totalChapters > 0 &&
            book.lastReadAt > 0 &&
            book.lastReadChapterIndex >= book.totalChapters - 1
    }
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
        topBooks = topBooks
    )
}

internal fun statsPeriodRange(period: StatsPeriod, anchor: LocalDate): StatsPeriodRange {
    val start = when (period) {
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
    StatsPeriod.DAY -> "${anchor.year}年${anchor.monthValue}月${anchor.dayOfMonth}日"
    StatsPeriod.WEEK -> {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        val end = start.plusDays(6)
        if (start.year == end.year && start.monthValue == end.monthValue) {
            "${start.year}年${start.monthValue}月${start.dayOfMonth}—${end.dayOfMonth}日"
        } else {
            "${start.monthValue}月${start.dayOfMonth}日—${end.monthValue}月${end.dayOfMonth}日"
        }
    }
    StatsPeriod.MONTH -> "${anchor.year}年${anchor.monthValue}月"
    StatsPeriod.YEAR -> "${anchor.year}年"
}

private fun shiftPeriod(date: LocalDate, period: StatsPeriod, amount: Long): LocalDate = when (period) {
    StatsPeriod.DAY -> date.plusDays(amount)
    StatsPeriod.WEEK -> date.plusWeeks(amount)
    StatsPeriod.MONTH -> date.plusMonths(amount)
    StatsPeriod.YEAR -> date.plusYears(amount)
}
