package com.mozhi.reader.feature.reader

import androidx.annotation.StringRes
import com.mozhi.reader.ai.companion.ProactiveAnnotationBatchResult
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule

data class ReaderUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val tocEntries: List<BookTocEntryEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val annotations: List<AnnotationEntity> = emptyList(),
    val illustrations: List<IllustrationEntity> = emptyList(),
    /** 有讨论回复的批注 id：纯高亮有讨论时也要显示评论小点。 */
    val repliedAnnotationIds: Set<Long> = emptySet(),
    val showAiAnnotations: Boolean = true,
    val annotationNotice: ReaderAnnotationNotice? = null,
    /** 即划即改：上次使用的划线样式与颜色。 */
    val lastAnnotationStyle: AnnotationStyle = AnnotationStyle.HIGHLIGHT,
    val lastAnnotationColor: String = AnnotationColors.AMBER,
    val settings: ReaderSettings = ReaderSettings(),
    val currentChapterIndex: Int = 0,
    val currentCharOffset: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val readingProgress: Float = 0f,
    val chapterProgress: Float = 0f,
    val readingStats: ReaderStatistics = ReaderStatistics(),
    val isLoading: Boolean = true,
    val isPreparingText: Boolean = false,
    /** 当前章已排完版、首页可画；进场揭示以它为准，不再掐固定表。 */
    val isContentReady: Boolean = false,
    val contentRevision: Int = 0,
    val cleanupPreview: com.mozhi.reader.core.library.TextCleanupPreview? = null,
    val cleanupBusy: Boolean = false,
    val translation: ReaderTranslationState = ReaderTranslationState(),
    val errorMessage: String? = null
)

data class ReaderTranslationState(val busy: Boolean = false, val done: Int = 0, val total: Int = 0, val message: String? = null)

data class ReaderAnnotationNotice(val result: ProactiveAnnotationBatchResult, val message: String)

data class ReadingDayStat(
    val epochDay: Long,
    val durationMs: Long
)

data class ReaderSourceSelection(
    val start: Int,
    val end: Int,
    val text: String,
    val textAnchorJson: String
)

data class EpubLinkPreview(
    val sourceChapterIndex: Int,
    val href: String,
    val label: String,
    val targetChapterIndex: Int?,
    val targetCharOffset: Int,
    val targetTitle: String,
    val content: String,
    val externalUrl: String? = null,
    val presentation: ReaderPresentationSnapshot? = null
)

data class ReaderStatistics(
    val totalDurationMs: Long = 0,
    val readingDays: Int = 0,
    val streakDays: Int = 0,
    val lastSevenDays: List<ReadingDayStat> = emptyList()
)

enum class PageTurnDirection {
    PREVIOUS,
    NEXT
}

sealed interface ReaderEvent {
    data class ShowMessage(val message: String) : ReaderEvent
    data class ShowLocalizedMessage(@param:StringRes val resourceId: Int) : ReaderEvent
    data class ConfirmFontImport(val pending: PendingReaderFont) : ReaderEvent
    data class TextReplacementRuleSuggested(val rule: ReaderTextReplacementRule) : ReaderEvent
}
