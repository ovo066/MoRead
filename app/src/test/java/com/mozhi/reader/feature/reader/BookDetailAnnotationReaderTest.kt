package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.ResolvedTextAnchor
import com.mozhi.reader.feature.bookdetail.*
import com.mozhi.reader.feature.reader.engine.*
import com.mozhi.reader.ui.*
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.theme.MoReadTheme
import io.mockk.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real details page → real note sheet → saved navigation request → real reader/ink rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDetailAnnotationReaderTest {
    @get:Rule val compose = createComposeRule()
    private val body = (1..30).joinToString("\n") { "第${it}段，夜色渐深，河岸的灯火映在水面上。林舟翻开了信，想起昨天在渡口遇见的朋友。" }
    private val quote = "第15段，夜色渐深"
    private val annotation = AnnotationEntity(id = 11, bookId = 1, chapterIndex = 10,
        startCharOffset = body.indexOf(quote), endCharOffset = body.indexOf(quote) + quote.length,
        selectedText = quote, note = "第十一章的批注，跳转后应始终保留。", style = "UNDERLINE", createdAt = 1)
    private val storedAnnotations = MutableStateFlow(listOf(annotation))
    private lateinit var nav: NavHostController
    private lateinit var root: View
    private lateinit var controller: ReaderContentController
    private lateinit var holder: ReaderPaneHolder
    private lateinit var scrollHolder: ScrollPaneHolder
    private lateinit var screen: ReaderScreenState
    private var savedChapter = 0
    private var savedOffset = 0
    private var chapterGate: CompletableDeferred<Unit>? = null
    private var mode = PageMode.PAGINATED
    private var renderedMarks: List<ReaderAnnotationMark> = emptyList()
    private var clickedRequest: ReaderLocateRequest? = null

    private fun mount(pageMode: PageMode = PageMode.PAGINATED) {
        mode = pageMode
        val book = BookEntity(id = 1, title = "灯塔来信", author = "回归作者", coverPath = null,
            epubPath = "", sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 17, lastReadAt = 1)
        val detail = mockk<BookDetailViewModel>(relaxed = true)
        every { detail.uiState } returns MutableStateFlow(BookDetailUiState(book = book,
            annotations = listOf(annotation), isLoading = false))
        every { detail.coverCandidates } returns MutableStateFlow(emptyList())
        every { detail.coverSearchQueries } returns MutableStateFlow(emptyList())
        every { detail.coverSearchAgentEnhanced } returns MutableStateFlow(false)
        every { detail.pendingCover } returns MutableStateFlow(null)
        every { detail.coverGenerationProgress } returns MutableStateFlow(null)
        every { detail.events } returns emptyFlow()
        compose.setContent {
            nav = rememberNavController()
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MoReadTheme { MoReadBackdrop { MoReadNavigationScaffold {
                MoReadNavigationHost(nav) {
                    rootComposable("bookshelf", expanded = false) {}
                    pushComposable("book/{bookId}") {
                        BookDetailScreen(bookId = 1, onBack = { nav.popBackStack() },
                            onContinueReading = { nav.navigate("reader/$it") },
                            onLocateAnnotation = {
                                clickedRequest = it.readerLocateRequest()
                                nav.navigate("reader/${it.bookId}")
                                nav.currentBackStackEntry!!.savedStateHandle.requestReaderLocate(it.readerLocateRequest())
                            }, viewModel = detail)
                    }
                    pushComposable("reader/{bookId}") { entry ->
                        val request by remember(entry) { entry.savedStateHandle.readerLocateRequests() }.collectAsState(null)
                        Reader(request, entry.savedStateHandle::consumeReaderLocate)
                    }
                }
            } } }
        }
        compose.runOnIdle { nav.navigate("book/1") }
        compose.waitForIdle()
    }

    @Composable
    private fun Reader(request: ReaderLocateRequest?, consumed: (ReaderLocateRequest) -> Unit) {
        val scope = rememberCoroutineScope()
        var revision by remember { mutableIntStateOf(0) }
        var chapter by remember { mutableIntStateOf(savedChapter) }
        var hook by remember { mutableStateOf<((Int) -> Unit)?>(null) }
        val reader = remember {
            ReaderContentController(scope, { index ->
                if (index == 10) chapterGate?.await()
                ReaderChapterContent(body)
            }, object : ReaderContentController.Listener {
                override fun onContentChanged(relativePosition: Int) { hook?.invoke(relativePosition); revision++ }
                override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int,
                    pageCount: Int, bookProgress: Float) {
                    chapter = chapterIndex
                    savedChapter = chapterIndex
                    savedOffset = charOffset
                }
                override fun onContentError(chapterIndex: Int, error: Throwable) { throw AssertionError(error) }
            }).also {
                it.setChapters(List(17) { index -> ChapterMeta(index, "第 ${index + 1} 章", body.length) })
                it.openPosition(savedChapter, savedOffset)
            }
        }
        controller = reader
        val pageHolder = remember(reader) { ReaderPaneHolder(reader) }
        val scrollingHolder = remember(reader) { ScrollPaneHolder(reader) }
        val readerScreen = remember(reader) { ReaderScreenState() }
        holder = pageHolder
        scrollHolder = scrollingHolder
        screen = readerScreen
        DisposableEffect(reader) { onDispose { pageHolder.release(); scrollingHolder.release() } }
        val annotations by storedAnnotations.collectAsState()
        val marks = rememberReaderAnnotationMarks(annotations, emptySet(), chapter, revision,
            ChineseConversionMode.OFF, reader, remember(reader) {
                { item -> reader.chapterBody(item.chapterIndex)?.let { ResolvedTextAnchor(item.startCharOffset, item.endCharOffset) } }
            })
        SideEffect { renderedMarks = marks }
        ReaderLocateEffects(request, true, reader.isReady, revision, ChineseConversionMode.OFF, readerScreen,
            resolve = { ResolvedTextAnchor(it.startCharOffset, it.endCharOffset) },
            jump = reader::jumpToChapter, onConsumed = consumed,
            highlightIsVisible = { reader.isReady && reader.isDisplaying(it.chapterIndex, it.startCharOffset) })
        val settings = ReaderSettings(pageMode = mode, showFooter = false)
        val palette = readerPalette(settings.theme, false, Color(0xff526d58))
        if (mode == PageMode.SCROLL) {
            ReaderScrollPane(controller = reader, holder = scrollingHolder, settings = settings, palette = palette,
                enabled = true, registerContentHook = { hook = it }, onScrollSupersedesNavigation = {},
                onCenterTap = {}, onBoundary = {}, onNotice = {}, annotations = marks,
                transientHighlight = readerScreen.locateHighlight, onAiAction = { _, _, _ -> },
                onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {}, onTtsAction = {},
                onImageAction = { _, _, _ -> }, onEditText = null, modifier = Modifier.fillMaxSize())
        } else {
            ReaderPane(controller = reader, holder = pageHolder, settings = settings, palette = palette,
                enabled = true, registerContentHook = { hook = it }, onCenterTap = {}, onAddBookmark = {},
                onBoundary = {}, onNotice = {}, annotations = marks, transientHighlight = readerScreen.locateHighlight,
                onAiAction = { _, _, _ -> }, onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {},
                onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null, modifier = Modifier.fillMaxSize())
        }
    }

    private fun clickDetailAnnotation() {
        compose.onNodeWithTag("detail-asset-annotations").performScrollTo().performClick()
        compose.onNodeWithText("跳到原文").performClick()
        compose.waitUntil(15_000) { ::controller.isInitialized && controller.chapterIndex == 10 }
        assertEquals(annotation.readerLocateRequest(), clickedRequest)
    }

    private fun awaitInk() {
        compose.waitUntil(15_000) { controller.isReady && renderedMarks.any { it.id == annotation.id } }
        compose.waitForIdle()
        assertInk()
    }

    private fun assertInk() = compose.runOnIdle {
        val pages = if (mode == PageMode.SCROLL) scrollHolder.visiblePages(10).map { it.page to it.origin }
            else holder.visiblePages().map { it.first.page to it.second }
        val point = pages.firstNotNullOf { (page, origin) ->
            page.annotationGeometry(renderedMarks, 8f, 6f, 1000f).highlights.firstOrNull()?.let {
                origin + Offset((it.left + it.right) / 2, (it.top + it.bottom) / 2)
            }
        }
        assertEquals(listOf(annotation.id), if (mode == PageMode.SCROLL) scrollHolder.annotationIdsAt(point) else holder.annotationIdsAt(point))
    }

    private fun expireHint() {
        compose.mainClock.advanceTimeBy(LOCATE_HIGHLIGHT_MS + 200)
        compose.waitForIdle()
        compose.runOnIdle { assertNull(screen.locateHighlight) }
        assertInk()
    }

    private fun verifyDetailEntry(pageMode: PageMode) {
        mount(pageMode)
        clickDetailAnnotation()
        awaitInk()
        expireHint()
        capture("detail-${pageMode.name.lowercase()}-after-hint")
        compose.runOnIdle { controller.jumpToChapter(11, 0) }
        compose.waitUntil(15_000) { controller.isReady && controller.chapterIndex == 11 }
        compose.runOnIdle { controller.jumpToChapter(10, annotation.startCharOffset) }
        awaitInk()
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        clickDetailAnnotation()
        awaitInk()
        expireHint()
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        compose.onNodeWithText("继续阅读").performScrollTo().performClick()
        compose.waitUntil(15_000) { controller.isReady && controller.chapterIndex == 10 }
        awaitInk()
        compose.runOnIdle { assertNull(screen.locateHighlight) }
        assertEquals(listOf(annotation), storedAnnotations.value)
    }

    @Test fun detailsJumpAndOrdinaryReentryKeepInkAfterTheLocationHintExpires() = verifyDetailEntry(PageMode.PAGINATED)
    @Test fun scrollingDetailsJumpAndReentryKeepInkAfterTheLocationHintExpires() = verifyDetailEntry(PageMode.SCROLL)

    @Test fun slowChapterLoadingAndLateStoredAnnotationsDoNotLoseInk() {
        storedAnnotations.value = emptyList()
        chapterGate = CompletableDeferred()
        mount()
        clickDetailAnnotation()
        compose.mainClock.advanceTimeBy(LOCATE_HIGHLIGHT_MS + 500)
        compose.runOnIdle { assertFalse(controller.isReady); assertNotNull(screen.locateHighlight) }
        compose.runOnIdle { chapterGate!!.complete(Unit) }
        compose.waitUntil(15_000) { controller.isReady }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(LOCATE_HIGHLIGHT_MS + 200)
        compose.waitForIdle()
        lateinit var pageBeforeArrival: TextPage
        var generationBeforeArrival = 0
        compose.runOnIdle {
            assertNull(screen.locateHighlight)
            assertTrue(renderedMarks.isEmpty())
            pageBeforeArrival = (controller.curPage() as RenderPage.Laid).page
            generationBeforeArrival = controller.layoutGeneration
        }
        compose.runOnIdle { storedAnnotations.value = listOf(annotation) }
        awaitInk()
        compose.runOnIdle {
            assertNull(screen.locateHighlight)
            assertSame(pageBeforeArrival, (controller.curPage() as RenderPage.Laid).page)
            assertEquals(generationBeforeArrival, controller.layoutGeneration)
        }
        capture("detail-delayed-annotation")
    }

    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File("build/reports/ui-qa/annotations/$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }
}
