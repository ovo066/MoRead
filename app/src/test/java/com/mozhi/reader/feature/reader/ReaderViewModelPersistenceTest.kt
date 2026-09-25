package com.mozhi.reader.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.core.database.dao.AnnotationDao
import com.mozhi.reader.core.database.dao.BookDao
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.BookTextStore
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ReaderTextAnchorCodec
import com.mozhi.reader.core.library.ReaderTextAnchor
import com.mozhi.reader.core.library.ReaderTextAnchors
import com.mozhi.reader.core.library.ResolvedTextAnchor
import com.mozhi.reader.core.text.ChineseTextConverter
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.feature.reader.engine.ChineseChapterPresenter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import io.mockk.coEvery
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewModelPersistenceTest {
    private val source = "網際網路與程式碼。" + "甲".repeat(40) + "主機板。後文。"
    private val regionalSource = "網際網路".repeat(12) + "主機板" + "資料庫".repeat(12)

    @Test
    fun epubNotePreviewContainsNoteTextAfterNumberAndPreservesJumpAnchor() {
        val parsed = com.mozhi.reader.feature.importer.EpubLayoutDocumentParser().parseWithText(
            """<html><body><p>Some text.</p><p><a id="note1">[1]</a> A complete explanation.</p><p id="note2">[2] Another note.</p></body></html>""".toByteArray(),
            0, "chapter.xhtml", emptyMap())
        val layout = com.mozhi.reader.core.library.EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom)
        withReader(parsed.text, layout = layout) { reader ->
            val preview = reader.viewModel.previewEpubLink(com.mozhi.reader.feature.reader.engine.ReaderPageLink(0, "#note1", "[1]"))!!
            assertEquals("[1] A complete explanation.", preview.content)
            assertEquals(parsed.text.indexOf("[1]"), preview.targetCharOffset)
            assertEquals(0, preview.targetChapterIndex)
        }
    }

    @Test
    fun retranslationReplacesOnlySelectedParagraphAndDeletionDoesNotCallAi() {
        val body = "A reader opens a book.\nAnother reader leaves."
        val initial = englishParagraphs(body).map { ParagraphTranslation(it.start, it.end, it.key, "旧译文${it.start}") }
        withReader(body, translations = initial) { reader ->
            coEvery { reader.translationClient.chat(any(), any()) } returns "新的译文"
            val target = reader.viewModel.paragraphTranslationAt(0, 3)!!
            reader.viewModel.retranslateParagraph(target)
            reader.viewModel.uiState.first { !it.translation.busy && it.translation.message == "译文已缓存，可随时隐藏或显示" }
            assertEquals(setOf(initial.first().copy(chinese = "新的译文"), initial.last()), reader.cachedTranslations.toSet())
            val callsBeforeDelete = reader.translationCalls
            reader.viewModel.deleteParagraphTranslation(reader.viewModel.paragraphTranslationAt(0, 3)!!)
            reader.viewModel.uiState.first { !it.translation.busy && it.translation.message == "已删除本段译文" }
            assertEquals(listOf(initial.last()), reader.cachedTranslations)
            assertEquals(callsBeforeDelete, reader.translationCalls)
        }
    }

    @Test
    fun failedRetranslationPreservesThePreviousCachedResult() {
        val body = "A reader opens a book."
        val initial = englishParagraphs(body).map { ParagraphTranslation(it.start, it.end, it.key, "原有译文") }
        withReader(body, translations = initial) { reader ->
            coEvery { reader.translationClient.chat(any(), any()) } throws IllegalStateException("网络不可用")
            reader.viewModel.retranslateParagraph(reader.viewModel.paragraphTranslationAt(0, 3)!!)
            reader.viewModel.uiState.first { !it.translation.busy && it.translation.message == "网络不可用" }
            assertEquals(initial, reader.cachedTranslations)
        }
    }

    @Test
    fun regionalSelectionPersistsTheEntireOriginalPhrase() = withReader(regionalSource) { reader ->
        reader.viewModel.quickAnnotate(0, "主板", 36 until 38)

        val annotation = reader.annotations.receive()
        assertEquals(48, annotation.startCharOffset)
        assertEquals(51, annotation.endCharOffset)
        assertEquals("主機板", annotation.selectedText)
    }

    @Test
    fun sameModeUnmatchedAnchorsUseStoredSourceCoordinates() = withReader(regionalSource) { reader ->
        // A failed dictionary load previously displayed raw text while retaining the selected mode.
        val selectionAnchor = ReaderTextAnchors.create(regionalSource, 48, 51, ChineseConversionMode.TW2SP)
        val pointAnchor = ReaderTextAnchors.create(regionalSource, 48, 48, ChineseConversionMode.TW2SP)

        assertStoredAnchorPositions(reader, selectionAnchor, pointAnchor, 36, 38)
    }

    @Test
    fun unmatchedSourceAnchorsUseStoredCoordinatesInBothDisplayModes() = withReader(regionalSource) { reader ->
        val staleAnchor = ReaderTextAnchor(
            mode = ChineseConversionMode.OFF,
            quote = "失效的舊引文",
            prefix = "舊版前文",
            suffix = "舊版後文",
            ratio = 0.1f
        )
        val stalePoint = staleAnchor.copy(quote = "")

        assertStoredAnchorPositions(reader, staleAnchor, stalePoint, 36, 38)
        reader.changeMode(ChineseConversionMode.OFF)
        assertStoredAnchorPositions(reader, staleAnchor, stalePoint, 48, 51)
    }

    private suspend fun assertStoredAnchorPositions(
        reader: ReaderFixture,
        selectionAnchor: ReaderTextAnchor,
        pointAnchor: ReaderTextAnchor,
        displayStart: Int,
        displayEnd: Int
    ) {
        val anchorJson = ReaderTextAnchorCodec.encode(selectionAnchor)
        val expected = ResolvedTextAnchor(displayStart, displayEnd)
        val annotation = AnnotationEntity(
            bookId = 1, chapterIndex = 0, startCharOffset = 48, endCharOffset = 51,
            selectedText = "主機板", textAnchorJson = anchorJson, createdAt = 0
        )
        val illustration = IllustrationEntity(
            bookId = 1, chapterIndex = 0, charOffset = 48, sourceText = "主機板",
            prompt = "", imagePath = "/tmp/illustration.png", textAnchorJson = anchorJson, createdAt = 0
        )
        assertEquals(expected, reader.viewModel.resolveAnnotationRange(annotation))
        assertEquals(expected, reader.viewModel.resolveIllustrationRange(illustration))
        if (selectionAnchor.mode == ChineseConversionMode.OFF) {
            assertEquals(expected, reader.viewModel.resolveSourceRange(0, 48, 51, anchorJson))
        }

        reader.viewModel.goToBookmark(
            BookmarkEntity(
                bookId = 1, chapterIndex = 0, charOffset = 48,
                locatorJson = ReaderTextAnchorCodec.encode(pointAnchor), label = "", createdAt = 0
            )
        )
        assertEquals(displayStart, reader.viewModel.contentController.charOffset)
    }

    @Test
    fun annotationPersistsOriginalRangeAndQuoteAndRendersAfterModeChanges() = withReader(source) { reader ->
        val body = reader.viewModel.contentController.chapterBody(0)!!
        val start = body.indexOf("主板")

        reader.viewModel.quickAnnotate(0, "主板", start until start + 2)

        val annotation = reader.annotations.receive()
        assertEquals(source.indexOf("主機板"), annotation.startCharOffset)
        assertEquals(source.indexOf("主機板") + 3, annotation.endCharOffset)
        assertEquals("主機板", annotation.selectedText)
        assertEquals("主板", ReaderTextAnchorCodec.decode(annotation.textAnchorJson)!!.quote)
        assertEquals(ResolvedTextAnchor(start, start + 2), reader.viewModel.resolveAnnotationRange(annotation))

        reader.changeMode(ChineseConversionMode.OFF)
        val originalStart = source.indexOf("主機板")
        assertEquals(ResolvedTextAnchor(originalStart, originalStart + 3), reader.viewModel.resolveAnnotationRange(annotation))
        assertEquals(
            ResolvedTextAnchor(originalStart, originalStart + 3),
            reader.viewModel.resolveAnnotationRange(annotation.copy(textAnchorJson = "broken")),
        )
        reader.changeMode(ChineseConversionMode.TW2SP)
        assertEquals(
            ResolvedTextAnchor(start, start + 2),
            reader.viewModel.resolveAnnotationRange(annotation.copy(textAnchorJson = "")),
        )
    }

    @Test
    fun bookmarkAndProgressStoreSourceOffsetsAndReopenWithoutLocator() = withReader(source) { reader ->
        val body = reader.viewModel.contentController.chapterBody(0)!!
        val displayStart = body.indexOf("主板")
        val originalStart = source.indexOf("主機板")
        reader.viewModel.goToPosition(0, displayStart)
        reader.viewModel.toggleBookmark()
        val bookmark = reader.bookmarks.receive()
        assertEquals(originalStart, bookmark.charOffset)

        reader.viewModel.flushProgress()
        val progress = reader.progress.receive()
        assertEquals(originalStart, progress.offset)
        assertEquals(ChineseConversionMode.TW2SP, ReaderTextAnchorCodec.decode(progress.locator)!!.mode)

        reader.changeMode(ChineseConversionMode.OFF)
        reader.viewModel.goToBookmark(bookmark.copy(locatorJson = "broken"))
        reader.viewModel.uiState.first { it.currentCharOffset == originalStart }
        assertEquals(originalStart, reader.viewModel.contentController.charOffset)
        assertTrue(reader.viewModel.isCurrentPositionBookmarked())

        reader.changeMode(ChineseConversionMode.TW2SP)
        reader.viewModel.goToPosition(0, 0)
        reader.viewModel.goToBookmark(bookmark.copy(locatorJson = ""))
        reader.viewModel.uiState.first { it.currentCharOffset == displayStart }
        assertEquals(displayStart, reader.viewModel.contentController.charOffset)
    }

    @Test
    fun repeatedPullAddsOnlyOneBookmarkAndKeepsSourceCoordinates() = withReader(source) { reader ->
        val displayStart = reader.viewModel.contentController.chapterBody(0)!!.indexOf("主板")
        reader.viewModel.goToPosition(0, displayStart)
        reader.viewModel.addBookmarkFromPull()
        reader.viewModel.addBookmarkFromPull()
        val bookmark = reader.bookmarks.receive()
        assertEquals(source.indexOf("主機板"), bookmark.charOffset)
        assertEquals(com.mozhi.reader.core.i18n.UiText.of(com.mozhi.reader.R.string.reader_bookmark_added),
            (reader.viewModel.events.first() as ReaderEvent.ShowLocalizedMessage).text)
        assertEquals(com.mozhi.reader.core.i18n.UiText.of(com.mozhi.reader.R.string.reader_bookmark_exists),
            (reader.viewModel.events.first() as ReaderEvent.ShowLocalizedMessage).text)
        assertTrue(reader.bookmarks.tryReceive().isFailure)
        reader.viewModel.uiState.first { it.bookmarks.size == 1 }
        assertTrue(reader.viewModel.isCurrentPositionBookmarked())
        reader.viewModel.toggleBookmark()
        assertEquals(com.mozhi.reader.core.i18n.UiText.of(com.mozhi.reader.R.string.reader_bookmark_removed),
            (reader.viewModel.events.first() as ReaderEvent.ShowLocalizedMessage).text)
        reader.viewModel.uiState.first { it.bookmarks.isEmpty() }
    }

    @Test
    fun continuousScrollIgnoresPullBookmarkButKeepsTheNormalBookmarkAction() =
        withReader(source, pageMode = PageMode.SCROLL) { reader ->
            reader.viewModel.addBookmarkFromPull()
            assertTrue(reader.bookmarks.tryReceive().isFailure)
            reader.viewModel.toggleBookmark()
            assertEquals(0, reader.bookmarks.receive().charOffset)
        }

    @Test
    fun pullCapturesItsOriginalPageBeforeNavigationOrConversionChanges() = withReader(source) { reader ->
        val displayStart = reader.viewModel.contentController.chapterBody(0)!!.indexOf("主板")
        reader.viewModel.goToPosition(0, displayStart)
        reader.viewModel.addBookmarkFromPull()
        reader.viewModel.goToPosition(0, 0)
        reader.changeMode(ChineseConversionMode.OFF)
        assertEquals(source.indexOf("主機板"), reader.bookmarks.receive().charOffset)
    }

    @Test
    fun emptyConvertedChapterStillPersistsProgress() = withReader("") { reader ->
        reader.viewModel.flushProgress()
        assertEquals(0, reader.progress.receive().offset)
    }

    @Test
    fun sourceBackedMarksReuseTheAlreadyConvertedChapter() = withReader(source) { reader ->
        val start = source.indexOf("主機板")
        val annotation = AnnotationEntity(
            bookId = 1, personaId = null, chapterIndex = 0,
            startCharOffset = start, endCharOffset = start + 3,
            selectedText = "主機板", note = "", createdAt = 0
        )
        clearMocks(reader.converter, answers = false)

        repeat(3) {
            val result = reader.viewModel.resolveAnnotationRange(annotation)!!
            val displayed = reader.viewModel.contentController.chapterBody(0)!!
            assertEquals("主板", displayed.substring(result.start, result.end))
        }

        verify(exactly = 0) { reader.converter.convert(source, ChineseConversionMode.TW2SP) }
    }

    @Test
    fun illustrationGenerationKeepsOriginalOffsetAndFullUntrimmedQuote() {
        val original = "網際網路。" + "甲".repeat(40) + " 主機板" + "乙".repeat(2100) + " \n"
        withReader(original) { reader ->
            val displayed = reader.viewModel.contentController.chapterBody(0)!!
            val displayStart = displayed.indexOf(' ')
            val selection = reader.viewModel.sourceSelectionForDisplayed(0, displayStart until displayed.length)!!
            val generated = Channel<IllustrationEntity>(Channel.UNLIMITED)
            val media = mockk<AiMediaGenerationService> {
                coEvery { generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    IllustrationEntity(
                        bookId = firstArg(), chapterIndex = secondArg(), charOffset = thirdArg(),
                        sourceText = arg(3), prompt = arg(4), textAnchorJson = arg(6),
                        imagePath = "/tmp/illustration.png", createdAt = 0
                    ).also { generated.send(it) }
                }
            }
            val speaker = mockk<SystemTtsSpeaker> {
                every { isSpeaking } returns MutableStateFlow(false)
            }
            val mediaViewModel = ReaderSelectionMediaViewModel(media, mockk(), speaker, mockk())
            try {
                mediaViewModel.generateImage(
                    bookId = 1, bookTitle = "", chapterTitle = "", chapterIndex = 0,
                    charOffset = selection.start, textAnchorJson = selection.textAnchorJson,
                    selection = selection.text, contextText = ""
                )
                val illustration = generated.receive()
                val originalStart = original.indexOf(' ')
                assertEquals(originalStart, illustration.charOffset)
                assertEquals(original.substring(originalStart), illustration.sourceText)
                assertEquals(
                    ResolvedTextAnchor(displayStart, displayed.length),
                    reader.viewModel.resolveIllustrationRange(illustration)
                )
                reader.changeMode(ChineseConversionMode.OFF)
                assertEquals(
                    ResolvedTextAnchor(originalStart, original.length),
                    reader.viewModel.resolveIllustrationRange(illustration.copy(textAnchorJson = ""))
                )
            } finally {
                mediaViewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            }
        }
    }

    @Test
    fun coldOpenSchedulesOnFirstReadyLayoutAndResumeCanRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reader = ReaderFixture(source)
        try {
            reader.viewModel.setReaderVisible(true)
            verify(exactly = 0) { reader.annotationScheduler.onChapterEntered(any(), any()) }
            reader.viewModel.uiState.first { it.isContentReady }
            verify(exactly = 1) { reader.annotationScheduler.onChapterEntered(1L, 0) }
            reader.viewModel.onContentChanged(0)
            verify(exactly = 1) { reader.annotationScheduler.onChapterEntered(1L, 0) }
            reader.viewModel.setReaderVisible(false)
            reader.viewModel.setReaderVisible(true)
            verify(exactly = 2) { reader.annotationScheduler.onChapterEntered(1L, 0) }
        } finally {
            reader.viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private fun withReader(body: String, pageMode: PageMode = PageMode.PAGINATED,
        layout: com.mozhi.reader.core.library.EpubLayoutChapterBundle? = null,
        translations: List<ParagraphTranslation> = emptyList(),
        test: suspend (ReaderFixture) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val reader = ReaderFixture(body, pageMode, layout, translations)
        try {
            reader.viewModel.uiState.first { it.isContentReady }
            while (reader.progress.tryReceive().isSuccess) { /* Discard progress from opening the fixture. */ }
            test(reader)
        } finally {
            // Combined flows must finish cancelling their children before Main is reset.
            reader.viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private data class SavedProgress(val locator: String, val offset: Int)

    /** Real ViewModel/controller/presenter/repositories; only storage and unrelated services are doubled. */
    private class ReaderFixture(body: String, pageMode: PageMode = PageMode.PAGINATED,
        layout: com.mozhi.reader.core.library.EpubLayoutChapterBundle? = null,
        translations: List<ParagraphTranslation> = emptyList()) {
        var cachedTranslations = translations
        var translationCalls = 0
        val translationClient = mockk<ChatApiClient>()
        val annotations = Channel<AnnotationEntity>(Channel.UNLIMITED)
        val bookmarks = Channel<BookmarkEntity>(Channel.UNLIMITED)
        val progress = Channel<SavedProgress>(Channel.UNLIMITED)
        private val storedBookmarks = MutableStateFlow<List<BookmarkEntity>>(emptyList())
        val converter = spyk(ChineseTextConverter())
        val viewModel: ReaderViewModel
        val annotationScheduler = mockk<com.mozhi.reader.ai.companion.ProactiveAnnotationScheduler>(relaxed = true) {
            every { results } returns kotlinx.coroutines.flow.MutableSharedFlow()
        }

        init {
            val book = BookEntity(
                id = 1, title = "測試", author = "", coverPath = null, epubPath = "",
                sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1,
                textVersion = LibraryRepository.CURRENT_TEXT_VERSION
            )
            val chapter = ChapterEntity(
                bookId = 1, chapterIndex = 0, title = "章節", href = "", charCount = body.length,
                textByteOffset = 0, textByteLength = body.toByteArray().size
            )
            val bookDao = mockk<BookDao> {
                coEvery { getBook(1) } returns book
                every { observeBook(1) } returns flowOf(book)
                coEvery { getChapters(1) } returns listOf(chapter)
                every { observeBookmarks(1) } returns storedBookmarks
                coEvery { getBookmarks(1) } answers { storedBookmarks.value }
                coEvery { deleteBookmark(any()) } answers {
                    storedBookmarks.value = storedBookmarks.value.filterNot { it.id == firstArg<Long>() }
                }
                every { observeTocEntries(1) } returns flowOf(emptyList())
                every { observeReadingDays(1) } returns flowOf(emptyList())
                coEvery { insertBookmark(any()) } coAnswers {
                    val row = firstArg<BookmarkEntity>().copy(id = 1)
                    storedBookmarks.value = listOf(row)
                    bookmarks.send(row)
                    1L
                }
                coEvery { updateProgress(1, any(), 0, any(), any(), any()) } coAnswers {
                    progress.send(SavedProgress(secondArg(), arg(3)))
                }
            }
            val annotationDao = mockk<AnnotationDao> {
                every { observeForBook(1) } returns flowOf(emptyList())
                every { observeRepliedAnnotationIds(1) } returns flowOf(emptyList())
                coEvery { insert(any()) } coAnswers {
                    annotations.send(firstArg())
                    1L
                }
            }
            val textStore = mockk<BookTextStore> {
                coEvery { readChapter(1, 0, any()) } returns body
            }
            val mediaStore = mockk<BookMediaStore> {
                coEvery { read(1) } returns emptyList()
            }
            val layoutStore = mockk<BookLayoutStore> {
                coEvery { readChapter(1, 0, any()) } returns layout
            }
            val settings = ReaderSettings(pageMode = pageMode, bookChineseConversions = mapOf(1L to ChineseConversionMode.TW2SP))
            val settingsRepository = mockk<ReaderSettingsRepository> {
                every { cachedSettings } returns MutableStateFlow(settings)
                every { this@mockk.settings } returns flowOf(settings)
                every { showAiAnnotations } returns flowOf(true)
                every { companionAutonomySettings } returns flowOf(com.mozhi.reader.core.datastore.CompanionAutonomySettings())
                every { lastAnnotationStyle } returns flowOf("highlight")
                every { lastAnnotationColor } returns flowOf("")
                coEvery { setBookChineseConversionMode(1, any()) } returns Unit
                coEvery { setBilingual(1, any()) } returns Unit
            }
            val libraryRepository = LibraryRepository(
                mockk(), mockk(), bookDao, textStore, mockk(), mediaStore, layoutStore, mockk(), mockk()
            )
            val illustrations = mockk<IllustrationRepository> {
                every { observeForBook(1) } returns flowOf(emptyList())
            }
            viewModel = ReaderViewModel(
                SavedStateHandle(mapOf("bookId" to 1L)), libraryRepository,
                AnnotationRepository(annotationDao), illustrations, mediaStore, layoutStore,
                settingsRepository, mockk(), mockk(), mockk(), mockk(), mockk(),
                annotationScheduler, mockk(), ChineseChapterPresenter(converter), converter,
                mockk {
                    coEvery { load(any(), any(), any()) } answers { cachedTranslations }
                    coEvery { save(any(), any(), any(), any()) } answers {
                        val record = arg<ParagraphTranslation>(3)
                        cachedTranslations = cachedTranslations.filterNot { it.start == record.start } + record
                        cachedTranslations
                    }
                    coEvery { delete(any(), any(), any(), any()) } answers {
                        val record = arg<ParagraphTranslation>(3)
                        cachedTranslations = cachedTranslations.filterNot { it.start == record.start }
                        cachedTranslations
                    }
                }, mockk {
                    coEvery { forRole(com.mozhi.reader.core.database.entity.ModelRole.TRANSLATION) } answers {
                        translationCalls++
                        ResolvedChatClient(translationClient, ChatOptions(), mockk(), "translation")
                    }
                }
            )
            viewModel.contentController.updateEnvironment(
                TypesetSpec(100f, 200f, 25f, 34f, 9f, 9f, 10f, 25f),
                FakeMeasure()
            )
        }

        suspend fun changeMode(mode: ChineseConversionMode) {
            viewModel.setChineseConversionMode(mode)
            viewModel.uiState.first { it.isContentReady }
        }
    }
}
