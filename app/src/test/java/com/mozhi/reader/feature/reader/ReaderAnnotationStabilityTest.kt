package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.retrieval.AnnotationVisibility
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.feature.reader.engine.ChapterMeta
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderChapterContent
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.TextPage
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real ReaderPane gestures, real pagination and native bitmaps while annotations arrive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderAnnotationStabilityTest {
    @get:Rule val compose = createComposeRule()
    private val marks = mutableStateOf<List<ReaderAnnotationMark>>(emptyList())
    private val animation = mutableStateOf(PageTurnAnimation.SLIDE)
    private val readTracking = mutableStateOf(true)
    private val readEnd = mutableIntStateOf(0)
    private val aiAnnotations = mutableStateOf<List<AnnotationEntity>>(emptyList())
    private lateinit var controller: ReaderContentController
    private lateinit var holder: ReaderPaneHolder
    private lateinit var root: View
    private var contentHook: ((Int) -> Unit)? = null

    private fun mount() {
        val body = List(45) {
            "夜色渐深，河岸的灯火映在水面上。她停下脚步，听见远处传来一阵钟声，" +
                "才发觉信里提到的渡口就在眼前。风翻动书页，而她仍记得刚才读到的那句话。"
        }.joinToString("\n")
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            val scope = rememberCoroutineScope()
            val reader = remember {
                ReaderContentController(scope, { ReaderChapterContent(body) }, object : ReaderContentController.Listener {
                    override fun onContentChanged(relativePosition: Int) { contentHook?.invoke(relativePosition) }
                    override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int,
                        pageCount: Int, bookProgress: Float) = Unit
                }).also { it.setChapters(listOf(ChapterMeta(0, "河岸", body.length))) }
            }
            controller = reader
            holder = remember(reader) { ReaderPaneHolder(reader) }
            DisposableEffect(holder) { onDispose { holder.release() } }
            val settings = ReaderSettings(pageTurnAnimation = animation.value)
            ReaderPane(
                controller = reader, holder = holder, settings = settings,
                palette = readerPalette(settings.theme, false, Color(0xff526d58)), enabled = true,
                registerContentHook = { contentHook = it },
                onCenterTap = {}, onAddBookmark = {}, onBoundary = {}, onNotice = {},
                annotations = marks.value + aiAnnotations.value.filter {
                    AnnotationVisibility.isVisible(it, ReadingScope.upto(0, readEnd.intValue))
                }.map {
                    ReaderAnnotationMark(it.id, it.chapterIndex, it.startCharOffset, it.endCharOffset,
                        hasComment = it.note.isNotBlank(), style = it.style, colorTag = it.colorTag)
                }, onAiAction = { _, _, _ -> },
                onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {},
                onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null,
                readTrackingEnabled = readTracking.value,
                onVisiblePagesDrawn = { snapshot ->
                    if (readTracking.value) readEnd.intValue = maxOf(readEnd.intValue, snapshot.displayEnd)
                },
                modifier = Modifier.size(360.dp, 640.dp).testTag("annotation-reader")
            )
        }
        compose.waitUntil(15_000) {
            ::holder.isInitialized && controller.isReady && holder.curBitmap != null &&
                controller.nextPage() is RenderPage.Laid
        }
        compose.waitForIdle()
    }

    @Test
    fun aiCommentsArrivingAfterEntryOrResumeAppearWithoutTurningAndKeepUnreadNotesHidden() {
        readTracking.value = false
        mount()
        drawScreen()
        lateinit var page: TextPage
        var generation = 0
        lateinit var before: Bitmap
        compose.runOnIdle {
            page = (controller.curPage() as RenderPage.Laid).page
            generation = controller.layoutGeneration
            before = checkNotNull(holder.curBitmap!!.copy(Bitmap.Config.ARGB_8888, false))
            assertEquals(0, readEnd.intValue)
            val mark = mark(page, 11, 0)
            val end = page.chapterPosition + page.charLength
            aiAnnotations.value = listOf(
                AnnotationEntity(id = 11, bookId = 1, personaId = 9, chapterIndex = 0,
                    startCharOffset = mark.startCharOffset, endCharOffset = mark.endCharOffset,
                    selectedText = "河岸", note = "刚生成的想法", style = "UNDERLINE",
                    sourceScopeChapterIndex = 0, sourceScopeCharOffset = end, createdAt = 1),
                AnnotationEntity(id = 12, bookId = 1, personaId = 9, chapterIndex = 0,
                    startCharOffset = mark.startCharOffset, endCharOffset = mark.endCharOffset,
                    selectedText = "河岸", note = "涉及尚未读到的内容", style = "UNDERLINE",
                    sourceScopeChapterIndex = 0, sourceScopeCharOffset = end + 100, createdAt = 2)
            )
        }
        try {
            compose.waitForIdle()
            compose.runOnIdle {
                assertTrue(holder.annotationIdsAt(markPoint(page)).isEmpty())
                // Only lifecycle/entry readiness changes. No touch, navigation or mark update.
                readTracking.value = true
            }
            drawScreen()
            compose.waitUntil(10_000) { readEnd.intValue >= page.chapterPosition + page.charLength }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(listOf(11L), holder.annotationIdsAt(markPoint(page)))
                assertSame(page, (controller.curPage() as RenderPage.Laid).page)
                assertEquals(generation, controller.layoutGeneration)
                assertFalse(before.sameAs(holder.curBitmap!!))
                // A further AI transaction arrives while the same page remains stationary.
                aiAnnotations.value = aiAnnotations.value + aiAnnotations.value.first().copy(id = 13)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(setOf(11L, 13L), holder.annotationIdsAt(markPoint(page)).toSet())
                assertSame(page, (controller.curPage() as RenderPage.Laid).page)
                assertEquals(generation, controller.layoutGeneration)
                save(holder.curBitmap!!, "ai-arrival-without-turn")
            }
        } finally { before.recycle() }
    }

    /** Robolectric has no display vsync; execute the pending native View/Compose draw explicitly. */
    private fun drawScreen() {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try { root.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
        }
        compose.waitForIdle()
    }

    @Test
    fun commentsDuringForwardAndBackwardTurnsLeaveBothFrozenFacesAndPaginationUntouched() {
        mount()
        for (mode in listOf(PageTurnAnimation.SLIDE, PageTurnAnimation.COVER, PageTurnAnimation.SIMULATION)) {
            compose.runOnIdle { animation.value = mode; marks.value = emptyList() }
            compose.waitForIdle()
            for (direction in listOf(PageTurnDirection.NEXT, PageTurnDirection.PREVIOUS)) {
                verifyTurn(direction, cancel = false, label = "${mode.name.lowercase()}-${direction.name.lowercase()}")
                compose.runOnIdle { marks.value = emptyList() }
                compose.waitForIdle()
            }
        }
    }

    @Test
    fun cancellingATurnPublishesTheLatestCommentsOnTheUnmovedPage() {
        mount()
        verifyTurn(PageTurnDirection.NEXT, cancel = true, label = "cancel")
    }

    @Test
    fun addingACommentOnlyChangesPixelsInsideTheLastMarkedCharacter() {
        mount()
        lateinit var page: TextPage
        compose.runOnIdle { page = (controller.curPage() as RenderPage.Laid).page }
        for (style in listOf("UNDERLINE", "WAVY", "HIGHLIGHT")) {
            val annotation = mark(page, 1, 0).copy(style = style, hasComment = false)
            compose.runOnIdle { marks.value = listOf(annotation) }
            compose.waitForIdle()
            lateinit var before: Bitmap
            compose.runOnIdle { before = checkNotNull(holder.curBitmap!!.copy(Bitmap.Config.ARGB_8888, false)) }
            try {
                compose.runOnIdle { marks.value = listOf(annotation.copy(hasComment = true)) }
                compose.waitForIdle()
                compose.runOnIdle {
                    val after = checkNotNull(holder.curBitmap)
                    val line = page.lines.first { !it.isTitle && it.charLength > 8 }
                    val lastMarked = line.columns.filter { it.sourceLength > 0 }[3]
                    val origin = holder.visiblePages().first().second
                    var changed = 0
                    for (y in 0 until after.height) for (x in 0 until after.width) {
                        if (before.getPixel(x, y) == after.getPixel(x, y)) continue
                        changed++
                        assertTrue("$style changed text outside the final character at $x,$y",
                            x >= origin.x + lastMarked.start - 1 && x <= origin.x + lastMarked.end + 1 &&
                                y >= origin.y + line.lineTop - 1 && y <= origin.y + line.lineBottom + 1)
                    }
                    assertTrue("$style comment dot is invisible", changed > 0)
                    assertSame(page, (controller.curPage() as RenderPage.Laid).page)
                    save(after, "style-${style.lowercase()}-dot")
                }
            } finally { before.recycle() }
        }
    }

    private fun verifyTurn(direction: PageTurnDirection, cancel: Boolean, label: String) {
        lateinit var source: RenderPage.Laid
        lateinit var destination: RenderPage.Laid
        var generation = 0
        var offset = 0
        compose.runOnIdle {
            source = controller.curPage() as RenderPage.Laid
            destination = (if (direction == PageTurnDirection.NEXT) controller.nextPage() else controller.prevPage()) as RenderPage.Laid
            generation = controller.layoutGeneration
            offset = controller.charOffset
        }
        val forward = direction == PageTurnDirection.NEXT
        compose.onNodeWithTag("annotation-reader").performTouchInput {
            down(Offset(if (forward) 310f else 45f, 280f))
            moveTo(Offset(if (forward) 280f else 75f, 280f), delayMillis = 32)
            moveTo(Offset(if (forward) 170f else 205f, 280f), delayMillis = 100)
        }
        lateinit var front: Bitmap
        lateinit var under: Bitmap
        lateinit var frozenFront: Bitmap
        lateinit var frozenUnder: Bitmap
        compose.runOnIdle {
            front = checkNotNull(holder.bitmapFor(direction, true))
            under = checkNotNull(holder.bitmapFor(direction, false))
            frozenFront = checkNotNull(front.copy(Bitmap.Config.ARGB_8888, false))
            frozenUnder = checkNotNull(under.copy(Bitmap.Config.ARGB_8888, false))
            save(checkNotNull(holder.curBitmap), "$label-before")
        }
        try {
            // Separate arrivals exercise recomposition and the deferred refresh queue each time.
            repeat(4) { update ->
                compose.runOnIdle {
                    marks.value = listOf(mark(source.page, 1, update), mark(destination.page, 2, update))
                }
                compose.waitForIdle()
                compose.runOnIdle {
                    assertEquals(generation, controller.layoutGeneration)
                    assertEquals(offset, controller.charOffset)
                    assertSame(source.page, (controller.curPage() as RenderPage.Laid).page)
                    assertTrue("front changed during $label", frozenFront.sameAs(front))
                    assertTrue("under changed during $label", frozenUnder.sameAs(under))
                    assertTrue("unpublished comments became clickable", holder.annotationIdsAt(markPoint(source.page)).isEmpty())
                }
            }
            compose.onNodeWithTag("annotation-reader").performTouchInput {
                if (cancel) moveTo(Offset(180f, 280f), delayMillis = 40)
                up()
            }
            compose.waitForIdle()
            compose.runOnIdle {
                val expected = if (cancel) source else destination
                assertEquals(generation, controller.layoutGeneration)
                assertSame(expected.page, (controller.curPage() as RenderPage.Laid).page)
                assertEquals(if (cancel) offset else destination.page.chapterPosition, controller.charOffset)
                assertEquals(listOf(if (cancel) 1L else 2L), holder.annotationIdsAt(markPoint(expected.page)))
                val previouslyDrawn = if (cancel == forward) frozenFront else frozenUnder
                assertFalse("comments did not appear after $label", previouslyDrawn.sameAs(checkNotNull(holder.curBitmap)))
                save(checkNotNull(holder.curBitmap), "$label-after")
            }
        } finally {
            frozenFront.recycle()
            frozenUnder.recycle()
        }
    }

    private fun mark(page: TextPage, id: Long, update: Int): ReaderAnnotationMark {
        val line = page.lines.first { !it.isTitle && it.charLength > 8 }
        return ReaderAnnotationMark(id, 0, line.chapterPosition + 1, line.chapterPosition + 4 + update,
            hasComment = true, style = listOf("UNDERLINE", "WAVY", "HIGHLIGHT", "UNDERLINE")[update], colorTag = "green")
    }

    private fun markPoint(page: TextPage): Offset {
        val line = page.lines.first { !it.isTitle && it.charLength > 8 }
        val column = line.columns.filter { it.sourceLength > 0 }[2]
        val origin = holder.visiblePages().first().second
        return origin + Offset((column.start + column.end) / 2f, (line.lineTop + line.lineBottom) / 2f)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val file = File("build/reports/ui-qa/annotations/$name.png")
        file.parentFile?.mkdirs()
        // 平移/覆盖模式的位图透明，QA 图片需要带上阅读器实际使用的独立纸面。
        val composite = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(composite)
            holder.drawBackground(canvas, bitmap.width.toFloat(), bitmap.height.toFloat())
            canvas.drawBitmap(bitmap, 0f, 0f, null)
            file.outputStream().use { composite.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { composite.recycle() }
    }
}
