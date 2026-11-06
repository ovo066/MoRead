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

    @Test fun modernCurlEdgeEasesUnderTheFingerInsteadOfJumping() {
        mount()
        compose.runOnIdle { driver.mode = PageTurnDriver.Mode.MODERN_CURL; driver.setViewport(300f, 400f) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(200f, 200f))
            moveTo(Offset(180f, 205f), delayMillis = 16)
        }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle {
            assertEquals(PageTurnDirection.NEXT, driver.direction)
            // A press in mid-page starts from the outer edge and travels toward the finger.
            assertTrue(driver.curlX > 200f && driver.curlX < 300f)
        }
        compose.onNodeWithTag("page").performTouchInput { moveTo(Offset(150f, 260f), delayMillis = 30) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle {
            assertEquals(150f, driver.curlX, .5f)
            assertEquals(260f, driver.curlY, .5f)
            assertEquals(200f, driver.startX, .01f)
            assertEquals(200f, driver.startY, .01f)
        }
        compose.onNodeWithTag("page").performTouchInput {
            advanceEventTime(180)
            up()
        }
        compose.mainClock.advanceTimeBy(600)
        // Held at a sixth of the width from the press: the sheet lies back down.
        compose.runOnIdle { assertEquals(0, turns); assertFalse(driver.isRunning) }
    }

    @Test fun modernAQuarterPullOrAnyForwardFlickTurnsWhileAFlickBackCancels() {
        mount()
        compose.runOnIdle { driver.mode = PageTurnDriver.Mode.MODERN_CURL; driver.setViewport(300f, 400f) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(280f, 200f))
            moveTo(Offset(240f, 202f), delayMillis = 40)
            moveTo(Offset(195f, 204f), delayMillis = 60)
            advanceEventTime(180)
            up()
        }
        compose.runOnIdle { assertEquals(1, turns); assertFalse(driver.isRunning) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(280f, 200f))
            moveTo(Offset(230f, 202f), delayMillis = 400)
            advanceEventTime(180)
            up()
        }
        compose.runOnIdle { assertEquals(1, turns); assertFalse(driver.isRunning) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(280f, 200f))
            moveTo(Offset(255f, 201f), delayMillis = 60)
            moveTo(Offset(230f, 202f), delayMillis = 60)
            up()
        }
        compose.runOnIdle { assertEquals(2, turns); assertFalse(driver.isRunning) }
        compose.onNodeWithTag("page").performTouchInput {
            down(Offset(280f, 200f))
            moveTo(Offset(150f, 202f), delayMillis = 100)
            moveTo(Offset(60f, 204f), delayMillis = 100)
            moveTo(Offset(90f, 204f), delayMillis = 20)
            moveTo(Offset(130f, 204f), delayMillis = 20)
            up()
        }
        compose.runOnIdle { assertEquals(2, turns); assertFalse(driver.isRunning) }
    }

    @Test fun modernReturningCurlCanBeGrabbedWithoutAJump() {
        mount()
        compose.runOnIdle {
            driver.mode = PageTurnDriver.Mode.MODERN_CURL
            driver.setViewport(300f, 400f)
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            driver.onDown(290f, 200f, 1000)
            driver.onMove(240f, 230f, 8f, 1040)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(240f, driver.curlX, .5f)
            driver.onUp(1220)
        }
        compose.mainClock.advanceTimeBy(48)
        compose.runOnIdle {
            assertTrue(driver.isAnimating)
            val x = driver.curlX
            val y = driver.curlY
            assertTrue(x > 240f && x < 300f)
            driver.onDown(250f, 200f, 1300)
            assertEquals(x, driver.curlX, 0f)
            assertEquals(y, driver.curlY, 0f)
            assertFalse(driver.isAnimating)
            assertEquals(0, turns)
            driver.onMove(200f, 205f, 8f, 1320)
            driver.onMove(150f, 205f, 8f, 1340)
            driver.onUp(1341)
        }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertEquals(1, turns); assertFalse(driver.isRunning); assertEquals(1, starts) }
    }

    @Test fun modernTapTurnSweepsTheSheetAcrossAndLandsOnce() {
        mount()
        compose.runOnIdle { driver.mode = PageTurnDriver.Mode.MODERN_CURL; driver.setViewport(300f, 400f) }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { driver.turnByTap(PageTurnDirection.PREVIOUS) }
        compose.mainClock.advanceTimeBy(16)
        compose.runOnIdle {
            // The previous sheet starts turned away to the left and comes back across the page.
            assertTrue(driver.curlX < -250f)
            assertTrue(driver.isAnimating)
        }
        compose.mainClock.advanceTimeBy(150)
        compose.runOnIdle { assertTrue(driver.curlX > -250f && driver.curlX < 300f); assertEquals(0, turns) }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {
            assertEquals(listOf(PageTurnDirection.PREVIOUS), filledDirections)
            assertFalse(driver.isRunning)
        }
        // Going forward, the turn lands as soon as the sheet has left the page, before the curve ends.
        compose.runOnIdle { driver.turnByTap(PageTurnDirection.NEXT) }
        compose.mainClock.advanceTimeBy(304)
        compose.runOnIdle {
            assertEquals(listOf(PageTurnDirection.PREVIOUS, PageTurnDirection.NEXT), filledDirections)
            assertFalse(driver.isRunning)
        }
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
