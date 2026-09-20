package com.mozhi.reader.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderParagraphTranslation

internal enum class ReaderSheet {
    AUTO_READ, CONTENTS, BOOKMARKS, SETTINGS, SEARCH, REIDENTIFY_CHAPTERS, TEXT_REPLACEMENT_RULES, TAP_ZONES, ENGLISH_LEARNING, DICTIONARY, BILINGUAL, SYNTAX, TITLE_STYLE
}

internal data class AnnotationInkFloater(val annotationId: Long, val topPx: Int)
internal data class AnnotationThreadKey(val annotationIds: Set<Long>)
internal data class ReaderReturnPosition(val chapterIndex: Int, val charOffset: Int)

private sealed interface ReaderPanel {
    data class Sheet(val sheet: ReaderSheet) : ReaderPanel
    data object Typography : ReaderPanel
}

/** UI lifetime and transitions only; reading, playback and model requests keep their own owners. */
@Stable
internal class ReaderScreenState(chromeVisible: Boolean = true, detailsVisible: Boolean = false) {
    var chromeVisible by mutableStateOf(chromeVisible)
    var detailsVisible by mutableStateOf(detailsVisible)
        private set
    private var panel by mutableStateOf<ReaderPanel?>(null)

    var activeSheet: ReaderSheet?
        get() = (panel as? ReaderPanel.Sheet)?.sheet
        set(value) {
            if (value != null) panel = ReaderPanel.Sheet(value)
            else if (panel is ReaderPanel.Sheet) panel = null
        }

    var typographyCardVisible: Boolean
        get() = panel == ReaderPanel.Typography
        set(value) {
            if (value) panel = ReaderPanel.Typography
            else if (panel == ReaderPanel.Typography) panel = null
        }

    // Dialogs may be children of a sheet; dismissing one must preserve its parent's position.
    var aiRequest by mutableStateOf<ReaderAiRequest?>(null)
    var inkFloater by mutableStateOf<AnnotationInkFloater?>(null)
    var annotationThread by mutableStateOf<AnnotationThreadKey?>(null)
    var epubImage by mutableStateOf<com.mozhi.reader.feature.reader.engine.ReaderPageImage?>(null)
    var linkPreview by mutableStateOf<EpubLinkPreview?>(null)
    var paragraphTranslation by mutableStateOf<ReaderParagraphTranslation?>(null)
    var returnPosition by mutableStateOf<ReaderReturnPosition?>(null)
    var ttsDraft by mutableStateOf<String?>(null)
    var locateHighlight by mutableStateOf<TransientHighlightSpan?>(null)
    var textEditDraft by mutableStateOf<TextEditDraft?>(null)
    var textRuleDraft by mutableStateOf<ReaderTextReplacementRule?>(null)
    var aiTextRuleDialogVisible by mutableStateOf(false)
    var pendingFont by mutableStateOf<PendingReaderFont?>(null)
    var pendingFontName by mutableStateOf("")
    var listenTimerVisible by mutableStateOf(false)
    var dictionaryHit by mutableStateOf<com.mozhi.reader.core.dictionary.DictionaryLookupHit?>(null)

    private val modalVisible: Boolean
        get() = panel != null || detailsVisible || aiRequest != null || inkFloater != null ||
            annotationThread != null || epubImage != null || linkPreview != null || paragraphTranslation != null || ttsDraft != null ||
            textEditDraft != null || textRuleDraft != null || aiTextRuleDialogVisible ||
            pendingFont != null || listenTimerVisible

    fun blocksAutoRead(companionVisible: Boolean, generatedImageVisible: Boolean): Boolean =
        modalVisible || chromeVisible || companionVisible || generatedImageVisible

    fun allowsPageInput(contentVisible: Boolean, generatedImageVisible: Boolean): Boolean =
        contentVisible && !modalVisible && !generatedImageVisible

    fun showBookDetails() {
        panel = null
        chromeVisible = false
        detailsVisible = true
    }

    fun closeBookDetails() {
        detailsVisible = false
        chromeVisible = true
    }

    // 排版细调使用悬浮卡片，让每次重排的结果都能在正文中看到。
    fun openTypographyCard() {
        panel = ReaderPanel.Typography
    }

    fun returnToTypographySheet() {
        panel = ReaderPanel.Sheet(ReaderSheet.SETTINGS)
    }

    companion object {
        // Preserve the previous restoration contract: dialogs and drafts are transient.
        val Saver = listSaver<ReaderScreenState, Boolean>(
            save = { listOf(it.chromeVisible, it.detailsVisible) },
            restore = { ReaderScreenState(chromeVisible = it[0], detailsVisible = it[1]) }
        )
    }
}

@Composable
internal fun rememberReaderScreenState(bookId: Long): ReaderScreenState =
    rememberSaveable(bookId, saver = ReaderScreenState.Saver) { ReaderScreenState() }
