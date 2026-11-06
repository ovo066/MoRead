package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.ui.components.MoReadBottomSheet
import com.mozhi.reader.ui.theme.MoReadTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalMaterial3Api::class)
class AnnotationDiscussionImeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var view: View
    private var dismissed = 0
    private var sent: String? = null
    private val quote = "海风吹过灯塔，光线越过雨幕，远处的船终于找到了回家的方向。".repeat(8)
    private val annotation = AnnotationEntity(id = 7, bookId = 1, chapterIndex = 0,
        startCharOffset = 0, endCharOffset = quote.length, selectedText = quote, createdAt = 1)
    private val persona = PersonaEntity(id = 5, name = "知秋", personality = "", isRoleplay = true, createdAt = 0)

    private fun show(dark: Boolean) {
        val replies = (0 until 40).map { AnnotationReplyEntity(id = it + 1L, annotationId = 7,
            contentMarkdown = "第 $it 条讨论：雨停之后，灯塔依然亮着，船上的人看见了回家的方向。", createdAt = 1) }
        compose.setContent { MoReadTheme {
            val palette = readerPalette(ReaderSettings(theme = if (dark) ReaderTheme.DARK else ReaderTheme.PAPER), dark)
            MoReadBottomSheet(onDismissRequest = { dismissed++ }, containerColor = palette.glassStrong,
                contentColor = palette.onBackground, scrimColor = palette.scrim) {
                val contentView = LocalView.current
                SideEffect { view = contentView }
                AnnotationDiscussionSheet(listOf(annotation), replies, null, null, listOf(persona), emptyList(), palette, {},
                    { _, text, _ -> sent = text }, { _, _, _ -> }, {}, {}, {}, { dismissed++ })
            }
        } }
        insets(0)
    }

    private fun insets(keyboard: Int) {
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 32))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 32))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsetsCompat.Type.ime(), keyboard > 0).build())
        }
        compose.waitForIdle()
    }

    private fun assertComposerVisible(keyboard: Int) {
        val field = compose.onNode(hasSetTextAction()).assertIsDisplayed().getUnclippedBoundsInRoot()
        val send = compose.onNodeWithContentDescription("发送").assertIsDisplayed().getUnclippedBoundsInRoot()
        val visibleBottom = view.rootView.height - maxOf(keyboard, 32)
        assertTrue("输入框被键盘遮挡：$field，键盘顶部=$visibleBottom", field.bottom.value <= visibleBottom + 1f)
        assertTrue("发送键被键盘遮挡：$send，键盘顶部=$visibleBottom", send.bottom.value <= visibleBottom + 1f)
        assertTrue("输入框必须保留实际高度：$field", (field.bottom - field.top).value > 0f)
    }

    @Test fun expandedQuoteAndKeyboardAnimationKeepInputAndSendVisible() {
        exerciseKeyboard(dark = false, keyboardHeight = 460)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w800dp-h1000dp-mdpi")
    fun tabletDarkDiscussionKeepsComposerVisibleWithKeyboard() {
        exerciseKeyboard(dark = true, keyboardHeight = 500)
    }

    private fun exerciseKeyboard(dark: Boolean, keyboardHeight: Int) {
        show(dark)
        // Scroll to the excerpt first; it shares the message viewport in the corrected layout.
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("展开").performClick()
        compose.onNode(hasSetTextAction()).performClick()
        val originalBottom = compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot.bottom
        for (height in listOf(80, 160, 300, keyboardHeight)) {
            insets(height)
            assertComposerVisible(height)
        }
        compose.onNode(hasSetTextAction()).performTextInput("这段的灯塔意象很动人。")
        capture(if (dark) "annotation-ime-tablet-dark.png" else "annotation-ime-phone-light.png")
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals("这段的灯塔意象很动人。", sent)
        for (height in listOf(300, 160, 0)) {
            insets(height)
            assertComposerVisible(height)
        }
        assertEquals(originalBottom, compose.onNodeWithContentDescription("发送").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        assertEquals(0, dismissed)
    }

    private fun capture(name: String) = compose.runOnIdle {
        val root = view.rootView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File("build/reports/ui-qa/$name").apply { parentFile?.mkdirs() }.outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
