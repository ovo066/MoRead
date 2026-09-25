package com.mozhi.reader.feature.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.feature.bookshelf.BookshelfScreen
import com.mozhi.reader.feature.bookshelf.BookshelfUiState
import com.mozhi.reader.feature.bookshelf.BookshelfViewModel
import com.mozhi.reader.feature.companion.CompanionScreen
import com.mozhi.reader.feature.companion.CompanionUiState
import com.mozhi.reader.feature.companion.CompanionViewModel
import com.mozhi.reader.feature.listen.ListenTransportControls
import com.mozhi.reader.ui.MoReadNavigationDock
import com.mozhi.reader.ui.RootDestination
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.*
import dev.chrisbanes.haze.rememberHazeState
import io.mockk.every
import io.mockk.mockk
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

@RunWith(RobolectricTestRunner::class)
// 按默认中文界面断言文案；英文资源由 LocalizationResourcesTest 覆盖。
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private var appearance by mutableStateOf(AppearanceSettings(themeMode = ThemeMode.LIGHT))

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val view = LocalView.current
            SideEffect { root = view.rootView }
            MoReadTheme(appearance) { MoReadBackdrop { content() } }
        }
    }

    private fun choose(preset: ColorSchemePreset, dark: Boolean = false) {
        appearance = appearance.copy(colorScheme = preset, accent = AccentPreset.FOLLOW, customAccentArgb = null,
            surfaceStyle = preset.recommendedSurface, shapeStyle = preset.recommendedShape,
            navStyle = if (preset == ColorSchemePreset.NEUTRAL) NavStyle.Default else appearance.navStyle,
            semanticHarmony = if (preset == ColorSchemePreset.NEUTRAL) SemanticHarmony.Default else appearance.semanticHarmony,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
    }

    private fun showAppearance() = show {
        MoReadSecondaryPage("阅读与外观", {}) {
            item {
                MoReadSection(title = "应用外观", icon = Icons.Outlined.Palette, tone = SemanticSlot.READING) {
                    AppearanceCard(appearance,
                        onThemeModeChange = { appearance = appearance.copy(themeMode = it) },
                        onAccentChange = { appearance = appearance.copy(accent = it, customAccentArgb = null) },
                        onCustomAccent = { appearance = appearance.copy(customAccentArgb = it) },
                        onColorSchemeChange = { choose(it, appearance.themeMode == ThemeMode.DARK) },
                        onSemanticHarmonyChange = { appearance = appearance.copy(semanticHarmony = it) },
                        onSurfaceStyleChange = { appearance = appearance.copy(surfaceStyle = it) },
                        onNavStyleChange = { appearance = appearance.copy(navStyle = it) },
                        onShapeStyleChange = { appearance = appearance.copy(shapeStyle = it) })
                }
            }
        }
    }

    @Test fun appearanceControlsUpdateInPlaceAndExposeAllChoices() {
        showAppearance()
        compose.onNodeWithText("雾蓝").performClick()
        assertEquals(ColorSchemePreset.HAZE_BLUE, appearance.colorScheme)
        compose.onNodeWithContentDescription("强调色：橙").performScrollTo().performClick().assertIsSelected()
        assertEquals(AccentPreset.AMBER, appearance.accent)
        compose.onNodeWithContentDescription("强调色：随方案").performClick().assertIsSelected()
        capture("appearance-haze-blue.png")
        compose.onNodeWithText("统一色相").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("玻璃").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("通栏").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithText("标准").performScrollTo().performClick().assertIsSelected()
        assertEquals(SemanticHarmony.MONO, appearance.semanticHarmony)
        assertEquals(SurfaceStyle.GLASS, appearance.surfaceStyle)
        assertEquals(NavStyle.BAR, appearance.navStyle)
        assertEquals(ShapeStyle.STANDARD, appearance.shapeStyle)
        capture("appearance-dimensions.png")
        compose.onNodeWithText("原版").performScrollTo().performClick().assertIsSelected()
        assertEquals(AppearanceSettings(themeMode = ThemeMode.LIGHT), appearance)
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun smallDarkAppearanceKeepsEveryAccentAndDimensionReachable() {
        choose(ColorSchemePreset.ROSE_DUST, dark = true)
        showAppearance()
        AccentPreset.entries.forEach { preset ->
            compose.onNodeWithContentDescription("强调色：${preset.label}").performScrollTo().performClick().assertIsSelected()
            val bounds = compose.onNodeWithContentDescription("强调色：${preset.label}").fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= 0 && bounds.right <= root.width)
            assertTrue(bounds.width >= 44f && bounds.height >= 44f)
        }
        compose.onNodeWithText("通栏").performScrollTo().performClick()
        compose.onNodeWithText("舒展").performScrollTo().assertIsDisplayed()
        capture("appearance-small-dark.png")
    }

    @Test @Config(sdk = [30]) fun wallpaperChoiceIsHiddenOnOlderAndroid() {
        showAppearance()
        compose.onNodeWithText("跟随壁纸").assertDoesNotExist()
        compose.onNodeWithText("灰粉").performScrollTo().performClick().assertIsSelected()
    }

    @Test fun settingsHomeRendersThePresetFamiliesInBothModes() {
        val vm = mockk<SettingsViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(SettingsUiState(isLoaded = true))
        show { SettingsScreen(PaddingValues(bottom = 124.dp), {}, {}, {}, {}, {}, {}, {}, vm) }
        listOf(ColorSchemePreset.NEUTRAL, ColorSchemePreset.HAZE_BLUE, ColorSchemePreset.SAGE, ColorSchemePreset.ROSE_DUST)
            .forEach { preset ->
                listOf(false, true).forEach { dark ->
                    compose.runOnIdle { choose(preset, dark) }
                    compose.onNodeWithText("阅读与外观").assertIsDisplayed()
                    capture("settings-${preset.name.lowercase()}-${if (dark) "dark" else "light"}.png")
                }
            }
    }

    @Test fun bookshelfAndBarKeepTheLastBookAboveNavigation() {
        choose(ColorSchemePreset.HAZE_BLUE)
        appearance = appearance.copy(navStyle = NavStyle.BAR)
        val books = (1L..18L).map { id -> BookEntity(id = id, title = "海边来信 $id", author = "林间", coverPath = null,
            epubPath = "", sourceType = BookSourceType.EPUB, importedAt = 1, totalChapters = 24) }
        val vm = mockk<BookshelfViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(BookshelfUiState(books = books, allBooks = books,
            totalBooks = books.size, recentBook = books.first(), recentChapterTitle = "第一章 清晨"))
        every { vm.events } returns emptyFlow()
        var route by mutableStateOf(RootDestination.Bookshelf.route)
        show {
            Box(Modifier.fillMaxSize()) {
                BookshelfScreen(contentPadding = PaddingValues(bottom = 124.dp), onOpenBook = {}, onOpenBookDetail = { _, _ -> },
                    onOpenImportPreview = {}, viewModel = vm)
                MoReadNavigationDock(true, rememberHazeState(), false, route, Modifier.align(Alignment.BottomCenter)) { route = it.route }
            }
        }
        capture("bookshelf-haze-blue.png")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("海边来信 18"))
        compose.onNodeWithText("海边来信 18").assertIsDisplayed()
        val bottomBook = compose.onNodeWithText("海边来信 18").fetchSemanticsNode().boundsInRoot
        assertTrue(bottomBook.bottom < root.height - 80)
        compose.onNode(hasText("设置") and isSelectable()).performClick().assertIsSelected()
        assertEquals(RootDestination.Settings.route, route)
        capture("bookshelf-bar-bottom.png")
    }

    @Test fun companionAvatarsFollowTheSelectedColorFamily() {
        choose(ColorSchemePreset.SAGE)
        val people = listOf("知秋", "拾光", "南风", "青禾").mapIndexed { index, name ->
            PersonaEntity(id = index + 1L, name = name, personality = "慢慢读，也慢慢聊。", isRoleplay = true, createdAt = 1)
        }
        val vm = mockk<CompanionViewModel>(relaxed = true)
        every { vm.uiState } returns MutableStateFlow(CompanionUiState(people, 1L, loaded = true))
        show { CompanionScreen(PaddingValues(bottom = 124.dp), {}, {}, {}, {}, vm) }
        compose.onNodeWithText("知秋").assertIsDisplayed()
        capture("companion-sage.png")
        compose.runOnIdle { appearance = appearance.copy(semanticHarmony = SemanticHarmony.MONO) }
        capture("companion-sage-mono.png")
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi")
    fun expressivePlayerControlsFitSmallScreensAndKeepAllActions() {
        choose(ColorSchemePreset.HAZE_BLUE)
        val calls = mutableListOf<String>()
        show {
            Box(Modifier.fillMaxSize().background(Color(0xFF101012)).padding(20.dp), contentAlignment = Alignment.Center) {
                ListenTransportControls(false, { calls += "上一章" }, { calls += "上一段" }, { calls += "播放" },
                    { calls += "下一段" }, { calls += "下一章" })
            }
        }
        listOf("上一章", "上一段", "播放", "下一段", "下一章").forEach { label ->
            compose.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
            val bounds = compose.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot
            assertTrue("$label is clipped", bounds.left >= 0 && bounds.right <= root.width)
        }
        assertEquals(listOf("上一章", "上一段", "播放", "下一段", "下一章"), calls)
        capture("listen-controls-small.png")
    }

    @Test fun readerKeepsPaperSurfaceAndMetricsWhenAppUsesExpressiveFlatTheme() {
        choose(ColorSchemePreset.SAGE)
        var readerMetrics: MoReadMetrics? = null
        var readerSurface: SurfaceStyle? = null
        var appSurface: SurfaceStyle? = null
        show {
            appSurface = surfaceStyle()
            ReaderAppearanceScope {
                readerMetrics = moReadMetrics()
                readerSurface = surfaceStyle()
            }
        }
        compose.runOnIdle {
            assertEquals(SurfaceStyle.FLAT, appSurface)
            assertEquals(SurfaceStyle.GLASS, readerSurface)
            assertEquals(MoReadMetrics.Standard, readerMetrics)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File("build/reports/theme-qa/$name").apply { parentFile.mkdirs() }.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
