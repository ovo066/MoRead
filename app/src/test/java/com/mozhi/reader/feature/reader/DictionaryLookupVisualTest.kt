package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.feature.settings.*
import com.mozhi.reader.ui.theme.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
// 按默认中文界面断言文案；英文资源由 LocalizationResourcesTest 覆盖。
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DictionaryLookupVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private val first = LocalDictionary("a", "古汉语", 0)
    private val second = LocalDictionary("b", "汉语", 0)
    private val initial = EnglishLearningState(dictionaries = listOf(first, second), hit = DictionaryLookupHit("故", "温故而知新", 2, 4),
        definitions = listOf(DictionaryDefinition("a", "古汉语", "<h2>故</h2><p>旧的。</p>"), DictionaryDefinition("b", "汉语", "<b>故</b><p>缘故。</p>")),
        aiDefinition = (1..60).joinToString("\n") { "解释 $it：故，可以指旧的事物。" })

    @Test fun selectionToolbarOpensRenderedDictionaryDirectlyAndSwitchesSources() {
        var saved: String? = null
        compose.setContent {
            MoReadTheme(AppearanceSettings(colorScheme = ColorSchemePreset.ROSE_DUST)) {
                val palette = companionChatPalette()
                var visible by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    SelectionToolbar(palette, 100, {}, {}, {}, {}, {}, null, {}, {}, onDictionary = { visible = true })
                }
                if (visible) DictionaryLookupSheet(initial, palette, emptyList(), {}, {}, { id, _ -> saved = id }, {}, { visible = false }) { entry, modifier ->
                    val view = LocalView.current
                    SideEffect { root = view.rootView }
                    DictionaryWebContent(entry, false, LocalDictionaryRepository(RuntimeEnvironment.getApplication()), {}, modifier)
                }
            }
        }
        compose.onNodeWithText("词典").assertIsDisplayed().performClick()
        compose.onNodeWithTag("dictionary-lookup").assertIsDisplayed()
        compose.onNodeWithText("英语学习").assertDoesNotExist()
        compose.runOnIdle { assertRenderedDocument("旧的") }
        compose.onNodeWithText("汉语", substring = false).performClick()
        compose.runOnIdle { assertRenderedDocument("缘故") }
        compose.onNodeWithText("加入生词本").performClick()
        assertEquals("b", saved)
        compose.runOnIdle {
            val web = webView(root)!!
            val request = mockk<android.webkit.WebResourceRequest>()
            every { request.isForMainFrame } returns true
            web.webViewClient.onReceivedError(web, request, mockk())
        }
        compose.onNodeWithText("词典排版加载失败，已显示简明释义").assertIsDisplayed()
        compose.onNodeWithText("缘故。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("查看词典排版").performClick()
        compose.runOnIdle { assertNotNull(webView(root)) }
        compose.onNodeWithContentDescription("关闭词典").performClick()
        compose.onNodeWithTag("dictionary-lookup").assertDoesNotExist()
    }

    @Test fun realDictionarySheetKeepsBottomAndAnchorsDuringBoundarySwipesSourceSwitchesAndRefresh() {
        val live = mutableStateOf(initial)
        var dismissed = 0
        compose.setContent {
            MoReadTheme(AppearanceSettings(colorScheme = ColorSchemePreset.ROSE_DUST)) {
                DictionaryLookupSheet(live.value, companionChatPalette(), emptyList(), {}, {}, { _, _ -> }, {}, { dismissed++ }) { entry, modifier ->
                    val view = LocalView.current
                    SideEffect { root = view.rootView }
                    DictionaryWebContent(entry, false, LocalDictionaryRepository(RuntimeEnvironment.getApplication()), {}, modifier)
                }
            }
        }
        compose.onNodeWithText("AI 词典").performClick()
        val bounds = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        val top = compose.onNodeWithTag("dictionary-sources").fetchSemanticsNode().boundsInRoot.top
        val target = compose.onNodeWithTag("dictionary-ai-content")
        repeat(2) { target.performTouchInput { swipeDown(durationMillis = 100) } }
        repeat(14) { target.performTouchInput { swipeUp(durationMillis = 100) } }
        repeat(2) { target.performTouchInput { swipeUp(durationMillis = 100) } }
        target.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, -150f) }
        val anchor = scroll()
        assertTrue(anchor > 0)
        compose.runOnIdle { live.value = live.value.copy(lookingUp = true) }
        assertEquals(anchor, scroll(), 1f)
        compose.runOnIdle { live.value = live.value.copy(lookingUp = false) }
        compose.onNodeWithText("古汉语").performClick()
        compose.onNodeWithText("AI 词典").performClick()
        assertEquals(anchor, scroll(), 1f)
        val after = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(bounds.top, after.top, .5f)
        assertEquals(root.height.toFloat(), after.bottom, 1f)
        assertEquals(top, compose.onNodeWithTag("dictionary-sources").fetchSemanticsNode().boundsInRoot.top, .5f)
        assertEquals(0, dismissed)
        capture("dictionary-selection-popup.png")
    }

    @Test fun settingsHasDictionaryAndVocabularyEntriesAndVocabularyIncludesChinese() {
        val settings = mockk<SettingsViewModel>()
        every { settings.uiState } returns MutableStateFlow(SettingsUiState())
        val vm = mockk<EnglishLearningViewModel>(relaxed = true)
        every { vm.readerSettings } returns MutableStateFlow(ReaderSettings(vocabulary = listOf(VocabularyWord("故", definition = "旧的", context = "温故而知新"))))
        val book = mutableStateOf(false)
        compose.setContent {
            MoReadTheme {
                SettingsScreen(PaddingValues(0.dp), {}, {}, {}, {}, {}, {}, {}, settings)
                if (book.value) VocabularyDialog(onDismiss = { book.value = false }, viewModel = vm)
            }
        }
        compose.onNodeWithText("词典管理").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("生词本").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { book.value = true }
        compose.onNodeWithText("故").assertIsDisplayed()
        compose.onNodeWithText("温故而知新").assertIsDisplayed()
        compose.onNodeWithContentDescription("「故」的更多操作").performClick()
        compose.onNodeWithText("移出生词本").performClick()
        verify { vm.updateWord(match { it.word == "故" }, true) }
    }

    @Test fun renderedDictionaryKeepsTouchStreamInsideWebViewAndHeaderCanStillDismiss() {
        var dismissed = 0
        compose.setContent {
            MoReadTheme {
                DictionaryLookupSheet(initial, companionChatPalette(), emptyList(), {}, {}, { _, _ -> }, {}, { dismissed++ }) { entry, modifier ->
                    val view = LocalView.current
                    SideEffect { root = view.rootView }
                    DictionaryWebContent(entry, false, LocalDictionaryRepository(RuntimeEnvironment.getApplication()), {}, modifier)
                }
            }
        }
        val actions = mutableListOf<Int>()
        compose.runOnIdle {
            val web = webView(root)!!
            val host = web.parent as View
            // Robolectric has no Chromium layout or touch handler. Supply its bounds and a
            // consuming handler, but exercise the real AndroidView + parent sheet dispatch.
            // WebView.setFrame delegates to the absent Chromium provider too, so calling
            // layout() is a no-op. Set the actual View frame for Android hit testing.
            Shadow.directlyOn<Boolean, View>(web, View::class.java, "setFrame",
                from(Int::class.javaPrimitiveType, 0), from(Int::class.javaPrimitiveType, 0),
                from(Int::class.javaPrimitiveType, host.width), from(Int::class.javaPrimitiveType, host.height))
            assertEquals(host.width, web.width)
            assertEquals(host.height, web.height)
            web.setOnTouchListener { _, event -> actions += event.actionMasked; true }
        }
        val viewport = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("dictionary-sources").fetchSemanticsNode().boundsInRoot
        val target = compose.onNodeWithTag("dictionary-definition")
        for (dy in listOf(120f, -120f, 180f, -180f)) {
            actions.clear()
            target.performTouchInput {
                down(center)
                moveBy(Offset(0f, dy / 2))
                moveBy(Offset(0f, dy / 2))
            }
            compose.runOnIdle {
                assertTrue("WebView must receive down: $actions", MotionEvent.ACTION_DOWN in actions)
                assertTrue("WebView must keep move events: $actions", MotionEvent.ACTION_MOVE in actions)
                assertFalse("Sheet must not cancel WebView scrolling: $actions", MotionEvent.ACTION_CANCEL in actions)
            }
            assertEquals(viewport.top, compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot.top, .5f)
            target.performTouchInput { up() }
            compose.runOnIdle { assertEquals(MotionEvent.ACTION_UP, actions.last()) }
        }
        target.performTouchInput { down(center); moveBy(Offset(0f, 60f)); cancel() }
        compose.runOnIdle { assertEquals(MotionEvent.ACTION_CANCEL, actions.last()) }
        assertEquals(tabs.top, compose.onNodeWithTag("dictionary-sources").fetchSemanticsNode().boundsInRoot.top, .5f)
        assertEquals(root.height.toFloat(), compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        assertEquals(0, dismissed)
        compose.onNodeWithText("故", substring = false).performTouchInput {
            swipe(Offset(centerX, centerY), Offset(centerX, centerY + 700f), durationMillis = 400)
        }
        compose.waitUntil(5000) { dismissed > 0 }
    }

    @Test fun aiDictionaryRendersMarkdownHeadingsAndExamplesInsideTheRealSheet() {
        val rich = initial.copy(aiDefinition = "## 故\n\n**gù · 名词**\n\n1. 缘故；原因。\n\n### 语境义\n\n温习学过的知识。\n\n### 例句\n\n> 温故而知新。")
        compose.setContent {
            MoReadTheme {
                DictionaryLookupSheet(rich, companionChatPalette(), emptyList(), {}, {}, { _, _ -> }, {}, {}) { _, _ ->
                    val view = LocalView.current
                    SideEffect { root = view.rootView }
                }
            }
        }
        compose.onNodeWithText("AI 词典").performClick()
        capture("dictionary-ai-rich.png")
        compose.onNodeWithText("## 故", substring = true).assertDoesNotExist()
        compose.onNodeWithText("语境义", substring = false).assertIsDisplayed()
        compose.onNodeWithText("温习学过的知识。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("例句", substring = false).performScrollTo().assertIsDisplayed()
    }

    private fun scroll() = compose.onNodeWithTag("dictionary-ai-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun assertRenderedDocument(text: String) {
        val web = webView(root)!!
        // Robolectric has no Chromium provider: WebView's native measure/layout are no-ops.
        // Verify the real AndroidView host viewport and requested size, not fake web pixels.
        val host = web.parent as View
        assertTrue(host.width > 0 && host.height > 0)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, web.layoutParams.height)
        assertEquals(View.LAYER_TYPE_SOFTWARE, web.layerType)
        assertEquals(View.OVER_SCROLL_NEVER, web.overScrollMode)
        val request = mockk<android.webkit.WebResourceRequest>()
        every { request.url } returns android.net.Uri.parse(Shadows.shadowOf(web).lastLoadedUrl)
        every { request.isForMainFrame } returns true
        val response = web.webViewClient.shouldInterceptRequest(web, request)!!
        assertEquals(200, response.statusCode)
        assertEquals("text/html", response.mimeType)
        assertTrue(response.data.bufferedReader().use { it.readText() }.contains(text))
    }
    private fun webView(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup) (0 until view.childCount).firstNotNullOfOrNull { webView(view.getChildAt(it)) } else null
    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        File("build/reports/ui-qa/$name").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
