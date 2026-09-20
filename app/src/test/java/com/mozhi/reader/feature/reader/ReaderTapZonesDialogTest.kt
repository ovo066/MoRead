package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.view.WindowInsets
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import io.mockk.mockk
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
class ReaderTapZonesDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun configuredActionOnlyCommitsOnDone() {
        var saved: ReaderTapZones? = null
        compose.setContent { MoReadTheme {
            ReaderTapZonesDialog(null, {}, { saved = it })
        } }
        dispatchVisibleSystemInsets()
        assertTrue(compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot.top < 24f)
        capture("tap-zones-portrait.png")
        compose.onNodeWithTag("tap-zone-0", useUnmergedTree = true).performClick()
        compose.onNodeWithText(ReaderTapAction.CONTENTS.label).performClick()
        assertNull(saved)
        compose.onNodeWithText("完成配置").performClick()
        assertEquals(ReaderTapAction.CONTENTS, saved!!.actions[0])
    }

    @Test fun readingAidsStartsAtDisplayTopEvenWhenStatusBarInsetsAreReported() {
        var dismissed = 0
        val model = mockk<EnglishLearningViewModel>(relaxed = true)
        compose.setContent { MoReadTheme {
            EnglishLearningDialog(0, ReaderSettings(), companionChatPalette(), { dismissed++ }, model)
        } }
        dispatchVisibleSystemInsets()
        assertTrue(compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot.top < 16f)
        compose.onNodeWithText("英文仿生阅读").assertIsDisplayed()
        capture("reading-aids-fullscreen.png")
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, dismissed)
    }

    @Test @Config(qualifiers = "w891dp-h411dp-mdpi")
    fun landscapeKeepsCompletionAndAllZonesReachable() {
        compose.setContent { MoReadTheme { ReaderTapZonesDialog(null, {}, {}) } }
        compose.onNodeWithText("完成配置").assertIsDisplayed()
        repeat(4) { compose.onNodeWithTag("tap-zone-grid", useUnmergedTree = true).performTouchInput { swipeUp() } }
        capture("tap-zones-landscape.png")
        compose.onNodeWithTag("tap-zone-12", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("选择操作").assertIsDisplayed()
    }

    private fun dispatchVisibleSystemInsets() {
        compose.runOnIdle {
            val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            root.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, 72, 0, 0))
                .setVisible(WindowInsets.Type.statusBars(), true).build())
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("build/reports/ui-qa/$name").apply { parentFile!!.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
