package com.mozhi.reader.feature.review

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReviewBookSelectorVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private var dismissed = 0
    private var result: Set<Long>? = null
    private var state by mutableStateOf(ReadingReviewState(entries = (1L..40L).map { id ->
        ReviewEntry(reviewTestBook(id, "%02d · 书页里的故事".format(id)), "我的", annotation = reviewTestAnnotation(id, id))
    }, loading = false))

    private fun show() {
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AZURE)) {
                ReviewBookSelector(state, emptySet(), { dismissed++ }, { result = it })
            }
        }
        compose.waitForIdle()
    }

    @Test fun multipleBooksRemainSelectedWhileSearchingAndApplyTogether() {
        show()
        compose.onNodeWithTag("review-books-all").performClick()
        compose.onNodeWithTag("review-book-select-1").performClick()
        compose.onNodeWithTag("review-book-select-3").performClick()
        compose.onNodeWithTag("review-books-search").performTextInput("09")
        compose.onNodeWithTag("review-book-select-9").performClick()
        compose.onNodeWithTag("review-books-search").performTextClearance()
        compose.onNodeWithText("已选 3 / 40").assertIsDisplayed()
        capture("review-book-selector.png")
        compose.onNodeWithText("查看这 3 本书").performClick()
        assertEquals(setOf(1L, 3L, 9L), result)
    }

    @Test fun realParentSheetStaysAnchoredAtBoundariesAndDuringRefresh() {
        show()
        val viewportTop = top("navigation-viewport")
        val header = top("review-books-header")
        val list = compose.onNodeWithTag("review-books-list")
        repeat(3) { list.performTouchInput { swipeDown(durationMillis = 120) } }
        assertEquals(viewportTop, top("navigation-viewport"), .5f)
        list.performScrollToIndex(39)
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 120) } }
        assertEquals(viewportTop, top("navigation-viewport"), .5f)
        assertEquals(header, top("review-books-header"), .5f)
        list.performScrollToIndex(15)
        val anchor = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle {
            state = state.copy(entries = state.entries + state.entries.first().copy(annotation = reviewTestAnnotation(1000, 1)))
        }
        assertEquals(anchor, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        assertEquals(viewportTop, top("navigation-viewport"), .5f)
        val bottom = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot.bottom
        compose.runOnIdle {
            val dialog = WindowInspector.getGlobalWindowViews().last { it.isShown && it !== root }
            assertEquals(dialog.height.toFloat(), bottom, 1f)
        }
        assertEquals(0, dismissed)
        compose.onNodeWithContentDescription("关闭书籍选择").performClick()
        assertEquals(1, dismissed)
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val target = WindowInspector.getGlobalWindowViews().last { it.isShown && it !== root }
            val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
            target.draw(Canvas(bitmap))
            File("build/reports/reading-review/$name").apply { requireNotNull(parentFile).mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
