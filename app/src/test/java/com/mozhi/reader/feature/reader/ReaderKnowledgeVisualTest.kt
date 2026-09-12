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
        actions: ReaderKnowledgeActions = ReaderKnowledgeActions(), onDismiss: () -> Unit = {}) {
        compose.setContent {
            MoReadTheme {
                val palette = readerPalette(if (dark) ReaderSettings(theme = ReaderTheme.DARK) else ReaderSettings(), dark)
                Box(Modifier.fillMaxSize().background(palette.background)) {
                    NavigationSheet(onDismiss, palette.glassStrong, palette.onBackground, palette.scrim) {
                        val view = LocalView.current
                        SideEffect { root = view.rootView }
                        ReaderKnowledgePages(input(), chapters, emptyList(), 1, palette, { _, _ -> }, onDismiss, actions, tab)
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

    @Test fun wholeBookCharactersUseCompactCardsAndExpandableEvidenceInDarkTheme() {
        show(tab = 2, dark = true)
        compose.onNodeWithText("林舟", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("小满", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(fact.text).assertDoesNotExist()
        compose.onNodeWithText("更新全书人物").assertIsDisplayed()
        capture("chapter-characters-dark.png")
        compose.onNodeWithText("展开 1 条资料").performClick()
        compose.onNodeWithText(fact.text).assertIsDisplayed()
        compose.onNodeWithContentDescription("书籍资料说明").performClick()
        compose.onNodeWithText("包括还没读到的章节", substring = true).assertIsDisplayed()
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

    private fun selectTab(label: String) { compose.onNode(hasText(label) and hasClickAction()).performClick() }
    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
    private fun scroll(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val file = File("build/reports/ui-qa/$name").apply { parentFile.mkdirs() }
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }
}
