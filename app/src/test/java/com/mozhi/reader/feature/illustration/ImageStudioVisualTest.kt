package com.mozhi.reader.feature.illustration

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.knowledge.BookCharacter
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.entity.ImageStyleTemplateEntity
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageStudioVisualTest {
    @get:Rule val compose = createComposeRule()
    private val look = LookSpec("shen-look", "shen", "沈砚", 0,
        "黑色长发，半束，眉目清冷，高而清瘦；墨色斗篷，内着青灰长衫，左腕系一截旧红绳。",
        "1boy, long_hair, black_hair, brown_eyes, tall, slim, black_cloak, grey_robe, red_string")
    private fun ready() = StudioState(bookId = 1, title = "雪夜行", progress = 30, page = StudioPage.GENERATE,
        loading = false, configured = true, backend = "NovelAI", source = "她推开窗，雪落在她的红围巾上，也落在楼下那人的肩头。苏晚低头看了很久，才认出那是三年前送她出城的书生。",
        chapterIndex = 11, people = listOf(BookCharacter("沈砚", emptyList(), sourceName = "shen"), BookCharacter("苏晚", emptyList(), sourceName = "su")),
        looks = listOf(look), selectedCast = listOf("shen", "su"), count = 2,
        capabilities = ImageCapabilities(maxReferences = 3, maxCharacterReferences = 1, perCharacterPrompt = true, seed = true, tags = true, vibe = true, exclusiveCharacterAndStyle = true))

    @OptIn(ExperimentalMaterial3Api::class)
    private fun sheet(dark: Boolean, state: () -> StudioState, actions: StudioActions = StudioActions(), dismiss: () -> Unit = {}) {
        compose.setContent { MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
            MoReadBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                ImageGenerationPanel(state(), actions, Modifier.fillMaxWidth().fillMaxHeight(.9f))
            }
        } }
        compose.waitForIdle()
    }

    @Test fun lightPanelMatchesHierarchyAndKeepsGenerateReachable() {
        var generated = 0
        sheet(false, { ready() }, StudioActions(generate = { generated++ }))
        compose.onNodeWithText("生成 2 张").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("出场").assertIsDisplayed()
        assertEquals(1, generated)
        screenshot("panel-light")
    }
    @Test fun darkPanelHasReadableControls() { sheet(true, { ready() }); screenshot("panel-dark") }
    @Test fun missingServiceHasAnActionAndNeverEnablesGeneration() {
        sheet(false, { ready().copy(configured = false) })
        compose.onNodeWithText("尚未设置生图服务").assertIsDisplayed()
        compose.onNodeWithText("生成 2 张").assertIsNotEnabled()
        screenshot("no-service")
    }
    @Test fun realSheetEdgesAndProgressRefreshPreservePositionAndAnchor() {
        var state by mutableStateOf(ready())
        var dismissed = 0
        sheet(false, { state }, dismiss = { dismissed++ })
        val header = compose.onNodeWithTag("image-panel-header")
        val list = compose.onNodeWithTag("image-panel-list")
        val top = header.fetchSemanticsNode().boundsInRoot.top
        list.performTouchInput { down(center); moveBy(Offset(0f, 120f)) }
        assertEquals(top, header.fetchSemanticsNode().boundsInRoot.top, .5f)
        list.performTouchInput { up() }
        repeat(4) { list.performTouchInput { swipeUp(durationMillis = 100) } }
        assertEquals(top, header.fetchSemanticsNode().boundsInRoot.top, .5f)
        val anchor = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle { state = state.copy(generatedCount = 1, gallery = emptyList()) }
        assertEquals(anchor, list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .01f)
        repeat(5) { list.performTouchInput { swipeDown(durationMillis = 100) } }
        assertEquals(top, header.fetchSemanticsNode().boundsInRoot.top, .5f)
        assertEquals(0, dismissed)
        val root = compose.onAllNodes(isRoot()).fetchSemanticsNodes().maxBy { it.boundsInRoot.height }.boundsInRoot
        val panel = compose.onNodeWithTag("image-generation-panel").fetchSemanticsNode().boundsInRoot
        assertTrue("sheet bottom must meet window bottom: $panel / $root", root.bottom - panel.bottom < 32f)
        screenshot("sheet-boundaries")
        header.performTouchInput { swipe(Offset(centerX, 20f), Offset(centerX, 760f), durationMillis = 400) }
        compose.waitUntil(5000) { dismissed > 0 }
    }
    @Test fun styleCardsAndSaveActionHaveLightThemePreview() = page(ready().copy(page = StudioPage.STYLE), false, "style-light")
    @Test fun diyStartsOnAnIndependentPageAndSavesANewNamedTemplate() {
        var state by mutableStateOf(ready().copy(page = StudioPage.STYLE))
        var saved: ImageStyleTemplateEntity? = null
        val actions = StudioActions(
            newStyle = { state = state.copy(page = StudioPage.STYLE_EDITOR, styleEditorName = "",
                styleEditorDraft = StyleSpec(presetId = "custom", natural = "", tags = "")) },
            styleEditorName = { state = state.copy(styleEditorName = it) },
            styleEditor = { state = state.copy(styleEditorDraft = it) },
            saveStyleEditor = { saved = ImageStyleTemplateEntity("new", state.styleEditorName,
                ImageRecipeCodec.json.encodeToString(StyleSpec.serializer(), state.styleEditorDraft), 1) })
        compose.setContent { MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT)) {
            MoReadPageDialog({}) { ImageStudioPage(state, actions) }
        } }
        compose.onNodeWithTag("secondary-page-list").performScrollToNode(hasTestTag("new-style-entry"))
        compose.onNodeWithTag("new-style-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("new-style-blank").assertIsDisplayed().performClick()
        compose.onNodeWithTag("style-editor-name").assertIsDisplayed()
        compose.onNodeWithText("水彩插画").assertDoesNotExist()
        compose.onNodeWithText("保存画风").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("style-editor-name"))).performTextInput("柔和绘本")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("style-editor-description"))).performTextInput("暖色纸张、柔和线条、低饱和配色")
        screenshot("diy-light")
        compose.onNodeWithText("保存画风").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals("柔和绘本", saved!!.name)
        assertEquals("暖色纸张、柔和线条、低饱和配色", ImageRecipeCodec.json.decodeFromString<StyleSpec>(saved!!.specJson).natural)
    }
    @Test fun savedTemplateHasASeparateEditEntryAndCanStillBeSelected() {
        val style = StyleSpec(presetId = "custom", natural = "warm ink on paper", seed = 42)
        val template = ImageStyleTemplateEntity("template", "暮色水墨 · 我的长篇插图方案", ImageRecipeCodec.json.encodeToString(StyleSpec.serializer(), style), 1)
        var edited: ImageStyleTemplateEntity? = null
        var selected: ImageStyleTemplateEntity? = null
        page(ready().copy(page = StudioPage.STYLE, styleTemplates = listOf(template)), false, "style-with-template",
            StudioActions(editStyle = { edited = it }, applyTemplate = { selected = it }))
        compose.onNodeWithTag("secondary-page-list").performScrollToNode(hasTestTag("style-template-template"))
        screenshot("templates-in-grid")
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("编辑画风").performClick()
        assertEquals(template, edited)
        compose.onNodeWithText(template.name).performClick()
        assertEquals(template, selected)
    }
    @Test fun diyDarkLongTextKeepsSaveReachableAndAdvancedFieldsCollapsed() {
        page(ready().copy(page = StudioPage.STYLE_EDITOR, styleEditorName = "低饱和的雨夜水彩与复古侦探插图方案",
            styleEditorDraft = StyleSpec(presetId = "custom", natural = "雨夜街灯下的水彩纸张质感，安静细腻。".repeat(12))), true, "diy-dark")
        compose.onNodeWithText("保存画风").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("NovelAI 画师串 / 标签").assertDoesNotExist()
    }
    @Test fun characterLookHasVersioningAndDarkThemePreview() = page(ready().copy(page = StudioPage.LOOK,
        lookDraft = look, looks = listOf(look, look.copy(id = "future", sinceChapter = 80, natural = "未来白发疤痕"))), true, "look-dark")
    @Test fun futureAppearanceNeverRendersInTheLookTimeline() {
        page(ready().copy(page = StudioPage.LOOK, lookDraft = look,
            looks = listOf(look, look.copy(id = "future", sinceChapter = 80, natural = "未来白发疤痕"))), false, "look-light")
        compose.onNodeWithTag("secondary-page-list").performScrollToNode(hasText("后文还有 1 次变化"))
        compose.onNodeWithText("未来白发疤痕", substring = true).assertDoesNotExist()
        compose.onNodeWithText("保存").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "en-w412dp-h700dp-mdpi")
    fun englishLongSceneCannotCoverFooter() {
        sheet(false, { ready().copy(source = "A long scene beside the snowy window. ".repeat(25)) })
        compose.onNodeWithText("Generate 2 images").assertIsDisplayed()
        screenshot("panel-english-long")
    }
    @Test fun galleryEmptyState() = page(ready().copy(page = StudioPage.GALLERY), false, "gallery-empty")
    @Test fun peopleListShowsReferenceStatus() = page(ready().copy(page = StudioPage.PEOPLE), false, "people")
    @Test fun styleGridDark() = page(ready().copy(page = StudioPage.STYLE), true, "style-dark")
    @Test fun resultPanelOffersRerollAdjustAndKeep() {
        sheet(false, { ready().copy(count = 2, candidates = listOf(
            com.mozhi.reader.core.database.entity.IllustrationEntity(bookId = 1, prompt = "scene", imagePath = "missing.png", createdAt = 0))) })
        compose.onNodeWithText("收入插图廊").assertIsDisplayed()
        compose.onNodeWithText("同配方重来").assertIsDisplayed()
        screenshot("panel-result")
    }
    private fun page(state: StudioState, dark: Boolean, name: String, actions: StudioActions = StudioActions()) {
        compose.setContent { MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)) {
            MoReadPageDialog({}) { ImageStudioPage(state, actions) }
        } }
        compose.waitForIdle()
        when (state.page) {
            StudioPage.STYLE -> compose.onNodeWithText("试一张").assertIsDisplayed()
            StudioPage.STYLE_EDITOR -> compose.onNodeWithText("保存画风").assertIsDisplayed()
            StudioPage.LOOK -> compose.onNodeWithText("保存").assertIsDisplayed()
            else -> Unit
        }
        screenshot(name)
    }
    private fun screenshot(name: String) = compose.runOnIdle {
        val view = WindowInspector.getGlobalWindowViews().last { it.isShown && it.width > 0 }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/outputs/image-consistency-visual/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
