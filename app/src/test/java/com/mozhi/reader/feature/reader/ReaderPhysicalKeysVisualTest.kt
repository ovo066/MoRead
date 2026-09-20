package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPhysicalKeysVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualDialogWindowRecordsVolumeKeysAndConfirmsConflictsOnlyAfterRelease() {
        val live = mutableStateOf(ReaderSettings(volumeKeysPageTurn = true))
        compose.setContent { MoReadTheme {
            ReaderPhysicalKeysSheet(live.value, readerPalette(ReaderTheme.PAPER, false), {},
                { live.value = live.value.copy(volumeKeysPageTurn = it) }, { live.value = live.value.copy(physicalKeyBindings = it) })
        } }
        capture("physical-keys.png")
        compose.onNodeWithTag("record-PREVIOUS_PAGE").performClick()
        compose.waitForIdle()
        val captureWindow = ShadowDialog.getLatestDialog().window!!
        val callback = captureWindow.callback
        sendKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN)
        compose.onNodeWithTag("save-recorded-key").assertIsNotEnabled()
        compose.onNodeWithTag("key-binding-conflict").assertTextEquals("此按键当前用于下一页，保存后改为上一页。")
        sendKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_DOWN, repeat = 1)
        compose.onNodeWithTag("save-recorded-key").assertIsNotEnabled()
        sendKey(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP)
        capture("record-key-conflict.png")
        compose.onNodeWithTag("save-recorded-key").assertIsEnabled().performClick()
        compose.onNodeWithTag("save-recorded-key").assertDoesNotExist()
        compose.waitUntil(timeoutMillis = 5_000) { captureWindow.callback !== callback }
        compose.runOnIdle {
            assertNotSame(callback, captureWindow.callback)
            assertEquals(2, live.value.physicalKeyBindings.size)
            assertTrue(live.value.physicalKeyBindings.all { it.action == ReaderKeyAction.PREVIOUS_PAGE })
        }
        compose.onNodeWithTag("physical-keys-list").performScrollToNode(hasTestTag("record-NEXT_PAGE"))
        compose.onNodeWithTag("record-NEXT_PAGE").performClick()
        sendKey(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_DOWN)
        sendKey(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_UP)
        compose.onNodeWithTag("save-recorded-key").performClick()
        assertTrue(live.value.physicalKeyBindings.any { it.key.keyCode == KeyEvent.KEYCODE_PAGE_DOWN && it.action == ReaderKeyAction.NEXT_PAGE })
        compose.onNodeWithTag("physical-keys-list").performScrollToIndex(0)
        compose.onNodeWithTag("volume-preset-true").performClick()
        assertEquals(3, live.value.physicalKeyBindings.size)
        assertEquals(ReaderKeyAction.NEXT_PAGE, live.value.physicalKeyBindings.single { it.key.keyCode == KeyEvent.KEYCODE_VOLUME_UP }.action)
        compose.onNodeWithTag("physical-keys-enabled").performClick()
        assertFalse(live.value.volumeKeysPageTurn)
        assertEquals(3, live.value.physicalKeyBindings.size)
    }

    @Test fun scrollingInTheRealSheetKeepsViewportAndAnchorStableDuringSettingsUpdates() {
        val bindings = (131..178).mapIndexed { i, code -> ReaderKeyBinding(ReaderPhysicalKey(code),
            if (i < 24) ReaderKeyAction.PREVIOUS_PAGE else ReaderKeyAction.NEXT_PAGE) }
        val live = mutableStateOf(ReaderSettings(volumeKeysPageTurn = true, physicalKeyBindings = bindings))
        compose.setContent { MoReadTheme {
            ReaderPhysicalKeysSheet(live.value, readerPalette(ReaderTheme.PAPER, false), {},
                { live.value = live.value.copy(volumeKeysPageTurn = it) }, { live.value = live.value.copy(physicalKeyBindings = it) })
        } }
        val viewport = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(ShadowDialog.getLatestDialog().window!!.decorView.height.toFloat(), viewport.bottom, 1f)
        compose.onNodeWithTag("physical-keys-list").performScrollToIndex(30)
        fun anchor() = compose.onNodeWithTag("physical-keys-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = anchor()
        compose.runOnIdle { live.value = live.value.copy(volumeKeysPageTurn = false) }
        assertEquals(before, anchor(), .01f)
        repeat(3) {
            compose.onNodeWithTag("physical-keys-list").performTouchInput { swipeUp(durationMillis = 80) }
            compose.onNodeWithTag("physical-keys-list").performTouchInput { swipeDown(durationMillis = 80) }
        }
        assertEquals(viewport, compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("physical-keys-list").performScrollToIndex(0)
        compose.onNodeWithTag("record-PREVIOUS_PAGE").performClick()
        sendKey(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_DOWN)
        sendKey(KeyEvent.KEYCODE_SPACE, KeyEvent.ACTION_UP)
        compose.onNodeWithText("取消").performClick()
        assertEquals(bindings, live.value.physicalKeyBindings)
        assertEquals(viewport, compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun compactCaptureKeepsSaveAndCancelReachableForAKeyboardChord() {
        var saved: ReaderPhysicalKey? = null
        compose.setContent { MoReadTheme {
            ReaderPhysicalKeyCaptureDialog(ReaderKeyAction.NEXT_PAGE, ReaderKeyBindings.DEFAULT, {}, { saved = it })
        } }
        sendKey(KeyEvent.KEYCODE_K, KeyEvent.ACTION_DOWN, meta = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON)
        sendKey(KeyEvent.KEYCODE_K, KeyEvent.ACTION_UP)
        compose.waitForIdle()
        capture("record-key-compact.png")
        compose.onNodeWithText("Ctrl + Shift + K").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed()
        capture("record-key-compact.png")
        compose.onNodeWithTag("save-recorded-key").assertIsDisplayed().performClick()
        assertEquals(ReaderPhysicalKey(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON), saved)
    }

    private fun sendKey(key: Int, action: Int, repeat: Int = 0, meta: Int = 0) = compose.runOnIdle {
        assertTrue(ShadowDialog.getLatestDialog().window!!.callback.dispatchKeyEvent(KeyEvent(100, 100 + repeat.toLong(), action, key, repeat, meta)))
    }

    private fun capture(name: String) = compose.runOnIdle {
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/reader-key-bindings/$name").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
