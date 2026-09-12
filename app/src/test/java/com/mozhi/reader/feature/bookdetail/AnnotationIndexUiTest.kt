package com.mozhi.reader.feature.bookdetail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.ui.components.FontPreviewChoice
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
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnnotationIndexUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var rootView: View
    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { rootView = view.rootView }
            MoReadTheme { Column(Modifier.fillMaxSize().padding(20.dp)) { content() } }
        }
    }
    private fun annotation(id: Long, persona: Long? = null) = AnnotationEntity(id = id, bookId = 7,
        personaId = persona, chapterIndex = 0, startCharOffset = id.toInt() * 10, endCharOffset = id.toInt() * 10 + 4,
        selectedText = "原文片段 $id", note = "想法 $id", createdAt = id)

    @Test fun userAndAiTabsFilterIndependentlyAndDeletionRequiresConfirmation() {
        var deleted: Long? = null
        show { AnnotationIndex(listOf(annotation(1), annotation(2, 9), annotation(3, 99)), mapOf(9L to "伴读一", 100L to "不可见角色"), { deleted = it }) }
        compose.onNodeWithText("我的 1").performClick()
        compose.onNodeWithText("我的划线").assertIsDisplayed()
        compose.onNodeWithText("AI · 伴读一").assertDoesNotExist()
        compose.onNodeWithContentDescription("删除批注").performClick()
        compose.onNodeWithText("删除我的划线？").assertIsDisplayed()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("AI 2").performClick()
        compose.onNodeWithText("AI · 伴读一").assertIsDisplayed()
        compose.onNodeWithText("AI · 已删除角色 #99").assertIsDisplayed()
        compose.onNodeWithText("我的划线").assertDoesNotExist()
        compose.onNodeWithText("不可见角色").assertDoesNotExist()
        capture("annotation-index-ai.png")
        compose.onNodeWithText("伴读一").performClick()
        compose.onNodeWithText("AI · 已删除角色 #99").assertDoesNotExist()
    }

    @Test fun fontChoiceRendersTheSampleInTheCandidateFontAndDoesNotSelectOnPreview() {
        var selected by mutableStateOf(false)
        show { FontPreviewChoice(ReaderFontAsset("demo", "等宽字体预览", "/unused.ttf"), selected,
            onClick = { selected = true }, fontFamily = FontFamily.Monospace) }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("阅读 Aa 123", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(FontFamily.Monospace, layouts.single().layoutInput.style.fontFamily)
        assertFalse(selected)
        compose.onNodeWithText("等宽字体预览").performClick()
        compose.runOnIdle { assertTrue(selected) }
        capture("font-selector-preview.png")
    }

    private fun capture(name: String) = compose.runOnIdle {
        val file = File("build/reports/ui-qa/$name").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        rootView.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
