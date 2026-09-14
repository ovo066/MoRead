package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mozhi.reader.ai.provider.AiModelDraft
import com.mozhi.reader.ai.search.WebSearchProvider
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.MoReadTheme
import com.mozhi.reader.ui.theme.ThemeMode
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
class AiSettingsVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private val providers = listOf(
        AiProviderEntity(1, "OpenRouter", "https://openrouter.ai/api/v1", "", AiProviderType.CHAT, adapter = AiProviderAdapter.OPENROUTER, createdAt = 0),
        AiProviderEntity(2, "DeepSeek", "https://api.deepseek.com", "", AiProviderType.CHAT, adapter = AiProviderAdapter.DEEPSEEK, createdAt = 0)
    )
    private val models = listOf(
        AiModelEntity(1, 1, "anthropic/claude-sonnet-4", createdAt = 0),
        AiModelEntity(2, 1, "google/gemini-2.5-pro", createdAt = 0),
        AiModelEntity(3, 2, "deepseek-chat", extraJson = "{\"temperature\":0.7,\"max_tokens\":2048}", createdAt = 0)
    )
    private val state = SettingsUiState(isLoaded = true, providers = providers, models = models,
        assignments = mapOf(ModelRole.CHAT to 1L, ModelRole.CHEAP to 3L))

    private fun show(dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT), content)
        }
    }

    @Test fun providerCardsAndAssignmentPickerShowModelIdentities() {
        var selection: Pair<ModelRole, Long?>? = null
        show { AiServiceContent(state, {}, {}, { role, id -> selection = role to id }) }
        compose.onNodeWithText("模型供应商").assertIsDisplayed()
        capture("ai-services.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("主动段评"))
        capture("model-assignments.png")
        compose.onNodeWithText("主动段评").performClick()
        compose.onNodeWithText("google/gemini-2.5-pro").performClick()
        assertEquals(ModelRole.PROACTIVE_ANNOTATION to 2L, selection)
    }

    @Test fun modelSettingsHaveIconsAndEditableParametersWithoutJson() {
        var saved: AiModelDraft? = null
        show { ModelEditorContent(providers[1], models[2], {}, { saved = it }, null) }
        compose.onNodeWithContentDescription("DeepSeek 模型图标").assertIsDisplayed()
        compose.onNodeWithText("保存模型").assertIsDisplayed()
        capture("model-settings.png")
        compose.onNodeWithText("温度").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("0.7")).performTextReplacement("0.5")
        compose.onNodeWithText("确定").performClick()
        compose.onNodeWithText("保存模型").performClick()
        assertEquals("0.5", parameterValue(requireNotNull(saved).extraJson, "temperature"))
        assertEquals("2048", parameterValue(requireNotNull(saved).extraJson, "max_tokens"))
    }

    @Test fun invalidExplicitAssignmentDoesNotAppearToFollowCheap() {
        var selection: Pair<ModelRole, Long?>? = null
        show { ModelAssignmentCard(providers, models,
            mapOf(ModelRole.CHEAP to 3L, ModelRole.PROACTIVE_ANNOTATION to 999L),
            { role, id -> selection = role to id }) }
        compose.onNodeWithText("已分配模型不可用").assertIsDisplayed()
        compose.onNodeWithText("主动段评").performClick()
        compose.onNodeWithText("跟随批量任务（cheap）").performClick()
        assertEquals(ModelRole.PROACTIVE_ANNOTATION to null, selection)
    }

    @Test fun providerConfigurationUsesStructuredRows() {
        show { ProviderForm(ProviderDetailState(providers[1], listOf(models[2])), {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("保存配置").assertIsDisplayed()
        capture("provider-settings.png")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("deepseek-chat"))
        compose.onNodeWithContentDescription("DeepSeek 模型图标").assertIsDisplayed()
    }

    @Test fun searchEngineCardsAreSelectableAndHaveDistinctIcons() {
        var selected by mutableStateOf(WebSearchProvider.FIRECRAWL)
        show { MoReadSecondaryPage("网络搜索", {}, subtitle = "选择搜索引擎，让伴读查资料、读网页。") {
            item { SearchEngineChoices(selected, mapOf(WebSearchProvider.FIRECRAWL to true)) { selected = it } }
        } }
        capture("search-engines.png")
        compose.onNodeWithText("Tavily").performClick()
        assertEquals(WebSearchProvider.TAVILY, selected)
        compose.onNode(hasText("Tavily") and isSelectable()).assertIsSelected()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun longModelNamesRemainUsableOnSmallDarkScreens() {
        show(dark = true) { ModelEditorContent(providers[0], models[0].copy(modelName = "anthropic/claude-sonnet-4-long-context-model"), {}, {}, null) }
        compose.onNodeWithContentDescription("Claude 模型图标").assertIsDisplayed()
        compose.onNodeWithText("保存模型").assertIsDisplayed().assertIsEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("温度"))
        compose.onNodeWithText("温度").assertIsDisplayed()
        capture("model-settings-small-dark.png")
    }

    private val correctedBrands get() = listOf(
        SettingChoice("glm", "GLM-4.6", "智谱 · Z.ai", brand = modelBrand("glm-4.6")),
        SettingChoice("minimax", "MiniMax-M2.7", "MiniMax", brand = modelBrand("minimax-m2.7")),
        SettingChoice("gemini", "gemini-3.1-flash-lite", "Gemini", brand = modelBrand("gemini-3.1-flash-lite")),
        SettingChoice("ark", "火山方舟", "OpenAI 兼容", brand = providerBrand(AiProviderAdapter.CUSTOM, "火山方舟")),
        SettingChoice("silicon", "硅基流动", "OpenAI 兼容", brand = providerBrand(AiProviderAdapter.CUSTOM, "硅基流动"))
    )

    @Test fun correctedBrandShapesAndColorsInTheRealSelectionDialog() {
        show { SettingChoiceDialog("模型与供应商", correctedBrands, "", {}, {}) }
        compose.onNodeWithText("gemini-3.1-flash-lite").assertIsDisplayed()
        capture("ai-icons-corrected.png", dialog = true)
    }

    @Test fun correctedBrandShapesRemainVisibleInDarkMode() {
        show(dark = true) { SettingChoiceDialog("模型与供应商", correctedBrands, "", {}, {}) }
        compose.onNodeWithText("MiniMax-M2.7").assertIsDisplayed()
        capture("ai-icons-corrected-dark.png", dialog = true)
    }

    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle {
            val content = if (dialog) requireNotNull(ShadowDialog.getLatestDialog().window).decorView else root
            val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
            content.draw(Canvas(bitmap))
            val file = File("build/reports/ui-qa/$name").apply { parentFile.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
