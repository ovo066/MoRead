package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.ai.companion.ProactiveAnnotationCommitStore
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.library.ResolvedTextAnchor
import com.mozhi.reader.core.retrieval.AnnotationVisibility
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.feature.reader.engine.*
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual proactive SQLite commits -> repository flows -> scope -> marks -> native reader ink. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderLiveAnnotationUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: MoReadDatabase
    private lateinit var job: ProactiveAnnotationJobEntity
    private lateinit var reader: ReaderContentController
    private lateinit var paging: ReaderPaneHolder
    private lateinit var scrolling: ScrollPaneHolder
    private lateinit var root: View
    private var hook: ((Int) -> Unit)? = null
    private val revision = mutableIntStateOf(0)
    private val chapter = mutableIntStateOf(0)
    private val tracking = mutableStateOf(false)
    private var observedBook: BookEntity? = null
    private var stored: List<AnnotationEntity> = emptyList()
    private var marks: List<ReaderAnnotationMark> = emptyList()
    private var body = List(45) { "夜色渐深，河岸的灯火映在水面上。她停下脚步，听见远处传来一阵钟声，才发觉信里提到的渡口就在眼前。" }.joinToString("\n")

    @Before fun openDatabase() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(app, MoReadDatabase::class.java).allowMainThreadQueries().build()
        database.bookDao().insertBook(BookEntity(id = 1, title = "河岸", author = "", coverPath = null,
            epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 2))
        val row = ProactiveAnnotationJobEntity(bookId = 1, chapterIndex = 0, personaId = 9,
            sourceRevision = "source", createdAt = 1, updatedAt = 1)
        job = row.copy(id = database.proactiveAnnotationJobDao().insert(row))
    }

    @After fun closeDatabase() {
        if (::paging.isInitialized) compose.runOnIdle { paging.release(); scrolling.release() }
        database.close()
    }

    private fun mount(mode: PageMode) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val observations = observeReaderAnnotations(AnnotationRepository(database.annotationDao()),
            IllustrationRepository(app, database.illustrationDao()), 1)
        compose.setContent {
            val view = LocalView.current
            val scope = rememberCoroutineScope()
            val controller = remember {
                ReaderContentController(scope, { ReaderChapterContent(body) }, object : ReaderContentController.Listener {
                    override fun onContentChanged(relativePosition: Int) { hook?.invoke(relativePosition); revision.intValue++ }
                    override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int,
                        pageCount: Int, bookProgress: Float) { chapter.intValue = chapterIndex }
                    override fun onContentError(chapterIndex: Int, error: Throwable) { throw AssertionError(error) }
                }).also {
                    it.setChapters(List(2) { index -> ChapterMeta(index, "河岸", body.length) })
                    it.openPosition(0, 0)
                }
            }
            val pageHolder = remember(controller) { ReaderPaneHolder(controller) }
            val scrollHolder = remember(controller) { ScrollPaneHolder(controller) }
            DisposableEffect(controller) { onDispose { pageHolder.release(); scrollHolder.release() } }
            val observation by observations.collectAsState(ReaderAnnotationObservation(emptyList(), emptyList(), emptySet()))
            val book by remember { database.bookDao().observeBook(1) }.collectAsState(null)
            val boundary = book?.let { ReadingScope.uptoProgress(it) } ?: ReadingScope.upto(0, 0)
            val visible = observation.annotations.filter { AnnotationVisibility.isVisible(it, boundary) }
            val resolved = rememberReaderAnnotationMarks(visible, observation.repliedIds, chapter.intValue,
                revision.intValue, ChineseConversionMode.OFF, controller, remember(controller) {
                    { row -> controller.chapterSource(row.chapterIndex)?.let { ResolvedTextAnchor(row.startCharOffset, row.endCharOffset) } }
                })
            SideEffect {
                reader = controller; paging = pageHolder; scrolling = scrollHolder; root = view.rootView
                stored = observation.annotations; marks = resolved; observedBook = book
            }
            val settings = ReaderSettings(pageMode = mode, showFooter = false)
            val palette = readerPalette(settings.theme, false, Color(0xff526d58))
            val drawn: (ReaderVisibleReadSnapshot) -> Unit = { snapshot ->
                scope.launch {
                    val current = if (mode == PageMode.SCROLL) scrollHolder.isCurrentVisibleRead(snapshot)
                        else controller.isCurrentVisibleRead(snapshot)
                    if (tracking.value && current) database.bookDao().markVisibleReadEnd(1, snapshot.chapterIndex, snapshot.displayEnd)
                }
            }
            if (mode == PageMode.SCROLL) {
                ReaderScrollPane(controller, scrollHolder, settings, palette, true, { hook = it }, {}, {},
                    onBoundary = {}, onNotice = {}, annotations = resolved, onAiAction = { _, _, _ -> },
                    onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {}, onTtsAction = {},
                    onImageAction = { _, _, _ -> }, onEditText = null,
                    readTrackingEnabled = tracking.value, onVisiblePagesDrawn = drawn,
                    modifier = Modifier.size(360.dp, 640.dp))
            } else {
                ReaderPane(controller, pageHolder, settings, palette, true, { hook = it },
                    onCenterTap = {}, onAddBookmark = {}, onBoundary = {}, onNotice = {},
                    annotations = resolved, onAiAction = { _, _, _ -> }, onAnnotationAction = { _, _, _ -> },
                    onAnnotationClick = {}, onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null,
                    readTrackingEnabled = tracking.value, onVisiblePagesDrawn = drawn,
                    modifier = Modifier.size(360.dp, 640.dp))
            }
        }
        compose.waitUntil(15_000) { ::reader.isInitialized && reader.isReady && observedBook != null &&
            (if (mode == PageMode.SCROLL) scrolling.captureVisibleRead() != null else paging.curBitmap != null) }
        compose.waitForIdle()
    }

    private fun commit(row: AnnotationEntity): Long = runBlocking {
        val updated = job.copy(doneParagraphEnds = "[${row.sourceScopeCharOffset}]", updatedAt = 2)
        val result = ProactiveAnnotationCommitStore(database).commit(row, updated, null, 999, 0) { true }!!
        job = result.job
        result.annotationId
    }

    private fun draw(): Bitmap {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
        }
        compose.waitForIdle()
        return bitmap
    }

    private fun verifyStationaryArrival(mode: PageMode) {
        mount(mode)
        val page = (reader.curPage() as RenderPage.Laid).page
        val line = page.lines.first { !it.isTitle && it.charLength > 10 }
        val row = AnnotationEntity(bookId = 1, personaId = 9, chapterIndex = 0,
            startCharOffset = line.chapterPosition + 1, endCharOffset = line.chapterPosition + 7,
            selectedText = body.substring(line.chapterPosition + 1, line.chapterPosition + 7), note = "刚生成的段评",
            style = "UNDERLINE", colorTag = "green", sourceScopeChapterIndex = 0,
            sourceScopeCharOffset = line.chapterPosition + line.charLength, createdAt = 1)
        val visibleId = commit(row)
        val futureId = commit(row.copy(sourceScopeCharOffset = body.length, note = "后文段评"))
        compose.waitUntil(10_000) { stored.size == 2 }
        assertTrue(marks.isEmpty())
        assertEquals(0, observedBook!!.maxReachedCharOffset)
        val generation = reader.layoutGeneration
        val offset = reader.charOffset
        val anchor = scrolling.anchorY
        val before = draw()
        try {
            compose.runOnIdle { tracking.value = true }
            draw().recycle()
            compose.waitUntil(10_000) { marks.any { it.id == visibleId } }
            compose.waitForIdle()
            assertFalse(marks.any { it.id == futureId })
            val repeatedId = commit(row.copy(note = "第二条已提交段评"))
            compose.waitUntil(10_000) { marks.any { it.id == repeatedId } }
            compose.waitForIdle()
            compose.runOnIdle {
                val origin = if (mode == PageMode.SCROLL) scrolling.visiblePages(0).first().origin
                    else paging.visiblePages().first().second
                val column = line.columns.filter { it.sourceLength > 0 }[2]
                val point = origin + Offset((column.start + column.end) / 2, (line.lineTop + line.lineBottom) / 2)
                val ids = if (mode == PageMode.SCROLL) scrolling.annotationIdsAt(point) else paging.annotationIdsAt(point)
                assertEquals(setOf(visibleId, repeatedId), ids.toSet())
                assertEquals(offset, reader.charOffset)
                assertEquals(generation, reader.layoutGeneration)
                assertEquals(anchor, scrolling.anchorY, 0f)
                assertSame(page, (reader.curPage() as RenderPage.Laid).page)
            }
            val after = draw()
            try {
                assertFalse("Room commits must repaint stationary ${mode.name} content", before.sameAs(after))
                val file = File("build/reports/ui-qa/annotations-live/${mode.name.lowercase()}.png")
                file.parentFile.mkdirs()
                file.outputStream().use { after.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { after.recycle() }
        } finally { before.recycle() }
    }

    @Test fun paginatedCommitsAppearWithoutLeavingOrTurningTheCurrentPage() = verifyStationaryArrival(PageMode.PAGINATED)
    @Test fun scrollingCommitsAppearOnResumeAndWhileStationaryWithoutMovingTheAnchor() = verifyStationaryArrival(PageMode.SCROLL)

    @Test fun changedScrollViewportInvalidatesAnUndeliveredDrawSnapshot() {
        mount(PageMode.SCROLL)
        compose.runOnIdle {
            val snapshot = scrolling.captureVisibleRead()!!
            assertTrue(scrolling.isCurrentVisibleRead(snapshot))
            scrolling.applyScroll(150f)
            assertFalse(scrolling.isCurrentVisibleRead(snapshot))
        }
        assertEquals(0, observedBook!!.maxReachedCharOffset)
    }

    @Test fun partiallyVisibleNextChapterUsesItsActualViewportPrefix() {
        body = "夜色渐深，河岸的灯火映在水面上。她停下脚步，听见远处传来一阵钟声，才发觉信里提到的渡口就在眼前。".repeat(3)
        mount(PageMode.SCROLL)
        compose.waitUntil(15_000) { reader.chapterSource(1) != null }
        compose.runOnIdle { scrolling.applyScroll(scrolling.viewportHeight * 0.25f) }
        compose.waitForIdle()
        lateinit var snapshot: ReaderVisibleReadSnapshot
        compose.runOnIdle {
            snapshot = scrolling.captureVisibleRead()!!
            assertEquals(0, reader.chapterIndex)
            assertEquals(1, snapshot.chapterIndex)
            assertTrue(snapshot.displayEnd > 0)
            assertTrue(snapshot.displayEnd < body.length)
            tracking.value = true
        }
        draw().recycle()
        compose.waitUntil(10_000) { observedBook?.maxReachedChapterIndex == 1 }
        assertEquals(snapshot.displayEnd, observedBook!!.maxReachedCharOffset)
        assertEquals(0, reader.chapterIndex)
    }
}
