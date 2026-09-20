package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.feature.reader.engine.ReaderPageImage
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.*
import androidx.compose.ui.graphics.toArgb
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderEpubImageDialogTest {
    @get:Rule val compose = createComposeRule()
    private var located = 0
    private var dismissed = 0
    private fun show(imagePath: String? = null, appearance: () -> AppearanceSettings = { AppearanceSettings() }) {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "dialog-fixture.png")
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(225, 231, 240))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 100, 165) }
            drawCircle(300f, 150f, 100f, paint)
            paint.color = Color.WHITE; paint.textSize = 48f
            drawText("EPUB", 230f, 165f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            MoReadTheme(appearance()) {
                if (open) ReaderEpubImageDialog(ReaderPageImage(imagePath ?: file.path, 3, 120, "测试书内图片"),
                    onDismiss = { dismissed++; open = false }, onLocate = { located++; open = false })
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("测试书内图片").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("保存").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
        compose.waitForIdle()
    }
    private fun image() = compose.onNodeWithContentDescription("测试书内图片")
    private fun state() = image().fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test fun imageActionsFollowLiveApplicationAccentAndDarkMode() {
        val appearance = mutableStateOf(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AMBER))
        show(appearance = { appearance.value })
        capture("epub-image-editor-orange.png", AccentPreset.AMBER.light.toArgb())
        compose.runOnIdle { appearance.value = AppearanceSettings(themeMode = ThemeMode.LIGHT, colorScheme = ColorSchemePreset.ROSE_DUST) }
        capture("epub-image-editor-rose.png", MoReadSchemes.side(ColorSchemePreset.ROSE_DUST, false).colors.primary.toArgb())
        compose.runOnIdle { appearance.value = AppearanceSettings(themeMode = ThemeMode.DARK, colorScheme = ColorSchemePreset.HAZE_BLUE) }
        capture("epub-image-editor-blue-dark.png", MoReadSchemes.side(ColorSchemePreset.HAZE_BLUE, true).colors.primary.toArgb())
    }

    @Test fun realDialogSupportsZoomPanRotationResetAndLocate() {
        show()
        compose.onNodeWithTag("epub-image-editor").assertIsDisplayed()
        assertTrue(state().contains("缩放100%"))
        image().performTouchInput { doubleClick(center) }
        assertTrue(state().contains("缩放250%"))
        image().performTouchInput {
            down(0, center + Offset(-40f, 0f)); down(1, center + Offset(40f, 0f))
            moveTo(0, center + Offset(-60f, 25f), delayMillis = 100)
            moveTo(1, center + Offset(80f, 25f), delayMillis = 100)
            up(1); up(0)
        }
        assertFalse(state().contains("缩放100%"))
        compose.onNodeWithText("旋转").performClick()
        assertTrue(state().contains("旋转90度"))
        assertTrue(state().contains("缩放100%"))
        capture("epub-image-editor-rotated.png")
        compose.onNodeWithText("重置").performClick()
        assertTrue(state().contains("旋转0度"))
        compose.waitUntil(15_000) { compose.onAllNodesWithText("保存").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
        capture("epub-image-editor.png")
        compose.onNodeWithText("定位原文").performClick()
        assertEquals(1, located); assertEquals(0, dismissed)
        compose.onNodeWithTag("epub-image-editor").assertDoesNotExist()
    }
    @Test @Config(qualifiers = "w891dp-h411dp-mdpi")
    fun landscapeKeepsControlsVisibleAndBackDoesNotLocate() {
        show()
        compose.onNodeWithText("定位原文").assertIsDisplayed()
        compose.onNodeWithText("旋转").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("epub-image-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height > 80f)
        capture("epub-image-editor-landscape.png")
        compose.onNodeWithContentDescription("返回阅读").performClick()
        assertEquals(1, dismissed); assertEquals(0, located)
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun narrowScreenKeepsTheFloatingToolbarInsideTheDialog() {
        show()
        compose.onNodeWithText("定位原文").assertIsDisplayed()
        compose.onNodeWithText("旋转").assertIsDisplayed()
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("重置").assertIsDisplayed()
        val root = compose.onNodeWithTag("epub-image-editor").fetchSemanticsNode().boundsInRoot
        val toolbar = compose.onNodeWithTag("epub-image-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue(toolbar.left > root.left && toolbar.right < root.right)
        assertTrue(toolbar.bottom <= root.bottom)
        capture("epub-image-editor-narrow.png")
    }
    @Test fun saveMenuContainsDestinationsWithoutInstructionCopy() {
        show()
        compose.onAllNodesWithText("双指缩放", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("PNG 副本", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("epub-image-toolbar").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("保存到相册").assertIsDisplayed()
        compose.onNodeWithText("导出文件").assertIsDisplayed()
    }
    @Test fun optionalLocalEpubImagePreview() {
        val book = System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        val asset = System.getenv("MOREAD_IMAGE_QA_ASSET").orEmpty()
        org.junit.Assume.assumeTrue(book.isNotBlank() && asset.isNotBlank() && File(book).isFile)
        show(com.mozhi.reader.core.library.EpubArchiveAsset(File(book), asset).encode())
        capture("epub-image-editor-real.png")
    }
    private fun capture(name: String, expectedAccent: Int? = null) {
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("build/reports/ui-qa/$name").apply { parentFile?.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (expectedAccent != null) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("保存按钮必须使用当前主题主色", pixels.count { it == expectedAccent } > 1000)
            }
            bitmap.recycle()
        }
    }
}
