package com.mozhi.reader.feature.reader

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "mdpi")
class ReaderPageTouchTest {
    @get:Rule val compose = createComposeRule()
    private var bookmarks = 0
    private var turns = 0
    private var starts = 0
    private val filledDirections = mutableListOf<PageTurnDirection>()
    private var taps = 0
    private val progress = mutableListOf<Float>()
    private lateinit var driver: PageTurnDriver

    private fun mount(selection: SelectionGestureHooks? = null, image: ((Offset) -> Boolean)? = null) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            driver = remember { PageTurnDriver(scope, object : PageTurnDriver.Callbacks {
                override fun hasPage(direction: PageTurnDirection) = true
                override fun fillPage(direction: PageTurnDirection) { turns++; filledDirections += direction }
                override fun onBoundaryHit(direction: PageTurnDirection) = Unit
                override fun onTurnCommitted() = Unit
                override fun onTurnStarted(direction: PageTurnDirection) { starts++ }
            }).apply { mode = PageTurnDriver.Mode.INSTANT } }
            Box(Modifier.size(300.dp, 400.dp).testTag("page").readerPageTouch(
                enabled = true, driver = driver, selection = selection, onImageLongPress = image,
                onBookmarkPull = { progress += it }, onAddBookmark = { bookmarks++ }
            ) { _, _ -> taps++ })
        }
        compose.waitForIdle()
    }

    @Test
    fun downwardPullArmsThenAddsOnlyOnReleaseWithoutTurningPage() {
        mount()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(150f, 50f))
            moveTo(Offset(152f, 220f), delayMillis = 260)
        }
        compose.runOnIdle {
            assertEquals(0, bookmarks)
            assertEquals(1f, progress.last(), 0f)
            assertEquals(0, starts)
        }
        compose.onNodeWithTag("page").performTouchInput { up() }
        compose.runOnIdle {
            assertEquals(1, bookmarks)
            assertEquals(0, turns)
            assertEquals(0, taps)
            assertEquals(0f, progress.last(), 0f)
            assertFalse(driver.isRunning)
        }
    }

    @Test fun aFastLongDownwardFlingDoesNotAddABookmark() {
        mount()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(150f, 50f))
            moveTo(Offset(150f, 250f), delayMillis = 50)
            up()
        }
        compose.runOnIdle {
            assertEquals(0, bookmarks)
            assertEquals(0, turns)
            assertEquals(0, taps)
        }
    }

    @Test
    fun shortOrReversedPullDoesNotAddOrBecomeATap() {
        mount()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(150f, 50f))
            moveTo(Offset(150f, 95f), delayMillis = 100)
            up()
            down(Offset(150f, 50f))
            moveTo(Offset(150f, 190f), delayMillis = 100)
            moveTo(Offset(150f, 70f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(0, bookmarks)
            assertEquals(0, turns)
            assertEquals(0, taps)
            assertEquals(0f, progress.last(), 0f)
        }
    }

    @Test
    fun horizontalDragStillUsesThePageTurnDriver() {
        mount()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(250f, 150f))
            moveTo(Offset(200f, 151f), delayMillis = 40)
            moveTo(Offset(70f, 154f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, turns)
            assertEquals(0, bookmarks)
            assertEquals(0, taps)
        }
    }

    @Test
    fun rightToLeftPagesTurnForwardOnARightwardDrag() {
        mount()
        compose.runOnIdle { driver.mirrorProvider = { true } }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(50f, 150f))
            moveTo(Offset(100f, 151f), delayMillis = 40)
            moveTo(Offset(230f, 154f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            // 竖排书从右往左翻：向右拖是下一页，整次翻页在镜像空间里进行。
            assertEquals(listOf(PageTurnDirection.NEXT), filledDirections)
            assertTrue(driver.mirrored)
        }
        compose.runOnIdle { driver.mirrorProvider = { false } }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(50f, 150f))
            moveTo(Offset(100f, 151f), delayMillis = 40)
            moveTo(Offset(230f, 154f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(PageTurnDirection.NEXT, PageTurnDirection.PREVIOUS), filledDirections)
            assertFalse(driver.mirrored)
        }
    }

    @Test fun modernCurlKeepsTheDownAnchorAndLandsWithoutProjectingTheFingerOffScreen() {
        mount()
        compose.runOnIdle { driver.mode = PageTurnDriver.Mode.MODERN_CURL; driver.setViewport(300f, 400f) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(290f, 200f))
            moveTo(Offset(260f, 210f), delayMillis = 40)
            moveTo(Offset(150f, 270f), delayMillis = 70)
        }
        compose.runOnIdle {
            assertEquals(270f, driver.touchY, .01f)
            assertEquals(200f, driver.startY, .01f)
            assertEquals(290f, driver.startX, .01f)
            assertEquals(PageTurnDirection.NEXT, driver.direction)
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertTrue(driver.touchY in 200f..270f)
            assertTrue(driver.touchX < 150f)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, turns); assertFalse(driver.isRunning) }
    }

    @Test fun modernShortFastFlickCommitsButHoldingTheSameDistanceCancels() {
        mount()
        compose.runOnIdle { driver.mode = PageTurnDriver.Mode.MODERN_CURL; driver.setViewport(300f, 400f) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(270f, 200f))
            moveTo(Offset(210f, 220f), delayMillis = 40)
            up()
        }
        compose.runOnIdle { assertEquals(1, turns) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(270f, 200f))
            moveTo(Offset(210f, 220f), delayMillis = 40)
            advanceEventTime(180)
            up()
        }
        compose.runOnIdle { assertEquals(1, turns); assertFalse(driver.isRunning) }
    }

    @Test fun modernReverseFlickCancelsAndAReturningCurlCanBeGrabbedContinuously() {
        mount()
        compose.runOnIdle {
            driver.mode = PageTurnDriver.Mode.MODERN_CURL
            driver.setViewport(300f, 400f)
            driver.onDown(290f, 200f, 0)
            driver.onMove(30f, 220f, 8f, 200)
            driver.onMove(120f, 215f, 8f, 230)
            driver.onUp(231)
        }
        compose.runOnIdle { assertEquals(0, turns) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            driver.onDown(290f, 200f, 1000)
            driver.onMove(200f, 230f, 8f, 1040)
            driver.onUp(1220)
        }
        compose.mainClock.advanceTimeBy(64)
        compose.runOnIdle {
            assertTrue(driver.isAnimating)
            val x = driver.touchX
            val y = driver.touchY
            driver.onDown(250f, 200f, 1300)
            assertEquals(x, driver.touchX, 0f)
            assertEquals(y, driver.touchY, 0f)
            assertEquals(0, turns)
            driver.onMove(240f, 205f, 8f, 1320)
            assertEquals(x - 10f, driver.touchX, .001f)
            assertEquals(y + 5f, driver.touchY, .001f)
            driver.onMove(160f, 205f, 8f, 1340)
            driver.onUp(1341)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, turns); assertFalse(driver.isRunning); assertEquals(2, starts) }
    }

    @Test fun modernConsecutiveFlicksEachTurnAPageWhileThePreviousAnimationIsStillRunning() {
        mount()
        compose.runOnIdle { driver.mode = PageTurnDriver.Mode.MODERN_CURL }
        compose.mainClock.autoAdvance = false
        val directions = listOf(PageTurnDirection.NEXT, PageTurnDirection.NEXT, PageTurnDirection.PREVIOUS, PageTurnDirection.PREVIOUS)
        for ((index, direction) in directions.withIndex()) {
            val x = if (direction == PageTurnDirection.NEXT) 270f else 30f
            val dx = if (direction == PageTurnDirection.NEXT) -60f else 60f
            compose.onNodeWithTag("page").performTouchInput {
                down(Offset(x, 200f))
                moveTo(Offset(x + dx, 220f), delayMillis = 40)
                up()
            }
            compose.mainClock.advanceTimeBy(64)
            compose.runOnIdle {
                assertTrue(driver.isAnimating)
                assertEquals(index, turns)
                assertEquals(index + 1, starts)
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            assertEquals(directions, filledDirections)
            assertEquals(4, turns)
            assertFalse(driver.isRunning)
            assertEquals(0, bookmarks)
            assertEquals(0, taps)
        }
    }

    @Test
    fun additionalPointerCancelsAnArmedPull() {
        mount()
        compose.onNodeWithTag("page").performTouchInput {
            down(0, Offset(150f, 50f))
            moveTo(0, Offset(150f, 190f), delayMillis = 100)
            down(1, Offset(190f, 190f))
            up(1)
            up(0)
        }
        assertCancelled()
    }

    @Test
    fun platformCancellationCannotCommitAnArmedPull() {
        mount()
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(150f, 50f))
            moveTo(Offset(150f, 190f), delayMillis = 100)
            cancel()
        }
        assertCancelled()
    }

    @Test
    fun existingSelectionHandleHasPriorityOverBookmarkPull() {
        val selection = Selection(active = true)
        mount(selection)
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(150f, 50f))
            moveTo(Offset(150f, 190f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertTrue(selection.drags > 0)
            assertEquals(1, selection.ends)
            assertEquals(0, bookmarks)
            assertEquals(0, turns)
            assertEquals(0, taps)
        }
    }

    @Test
    fun longPressSelectionDoesNotBecomeABookmarkWhileExtendingDownward() {
        val selection = Selection(active = false)
        mount(selection)
        compose.onNodeWithTag("page").performTouchInput { down(Offset(150f, 50f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("page").performTouchInput {
            moveTo(Offset(150f, 190f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, selection.begins)
            assertTrue(selection.drags > 0)
            assertEquals(0, bookmarks)
            assertEquals(0, turns)
            assertEquals(0, taps)
        }
    }

    @Test fun imageLongPressConsumesReleaseWithoutSelectingOrTurning() {
        val selection = Selection(active = false)
        var images = 0
        mount(selection, image = { images++; true })
        compose.onNodeWithTag("page").performTouchInput { down(Offset(150f, 150f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("page").performTouchInput {
            moveTo(Offset(20f, 150f), delayMillis = 100)
            up()
        }
        compose.runOnIdle {
            assertEquals(1, images)
            assertEquals(0, selection.begins)
            assertEquals(0, turns); assertEquals(0, taps); assertEquals(0, bookmarks)
            assertFalse(driver.isRunning)
        }
    }

    @Test fun missingImageFallsBackToOrdinaryTextSelection() {
        val selection = Selection(active = false)
        mount(selection, image = { false })
        compose.onNodeWithTag("page").performTouchInput { down(Offset(150f, 150f)) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("page").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, selection.begins); assertEquals(0, turns); assertEquals(0, taps) }
    }

    private fun assertCancelled() = compose.runOnIdle {
        assertEquals(0, bookmarks)
        assertEquals(0, turns)
        assertEquals(0, taps)
        assertEquals(0f, progress.last(), 0f)
        assertFalse(driver.isRunning)
    }

    private class Selection(var active: Boolean) : SelectionGestureHooks {
        var begins = 0
        var drags = 0
        var ends = 0
        override val isActive get() = active
        override fun begin(position: Offset): Boolean { begins++; active = true; return true }
        override fun grabHandle(position: Offset, radiusPx: Float) = active
        override fun drag(position: Offset) { drags++ }
        override fun end() { ends++ }
        override fun clear() { active = false }
    }
}
