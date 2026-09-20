package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.*
import com.mozhi.reader.ui.*
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.*
import com.mozhi.reader.feature.reader.*
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.LocalDictionary
import com.mozhi.reader.core.dictionary.VocabularyWord
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1400dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TabletSettingsVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private var narrow by mutableStateOf(false)
    private val state = MutableStateFlow(SettingsUiState(isLoaded = true, appearance = AppearanceSettings(themeMode = ThemeMode.LIGHT)))
    private fun show() {
        val english = mockk<EnglishLearningViewModel>(relaxed = true)
        every { english.state } returns MutableStateFlow(EnglishLearningState(dictionaries = listOf(
            LocalDictionary("oxford", "牛津高阶英汉双解词典", 2), LocalDictionary("classical", "古汉语常用字字典", 1))))
        every { english.readerSettings } returns MutableStateFlow(ReaderSettings(vocabulary = (1..40).map {
            VocabularyWord("serendipity$it", "不期而遇的美好", "A moment of serendipity by the sea.", gloss = "意外之喜", phonetic = "/ˌserənˈdɪpəti/", createdAt = it.toLong())
        }))
        val vm = mockk<SettingsViewModel>(relaxed = true)
        every { vm.uiState } returns state
        every { vm.events } returns emptyFlow()
        every { vm.setThemeMode(any()) } answers { state.value = state.value.copy(appearance = state.value.appearance.copy(themeMode = firstArg())) }
        compose.setContent {
            root = LocalView.current.rootView
            val settings by state.collectAsState()
            MoReadTheme(settings.appearance) {
                MoReadBackdrop {
                Box(if (narrow) Modifier.width(500.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    MoReadWindowLayout { width ->
                        val expanded = width == MoReadWindowWidth.EXPANDED
                        val nav = rememberNavController()
                        val entry by nav.currentBackStackEntryAsState()
                        val route = entry?.destination?.route
                        MoReadNavigationHost(nav) {
                            rootComposable("bookshelf", expanded) { Text("书库") }
                            rootComposable("settings", expanded) {
                                if (expanded) SettingsDetailPane(true) { ReadingAppearanceSettingsScreen({}, { nav.navigate("font-library") }, {}, vm) }
                                else SettingsScreen(PaddingValues(), { nav.navigate("settings-reading") }, {}, {}, { nav.navigate("settings-ai") }, {}, {}, {}, vm,
                                    onOpenDictionaries = { nav.navigate("settings-dictionaries") }, onOpenVocabulary = { nav.navigate("settings-vocabulary") })
                            }
                            settingsComposable("settings-reading", nav) { ReadingAppearanceSettingsScreen({ nav.popBackStack() }, { nav.navigate("font-library") }, {}, vm) }
                            settingsComposable("settings-ai", nav) { AiAndCompanionSettingsScreen({ nav.popBackStack() }, {}, {}, {}, {}, {}, {}, {}, {}, vm) }
                            settingsComposable("font-library", nav) { MoReadSecondaryPage("字体库", { nav.popBackStack() }) { item { Text("已导入字体") } } }
                            settingsComposable("settings-dictionaries", nav) { DictionaryManagerPage({ nav.popBackStack() }, english) }
                            settingsComposable("settings-vocabulary", nav) { VocabularyPage(onBack = { nav.popBackStack() }, viewModel = english) }
                        }
                        if (expanded && settingsDestination(route) != null) TabletSettingsSidebar(route, { destination ->
                            nav.selectSettingsDestination(destination)
                        }, { nav.popBackStack("bookshelf", false) })
                        LaunchedEffect(Unit) { nav.navigate("settings") }
                    }
                }
                }
            }
        }
    }

    @Test fun categoriesKeepTheirScrollAndWindowNarrowingKeepsTheOpenDetail() {
        show()
        compose.onNodeWithTag("settings-sidebar").assertIsDisplayed()
        capture("tablet-settings-appearance.png")
        compose.onNodeWithText("夜间").performClick()
        assertEquals(ThemeMode.DARK, state.value.appearance.themeMode)
        capture("tablet-settings-dark.png")
        compose.onNodeWithText("日间").performClick()
        compose.onNodeWithText("伴读与联网").performClick()
        compose.onNodeWithText("显示伴读 Token 用量").performScrollTo().assertIsDisplayed()
        val anchor = scroll()
        capture("tablet-settings-companion.png")
        sidebar("阅读与外观").performClick()
        sidebar("伴读与联网").performClick()
        assertEquals(anchor, scroll(), .01f)
        compose.runOnIdle { narrow = true }
        compose.onNodeWithTag("settings-sidebar").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithText("显示伴读 Token 用量").assertExists()
    }

    @Test @Config(qualifiers = "w900dp-h1200dp-mdpi")
    fun portraitKeepsCategoryAndDetailUsableAndNestedEditorsHaveBack() {
        show()
        capture("tablet-settings-portrait.png")
        compose.onNodeWithText("字体库").performScrollTo().performClick()
        compose.onNodeWithTag("settings-sidebar").assertIsDisplayed()
        compose.onNodeWithText("已导入字体").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("字体库").assertIsDisplayed()
    }

    @Test fun dictionaryAndVocabularyBelongToSettingsAndRestoreTheirStateAcrossCategories() {
        show()
        sidebar("词典管理").performClick().assertIsSelected()
        compose.onNodeWithText("牛津高阶英汉双解词典").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").assertDoesNotExist()
        compose.onNodeWithTag("navigation-sheet").assertDoesNotExist()
        capture("tablet-settings-dictionaries.png")
        sidebar("生词本").performClick().assertIsSelected()
        compose.onNodeWithTag("vocabulary-search").performTextInput("serendipity4")
        capture("tablet-settings-vocabulary.png")
        sidebar("词典管理").performClick()
        sidebar("生词本").performClick().assertIsSelected()
        compose.onNodeWithTag("vocabulary-search").assertTextContains("serendipity4")
        compose.onNodeWithTag("vocabulary-search").performTextClearance()
        compose.onNodeWithTag("secondary-page-list").performScrollToIndex(12)
        val anchor = scroll()
        sidebar("词典管理").performClick()
        sidebar("生词本").performClick()
        assertEquals(anchor, scroll(), .01f)
        compose.runOnIdle { narrow = true }
        compose.onNodeWithTag("settings-sidebar").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed()
        compose.onNodeWithTag("vocabulary-search-capsule").assertIsDisplayed()
    }

    private fun sidebar(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("settings-sidebar")))
    private fun scroll() = compose.onNodeWithTag("secondary-page-list").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("build/reports/tablet-ui/secondary/$name").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
