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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MonthlyBookStat(
    val book: BookEntity,
    val durationMs: Long
)

data class StatsUiState(
    val monthLabel: String = "",
    val monthDurationMs: Long = 0,
    val lastMonthDurationMs: Long = 0,
    val streakDays: Int = 0,
    val longestStreakDays: Int = 0,
    val monthReadingDays: Int = 0,
    val finishedBooks: Int = 0,
    /** 笔记 + 段落批注的总量。 */
    val bookmarkNoteCount: Int = 0,
    /** 用户向 AI 发起过的消息总数（选段问答与伴读会话都算）。 */
    val aiChatCount: Int = 0,
    val durationsByEpochDay: Map<Long, Long> = emptyMap(),
    val topBooks: List<MonthlyBookStat> = emptyList()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    chatDao: ChatDao,
    noteDao: NoteDao,
    annotationDao: AnnotationDao
) : ViewModel() {

    val uiState = combine(
        libraryRepository.observeAllReadingDays(),
        libraryRepository.observeBooks(),
        chatDao.observeUserMessageCount(),
        noteDao.observeCount(),
        annotationDao.observeCount()
    ) { days, books, aiChats, notes, annotations ->
        buildState(days, books, aiChats, notes + annotations)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState()
    )

    private fun buildState(
        days: List<ReadingDailyEntity>,
        books: List<BookEntity>,
        aiChatCount: Int,
        noteCount: Int
    ): StatsUiState {
        val today = LocalDate.now()
        val monthStart = today.withDayOfMonth(1).toEpochDay()
        val nextMonthStart = today.withDayOfMonth(1).plusMonths(1).toEpochDay()
        val lastMonthStart = today.withDayOfMonth(1).minusMonths(1).toEpochDay()

        val byDay = days
            .groupBy(ReadingDailyEntity::epochDay)
            .mapValues { (_, list) -> list.sumOf(ReadingDailyEntity::durationMs) }

        val monthDays = byDay.filterKeys { it in monthStart until nextMonthStart }
        val lastMonthDays = byDay.filterKeys { it in lastMonthStart until monthStart }

        // 连续阅读：从今天（或昨天）往回数。
        var cursor = if ((byDay[today.toEpochDay()] ?: 0) > 0) today.toEpochDay() else today.toEpochDay() - 1
        var streak = 0
        while ((byDay[cursor] ?: 0) > 0) {
            streak += 1
            cursor -= 1
        }

        // 历史最长连读。
        var longest = 0
        var run = 0
        var prev: Long? = null
        byDay.keys.filter { (byDay[it] ?: 0) > 0 }.sorted().forEach { day ->
            run = if (prev != null && day == prev!! + 1) run + 1 else 1
            if (run > longest) longest = run
            prev = day
        }

        val monthByBook = days
            .filter { it.epochDay in monthStart until nextMonthStart && it.durationMs > 0 }
            .groupBy(ReadingDailyEntity::bookId)
            .mapValues { (_, list) -> list.sumOf(ReadingDailyEntity::durationMs) }
        val booksById = books.associateBy(BookEntity::id)
        val topBooks = monthByBook.entries
            .sortedByDescending { it.value }
            .mapNotNull { (bookId, duration) ->
                booksById[bookId]?.let { MonthlyBookStat(it, duration) }
            }
            .take(5)

        val finished = books.count { book ->
            book.totalChapters > 0 &&
                book.lastReadAt > 0 &&
                book.lastReadChapterIndex >= book.totalChapters - 1
        }

        return StatsUiState(
            monthLabel = "${today.monthValue}月",
            monthDurationMs = monthDays.values.sum(),
            lastMonthDurationMs = lastMonthDays.values.sum(),
            streakDays = streak,
            longestStreakDays = longest,
            monthReadingDays = monthDays.count { it.value > 0 },
            finishedBooks = finished,
            bookmarkNoteCount = noteCount,
            aiChatCount = aiChatCount,
            durationsByEpochDay = byDay,
            topBooks = topBooks
        )
    }
}
