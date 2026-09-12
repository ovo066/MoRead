package com.mozhi.reader.feature.companion

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.companion.*
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.AutoReadSettings
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.ReaderAutoReadSheet
import com.mozhi.reader.feature.reader.readerPalette
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.theme.MoReadTheme
import io.mockk.*
import java.io.File
import java.time.LocalDate
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
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingAndCompanionVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private val book = BookEntity(id = 1, title = "雨夜里的灯塔", author = "示例作者", coverPath = null, epubPath = "",
        sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 8, maxReachedChapterIndex = 2, maxReachedCharOffset = 240)
    private val persona = PersonaEntity(id = 3, name = "知秋", personality = "慢慢读，也慢慢聊。", isRoleplay = true, createdAt = 1)
    private val scopes = listOf(LibraryBookScope(1, book.title, "a".repeat(64), 2, 240), LibraryBookScope(2, "远行笔记", "b".repeat(64), 0, 80))

    @Test fun sharedMessageEditorAcceptsChangesBeforeConfirmation() {
        val message = MessageEntity(id = 1, conversationId = 1, role = "user", content = "原来的问题", createdAt = 1)
        var saved: String? = null
        show {
            var text by remember { mutableStateOf(message.content) }
            Box(Modifier.fillMaxSize()) {
                com.mozhi.reader.feature.reader.EditCompanionMessageDialog(message, text, { text = it }, {}) { _, value -> saved = value }
            }
        }
        compose.onNodeWithText("编辑并重新发送").assertIsDisplayed()
        capture("companion-message-editor.png")
        compose.onNode(hasSetTextAction()).performTextReplacement("修改后的问题")
        compose.onNodeWithText("保存").performClick()
        assertEquals("修改后的问题", saved)
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MoReadTheme { content() }
        }
    }

    private fun vm(session: LibraryChatSession, messages: LibraryChatMessages = LibraryChatMessages()): LibraryCompanionViewModel {
        val vm = mockk<LibraryCompanionViewModel>(relaxed = true)
        every { vm.session } returns MutableStateFlow(session)
        every { vm.messages } returns MutableStateFlow(messages)
        every { vm.catalog } returns MutableStateFlow(LibraryChatCatalog(listOf(book, book.copy(id = 2, title = "远行笔记")), listOf(persona), 3))
        every { vm.conversations } returns MutableStateFlow(listOfNotNull(session.conversation))
        every { vm.events } returns emptyFlow()
        return vm
    }

    @Test fun autoReadUsesAnActualCompactBottomSheetRatherThanASeparatePage() {
        show {
            val palette = readerPalette(ReaderSettings(), false)
            Column(Modifier.fillMaxSize().background(palette.background).padding(28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text("雨夜里的灯塔", style = MaterialTheme.typography.titleLarge, color = palette.onBackground)
                repeat(8) { Text("雨停后，他沿着海边的小路慢慢走着。远处亮起一盏灯，照着还没有读完的故事。", color = palette.onBackground) }
            }
            ReaderAutoReadSheet(AutoReadSettings(), false, false, palette, {}, {}, {})
        }
        compose.onNodeWithText("匀速滚动").assertIsDisplayed()
        compose.onNodeWithText("导读线").assertIsDisplayed()
        compose.onNodeWithText("开始阅读").assertIsDisplayed()
        val top = compose.onNodeWithText("自动阅读").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Settings should occupy less than half the screen; top=$top", top > root.height / 2f)
        capture("auto-read-scroll.png")
        compose.onNodeWithText("定时翻页").performClick()
        compose.onNodeWithText("每 15 秒翻一页").assertIsDisplayed()
        compose.onNodeWithText("导读线").assertDoesNotExist()
        capture("auto-read-paged.png")
    }

    @Test fun companionHomeUsesAFloatingChatEntryAndDiscreetStatisticsAction() {
        val vm = mockk<CompanionViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(CompanionUiState(listOf(persona,
            persona.copy(id = 4, name = "拾光", personality = "把读到的好句子，留给以后的自己。")), 3, mapOf(3L to 18L), loaded = true))
        var opened = 0
        var stats = 0
        show { MoReadBackdrop { CompanionScreen(PaddingValues(), {}, {}, { opened++ }, { stats++ }, vm) } }
        compose.onNodeWithContentDescription("书库伴读").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("陪伴足迹").performClick()
        assertEquals(1, opened); assertEquals(1, stats)
        capture("companion-home.png")
    }

    @Test fun noBookSelectionIsRequiredToSendAChatMessage() {
        val vm = vm(LibraryChatSession(draft = "今天读书有点累。"))
        show { LibraryCompanionScreen({}, {}, {}, vm) }
        compose.onNodeWithText("书库伴读").assertIsDisplayed()
        compose.onNodeWithText("选书").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").performClick()
        verify(exactly = 1) { vm.send() }
        capture("library-companion-empty.png")
    }

    @Test fun optionalBookPickerDoesNotSendOrStartIndexing() {
        val vm = vm(LibraryChatSession(selectedBooks = setOf(1)))
        show { LibraryCompanionScreen({}, {}, {}, vm) }
        compose.onNodeWithText("1 本重点").performClick()
        compose.onNodeWithText("重点讨论").assertIsDisplayed()
        compose.onNodeWithText("可以不选，伴读也能按需查书。").assertIsDisplayed()
        compose.onNodeWithText("雨夜里的灯塔").performClick()
        verify(exactly = 1) { vm.toggleBook(1) }
        verify(exactly = 0) { vm.send() }
        capture("library-companion-picker.png")
    }

    @Test fun libraryChatSharesReaderBubblesAndExplicitCitationVerification() {
        val conversation = ConversationEntity(id = 7, bookId = null, personaId = 3, title = "比较两种远行", type = LibraryBookScopes.CONVERSATION_TYPE,
            bookScopesJson = LibraryBookScopes.encode(scopes), createdAt = 1)
        val messages = LibraryChatMessages(listOf(
            MessageEntity(id = 1, conversationId = 7, role = "user", content = "对照一下两本书里我的笔记。", createdAt = 1),
            MessageEntity(id = 2, conversationId = 7, role = "assistant", content = "你在两本书里都留意到**等待与出发**。\n\n《雨夜里的灯塔》里这句让我印象很深：〔书籍#1 第2章〕「他在门口停下脚步。」\n\n或许你喜欢的，不只是远行，还有出发前那一刻的犹豫。", createdAt = 2, clientRoundId = "r1")
        ), conversationId = 7)
        val vm = vm(LibraryChatSession(conversation, scopes, setOf(1, 2)), messages)
        show { LibraryCompanionScreen({}, {}, {}, vm) }
        compose.onNodeWithText("雨夜里的灯塔 · 第2章 ↗").assertIsDisplayed().performClick()
        verify(exactly = 1) { vm.locate(any()) }
        capture("library-companion-chat.png")
        compose.onNodeWithText("对照一下两本书里我的笔记。").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("复制").assertIsDisplayed()
        compose.onNodeWithContentDescription("从这里开分支").assertIsDisplayed()
        compose.onNodeWithContentDescription("编辑").assertIsDisplayed().performClick()
        compose.onNodeWithText("编辑并重新发送").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("对照一下两本书里我的笔记。")).performTextReplacement("换个角度聊这两本书。")
        compose.onNodeWithText("保存").performClick()
        verify(exactly = 1) { vm.editMessage(1, "换个角度聊这两本书。") }
    }

    @Test fun organizationPreviewRequiresAnExplicitDecision() {
        val conversation = ConversationEntity(id = 7, bookId = null, personaId = 3, title = "整理书架", type = LibraryBookScopes.CONVERSATION_TYPE, createdAt = 1)
        val proposal = LibraryOrganizationMessage(9, LibraryOrganizationPlan("plan", listOf(
            LibraryOrganizationChange(1, book.title, listOf(LibraryOrganizationTag(1, "待读")), 2, "书架", addTags = listOf("小说"), removeTags = listOf("待读"), groupName = "文学"),
            LibraryOrganizationChange(2, "远行笔记", emptyList(), null, "", addTags = listOf("随笔", "旅行"), groupName = "散文")
        )))
        val vm = vm(LibraryChatSession(conversation), LibraryChatMessages(conversationId = 7, organizationPlans = listOf(proposal)))
        show { LibraryCompanionScreen({}, {}, {}, vm) }
        compose.onNodeWithText("整理方案 · 2 本书").performClick()
        compose.onNodeWithText("确认整理").assertIsDisplayed()
        compose.onNodeWithText("书架 → 文学").assertIsDisplayed()
        verify(exactly = 0) { vm.confirmOrganization(any(), any()) }
        capture("library-organization-preview.png")
        compose.onNodeWithText("取消方案").performClick()
        verify(exactly = 1) { vm.confirmOrganization(9, false) }
    }

    @Test fun libraryRerollRequiresConfirmationAndBranchesUseTheChosenMessage() {
        val conversation = ConversationEntity(id = 7, bookId = null, personaId = 3, title = "聊聊这本书", type = LibraryBookScopes.CONVERSATION_TYPE, createdAt = 1)
        val messages = LibraryChatMessages(listOf(
            MessageEntity(id = 1, conversationId = 7, role = "user", content = "说说这一章。", createdAt = 1),
            MessageEntity(id = 2, conversationId = 7, role = "assistant", content = "这一章从一封来信开始。", createdAt = 2)
        ), conversationId = 7)
        val vm = vm(LibraryChatSession(conversation), messages)
        show { LibraryCompanionScreen({}, {}, {}, vm) }
        compose.onNodeWithText("这一章从一封来信开始。").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("重新生成").performClick()
        compose.onNodeWithText("重新生成回复？").assertIsDisplayed()
        verify(exactly = 0) { vm.reroll(any()) }
        compose.onNodeWithText("取消").performClick()
        verify(exactly = 0) { vm.reroll(any()) }
        compose.onNodeWithText("这一章从一封来信开始。").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("重新生成").performClick()
        compose.onNodeWithText("重新生成").performClick()
        verify(exactly = 1) { vm.reroll(2) }
        compose.onNodeWithText("说说这一章。").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("从这里开分支").performClick()
        verify(exactly = 1) { vm.branchFrom(1) }
    }

    @Test fun statisticsFocusesOnSharedReadingAndChatRatherThanTokenBilling() {
        val vm = mockk<CompanionStatsViewModel>(relaxed = true)
        val today = LocalDate.now()
        every { vm.selection } returns MutableStateFlow(CompanionStatsSelection())
        every { vm.statistics } returns MutableStateFlow(CompanionStatistics(rounds = 28, activeDays = 9, books = 4, conversations = 6,
            firstChatDate = today.minusDays(63), readingDurationMs = 27_360_000, chatCharacters = 18_420,
            roundsByDay = (0L..8L).associate { today.minusDays(it * 3) to 3 }))
        show { CompanionStatsScreen({}, vm) }
        compose.onNodeWithText("相伴第 64 天").assertIsDisplayed()
        compose.onNodeWithText("一起读过").assertIsDisplayed()
        compose.onNodeWithText("聊了").assertIsDisplayed()
        compose.onNodeWithText("1.8 万字").assertIsDisplayed()
        compose.onNodeWithText("Token", substring = true).assertDoesNotExist()
        capture("companion-statistics.png")
        compose.onNodeWithText("全部 ▾").performClick()
        compose.onNodeWithText("书库伴读").performClick()
        verify(exactly = 1) { vm.selectScope(CompanionStatsScope.LIBRARY) }
        compose.onNodeWithContentDescription("统计说明").performClick()
        compose.onNodeWithText("非同步在线计时", substring = true).assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            root.draw(canvas)
            // Sheets live in a dialog window: composite it over the underlying screen.
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { dialog ->
                if (dialog !== root) dialog.draw(canvas)
            }
            val file = File("build/reports/ui-qa/$name").apply { parentFile.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
