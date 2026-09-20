package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.ui.theme.*
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
class ReaderControlsVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private lateinit var controls: ReaderPalette
    private var autoRead = 0
    private var cleanup = 0
    private val paper = CustomReaderTheme(7, "旧橙色阅读主题", 0xFFFAF3E6.toInt(), 0xFF32302A.toInt(), AccentPreset.AMBER.light.toArgb())
    private fun show(appearance: () -> AppearanceSettings = { AppearanceSettings() }, selection: Boolean = false) {
        compose.setContent {
            MoReadTheme(appearance()) {
                val palette = readerControlsPalette(customReaderPalette(paper))
                val view = LocalView.current
                SideEffect { root = view.rootView; controls = palette }
                var syntax by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(palette.background)) {
                    ReaderChrome(true, "英文阅读", "第一章", .5f, palette,
                        onBack = {}, onOpenDetails = {}, isCurrentPositionBookmarked = false,
                        onToggleBookmark = {}, onPrevChapter = {}, onNextChapter = {}, onSeekChapter = {},
                        onContents = {}, onBookmarks = {}, onSettings = {}, onTts = {}, onCompanion = {}, onSearch = {},
                        onReidentifyChapters = {}, onTextReplacementRules = { cleanup++ }, onAutoRead = { autoRead++ },
                        onSyntaxHighlight = { syntax = true })
                    if (selection) SelectionToolbar(palette, 140, {}, {}, {}, {}, {}, null, {}, {})
                }
                if (syntax) ReaderSyntaxSheet(ReaderSettings(), palette, ReaderSyntaxActions({}, {}, {})) { syntax = false }
            }
        }
    }

    @Test fun changingOrangeToRoseUpdatesProgressAndSelectionWithoutChangingPaper() {
        val appearance = mutableStateOf(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AMBER))
        show({ appearance.value }, selection = true)
        compose.waitForIdle()
        assertEquals(AccentPreset.AMBER.light, controls.accent)
        compose.runOnIdle { appearance.value = AppearanceSettings(themeMode = ThemeMode.LIGHT, colorScheme = ColorSchemePreset.ROSE_DUST) }
        compose.waitForIdle()
        val rose = MoReadSchemes.side(ColorSchemePreset.ROSE_DUST, false).colors.primary
        assertEquals(rose, controls.accent)
        assertEquals(paper.backgroundArgb, controls.background.toArgb())
        assertEquals(paper.textArgb, controls.onBackground.toArgb())
        val regions = listOf("reader-selection-toolbar", "reader-chapter-progress").map {
            compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
        }
        compose.runOnIdle {
            capture(root, "reader-controls-rose.png") { bitmap ->
                regions.forEach { bounds -> assertTrue("操作控件没有渲染灰粉主题色", countColor(bitmap, bounds, rose.toArgb()) > 20) }
            }
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun topMenuKeepsAutoReadAndSyntaxDirectAndMoreToolsReturns() {
        show()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("自动阅读").assertIsDisplayed().performClick()
        assertEquals(1, autoRead)
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("TXT 净化 / 替换规则").assertDoesNotExist()
        compose.onNodeWithText("操作区域").assertDoesNotExist()
        compose.onNodeWithText("更多工具").performScrollTo().performClick()
        compose.onNodeWithText("操作区域").assertIsDisplayed()
        compose.onNodeWithText("返回常用操作").assertIsDisplayed().performClick()
        compose.onNodeWithText("语法高亮").performScrollTo().performClick()
        compose.onNodeWithText("语法高亮规则").assertIsDisplayed()
        compose.onNodeWithText("添加规则").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭语法高亮").performClick()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("更多工具").performScrollTo().performClick()
        compose.onNodeWithText("TXT 净化 / 替换规则").performClick()
        assertEquals(1, cleanup)
    }

    @Test fun companionBubblesAndComposerFollowRoseInReaderAndFullChatContexts() {
        val appearance = mutableStateOf(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AMBER))
        val fullChat = mutableStateOf(false)
        compose.setContent {
            MoReadTheme(appearance.value) {
                val palette = if (fullChat.value) companionChatPalette() else readerControlsPalette(customReaderPalette(paper))
                Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize(), color = palette.background) {
                        CompositionLocalProvider(LocalShowCompanionTokenUsage provides true) {
                            Column {
                                CompanionChatBubble(ChatEntry.Bubble("user", CompanionBubblePart.Text("这段英文是什么意思？"), true,
                                    MessageEntity(1, 1, "user", "这段英文是什么意思？", createdAt = 1)), palette, "伴读", null)
                                CompanionChatBubble(ChatEntry.Bubble("assistant", CompanionBubblePart.Text("我们可以结合这一段的语境理解。"), false,
                                    MessageEntity(2, 1, "assistant", "我们可以结合这一段的语境理解。", createdAt = 2,
                                        inputTokens = 100, outputTokens = 20, generationTimeMs = 2000)), palette, "伴读", null)
                                CompanionComposer("继续聊聊", {}, emptyList(), {}, emptyList(), false, palette, {}, {})
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("↑ 100 · ↓ 20 · 10.0 tok/s").assertExists()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("我们可以结合这一段的语境理解。", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { appearance.value = AppearanceSettings(themeMode = ThemeMode.LIGHT, colorScheme = ColorSchemePreset.ROSE_DUST) }
        val rose = MoReadSchemes.side(ColorSchemePreset.ROSE_DUST, false).colors.primary.toArgb()
        listOf(false, true).forEach { full ->
            compose.runOnIdle { fullChat.value = full }
            compose.waitForIdle()
            compose.runOnIdle {
                val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                capture(view, if (full) "companion-full-theme-rose.png" else "companion-reader-theme-rose.png") { bitmap ->
                    assertTrue("伴读气泡与发送按钮必须使用当前主题色", countColor(bitmap, Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()), rose) > 500)
                }
            }
        }
    }

    @Test fun syntaxRulesStayAnchoredThroughBoundarySwipesAndEditorReturn() {
        val rules = (1..40).map { ReaderSyntaxRule(it.toLong(), "规则 $it", "「", "」", 0xFF446688.toInt()) }
        val settings = mutableStateOf(ReaderSettings(syntaxHighlightRules = rules))
        compose.setContent {
            MoReadTheme {
                ReaderSyntaxSheet(settings.value, readerControlsPalette(customReaderPalette(paper)), ReaderSyntaxActions({},
                    { changed -> settings.value = settings.value.copy(syntaxHighlightRules = settings.value.syntaxHighlightRules.map { if (it.id == changed.id) changed else it }) }, {})) {}
            }
        }
        fun bounds() = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        fun scroll() = compose.onNodeWithTag("syntax-rules-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val original = bounds()
        val parent = compose.runOnIdle { requireNotNull(ShadowDialog.getLatestDialog().window).decorView }
        assertEquals(parent.height.toFloat(), original.bottom, 1f)
        val list = compose.onNodeWithTag("syntax-rules-list")
        repeat(3) { list.performTouchInput { swipeDown(durationMillis = 120) } }
        assertEquals(original, bounds())
        compose.onNodeWithText("添加规则").performScrollTo()
        repeat(3) { list.performTouchInput { swipeUp(durationMillis = 120) } }
        assertEquals(original, bounds())
        compose.onNodeWithText("规则 20").performScrollTo()
        val anchor = scroll()
        compose.onNodeWithText("规则 20").performClick()
        compose.runOnIdle {
            capture(requireNotNull(ShadowDialog.getLatestDialog().window).decorView, "syntax-rule-editor.png") {}
        }
        compose.onNodeWithText("规则名称").performTextReplacement("已编辑规则 20")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("已编辑规则 20").assertExists()
        assertEquals(anchor, scroll(), 1f)
        assertEquals(original, bounds())
        assertEquals(parent.height.toFloat(), bounds().bottom, 1f)
        compose.runOnIdle {
            capture(parent, "syntax-rules-sheet.png") {}
        }
    }

    private fun countColor(bitmap: Bitmap, rect: Rect, color: Int): Int {
        var count = 0
        for (y in rect.top.toInt().coerceAtLeast(0) until rect.bottom.toInt().coerceAtMost(bitmap.height))
            for (x in rect.left.toInt().coerceAtLeast(0) until rect.right.toInt().coerceAtMost(bitmap.width))
                if (bitmap.getPixel(x, y) == color) count++
        return count
    }
    private fun capture(view: View, name: String, verify: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/ui-qa/$name").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        try { verify(bitmap) } finally { bitmap.recycle() }
    }
}
