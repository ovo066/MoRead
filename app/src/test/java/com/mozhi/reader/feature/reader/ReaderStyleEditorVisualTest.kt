package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import com.mozhi.reader.core.datastore.*
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
class ReaderStyleEditorVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun namedStylesSelectCopyAndEditInsideAStableNavigationSheet() {
        val live = mutableStateOf(ReaderSettings(titleStylePresets = (1..30).map {
            ReaderTitleStylePreset("style-$it", "样式 $it", ReaderTitleStyle(css = "font-size: 1.5em;"))
        }))
        compose.setContent { MoReadTheme {
            ReaderTitleStyleSheet(live.value, readerPalette(ReaderTheme.PAPER, false), {}, actions { live.value = live.value.copy(titleStyle = it) }.copy(
                onSaveTitleStylePreset = { value -> live.value = live.value.copy(titleStylePresets =
                    if (live.value.titleStylePresets.any { it.id == value.id }) live.value.titleStylePresets.map { if (it.id == value.id) value else it }
                    else live.value.titleStylePresets + value) },
                onDeleteTitleStylePreset = { id -> live.value = live.value.copy(titleStylePresets = live.value.titleStylePresets.filterNot { it.id == id }) }
            ))
        } }
        val viewport = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(ShadowDialog.getLatestDialog().window!!.decorView.height.toFloat(), viewport.bottom, 1f)
        capture("title-style-library.png")
        compose.onNodeWithTag("title-style-list").performScrollToNode(hasTestTag("title-preset-style-20"))
        fun anchor() = compose.onNodeWithTag("title-style-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = anchor()
        compose.onNodeWithContentDescription("管理 样式 20").performClick()
        compose.onNodeWithText("编辑样式").performClick()
        compose.onNodeWithText("为这套样式命名").performTextReplacement("保存后的样式")
        compose.onNodeWithText("保存").performClick()
        assertEquals(before, anchor(), .01f)
        assertEquals(viewport, compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("title-preset-style-20").performClick()
        assertEquals("style-20", live.value.titleStyle.presetId)
        compose.onNodeWithContentDescription("管理 保存后的样式").performClick()
        compose.onNodeWithText("复制为新样式").performClick()
        compose.onNodeWithText("保存").performClick()
        assertEquals(31, live.value.titleStylePresets.size)
        assertEquals(31, live.value.titleStylePresets.map { it.id }.distinct().size)
        repeat(3) {
            compose.onNodeWithTag("title-style-list").performTouchInput { swipeUp(durationMillis = 80) }
            compose.onNodeWithTag("title-style-list").performTouchInput { swipeDown(durationMillis = 80) }
        }
        assertEquals(viewport, compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("title-style-list").performScrollToIndex(0)
        assertEquals(viewport, compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun titlePresetsRemainEditableAndCssErrorsPreventSaving() {
        var saved: ReaderTitleStyle? = null
        compose.setContent { MoReadTheme {
            ReaderTitleStyleEditor(ReaderSettings(), readerPalette(ReaderTheme.PAPER, false), {}, actions { saved = it })
        } }
        compose.onNodeWithText("留白章首").performClick()
        capture("title-editor-font.png")
        compose.onNodeWithTag("style-tab-1").performClick()
        compose.onNodeWithText("居中").performClick()
        compose.onNodeWithContentDescription("上方留白 增大").performClick()
        compose.onNodeWithContentDescription("章节标题排版预览").assertIsDisplayed()
        capture("title-editor-layout.png")
        compose.onNodeWithTag("style-tab-2").performClick()
        capture("title-editor-decoration.png")
        compose.onNodeWithTag("style-tab-3").performClick()
        compose.onNodeWithTag("style-css").performTextReplacement("unknown: test;")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithTag("style-css").performTextReplacement("font-size: 1.7em; color: #685248;")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        assertEquals(ReaderTitleAlignment.CENTER, saved!!.alignment)
        assertEquals(1.7f, ReaderStyleCss.parse(saved!!.css, true).sizeEm)
    }

    @Test fun syntaxLivePreviewAndStyleChangesSurviveTabSwitches() {
        var saved: ReaderSyntaxRule? = null
        val initial = ReaderSyntaxRule(1, "对话", "“", "”", 0xffab5575.toInt())
        compose.setContent { MoReadTheme {
            SyntaxRuleEditorDialog(initial, emptyList(), {}, { saved = it }, {})
        } }
        compose.onNodeWithText("已匹配 1 处").assertIsDisplayed()
        capture("syntax-editor-match.png")
        compose.onNodeWithTag("style-tab-1").performClick()
        compose.onNodeWithText("粗体").performScrollTo().performClick()
        capture("syntax-editor-style.png")
        compose.onNodeWithTag("style-tab-2").performClick()
        compose.onNodeWithText("填入示例").performScrollTo().performClick()
        capture("syntax-editor-css.png")
        compose.onNodeWithTag("style-tab-0").performClick()
        compose.onNodeWithTag("syntax-sample").performScrollTo().performTextReplacement("这里没有成对的引号。")
        compose.onNodeWithText("未匹配", substring = true).assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        assertTrue(saved!!.bold)
        assertTrue(saved!!.css.contains("font-style: italic"))
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun compactEditorKeepsNavigationAndSaveReachable() {
        compose.setContent { MoReadTheme {
            ReaderTitleStyleEditor(ReaderSettings(), readerPalette(ReaderTheme.PAPER, false), {}, actions {})
        } }
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithTag("style-tab-3").performClick()
        compose.onNodeWithTag("style-css").performTextReplacement("margin-top: 4em;")
        compose.onNodeWithText("保存").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("取消编辑").assertIsDisplayed()
        capture("title-editor-compact.png")
    }

    @Test fun cssGradientAndImageInsertionSaveInTheRealSyntaxEditor() {
        var saved: ReaderSyntaxRule? = null
        val image = File("build/reports/reader-enhancements/css-image-choice.png").apply { parentFile!!.mkdirs() }
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(0xffedf2e5.toInt())
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val settings = ReaderSettings(imageLibrary = listOf(ReaderImageAsset("paper-test", "纸纹测试", image.absolutePath)))
        compose.setContent { MoReadTheme {
            SyntaxRuleEditorDialog(ReaderSyntaxRule(1, "对话", "“", "”", -1), emptyList(), {},
                { saved = it }, null, readerSettings = settings)
        } }
        compose.onNodeWithTag("style-tab-2").performClick()
        compose.onNodeWithTag("style-css").performTextReplacement("color: linear-gradient(90deg, #c65f76, #567bce);")
        // Scroll the vertical editor first; the chip itself belongs to a horizontal LazyRow.
        compose.onNodeWithText("＋ 导入背景图片").performScrollTo()
        compose.onNodeWithTag("style-css-image-paper-test").assertIsDisplayed().performClick()
        compose.waitForIdle()
        capture("syntax-css-image-insert.png")
        compose.onNodeWithTag("style-css").assertTextContains("asset:paper-test", substring = true)
        compose.onNodeWithTag("style-tab-0").performClick()
        compose.onNodeWithContentDescription("语法高亮效果预览").assertIsDisplayed()
        capture("syntax-css-gradient.png")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        val css = ReaderStyleCss.parse(saved!!.css)
        assertNotNull(css.paint.textGradient)
        assertEquals(saved!!.css, "paper-test", css.paint.backgroundImageId)
    }

    @Test fun insertingImageAfterTextClipRestoresVisibleTextAndKeepsSolidBackground() {
        val source = "background: linear-gradient(#f00,#00f); background-clip:text; color:transparent; background-color:#eee;"
        val parsed = ReaderStyleCss.parse(source.withStyleBackgroundImage("paper"))
        assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
        assertFalse(parsed.clipText)
        assertNull(parsed.color)
        assertEquals(0xffeeeeee.toInt(), parsed.background)
        assertEquals("paper", parsed.paint.backgroundImageId)
    }

    private fun actions(save: (ReaderTitleStyle) -> Unit) = ReaderLayoutActions(
        onFontScaleChange = {}, onLineHeightChange = {}, onPublisherStyleModeChange = {},
        onPageMarginLeftChange = {}, onPageMarginRightChange = {}, onPageMarginTopChange = {}, onPageMarginBottomChange = {},
        onHeaderMarginTopChange = {}, onFooterMarginBottomChange = {}, onFontWeightChange = {}, onLetterSpacingChange = {},
        onParagraphSpacingChange = {}, onFirstLineIndentChange = {}, onTitleScaleChange = {}, onTitleTopSpacingChange = {},
        onTitleBottomSpacingChange = {}, onTextJustificationChange = {}, onShowHeaderChange = {}, onShowFooterChange = {},
        onTitleStyleChange = save)

    private fun capture(name: String) = compose.runOnIdle {
        val view = ShadowDialog.getLatestDialog().window!!.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/reader-enhancements/$name").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
