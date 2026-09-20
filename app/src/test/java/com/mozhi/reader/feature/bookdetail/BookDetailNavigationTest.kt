package com.mozhi.reader.feature.bookdetail

import android.app.Application
import android.graphics.Rect as AndroidRect
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.*
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.ui.MoReadNavigationHost
import com.mozhi.reader.ui.MoReadNavigationScaffold
import com.mozhi.reader.ui.bookIdOrNull
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.pushComposable
import com.mozhi.reader.ui.rootComposable
import com.mozhi.reader.ui.theme.MoReadTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The real detail page must keep its geometry while the reader restores the status bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookDetailNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var view: View
    private var visibleStatusTop = -1
    private val state = MutableStateFlow(BookDetailUiState(
        book = BookEntity(
            id = 1, title = "沉浸阅读回归", author = "回归测试作者", coverPath = null,
            epubPath = "", sourceType = BookSourceType.TXT, importedAt = 1,
            totalChapters = 20, lastReadAt = 1
        ),
        isLoading = false
    ))
    private val landmarks = listOf(
        hasContentDescription("返回"),
        hasText("书籍详情"),
        hasText("回归测试作者 · 20 章"),
        hasText("继续阅读")
    )

    private fun mount(cutoutTop: Int) {
        val vm = mockk<BookDetailViewModel>()
        every { vm.uiState } returns state
        every { vm.coverCandidates } returns MutableStateFlow(emptyList())
        every { vm.coverSearchQueries } returns MutableStateFlow(emptyList())
        every { vm.coverSearchAgentEnhanced } returns MutableStateFlow(false)
        every { vm.pendingCover } returns MutableStateFlow(null)
        every { vm.coverGenerationProgress } returns MutableStateFlow(null)
        every { vm.events } returns emptyFlow()

        compose.setContent {
            nav = rememberNavController()
            val localView = LocalView.current
            val statusTop = WindowInsets.statusBars.getTop(LocalDensity.current)
            SideEffect { view = localView; visibleStatusTop = statusTop }
            MoReadTheme {
                MoReadBackdrop {
                    MoReadNavigationScaffold {
                        MoReadNavigationHost(nav) {
                            rootComposable("bookshelf", expanded = false) {
                                Box(Modifier.fillMaxSize())
                            }
                            pushComposable("book/{bookId}") {
                                BookDetailScreen(
                                    bookId = 1,
                                    onBack = { nav.popBackStack() },
                                    onContinueReading = { nav.navigate("reader/$it") },
                                    viewModel = vm
                                )
                            }
                            pushComposable("reader/{bookId}") {
                                Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        dispatchInsets(visible = true, cutoutTop = cutoutTop)
        compose.runOnIdle { nav.navigate("book/1") }
        compose.waitForIdle()
        assertEquals(1L, nav.currentBackStackEntry?.bookIdOrNull())
    }

    @Test @Config(qualifiers = "w1400dp-h960dp-mdpi")
    fun tabletDetailKeepsCoverAndActionsBesideScrollableReadingMaterial() {
        state.value = state.value.copy(book = state.value.book!!.copy(title = "雨夜里的灯塔", author = "演示作者"), description = "一封迟来的信，让灯塔里的守望变成了一段新的旅程。")
        mount(0)
        val before = compose.onNodeWithTag("detail-summary").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("继续阅读").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").onChildren().filter(hasScrollAction()).onFirst().performTouchInput { swipeUp() }
        assertEquals(before, compose.onNodeWithTag("detail-summary").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            val root = view.rootView
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File("build/reports/tablet-ui/secondary/tablet-book-detail.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun dispatchInsets(visible: Boolean, cutoutTop: Int) {
        compose.runOnIdle {
            val status = Insets.of(0, 24, 0, 0)
            val navigation = Insets.of(0, 0, 0, 32)
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), if (visible) status else Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                .setVisible(WindowInsetsCompat.Type.statusBars(), visible)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .setDisplayCutout(if (cutoutTop == 0) null else DisplayCutoutCompat(
                    AndroidRect(0, cutoutTop, 0, 0), listOf(AndroidRect(120, 0, 200, cutoutTop))
                )).build())
        }
        compose.waitForIdle()
        if (!compose.mainClock.autoAdvance) frame()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun geometry(): List<Rect> = landmarks.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot }

    private fun assertGeometry(expected: List<Rect>, frame: Int) {
        expected.zip(geometry()).forEachIndexed { index, (before, after) ->
            assertEquals("landmark $index moved vertically at frame $frame", before.top, after.top, 0.5f)
            assertEquals("landmark $index resized at frame $frame", before.height, after.height, 0.5f)
        }
    }

    private fun verifyReaderReturn(cutoutTop: Int) {
        mount(cutoutTop)
        val expected = geometry()
        assertTrue("back button must clear the status bar and cutout", expected.first().top >= maxOf(24, cutoutTop))
        compose.mainClock.autoAdvance = false
        repeat(3) {
            compose.onNodeWithText("继续阅读").performClick()
            repeat(24) { frame() }
            dispatchInsets(visible = false, cutoutTop = cutoutTop)
            assertEquals(0, visibleStatusTop)
            compose.runOnIdle { nav.popBackStack() }
            repeat(24) frames@{ frameIndex ->
                frame()
                // NavHost attaches the returning entry on the next composition frame.
                if (compose.onAllNodes(landmarks.first()).fetchSemanticsNodes().isEmpty()) {
                    assertTrue("detail page missing at frame $frameIndex", frameIndex < 2)
                    return@frames
                }
                assertGeometry(expected, frameIndex)
                // ReaderScreen restores the status bar on disposal, after its exit transition.
                if (frameIndex == 18) dispatchInsets(visible = true, cutoutTop = cutoutTop)
                if (frameIndex == 20) compose.runOnIdle {
                    state.value = state.value.copy(totalDurationMs = state.value.totalDurationMs + 60_000)
                }
                assertGeometry(expected, frameIndex)
            }
            assertEquals(24, visibleStatusTop)
        }
    }

    @Test fun readerPopKeepsDetailHeaderAndBodyStableBeforeAndAfterStatusBarRestoration() {
        verifyReaderReturn(cutoutTop = 0)
    }

    @Test fun readerPopKeepsDetailClearOfTheDisplayCutout() {
        verifyReaderReturn(cutoutTop = 44)
    }
}
