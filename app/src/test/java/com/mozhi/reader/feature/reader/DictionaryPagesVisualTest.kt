package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.LocalMoReadWindowWidthDp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.LocalDictionary
import com.mozhi.reader.core.dictionary.VocabularyWord
import com.mozhi.reader.ui.theme.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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
class DictionaryPagesVisualTest {
    @get:Rule val compose = createComposeRule()
    private var dark by mutableStateOf(false)
    private var open by mutableStateOf(true)
    private var narrow by mutableStateOf(false)

    private fun show(vocabulary: Boolean) {
        val vm = mockk<EnglishLearningViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(EnglishLearningState(dictionaries =
            (1..12).map { LocalDictionary("$it", "本地词典 $it", 1) }))
        every { vm.readerSettings } returns MutableStateFlow(ReaderSettings(vocabulary =
            (1..40).map { VocabularyWord("word$it", "释义 $it", "阅读语境 $it", createdAt = it.toLong()) }))
        compose.setContent {
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
                colorScheme = ColorSchemePreset.ROSE_DUST)) {
                CompositionLocalProvider(LocalMoReadWindowWidthDp provides (if (narrow) 500.dp else LocalConfiguration.current.screenWidthDp.dp)) {
                  if (open) {
                    if (vocabulary) VocabularyDialog(onDismiss = { open = false }, viewModel = vm)
                    else DictionaryManagerDialog(onDismiss = { open = false }, viewModel = vm)
                  }
                }
            }
        }
        compose.waitForIdle()
        val status = Insets.of(0, 32, 0, 0)
        val navigation = Insets.of(0, 0, 0, 24)
        compose.runOnIdle {
            val root = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            val composeView = requireNotNull(findComposeView(root))
            ViewCompat.dispatchApplyWindowInsets(composeView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), status)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setInsets(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), navigation)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
        }
        compose.waitForIdle()
    }

    @Test fun dictionaryBackdropContinuesUnderSystemBarsAndFollowsTheme() {
        show(false)
        checkWindowAndCapture("dictionary-phone-light.png")
        compose.runOnIdle { dark = true }
        checkWindowAndCapture("dictionary-phone-dark.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.runOnIdle { assertFalse(open) }
    }

    @Test @Config(qualifiers = "w1400dp-h960dp-mdpi")
    fun tabletDictionaryKeepsSafeAreaAndScrollInsideTheRightPanel() {
        show(false)
        val panel = compose.onNodeWithTag("navigation-sheet").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("reader-tool-header").fetchSemanticsNode().boundsInRoot
        val overlay = compose.onNodeWithTag("navigation-overlay").fetchSemanticsNode().boundsInRoot
        assertEquals(440f, panel.width, .5f)
        assertEquals(overlay.right, panel.right, .5f)
        assertEquals(overlay.bottom, panel.bottom, .5f)
        checkWindowAndCapture("dictionary-tablet-light.png")
        val list = compose.onNodeWithTag("secondary-page-list")
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 100) } }
        repeat(3) { list.performTouchInput { swipeDown(durationMillis = 100) } }
        list.performScrollToIndex(12)
        list.performTouchInput { swipeUp() }
        list.performScrollToIndex(0)
        list.performTouchInput { swipeDown() }
        assertEquals(panel, compose.onNodeWithTag("navigation-sheet").fetchSemanticsNode().boundsInRoot)
        assertEquals(header, compose.onNodeWithTag("reader-tool-header").fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle { dark = true }
        checkWindowAndCapture("dictionary-tablet-dark.png")
        compose.onNodeWithTag("navigation-scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(40f, 400f)) }
        compose.runOnIdle { assertFalse(open) }
    }

    @Test fun vocabularySearchStaysPinnedWhileScrollingAndFiltering() = checkVocabulary("phone")

    @Test @Config(qualifiers = "w1400dp-h960dp-mdpi")
    fun tabletVocabularySearchStaysPinnedWhileScrollingAndFiltering() {
        checkVocabulary("tablet")
        compose.onNodeWithTag("vocabulary-search").performTextInput("word4")
        compose.runOnIdle { narrow = true }
        compose.onNodeWithTag("navigation-sheet").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-search").assertTextContains("word4")
        compose.runOnIdle { narrow = false }
        compose.onNodeWithTag("navigation-sheet").assertExists()
        compose.onNodeWithTag("vocabulary-search").assertTextContains("word4")
    }

    private fun checkVocabulary(size: String) {
        show(true)
        checkWindowAndCapture("vocabulary-$size-light.png")
        val list = compose.onNodeWithTag("secondary-page-list")
        list.performScrollToIndex(12)
        val capsule = compose.onNodeWithTag("vocabulary-search-capsule")
        val pinned = capsule.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        list.performScrollToIndex(25)
        assertEquals(pinned, capsule.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("vocabulary-search").performTextInput("word40")
        compose.onNode(hasText("word40") and !hasSetTextAction()).assertIsDisplayed()
        capsule.assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-search").performTextReplacement("不存在的词")
        compose.onNodeWithText("没有匹配「不存在的词」的生词").assertIsDisplayed()
        capsule.assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-search").performTextClearance()
        compose.onNodeWithText("word40").assertIsDisplayed()
        compose.runOnIdle { dark = true }
        checkWindowAndCapture("vocabulary-$size-dark.png")
    }

    private fun checkWindowAndCapture(name: String) {
        compose.waitForIdle()
        val panel = compose.onAllNodesWithTag("navigation-sheet").fetchSemanticsNodes().isNotEmpty()
        val back = (if (panel) compose.onNode(hasContentDescription("关闭词典管理") or hasContentDescription("关闭生词本"))
            else compose.onNodeWithContentDescription("返回")).fetchSemanticsNode().boundsInRoot
        assertTrue("返回按钮必须避开顶部 32px 安全区", back.top >= 32f)
        compose.runOnIdle {
            val window = requireNotNull(ShadowDialog.getLatestDialog().window)
            val root = window.decorView
            val bars = WindowCompat.getInsetsController(window, root)
            assertEquals(!dark, bars.isAppearanceLightStatusBars)
            assertEquals(!dark, bars.isAppearanceLightNavigationBars)
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, window.statusBarColor)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            // Sample the unobstructed page edge above and below the status-bar boundary.
            // A separate status-bar fill, missing backdrop or inset outside the background fails here.
            val sampleX = if (panel) bitmap.width - 2 else 2
            val top = bitmap.getPixel(sampleX, 4)
            val body = bitmap.getPixel(sampleX, 40)
            assertEquals(255, Color.alpha(top))
            assertTrue("安全区背景与页面必须连续", colorDistance(top, body) < 12)
            assertTrue("背景明暗必须跟随当前主题", (Color.red(top) > 128) != dark)
            File("build/reports/tablet-ui/system-bars/$name").apply { parentFile.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun colorDistance(a: Int, b: Int) = kotlin.math.abs(Color.red(a) - Color.red(b)) +
        kotlin.math.abs(Color.green(a) - Color.green(b)) + kotlin.math.abs(Color.blue(a) - Color.blue(b))

    private fun findComposeView(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findComposeView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
