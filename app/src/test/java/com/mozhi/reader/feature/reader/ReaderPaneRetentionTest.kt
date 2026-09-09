package com.mozhi.reader.feature.reader

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.BatteryManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.engine.ChapterMeta
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real bitmaps: a sentinel pixel detects an unwanted repaint, not just object reuse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderPaneRetentionTest {
    private val scope = TestScope()
    private val controller = ReaderContentController(scope, { null }, object : ReaderContentController.Listener {
        override fun onContentChanged(relativePosition: Int) = Unit
        override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) = Unit
    })
    private val holder = ReaderPaneHolder(controller)
    private val scrollHolder = ScrollPaneHolder(controller)
    private val settings = ReaderSettings()
    private val palette = ReaderPalette(
        background = Color.White, onBackground = Color.Black, muted = Color.Gray,
        glass = Color.White, glassStrong = Color.White, glassBorder = Color.LightGray,
        accent = Color.Blue, accentContainer = Color.LightGray, onAccent = Color.White,
        scrim = Color.Black, isDark = false
    )
    private val key = readerRenderStyleKey(settings, palette, Density(1f), IntSize(320, 560), 0f, 0f)
    private val environment = key.environment
    private var styleResolves = 0

    private fun apply(next: ReaderRenderStyleKey = key, embedded: Boolean = false): Boolean {
        val env = next.environment
        holder.setViewport(env.width, env.height)
        return holder.applyStyle(next, createStyle = {
            styleResolves++
            ReaderPageStyle.resolve(settings.copy(fontScale = env.fontScale), next.palette,
                Density(env.density, env.systemFontScale), env.leafWidth, env.height,
                env.statusBarPx, env.navigationBarPx, env.columnWidth)
        }, spread = null, includeBackgroundInPages = embedded, onBackgroundReady = {})
    }

    private fun mount(): Bitmap {
        apply()
        holder.refresh(0)
        holder.ensureFresh()
        return checkNotNull(holder.curBitmap)
    }

    private fun mark(bitmap: Bitmap = checkNotNull(holder.curBitmap)) {
        bitmap.setPixel(0, 0, android.graphics.Color.RED)
    }

    private fun assertRepainted(bitmap: Bitmap = checkNotNull(holder.curBitmap)) {
        assertNotEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
    }

    @After fun tearDown() {
        holder.release()
        scrollHolder.release()
        scope.cancel()
    }

    @Test fun returningFromChatReusesInkFontsAndLayoutWithoutRepainting() {
        val bitmap = mount()
        val style = holder.style
        val generation = controller.environmentGeneration
        mark(bitmap)
        holder.detach()
        assertFalse(bitmap.isRecycled)
        assertFalse(apply())
        holder.refresh(0)
        assertSame(bitmap, holder.curBitmap)
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
        assertSame(style, holder.style)
        assertEquals(1, styleResolves)
        assertEquals(generation, controller.environmentGeneration)
    }

    @Test fun colorsRepaintWithoutRetypesetting() {
        val bitmap = mount()
        val generation = controller.environmentGeneration
        mark(bitmap)
        assertFalse(apply(key.copy(palette = palette.copy(background = Color.Black, isDark = true))))
        holder.refresh(0)
        assertRepainted(bitmap)
        assertEquals(generation, controller.environmentGeneration)
        assertEquals(2, styleResolves)
    }

    @Test fun fontViewportDensityAndInsetsInvalidateTheLayoutKey() {
        mount()
        listOf(
            environment.copy(fontScale = environment.fontScale + 0.2f),
            environment.copy(width = 400, leafWidth = 400, columnWidth = 400f),
            environment.copy(density = 1.2f),
            environment.copy(systemFontScale = 1.2f),
            environment.copy(statusBarPx = 24f),
            environment.copy(navigationBarPx = 32f),
            environment.copy(fontLibraryHash = 1)
        ).forEach { next ->
            val generation = controller.environmentGeneration
            assertTrue(apply(key.copy(environment = next)))
            assertEquals(generation + 1, controller.environmentGeneration)
        }
    }

    @Test fun resizedBitmapsAreReplacedAndRecycled() {
        val old = mount()
        apply(key.copy(environment = environment.copy(width = 400, leafWidth = 400, columnWidth = 400f)))
        holder.refresh(0)
        assertNotSame(old, holder.curBitmap)
        assertTrue(old.isRecycled)
        assertEquals(400, holder.curBitmap!!.width)
    }

    @Test fun returningFromAnotherReadingModeReappliesTheControllerEnvironment() {
        mount()
        val style = checkNotNull(holder.style)
        holder.detach()
        scrollHolder.applyStyle(key, createStyle = { style }, onBackgroundReady = {})
        val otherModeGeneration = controller.environmentGeneration
        assertTrue(apply())
        assertFalse(controller.spreadMode)
        assertEquals(otherModeGeneration + 1, controller.environmentGeneration)
        assertEquals(1, styleResolves)
    }

    @Test fun overlaysInvalidateInkButIdenticalInputsDoNot() {
        val bitmap = mount()
        val marks = listOf(ReaderAnnotationMark(1, 0, 0, 4, true))
        mark(bitmap)
        assertTrue(holder.setAnnotations(marks))
        holder.ensureFresh()
        assertRepainted(bitmap)
        mark(bitmap)
        assertFalse(holder.setAnnotations(marks.toList()))
        holder.refresh(0)
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(0, 0))
        assertTrue(holder.setIllustrations(listOf(ReaderIllustrationMark(2, 0, 0, 4))))
        holder.ensureFresh()
        assertRepainted(bitmap)
        mark(bitmap)
        assertTrue(holder.setTransientHighlight(TransientHighlightSpan(0, 1, 3)))
        holder.ensureFresh()
        assertRepainted(bitmap)
    }

    @Test fun changingAnnotationStyleInvalidatesInkWithoutRetypesetting() {
        val bitmap = mount()
        val generation = controller.environmentGeneration
        val annotation = ReaderAnnotationMark(1, 0, 0, 4, true)
        listOf("HIGHLIGHT", "WAVY", "UNDERLINE").forEach { style ->
            mark(bitmap)
            assertTrue(holder.setAnnotations(listOf(annotation.copy(style = style))))
            holder.ensureFresh()
            assertRepainted(bitmap)
            assertEquals(generation, controller.environmentGeneration)
        }
    }

    @Test fun immersiveReadingRetainsTopSafetyWithOrWithoutTheHeader() {
        listOf(true, false).forEach { showHeader ->
            fun style(immersive: Boolean) = ReaderPageStyle.resolve(
                settings.copy(immersiveReading = immersive, showHeader = showHeader, pageMarginTop = 0f),
                palette, Density(1f), 320, 560, statusBarPx = 36f, navigationBarPx = 24f
            )
            val immersive = style(true)
            assertTrue(immersive.contentTop >= 36f)
            assertTrue(immersive.immersiveContentTop >= 36f)
            assertEquals(style(false).contentTop, immersive.contentTop, 0f)
        }
    }

    @Test fun neighborRefreshDoesNotConsumePendingCurrentPageInvalidation() {
        val bitmap = mount()
        mark(bitmap)
        holder.setAnnotations(listOf(ReaderAnnotationMark(1, 0, 0, 4, true)))
        holder.refresh(1)
        holder.ensureFresh()
        assertRepainted(bitmap)
    }

    @Test fun changedPageContentCannotReuseOldInk() {
        val bitmap = mount()
        mark(bitmap)
        controller.setChapters(listOf(ChapterMeta(0, "A new chapter title", 0)))
        holder.refresh(0)
        assertRepainted(bitmap)
    }

    @Test fun animationBackgroundAndBatteryInvalidateOnlyInk() {
        val bitmap = mount()
        val generation = controller.environmentGeneration
        mark(bitmap)
        assertFalse(apply(embedded = true))
        holder.ensureFresh()
        assertRepainted(bitmap)
        mark(bitmap)
        assertTrue(holder.updateBattery(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, 48).putExtra(BatteryManager.EXTRA_SCALE, 100)))
        holder.ensureFresh()
        assertRepainted(bitmap)
        assertEquals(generation, controller.environmentGeneration)
    }

    @Test fun detachDropsTurnCallbacksButReleaseOwnsBitmapLifetime() {
        val current = mount()
        val next = checkNotNull(holder.bitmapFor(PageTurnDirection.NEXT, front = false))
        val previous = checkNotNull(holder.bitmapFor(PageTurnDirection.PREVIOUS, front = true))
        var finished: ReaderTurnResult? = null
        holder.followRequest = ReaderPageTurnRequest(sequence = 1, direction = PageTurnDirection.NEXT,
            onFinished = { finished = it })
        holder.prepareTurn(PageTurnDirection.NEXT)
        holder.detach()
        assertNotNull(finished)
        assertNull(holder.followRequest)
        assertFalse(holder.hasPreparedTurn())
        listOf(current, next, previous).forEach { assertFalse(it.isRecycled) }
        holder.release()
        listOf(current, next, previous).forEach { assertTrue(it.isRecycled) }
        assertNull(holder.curBitmap)
        assertNull(holder.style)
        assertTrue(apply())
    }

    @Test fun scrollingReaderAlsoRetainsItsFontsAndPixelAnchorAcrossChat() {
        var resolves = 0
        fun attach(next: ReaderRenderStyleKey = key) = scrollHolder.applyStyle(next, createStyle = {
            resolves++
            ReaderPageStyle.resolve(settings, next.palette, Density(1f), 320, 560, 0f, 0f)
        }, onBackgroundReady = {})
        assertTrue(attach())
        val generation = controller.environmentGeneration
        scrollHolder.anchorY = 123.5f
        scrollHolder.detach()
        assertFalse(attach())
        assertEquals(123.5f, scrollHolder.anchorY, 0f)
        assertEquals(1, resolves)
        assertEquals(generation, controller.environmentGeneration)
        assertFalse(attach(key.copy(palette = palette.copy(background = Color.Black))))
        assertEquals(generation, controller.environmentGeneration)
        scrollHolder.release()
        assertTrue(attach())
        assertEquals(3, resolves)
    }

    @Test fun bothSurfacesDetectWhenTheOtherSurfaceReplacedTheirEnvironment() {
        mount()
        val style = checkNotNull(holder.style)
        assertTrue(scrollHolder.applyStyle(key, createStyle = { style }, onBackgroundReady = {}))
        assertTrue(apply())
        val generation = controller.environmentGeneration
        assertTrue(scrollHolder.applyStyle(key, createStyle = { error("reuse style") }, onBackgroundReady = {}))
        assertEquals(generation + 1, controller.environmentGeneration)
    }

    @Test fun cacheKeyIncludesThemePolicyAndSystemGeometry() {
        fun keyFor(next: ReaderSettings = settings, density: Density = Density(1f), status: Float = 0f) =
            readerRenderStyleKey(next, palette, density, IntSize(320, 560), status, 0f)
        assertNotEquals(key, keyFor(density = Density(1f, 1.2f)))
        assertNotEquals(key, keyFor(status = 24f))
        assertNotEquals(key, keyFor(settings.copy(activeCustomThemeId = 1L)))
        assertEquals(key, keyFor(settings.copy(pageTurnAnimation = com.mozhi.reader.core.datastore.PageTurnAnimation.NONE)))
    }

}
