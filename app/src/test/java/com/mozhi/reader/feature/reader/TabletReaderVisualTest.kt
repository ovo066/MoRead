package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.graphics.Rect
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ai.knowledge.KnowledgeSnapshot
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.feature.reader.engine.*
import com.mozhi.reader.ui.MoReadWindowLayout
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.NavigationSheetEdge
import com.mozhi.reader.ui.theme.*
import io.mockk.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1400dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletReaderVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private lateinit var contentView: View
    private lateinit var controller: ReaderContentController
    private var panel by mutableStateOf("")
    private var chapter by mutableIntStateOf(0)
    private var progress by mutableFloatStateOf(0f)
    private var pageLabel by mutableStateOf("")
    private var controlsVisible by mutableStateOf(true)
    private var companionVisible by mutableStateOf(false)
    private var translation by mutableStateOf(ReaderTranslationState())
    private var translateExisting: Boolean? = null
    private var translatedParagraph = false
    private var settings by mutableStateOf(ReaderSettings(font = ReaderFont.SERIF, widePageLayout = WidePageLayout.DUAL,
        pageMarginTop = 2f, pageMarginBottom = 2f, fontScale = 1.1f))
    private val body = listOf(
        "雨停在傍晚。林舟推开窗，海面上浮着一层薄薄的雾，岸边的石阶刚被潮水洗过，在天光里泛着温润的灰。他把摊开的书压在杯子下面，听了一会儿远处传来的船笛。",
        "灯塔里的日子过得很慢。清晨要擦净玻璃，午后整理记录，到了夜里，便守着那束光掠过海湾。他从前总觉得，每一次明暗交替都差不多，如今却能从风声里分辨出季节。",
        "门外响起脚步声的时候，他正在读一封旧信。纸张的边缘已经发黄，折痕却很清楚，像一条在人心里走了许多年的路。他放下信，起身去开门。",
        "小满站在门边，外套上还带着雨水。她没有急着说话，只是把手中的信封递过去。那封信从很远的地方来，经过了许多人，最后终于到达这座海边的小屋。",
        "他们坐在窗边，看暮色一点点落下来。海上的灯光亮了，隔着很远的距离，与灯塔遥遥相望。谁也没有催促谁，有些故事需要这样一段安静，才肯从记忆里慢慢浮现。",
        "天亮之后就出发吧，小满说。林舟点了点头，把书签夹回书里。旅途还没有开始，但他已经感觉到，那些原以为不会再改变的日常，正在这一刻悄悄转向。"
    ).joinToString("\n\n").repeat(5)
    private val book = BookEntity(1, "雨夜里的灯塔", "演示作者", null, "", BookSourceType.TXT, 1, totalChapters = 40)
    private val chapters = List(40) { ChapterEntity(it + 1L, 1, it,
        listOf("第一章 · 灯亮之前", "第二章 · 雨中来客", "第三章 · 迟到的信").getOrElse(it) { "第 ${it + 1} 章 · 沿着海岸" }, "", body.length) }

    private fun show(dark: Boolean = false, companion: Boolean = false) {
        companionVisible = companion
        val learning = mockk<EnglishLearningViewModel>(relaxed = true)
        every { learning.state } returns MutableStateFlow(EnglishLearningState(dictionaries = listOf(
            LocalDictionary("oxford", "牛津高阶英汉双解词典", 2), LocalDictionary("classical", "古汉语常用字字典", 1))))
        every { learning.readerSettings } returns MutableStateFlow(ReaderSettings(vocabulary = listOf(
            VocabularyWord("serendipity", "不期而遇的美好；意外发现珍贵事物的机缘。", "A moment of serendipity by the sea.", gloss = "意外之喜", phonetic = "/ˌserənˈdɪpəti/"),
            VocabularyWord("lighthouse", "灯塔；指引方向的事物。", "The lighthouse stood above the bay.", gloss = "灯塔")
        )))
        val translationModel = mockk<TranslationModelViewModel>(relaxed = true)
        every { translationModel.state } returns MutableStateFlow(TranslationModelState(loaded = true))
        every { translationModel.error } returns MutableStateFlow(null)
        val persona = PersonaEntity(id = 3, name = "知秋", personality = "慢慢读，也慢慢聊。", isRoleplay = false, createdAt = 1)
        val companionModel = mockk<ReaderCompanionViewModel>(relaxed = true)
        every { companionModel.uiState } returns MutableStateFlow(CompanionChatUiState(
            personas = listOf(persona), activePersona = persona, conversationId = 7, isLoadingMessages = false,
            messages = listOf(
                MessageEntity(1, 7, "user", "这封旧信对林舟意味着什么？", createdAt = 1),
                MessageEntity(2, 7, "assistant", "信像是一个迟到的回应，把他与过去重新连在一起。\n\n我们可以回到他放下信、起身开门的那一段，再读一读这个转折。", createdAt = 2)
            )
        ))
        every { companionModel.chatContext } returns MutableStateFlow(CompanionChatContext(book.title))
        every { companionModel.events } returns emptyFlow()
        val mediaModel = mockk<ReaderSelectionMediaViewModel>(relaxed = true)
        val layoutActions = mockk<ReaderLayoutActions>(relaxed = true)
        every { layoutActions.onFontScaleChange } returns { settings = settings.copy(fontScale = it) }
        val actions = ReaderTypographyActions(
            font = mockk(relaxed = true), layout = layoutActions, theme = mockk(relaxed = true),
            syntax = mockk(relaxed = true), behavior = ReaderBehaviorActions(
                onAnimationChange = {}, onPageModeChange = {}, onKeepScreenOnChange = {},
                onImmersiveReadingChange = {}, onVolumeKeysPageTurnChange = {},
                onChineseConversionModeChange = {}, onScreenBrightnessChange = {},
                onWidePageLayoutChange = { settings = settings.copy(widePageLayout = it) }
            )
        )
        compose.setContent {
            contentView = LocalView.current
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
                MoReadWindowLayout {
                    val scope = rememberCoroutineScope()
                    var hook by remember { mutableStateOf<((Int) -> Unit)?>(null) }
                    controller = remember { ReaderContentController(scope, { ReaderChapterContent(body) }, object : ReaderContentController.Listener {
                        override fun onContentChanged(relativePosition: Int) { hook?.invoke(relativePosition) }
                        override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) {
                            chapter = chapterIndex; progress = if (pageCount > 1) pageIndex.toFloat() / (pageCount - 1) else 0f
                            pageLabel = "${pageIndex + 1} / $pageCount"
                        }
                    }).also { it.setChapters(chapters.map { ChapterMeta(it.chapterIndex, it.title, body.length) }) } }
                    val holder = remember { ReaderPaneHolder(controller) }
                    DisposableEffect(holder) { onDispose { holder.release() } }
                    val paper = readerPalette(settings.copy(theme = if (dark) ReaderTheme.DARK else ReaderTheme.PAPER), dark)
                    val palette = readerControlsPalette(paper)
                    ReaderCompanionLayout(companionVisible, Modifier.fillMaxSize().background(paper.background), companion = {
                        CompanionChatPane(1, { companionVisible = false }, companionModel, mediaModel)
                    }) {
                        ReaderPane(controller, holder, settings, paper, panel.isEmpty(), { hook = it }, {},
                            onAddBookmark = {}, onBoundary = {}, onNotice = {}, annotations = emptyList(),
                            onAiAction = { _, _, _ -> }, onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {},
                            onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null)
                        ReaderChrome(controlsVisible, book.title, chapters[chapter].title, progress, palette, {}, {}, false, {}, {}, {},
                            controller::seekWithinChapter, { panel = "contents" }, { panel = "bookmarks" }, { panel = "settings" }, {}, {}, {}, {}, {}, pageLabel = pageLabel)
                    }
                    if (panel == "contents") NavigationSheet({ panel = "" }, palette.glassStrong, palette.onBackground,
                        palette.scrim.copy(alpha = .08f), expandedEdge = NavigationSheetEdge.START) {
                        ReaderKnowledgePages(KnowledgeUiState(1, KnowledgeSnapshot(book), loading = false), chapters, emptyList(), chapter, palette,
                            { index, _ -> chapter = index; panel = "" }, { panel = "" })
                    }
                    if (panel == "settings") ReaderSettingsSheet(palette, { panel = "" }) {
                        ReaderTypographySheet(settings, 1, ReaderThemeSlot.DAY, palette,
                            actions.copy(behavior = actions.behavior.copy(spreadActive = controller.spreadMode)), {})
                    }
                    if (panel == "dictionaries") DictionaryManagerDialog({ panel = "" }, learning)
                    if (panel == "aids") EnglishLearningDialog(1, settings, palette, { panel = "" }, learning)
                    if (panel == "translation") BilingualReadingDialog(true, translation, {},
                        { translateExisting = it }, {}, { translation = translation.copy(busy = false) }, { panel = "" }, translationModel)
                    if (panel == "paragraph") ParagraphTranslationActionsDialog(
                        ParagraphTranslation(0, 1, "fixture", "灯塔里的日子过得很慢。清晨擦净玻璃，午后整理记录，到了夜里，便守着那束光掠过海湾。".repeat(12)),
                        true, false, { translatedParagraph = true }, {}, {}, { panel = "" })
                }
            }
        }
        compose.waitUntil(20_000) { controller.isReady }
        compose.waitForIdle()
        compose.waitUntil(5_000) { pageLabel.endsWith("/ ${controller.pageCount}") }
    }

    @Test fun spreadToolsAndSidePanelsUseTheActualPageRenderer() {
        show()
        assertTrue(controller.spreadMode)
        val page = controller.curSpread().first as RenderPage.Laid
        assertTrue("截图中的正文必须已排版且位于可见页面", page.page.lines.any { !it.isTitle && it.lineBottom < 800f })
        compose.runOnIdle { controlsVisible = false }
        capture("tablet-reader-spread.png")
        compose.runOnIdle { controlsVisible = true }
        compose.onNodeWithTag("tablet-reader-tools").assertIsDisplayed()
        assertFloatingActionsFit()
        capture("tablet-reader-tools.png")
        compose.onNodeWithContentDescription("目录").performClick()
        val pane = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, pane.left, .5f); assertEquals(440f, pane.width, .5f); assertEquals(root.height.toFloat(), pane.bottom, 1f)
        capture("tablet-reader-contents.png")
        compose.onNodeWithContentDescription("关闭目录与资料").performClick()
        compose.onNodeWithContentDescription("排版").performClick()
        val inspector = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(root.width.toFloat(), inspector.right, .5f)
        compose.onNodeWithText("当前宽度不足，暂按单页显示").assertDoesNotExist()
        capture("tablet-reader-typography.png")
        compose.onNodeWithText("单页").performClick()
        compose.runOnIdle { assertEquals(WidePageLayout.SINGLE, settings.widePageLayout) }
        compose.waitUntil(5_000) { !controller.spreadMode }
        compose.onNodeWithText("双页").performClick()
        compose.waitUntil(5_000) { controller.spreadMode }
        compose.onNodeWithContentDescription("关闭阅读设置").performClick()
        assertTrue(controller.isReady)
    }

    @Test fun darkSpreadKeepsFloatingControls() { show(true); assertFloatingActionsFit(); capture("tablet-reader-dark.png") }

    @Test @Config(qualifiers = "w900dp-h1200dp-mdpi")
    fun portraitSinglePageHasTheSameToolsAndReadableLineLength() {
        settings = settings.copy(widePageLayout = WidePageLayout.SINGLE)
        show(); assertFalse(controller.spreadMode); assertFloatingActionsFit(); capture("tablet-reader-portrait.png")
    }

    @Test @Config(qualifiers = "w600dp-h960dp-mdpi")
    fun narrowTabletKeepsEveryFloatingActionAndCompactsBookDetails() {
        show()
        assertFloatingActionsFit()
        compose.onNodeWithContentDescription("书籍详情").assertIsDisplayed()
        capture("tablet-reader-narrow-tools.png")
    }

    @Test fun embeddedCompanionAvoidsStatusBarAndCutoutWithoutJumpingWhenBarsHide() {
        show(companion = true)
        dispatchInsets(visible = true)
        val close = compose.onNodeWithContentDescription("关闭伴读面板").assertIsDisplayed()
        val position = close.fetchSemanticsNode().boundsInRoot
        assertTrue("内嵌面板必须避让 32px 状态栏", position.top >= 32f)
        compose.onNodeWithContentDescription("新会话").assertIsDisplayed()
        compose.onNodeWithContentDescription("会话历史").assertIsDisplayed()
        dispatchInsets(visible = false)
        assertEquals("沉浸模式不能让顶栏上跳", position, close.fetchSemanticsNode().boundsInRoot)
        dispatchInsets(visible = false, cutoutTop = 48)
        assertEquals(16f, close.fetchSemanticsNode().boundsInRoot.top - position.top, .5f)
        capture("tablet-reader-companion-safe-area.png")
        close.performClick()
        compose.onNodeWithContentDescription("关闭伴读面板").assertDoesNotExist()
        compose.waitUntil(20_000) { controller.isReady }
    }

    private fun assertFloatingActionsFit() {
        val labels = listOf("返回书架", "目录", "书签", "排版", "听书", "伴读", "书内搜索", "更多操作")
        val bounds = labels.map { compose.onNodeWithContentDescription(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.forEach {
            assertTrue("悬浮按钮的触达区至少 48dp", it.width >= 48f && it.height >= 48f)
            assertTrue(it.left >= 0f && it.right <= root.width)
        }
        bounds.zipWithNext().forEach { (left, right) -> assertTrue("悬浮按钮不能重叠", left.right < right.left) }
    }

    @Test fun readingAidsOpensVocabularyWithinTheSamePanelAndReturns() {
        show()
        compose.runOnIdle { controlsVisible = false; panel = "aids" }
        val bounds = assertRightToolPanel()
        capture("tablet-reader-reading-aids.png")
        compose.onNodeWithText("生词本").performClick()
        assertEquals(bounds, assertRightToolPanel())
        compose.onNodeWithTag("vocabulary-search").performTextInput("serendipity")
        capture("tablet-reader-vocabulary.png")
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("英文仿生阅读").assertIsDisplayed()
        compose.onNodeWithText("生词本").performClick()
        compose.onNodeWithTag("vocabulary-search").assertTextContains("serendipity")
        compose.onNodeWithContentDescription("关闭生词本").performClick()
        compose.onNodeWithTag("navigation-sheet").assertDoesNotExist()
        assertTrue(controller.isReady)
        compose.runOnIdle { panel = "dictionaries" }
        assertRightToolPanel()
        capture("tablet-reader-dictionaries.png")
    }

    @Test @Config(qualifiers = "w1200dp-h680dp-mdpi")
    fun bilingualProgressKeepsPanelHeaderAndScrollAnchorStable() {
        show()
        compose.runOnIdle { controlsVisible = false; panel = "translation" }
        val bounds = assertRightToolPanel()
        val header = compose.onNodeWithTag("reader-tool-header").fetchSemanticsNode().boundsInRoot
        capture("tablet-reader-bilingual.png")
        compose.onNodeWithText("更多选项").performScrollTo().performClick()
        compose.onNodeWithText("重新翻译已有段落").performScrollTo()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox)).performClick()
        compose.onNodeWithText("翻译当前页").performScrollTo().performClick()
        assertEquals(true, translateExisting)
        val list = compose.onNodeWithTag("secondary-page-list")
        list.performTouchInput { swipeUp(durationMillis = 100) }
        val anchor = toolScroll()
        compose.runOnIdle { translation = ReaderTranslationState(true, 3, 12, "正在翻译当前章") }
        compose.onNodeWithText("3 / 12 段").assertExists()
        assertEquals(anchor, toolScroll(), .01f)
        capture("tablet-reader-bilingual-progress.png")
        compose.runOnIdle { translation = ReaderTranslationState(false, 12, 12, "翻译完成") }
        assertEquals(anchor, toolScroll(), .01f)
        repeat(3) {
            list.performTouchInput { swipeDown(durationMillis = 100) }
            list.performTouchInput { swipeUp(durationMillis = 100) }
        }
        assertEquals(bounds, assertRightToolPanel())
        assertEquals(header, compose.onNodeWithTag("reader-tool-header").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("关闭中英对照").performClick()
        compose.onNodeWithTag("navigation-sheet").assertDoesNotExist()
    }

    @Test fun longParagraphTranslationScrollsWithoutMovingThePanelOrClippingActions() {
        show()
        compose.runOnIdle { controlsVisible = false; panel = "paragraph" }
        val bounds = assertRightToolPanel()
        compose.onNodeWithText("重新翻译本段").performScrollTo().performClick()
        assertTrue(translatedParagraph)
        compose.onNodeWithText("删除本段译文").performScrollTo().assertIsDisplayed()
        assertEquals(bounds, assertRightToolPanel())
        capture("tablet-reader-paragraph-translation.png")
    }

    private fun toolScroll() = compose.onNodeWithTag("secondary-page-list").fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun assertRightToolPanel(): androidx.compose.ui.geometry.Rect {
        val bounds = compose.onNodeWithTag("navigation-sheet").fetchSemanticsNode().boundsInRoot
        val overlay = compose.onNodeWithTag("navigation-overlay").fetchSemanticsNode().boundsInRoot
        assertEquals(440f, bounds.width, .5f)
        assertEquals(overlay.right, bounds.right, .5f)
        assertEquals(overlay.bottom, bounds.bottom, .5f)
        return bounds
    }

    private fun dispatchInsets(visible: Boolean, cutoutTop: Int = 0) {
        compose.runOnIdle {
            val status = Insets.of(0, 32, 0, 0)
            ViewCompat.dispatchApplyWindowInsets(contentView, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), if (visible) status else Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), status)
                .setVisible(WindowInsetsCompat.Type.statusBars(), visible)
                .setDisplayCutout(if (cutoutTop == 0) null else DisplayCutoutCompat(
                    Rect(0, cutoutTop, 0, 0), listOf(Rect(120, 0, 200, cutoutTop))
                )).build())
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); root.draw(canvas)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.draw(canvas)
            File("build/reports/tablet-ui/secondary/$name").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
