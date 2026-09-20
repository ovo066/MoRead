package com.mozhi.reader.feature.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ai.knowledge.*
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.*
import com.mozhi.reader.core.datastore.CustomReaderTheme
import androidx.compose.ui.graphics.toArgb
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
class ReaderKnowledgeVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private val book = BookEntity(id = 1, title = "雨夜里的灯塔", author = "示例作者", coverPath = null, epubPath = "", sourceType = BookSourceType.EPUB,
        importedAt = 1, totalChapters = 40, maxReachedChapterIndex = 39, maxReachedCharOffset = 80)
    private val chapters = (0 until 40).map { index ->
        val title = listOf("第一章 灯亮之前", "第二章 雨中来客", "第三章 一封迟到的信", "第四章 海风又起").getOrNull(index) ?: "第 ${index + 1} 章 海岸旅途"
        ChapterEntity(id = index + 1L, bookId = 1, chapterIndex = index, title = title, href = "", charCount = 240)
    }
    private val fact = KnowledgeFact("小满送来了信，两人约好天亮出发。", "小满把信放在桌上，两人约定天亮后出发。", 20, 20 + "小满把信放在桌上，两人约定天亮后出发。".length)
    private val people = listOf(
        KnowledgeCharacter("林舟", listOf(KnowledgeFact("独自守着灯塔，在雨夜等来了小满。", "林舟在灯塔等候。", 0, 8), fact)),
        KnowledgeCharacter("小满", listOf(KnowledgeFact("送信的来客，约好和林舟一同出发。", "小满带来一封信。", 10, 18))))
    private val content = ChapterKnowledge(listOf(fact), outline = "雨夜里，林舟仍独自守着灯塔，小满带着一封迟来的信登门。信的到来打断了他的等待，也让两人有了下一步的打算。\n\n小满把信放在桌上，两人约定等天亮后出发，沿着海岸寻找寄信人的下落。这个雨夜的相遇，由此成为一段旅途的起点。")
    private val row = ChapterKnowledgeEntity(1, 1, "a".repeat(64), 240, "b".repeat(64), "model-key", "示例模型", 2, "{}", 1)
    private val personEntry = BookCharacterGuideEntity(1, "generation", "a".repeat(64), "model-key", "示例模型", 1, "{}", 1)
    private val guide = BookCharactersCodec.fromChapters(listOf(1 to people), 40, 9600)
    private val state = KnowledgeUiState(bookId = 1, snapshot = KnowledgeSnapshot(book, listOf(VisibleChapterKnowledge(row, content))), loading = false,
        characters = BookCharactersSnapshot(VisibleBookCharacters(personEntry, guide)))

    private fun show(tab: Int = 1, dark: Boolean = false, input: () -> KnowledgeUiState = { state },
        actions: ReaderKnowledgeActions = ReaderKnowledgeActions(), onDismiss: () -> Unit = {},
        appearance: com.mozhi.reader.ui.theme.AppearanceSettings = com.mozhi.reader.ui.theme.AppearanceSettings(),
        appearanceState: () -> AppearanceSettings = { appearance }, paper: ReaderSettings? = null) {
        compose.setContent {
            MoReadTheme(appearanceState()) {
                com.mozhi.reader.ui.theme.ReaderAppearanceScope {
                val palette = readerControlsPalette(readerPalette(paper ?: if (dark) ReaderSettings(theme = ReaderTheme.DARK) else ReaderSettings(), dark))
                Box(Modifier.fillMaxSize().background(palette.background)) {
                    NavigationSheet(onDismiss, palette.glassStrong, palette.onBackground, palette.scrim,
                        expandedEdge = com.mozhi.reader.ui.components.NavigationSheetEdge.START) {
                        val view = LocalView.current
                        SideEffect { root = view.rootView }
                        ReaderKnowledgePages(input(), chapters, emptyList(), 1, palette, { _, _ -> }, onDismiss, actions, tab)
                    }
                }
                }
            }
        }
    }

    @Test fun savedOutlineUsesConnectedProseAndEvidenceOpensOnlyOnDemand() {
        var generated = 0
        var located = 0
        show(actions = ReaderKnowledgeActions(preview = { generated++ }, locate = { _, _ -> located++ }))
        compose.onNodeWithText(content.outline).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(fact.text).assertDoesNotExist()
        capture("chapter-outline.png")
        compose.onNodeWithText("原文依据 · 1 处").performScrollTo().performClick()
        compose.onNodeWithText(fact.text).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("核对原文").performScrollTo().performClick()
        assertEquals(1, located)
        compose.onNode(hasText(chapters[1].title) and hasContentDescription("收起章节大纲")).performScrollTo().performClick()
        compose.onNodeWithText(content.outline).assertDoesNotExist()
        capture("chapter-outline-folded.png")
        compose.onNode(hasText(chapters[1].title) and hasContentDescription("展开章节大纲")).performClick()
        compose.onNodeWithText(content.outline).assertExists()
        assertEquals(0, generated)
        compose.onNodeWithText("重新生成").performScrollTo().performClick()
        assertEquals(1, generated)
    }

    @Test fun directoryChangesToRoseDespiteSavedOrangeReaderTheme() {
        val appearance = mutableStateOf(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AMBER))
        val custom = CustomReaderTheme(1, "旧橙色纸色", 0xFFFAF3E6.toInt(), 0xFF32302A.toInt(), AccentPreset.AMBER.light.toArgb())
        show(tab = 0, appearanceState = { appearance.value }, paper = ReaderSettings(customThemes = listOf(custom), activeCustomThemeId = 1))
        val before = top("navigation-viewport")
        compose.onNodeWithTag("contents-list").performScrollToIndex(20)
        val anchor = scroll("contents-list")
        compose.runOnIdle { appearance.value = AppearanceSettings(themeMode = ThemeMode.LIGHT, colorScheme = ColorSchemePreset.ROSE_DUST) }
        assertEquals(before, top("navigation-viewport"), 1f)
        assertEquals(anchor, scroll("contents-list"), .001f)
        capture("navigation-contents-rose.png", MoReadSchemes.side(ColorSchemePreset.ROSE_DUST, false).colors.primary.toArgb())
    }

    @Test fun wholeBookCharactersUseCompactCardsAndExpandableEvidenceInDarkTheme() {
        show(tab = 2, dark = true)
        compose.onNodeWithText("林舟", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("小满", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(fact.text).assertDoesNotExist()
        compose.onNodeWithText("更新全书人物").assertDoesNotExist()
        capture("chapter-characters-dark.png")
        compose.onNodeWithText("展开 1 条资料").performClick()
        compose.onNodeWithText(fact.text).assertIsDisplayed()
        compose.onNodeWithText("提取", substring = false).performClick()
        compose.onNodeWithText("更新全书人物").assertIsDisplayed()
        compose.onNodeWithContentDescription("书籍资料说明").performClick()
        compose.onNodeWithText("包括还没读到的章节", substring = true).assertIsDisplayed()
    }

    @Test fun charactersOwnMostOfViewportAndManualEditingSearchKeepTheListAnchor() {
        val many = guide.copy(characters = (0 until 40).map { BookCharacter("人物$it", guide.characters.first().evidence) })
        val live = mutableStateOf(state.copy(characters = BookCharactersSnapshot(VisibleBookCharacters(personEntry, many))))
        var created: BookCharacter? = null
        val actions = ReaderKnowledgeActions(saveCharacter = { identity, name, description ->
            val next = BookCharactersCodec.edit(live.value.characters.saved!!.guide, identity, name, description)
            created = next.characters.first { it.name == name }
            live.value = live.value.copy(characters = BookCharactersSnapshot(VisibleBookCharacters(personEntry, next)))
        })
        show(tab = 2, input = { live.value }, actions = actions)
        val viewport = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        val list = compose.onNodeWithTag("characters-list").fetchSemanticsNode().boundsInRoot
        assertTrue("人物内容应占据弹层大部分高度", list.height > viewport.height * .60f)
        compose.onNodeWithTag("characters-list").performScrollToIndex(12)
        val anchor = scroll("characters-list")
        compose.onNodeWithContentDescription("人物12的人物操作").performClick()
        compose.onNodeWithText("编辑人物").performClick()
        compose.onNodeWithTag("character-description").performTextReplacement("守望灯塔的人，手动修订")
        compose.onNodeWithText("保存人物").performClick()
        assertEquals("守望灯塔的人，手动修订", created?.manualDescription)
        assertEquals(anchor, scroll("characters-list"), .001f)
        compose.onNodeWithContentDescription("新增人物").performClick()
        compose.onNodeWithTag("character-name").performTextInput("渡船人")
        compose.onNodeWithTag("character-description").performTextInput("在码头接应")
        compose.onNodeWithText("保存人物").performClick()
        assertEquals("渡船人", created?.name)
        assertEquals(anchor, scroll("characters-list"), .001f)
        compose.onNodeWithContentDescription("查找书中人物").performClick()
        compose.onNodeWithTag("character-search").performTextInput("渡船人")
        compose.onNodeWithText("查找", substring = false).performClick()
        compose.onNodeWithText("渡船人", substring = false).assertIsDisplayed()
        compose.onNodeWithText("在码头接应").assertIsDisplayed()
        assertEquals(viewport.bottom, compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot.bottom, .5f)
        capture("characters-manual-search.png")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun emptyStateHasClearManualEntryOnSmallScreens() {
        var generated = 0
        show(input = { state.copy(snapshot = KnowledgeSnapshot(book)) }, actions = ReaderKnowledgeActions(preview = { generated++ }))
        compose.onNodeWithText("生成当前章").assertIsDisplayed().performClick()
        assertEquals(1, generated)
        compose.onNode(hasText(chapters[0].title) and hasContentDescription("展开章节大纲")).assertIsDisplayed()
        capture("chapter-outline-small.png")
    }

    @Test fun anotherChapterCanBeGeneratedWhileTheCurrentChapterIsRunning() {
        var selected = -1
        var cancelled = -1
        show(input = { state.copy(tasks = mapOf(1 to KnowledgeTaskState(active = true, progress = "生成中 1 / 2"))) },
            actions = ReaderKnowledgeActions(preview = { selected = it }, cancel = { cancelled = it }))
        compose.onNode(hasText(chapters[0].title) and hasContentDescription("展开章节大纲")).performClick()
        compose.onNodeWithText("生成概括").performScrollTo().assertIsEnabled().performClick()
        assertEquals(0, selected)
        compose.onNodeWithText("停止本章").performScrollTo().performClick()
        assertEquals(1, cancelled)
    }

    @Test fun navigationSurfaceReachesTheWindowBottomOnEveryTab() {
        show(tab = 0)
        listOf("目录", "大纲", "人物").forEach { tab ->
            selectTab(tab)
            // Read the placed child: the modal's outer modifier precedes Material's anchor offset.
            val bounds = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
            assertEquals("$tab 的弹层底部必须贴住窗口底部，不得留下按比例缩小的空带", root.height.toFloat(), bounds.bottom, 1f)
            if (tab == "目录") capture("navigation-contents-bottom.png")
        }
        capture("navigation-sheet-bottom.png")
    }

    @Test fun realSheetStaysStillDuringRepeatedBoundarySwipesOnAllThreePages() {
        var dismissed = 0
        val many = state.copy(characters = BookCharactersSnapshot(VisibleBookCharacters(personEntry,
            guide.copy(characters = (0 until 40).map { BookCharacter("人物$it", guide.characters.first().evidence) }))))
        show(input = { many }, onDismiss = { dismissed++ })
        val sheetTop = top("navigation-viewport")
        val tabsTop = top("knowledge-tabs")
        listOf("大纲" to "outline-list", "人物" to "characters-list", "目录" to "contents-list").forEach { (tab, list) ->
            selectTab(tab)
            val target = compose.onNodeWithTag(list)
            target.performScrollToIndex(0)
            repeat(3) { target.performTouchInput { swipeDown(durationMillis = 160) } }
            assertEquals(sheetTop, top("navigation-viewport"), 0.5f)
            assertEquals(tabsTop, top("knowledge-tabs"), 0.5f)
            target.performScrollToIndex(39)
            repeat(3) { target.performTouchInput { swipeUp(durationMillis = 120) } }
            assertEquals(sheetTop, top("navigation-viewport"), 0.5f)
            assertEquals(tabsTop, top("knowledge-tabs"), 0.5f)
            target.performTouchInput { swipeDown(durationMillis = 240) }
            target.performTouchInput { swipeUp(durationMillis = 240) }
            assertEquals(sheetTop, top("navigation-viewport"), 0.5f)
        }
        assertEquals(0, dismissed)
        compose.onNodeWithContentDescription("关闭目录与资料").performClick()
        assertEquals(1, dismissed)
    }

    @Test fun tabScrollAnchorsSurviveSwitchingAndBackgroundProgressUpdates() {
        val live = mutableStateOf(state.copy(characters = BookCharactersSnapshot(VisibleBookCharacters(personEntry,
            guide.copy(characters = (0 until 40).map { BookCharacter("人物$it", guide.characters.first().evidence) })))))
        show(input = { live.value })
        compose.onNodeWithTag("outline-list").performScrollToIndex(12)
        val outlinePosition = scroll("outline-list")
        val header = top("knowledge-tabs")
        compose.runOnIdle {
            live.value = live.value.copy(tasks = mapOf(0 to KnowledgeTaskState(active = true, progress = "生成中 2 / 3")),
                characterTask = KnowledgeTaskState(active = true, progress = "已扫描 9 / 40 章"))
        }
        assertEquals(outlinePosition, scroll("outline-list"), 0.001f)
        selectTab("人物")
        compose.onNodeWithTag("characters-list").performScrollToIndex(10)
        val peoplePosition = scroll("characters-list")
        compose.runOnIdle { live.value = live.value.copy(characterTask = KnowledgeTaskState(active = true, progress = "已扫描 10 / 40 章")) }
        assertEquals(peoplePosition, scroll("characters-list"), 0.001f)
        selectTab("目录")
        compose.onNodeWithTag("contents-list").performScrollToIndex(20)
        val contentsPosition = scroll("contents-list")
        selectTab("大纲")
        assertEquals(outlinePosition, scroll("outline-list"), 0.001f)
        selectTab("人物")
        assertEquals(peoplePosition, scroll("characters-list"), 0.001f)
        selectTab("目录")
        assertEquals(contentsPosition, scroll("contents-list"), 0.001f)
        assertEquals(header, top("knowledge-tabs"), 0.5f)
        capture("navigation-sheet-scroll.png")
    }

    @Test fun expressiveFlatApplicationKeepsReaderSheetEdgesAndAnchorsStable() {
        val live = mutableStateOf(state.copy(characters = BookCharactersSnapshot(VisibleBookCharacters(personEntry,
            guide.copy(characters = (0 until 40).map { BookCharacter("人物$it", guide.characters.first().evidence) })))))
        show(input = { live.value }, appearance = com.mozhi.reader.ui.theme.AppearanceSettings(
            colorScheme = com.mozhi.reader.ui.theme.ColorSchemePreset.HAZE_BLUE,
            surfaceStyle = com.mozhi.reader.ui.theme.SurfaceStyle.FLAT,
            shapeStyle = com.mozhi.reader.ui.theme.ShapeStyle.EXPRESSIVE))
        val sheetTop = top("navigation-viewport")
        val tabsTop = top("knowledge-tabs")
        listOf("大纲" to "outline-list", "人物" to "characters-list", "目录" to "contents-list").forEach { (tab, list) ->
            selectTab(tab)
            val target = compose.onNodeWithTag(list)
            target.performScrollToIndex(0)
            repeat(2) { target.performTouchInput { swipeDown(durationMillis = 120) } }
            target.performScrollToIndex(39)
            repeat(2) { target.performTouchInput { swipeUp(durationMillis = 120) } }
            target.performScrollToIndex(12)
            val anchor = scroll(list)
            compose.runOnIdle {
                live.value = live.value.copy(tasks = mapOf(0 to KnowledgeTaskState(active = true, progress = "生成中 2 / 3")))
            }
            assertEquals(anchor, scroll(list), 0.001f)
            assertEquals(sheetTop, top("navigation-viewport"), 0.5f)
            assertEquals(tabsTop, top("knowledge-tabs"), 0.5f)
            assertEquals(root.height.toFloat(), compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        }
        capture("navigation-sheet-with-app-theme.png")
    }

    @Test fun richProfilesKeepRealSheetAnchorsStableAndAliasSearchLocatesEvidence() {
        val evidence = CharacterEvidence(1, fact)
        val rich = guide.copy(characters = (0 until 40).map { index -> BookCharacter("人物$index", listOf(evidence),
            attributes = listOf(BookCharacterAttribute(CharacterAttributeKind.ALIAS, "别名$index", evidence),
                BookCharacterAttribute(CharacterAttributeKind.AGE, "十八岁", evidence),
                BookCharacterAttribute(CharacterAttributeKind.GENDER, "男", evidence)),
            relationships = listOf(BookCharacterRelationship("同伴$index", "朋友", evidence))) })
        val live = mutableStateOf(state.copy(characters = BookCharactersSnapshot(VisibleBookCharacters(personEntry, rich))))
        var located = 0
        show(tab = 2, input = { live.value }, actions = ReaderKnowledgeActions(locateCharacter = { _, _ -> located++ }))
        val sheet = top("navigation-viewport")
        val tabs = top("knowledge-tabs")
        val list = compose.onNodeWithTag("characters-list")
        list.performScrollToIndex(0)
        compose.onNodeWithTag("relation-人物0-同伴0").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, located)
        capture("characters-rich-relations.png")
        repeat(2) { list.performTouchInput { swipeDown(durationMillis = 120) } }
        list.performScrollToIndex(39)
        repeat(2) { list.performTouchInput { swipeUp(durationMillis = 100) } }
        list.performScrollToIndex(12)
        val anchor = scroll("characters-list")
        compose.runOnIdle { live.value = live.value.copy(characterTask = KnowledgeTaskState(active = true, progress = "扫描 20 / 40")) }
        assertEquals(anchor, scroll("characters-list"), .001f)
        selectTab("目录"); selectTab("人物")
        assertEquals(anchor, scroll("characters-list"), .001f)
        assertEquals(sheet, top("navigation-viewport"), .5f)
        assertEquals(tabs, top("knowledge-tabs"), .5f)
        assertEquals(root.height.toFloat(), compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        compose.onNodeWithContentDescription("查找书中人物").performClick()
        compose.onNodeWithTag("character-search").performTextInput("别名37")
        compose.onNodeWithText("查找", substring = false).performClick()
        compose.onNodeWithTag("person-人物37").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1400dp-h960dp-mdpi")
    fun tabletSideNavigationRetainsTabsAndAnchorsDuringRefresh() {
        tabScrollAnchorsSurviveSwitchingAndBackgroundProgressUpdates()
        val pane = compose.onNodeWithTag("navigation-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, pane.left, .5f)
        assertEquals(440f, pane.width, .5f)
        assertEquals(root.height.toFloat(), pane.bottom, 1f)
    }

    @Test @Config(qualifiers = "w900dp-h1200dp-mdpi")
    fun portraitSideNavigationDoesNotMoveAtEitherScrollBoundary() {
        expressiveFlatApplicationKeepsReaderSheetEdgesAndAnchorsStable()
    }

    private fun selectTab(label: String) { compose.onNode(hasText(label) and hasClickAction()).performClick() }
    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun scroll(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun capture(name: String, expectedAccent: Int? = null) {
        compose.waitForIdle()
        compose.runOnIdle {
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File("build/reports/ui-qa/$name").apply { parentFile.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (expectedAccent != null) {
                val pixels = IntArray(image.width * image.height)
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                assertTrue("目录选中页签必须跟随应用主题色", pixels.count { it == expectedAccent } > 30)
            }
            image.recycle()
        }
    }
}
