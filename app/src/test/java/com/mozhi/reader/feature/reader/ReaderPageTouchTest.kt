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
    private var taps = 0
    private val progress = mutableListOf<Float>()
    private lateinit var driver: PageTurnDriver

    private fun mount(selection: SelectionGestureHooks? = null) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            driver = remember { PageTurnDriver(scope, object : PageTurnDriver.Callbacks {
                override fun hasPage(direction: PageTurnDirection) = true
                override fun fillPage(direction: PageTurnDirection) { turns++ }
                override fun onBoundaryHit(direction: PageTurnDirection) = Unit
                override fun onTurnCommitted() = Unit
                override fun onTurnStarted(direction: PageTurnDirection) { starts++ }
            }).apply { mode = PageTurnDriver.Mode.INSTANT } }
            Box(Modifier.size(300.dp, 400.dp).testTag("page").readerPageTouch(
                enabled = true, driver = driver, selection = selection,
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
