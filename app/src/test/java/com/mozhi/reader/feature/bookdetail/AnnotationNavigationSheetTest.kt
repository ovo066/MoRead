package com.mozhi.reader.feature.bookdetail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.core.database.entity.AnnotationEntity
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
class AnnotationNavigationSheetTest {
    @get:Rule val compose = createComposeRule()
    private val annotations = (1..50).map { id -> AnnotationEntity(id = id.toLong(), bookId = 7,
        personaId = if (id % 2 == 0) 3 else null, chapterIndex = id, startCharOffset = 40, endCharOffset = 54,
        selectedText = "雨后的灯塔，原文片段 $id", note = "想法 $id：这段描写让人想再慢慢读一次。", textAnchorJson = "anchor-$id", createdAt = id.toLong()) }
    private var hidden by mutableStateOf(0)

    private fun show(onLocate: (AnnotationEntity) -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent { MoReadTheme {
            AnnotationIndexSheet(annotations, mapOf(3L to "知秋"), hidden, {}, onDismiss, onLocate)
        } }
    }
    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun scroll() = compose.onNodeWithTag("annotation-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun realParentStaysAtWindowBottomDuringBoundaryFlingsAndTabSwitches() {
        var dismissed = 0
        show(onDismiss = { dismissed++ })
        val sheetTop = top("navigation-viewport")
        val tabsTop = top("annotation-tabs")
        listOf("全部 50" to 49, "我的 25" to 24, "AI 25" to 24).forEach { (tab, last) ->
            compose.onNodeWithText(tab).performClick()
            val list = compose.onNodeWithTag("annotation-list")
            list.performScrollToIndex(0)
            repeat(3) { list.performTouchInput { swipeDown(durationMillis = 120) } }
            list.performScrollToIndex(last)
            repeat(3) { list.performTouchInput { swipeUp(durationMillis = 120) } }
            list.performTouchInput { swipeDown(durationMillis = 150) }
            list.performTouchInput { swipeUp(durationMillis = 180) }
            assertEquals(sheetTop, top("navigation-viewport"), 0.5f)
            assertEquals(tabsTop, top("annotation-tabs"), 0.5f)
            val viewport = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
            val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            assertEquals(root.height.toFloat(), viewport.bottom, 1f)
        }
        assertEquals(0, dismissed)
        compose.onNodeWithContentDescription("关闭划线与批注").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun independentTabAnchorsSurviveProgressRefreshAndReturn() {
        show()
        compose.onNodeWithTag("annotation-list").performScrollToIndex(15)
        val all = scroll()
        val tabsTop = top("annotation-tabs")
        compose.runOnIdle { hidden = 8 }
        assertEquals(all, scroll(), 0.001f)
        compose.onNodeWithText("我的 25").performClick()
        compose.onNodeWithTag("annotation-list").performScrollToIndex(9)
        val mine = scroll()
        compose.onNodeWithText("AI 25").performClick()
        compose.onNodeWithTag("annotation-list").performScrollToIndex(11)
        val ai = scroll()
        compose.runOnIdle { hidden = 19 }
        assertEquals(ai, scroll(), 0.001f)
        compose.onNodeWithText("全部 50").performClick()
        assertEquals(all, scroll(), 0.001f)
        compose.onNodeWithText("我的 25").performClick()
        assertEquals(mine, scroll(), 0.001f)
        compose.onNodeWithText("AI 25").performClick()
        assertEquals(ai, scroll(), 0.001f)
        assertEquals(tabsTop, top("annotation-tabs"), 0.5f)
        capture()
    }

    @Test fun tappingACommentReturnsTheOriginalAnchorWithoutDeletingIt() {
        var located: AnnotationEntity? = null
        show(onLocate = { located = it })
        compose.onAllNodesWithText("跳到原文").onFirst().performClick()
        assertEquals(annotations.last(), located)
        assertEquals("anchor-50", located?.textAnchorJson)
        assertEquals(40, located?.startCharOffset)
    }

    private fun capture() = compose.runOnIdle {
        val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/ui-qa/annotation-navigation-sheet.png").apply { parentFile.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
