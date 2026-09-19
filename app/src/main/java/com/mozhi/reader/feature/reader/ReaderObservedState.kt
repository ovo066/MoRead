package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.library.LibraryRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Repository projections contain no navigation, playback or notification side effects. */
internal data class ReaderLibraryObservation(
    val book: BookEntity?,
    val bookmarks: List<BookmarkEntity>,
    val tocEntries: List<BookTocEntryEntity>,
    val statistics: ReaderStatistics
)

internal fun observeReaderLibrary(library: LibraryRepository, bookId: Long): Flow<ReaderLibraryObservation> =
    combine(library.observeBook(bookId), library.observeBookmarks(bookId),
        library.observeTocEntries(bookId), library.observeReadingDays(bookId)) { book, bookmarks, toc, days ->
        ReaderLibraryObservation(book, bookmarks, toc, days.toReaderStatistics())
    }

internal data class ReaderAnnotationObservation(
    val annotations: List<AnnotationEntity>,
    val illustrations: List<IllustrationEntity>,
    val repliedIds: Set<Long>
)

internal fun observeReaderAnnotations(
    annotations: AnnotationRepository,
    illustrations: IllustrationRepository,
    bookId: Long
): Flow<ReaderAnnotationObservation> = combine(
    annotations.observeForBook(bookId), illustrations.observeForBook(bookId),
    annotations.observeRepliedAnnotationIds(bookId)
) { marks, images, replied -> ReaderAnnotationObservation(marks, images, replied.toSet()) }

internal data class ReaderAnnotationPreferences(
    val showAiAnnotations: Boolean,
    val style: AnnotationStyle,
    val color: String
)

internal fun observeReaderAnnotationPreferences(settings: ReaderSettingsRepository): Flow<ReaderAnnotationPreferences> =
    combine(settings.showAiAnnotations, settings.lastAnnotationStyle, settings.lastAnnotationColor) { show, style, color ->
        ReaderAnnotationPreferences(show, AnnotationStyle.fromWire(style), AnnotationColors.normalize(color))
    }

private fun List<ReadingDailyEntity>.toReaderStatistics(): ReaderStatistics {
    val today = LocalDate.now().toEpochDay()
    val durationByDay = associate { it.epochDay to it.durationMs }
    val lastSevenDays = (6 downTo 0).map { offset ->
        val epochDay = today - offset
        ReadingDayStat(
            epochDay = epochDay,
            durationMs = durationByDay[epochDay] ?: 0
        )
    }
    var streakCursor = if ((durationByDay[today] ?: 0) > 0) today else today - 1
    var streakDays = 0
    while ((durationByDay[streakCursor] ?: 0) > 0) {
        streakDays += 1
        streakCursor -= 1
    }
    return ReaderStatistics(
        totalDurationMs = sumOf(ReadingDailyEntity::durationMs),
        readingDays = count { it.durationMs > 0 },
        streakDays = streakDays,
        lastSevenDays = lastSevenDays
    )
}
