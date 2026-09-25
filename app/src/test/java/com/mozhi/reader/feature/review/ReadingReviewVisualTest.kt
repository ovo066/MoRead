package com.mozhi.reader.feature.review

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.inspector.WindowInspector
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.imageDecoderEnabled
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import kotlinx.coroutines.runBlocking
import com.mozhi.reader.feature.reader.DiscussionUiState
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReviewShareTemplate
import com.mozhi.reader.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReviewVisualApplication::class, qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadingReviewVisualTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var root: View
    private var state by mutableStateOf(sample())
    private var opened: ReviewEntry? = null
    private lateinit var imageLoader: ImageLoader

    @OptIn(DelicateCoilApi::class)
    @Before fun loadRealPortrait() {
        val app = ApplicationProvider.getApplicationContext<ReviewVisualApplication>()
        imageLoader = app.newImageLoader(app)
        SingletonImageLoader.setUnsafe(imageLoader)
        val result = runBlocking { imageLoader.execute(ImageRequest.Builder(app)
            .data(File(requireNotNull(state.personas.first().avatarPath))).allowHardware(false).build()) }
        assertTrue("The supplied portrait must decode in the native preview", result is SuccessResult)
    }

    @OptIn(DelicateCoilApi::class)
    @After fun closeImageLoader() { SingletonImageLoader.reset(); if (::imageLoader.isInitialized) imageLoader.shutdown() }

    private fun sample(): ReadingReviewState {
        val portrait = File("build/tmp/review-visual/companion.webp").apply {
            requireNotNull(parentFile).mkdirs()
            requireNotNull(this@ReadingReviewVisualTest.javaClass.getResourceAsStream("/review/companion.webp")).use { input -> outputStream().use { input.copyTo(it) } }
        }
        val persona = reviewTestPersona().copy(avatarPath = portrait.absolutePath)
        val books = listOf(reviewTestBook(), reviewTestBook(2, "雨落书页"))
        val quotes = listOf("他把书翻到有折角的那页，才发现折角是自己三年前留下的。旧物替人记事，这一点作者写得很轻。那些被折起的页角没有改变故事，却留下了读者曾经停留的位置。",
            "鸟都没叫。", "书页的留白，在古书里也叫天头和地脚。它们不属于故事，却能容下读者自己的声音。",
            "信封没有落款，封口却压着一枚银杏叶。那叶片并未干枯，仿佛刚从秋天摘下。",
            "他没有急着拆信。雨声把屋子围成一座小岛。")
        val annotations = (1L..40L).map { id -> reviewTestAnnotation(id, if (id % 3 == 0L) 2 else 1, if (id % 4 == 0L) 7 else null)
            .copy(createdAt = 1790000000000L - id, selectedText = quotes[((id - 1) % quotes.size).toInt()], note = if (id % 3 == 0L) "" else reviewTestAnnotation().note) }
        return ReadingReviewState(reviewEntries(books, annotations, listOf(reviewTestNote()), listOf(persona), true), books, listOf(persona), loading = false)
    }

    private fun show(dark: Boolean = false, scheme: ColorSchemePreset = ColorSchemePreset.NEUTRAL, accent: AccentPreset = AccentPreset.AZURE) {
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, accent = accent, colorScheme = scheme)) {
                ReadingReviewContent(state, {}, { opened = it }, {}, {}, {})
            }
        }
    }

    @Test fun lightMasonrySearchAndSourceFilterAreUsable() {
        show()
        capture("review-light.png")
        selectSource("AI")
        compose.onNodeWithTag("review-avatar-highlight:4", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("review-search-button").performClick()
        compose.onNodeWithTag("review-search").performTextInput("银杏")
        compose.onNodeWithText("搜索", substring = false).performClick()
        assertTrue(compose.onAllNodesWithContentDescription("AI · 阿翎", substring = true).fetchSemanticsNodes().isNotEmpty())
        capture("review-ai-filter.png")
        compose.onAllNodes(hasTestTag("review-card-highlight:4")).onFirst().performClick()
        assertEquals("highlight:4", opened?.key)
        selectSource("MINE")
        compose.onNodeWithTag("review-avatar-highlight:4", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun darkCardsAndListRetainDistinction() {
        show(true)
        capture("review-dark.png")
        compose.onNodeWithTag("review-layout-toggle").performClick()
        capture("review-list-dark.png")
        compose.onNodeWithTag("review-filter-button").assertIsDisplayed()
    }

    @Test fun optionsUseSecondaryPagesAndStayAnchoredToTheTopButton() {
        show()
        compose.onNodeWithTag("review-filter-button").performClick()
        val header = compose.onNodeWithTag("review-options-menu").fetchSemanticsNode().boundsInRoot.top
        val anchor = compose.onNodeWithTag("review-filter-button").fetchSemanticsNode().boundsInRoot
        val menu = compose.onNodeWithTag("review-options-menu").fetchSemanticsNode().boundsInRoot
        var popupTop = 0
        compose.runOnIdle {
            val popup = WindowInspector.getGlobalWindowViews().last { it.isShown && it !== root }
            val position = popup.layoutParams as android.view.WindowManager.LayoutParams
            // The popup anchors to the button's touch container, which extends beyond its 40 dp face.
            assertTrue(position.y - anchor.bottom in 6f..14f)
            popupTop = position.y
        }
        assertTrue(menu.width <= 282f)
        compose.onNodeWithTag("navigation-sheet").assertDoesNotExist()
        compose.onNodeWithText("回顾选项").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭回顾选项").assertDoesNotExist()
        capture("review-options.png", dialog = true)
        compose.onNodeWithTag("review-choose-source").performClick()
        compose.onNodeWithTag("review-source-AI").performClick()
        compose.onNodeWithTag("review-choose-persona").performClick()
        compose.onNodeWithTag("review-persona-7").assertIsDisplayed()
        capture("review-options-persona.png", dialog = true)
        repeat(3) { compose.onNodeWithTag("review-options-list").performTouchInput { swipeDown(durationMillis = 140) } }
        assertEquals(header, compose.onNodeWithTag("review-options-menu").fetchSemanticsNode().boundsInRoot.top, .5f)
        compose.runOnIdle {
            val popup = WindowInspector.getGlobalWindowViews().last { it.isShown && it !== root }
            assertEquals(popupTop, (popup.layoutParams as android.view.WindowManager.LayoutParams).y)
        }
        compose.onNodeWithContentDescription("返回上一级").performClick()
        compose.onNodeWithTag("review-choose-font").performScrollTo().performClick()
        capture("review-options-font.png", dialog = true)
        compose.onNodeWithTag("review-filter-button").performClick()
        compose.onNodeWithTag("review-filter-button").performClick()
        compose.onNodeWithTag("review-choose-source").assertTextContains("AI 伴读")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun optionsStayUsableOnSmallDarkScreensWithLongRoleNames() {
        state = state.copy(personas = state.personas.map { it.copy(name = "守在灯塔旁一起读书的阿翎") })
        show(true)
        compose.onNodeWithTag("review-filter-button").performClick()
        capture("review-options-dark-small.png", dialog = true)
        compose.onNodeWithTag("review-choose-source").performClick()
        compose.onNodeWithTag("review-source-AI").performClick()
        compose.onNodeWithTag("review-choose-persona").performClick()
        compose.onNodeWithTag("review-persona-7").performClick()
        compose.onNodeWithTag("review-choose-font").performScrollTo().performClick()
        capture("review-options-font-dark-small.png", dialog = true)
        compose.onNodeWithContentDescription("返回上一级").assertIsDisplayed()
    }

    @Test fun focusGesturesOpenAndDismissWhileLongQuotesKeepScrolling() {
        val original = state.entries.first { it.annotation != null }
        val short = original.copy(annotation = original.annotation!!.copy(selectedText = "鸟都没叫。"))
        val long = original.copy(annotation = original.annotation!!.copy(id = 200, selectedText = "在雨声里翻开书页，记住这一刻。".repeat(100)))
        var opened = 0
        var closed = 0
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme { ReviewPagerDialog(listOf(short, long), { closed++ }, { opened++ }, {}, {}) }
        }
        val shortQuote = compose.onNodeWithTag("review-focus-quote-${short.key}")
        val shortHeight = shortQuote.fetchSemanticsNode().boundsInRoot.height
        capture("review-focus-short.png", dialog = true)
        compose.onNodeWithTag("review-pager").performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertEquals(1, opened)
        compose.onNodeWithTag("review-pager").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, closed)
        compose.onNodeWithTag("review-pager").performTouchInput { swipeLeft() }
        val longQuote = compose.onNodeWithTag("review-focus-quote-${long.key}")
        assertTrue(longQuote.fetchSemanticsNode().boundsInRoot.height > shortHeight)
        longQuote.performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertTrue(longQuote.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0)
        assertEquals(1, opened)
        assertEquals(1, closed)
        capture("review-focus-long.png", dialog = true)
    }

    @Test fun sageThemeKeepsItsHueInsteadOfForcingBlue() {
        show(scheme = ColorSchemePreset.SAGE, accent = AccentPreset.FOLLOW)
        capture("review-sage.png")
        compose.onNodeWithTag("review-books-button").assertIsDisplayed()
    }

    @Test fun returningToSourceAndBackgroundRefreshKeepAnchor() {
        show()
        selectSource("ALL")
        compose.onNodeWithTag("review-grid").performScrollToIndex(16)
        val anchor = scroll()
        selectSource("AI")
        selectSource("ALL")
        assertEquals(anchor, scroll(), .01f)
        compose.runOnIdle { state = state.copy(hiddenCount = 3) }
        assertEquals(anchor, scroll(), .01f)
        compose.onNodeWithTag("review-filter-button").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1000dp-h900dp-mdpi")
    fun tabletUsesRoomForThreeColumns() { show(); capture("review-tablet.png") }

    @Test fun focusCardKeepsLongTextReachableAndSupportsNext() {
        val first = state.entries.first { it.book.id == 2L && it.quote.startsWith("书页") }
        val entries = listOf(first) + state.entries.filter { it.annotation != null && it.key != first.key }.take(2)
        compose.setContent {
            MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AZURE)) {
                ReviewPagerDialog(entries, {}, {}, {}, {})
                root = LocalView.current.rootView
            }
        }
        compose.onNodeWithTag("review-pager").assertIsDisplayed()
        capture("review-focus.png", dialog = true)
        compose.onNodeWithTag("review-pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("02 / 03").assertIsDisplayed()
        compose.onNodeWithTag("review-pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("01 / 03").assertIsDisplayed()
    }

    @Test fun detailShowsThoughtAndAiReplyInTheRealDialog() {
        val original = state.entries.first { it.annotation != null && it.personaId == null }
        val entry = original.copy(annotation = requireNotNull(original.annotation).copy(
            selectedText = "他把书翻到有折角的那页，才发现折角是自己三年前留下的。",
            note = "忽然想起旧书摊那本《山海》，也有一个不是我折的折角。旧物替人记事，这点作者写得很轻。", colorTag = "#85AED1"))
        var sentPersona: Long? = null
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AZURE)) {
                ReviewDetailDialog(entry, state.personas,
                    DiscussionUiState(replies = listOf(AnnotationReplyEntity(id = 5, annotationId = 1, personaId = 7,
                        contentMarkdown = "折角是全书第三次出现“旧物替人记事”。前两次在第 12 章和第 40 章，要我一起翻出来吗？", createdAt = 0))),
                    {}, {}, { _, _ -> }, {}, {}, { _, persona -> sentPersona = persona }, {}, {})
            }
        }
        compose.onNodeWithContentDescription("回到原文").assertIsDisplayed()
        compose.onNodeWithContentDescription("当前划线颜色").assertDoesNotExist()
        compose.onNodeWithContentDescription("划线样式").assertIsDisplayed()
        capture("review-detail.png", dialog = true)
        compose.onNodeWithTag("review-reply").assertDoesNotExist()
        compose.onNodeWithTag("review-detail-list").performScrollToNode(hasText("接着聊"))
        compose.onNodeWithText("接着聊").performClick()
        compose.onNodeWithTag("review-invite-ai").performScrollTo().performClick()
        compose.onNodeWithContentDescription("选择伴读 阿翎").performScrollTo().assertIsSelected()
        capture("review-detail-ai.png", dialog = true)
        compose.onNodeWithText("阿翎").assertIsDisplayed()
        assertNull(sentPersona)
        compose.onNodeWithContentDescription("发送", substring = false).performClick()
        assertEquals(7L, sentPersona)
    }

    @Test fun coauthorUsesAvatarSelectionAndOnlyGeneratesOnExplicitAction() {
        var generated: Long? = null
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AZURE)) {
                ReviewComposerDialog(state.entries.take(3), state.personas, null, false, {},
                    { _, persona, _ -> generated = persona }, { _, _ -> }, {}, {})
            }
        }
        compose.onNodeWithContentDescription("选择伴读 阿翎").performScrollTo().performClick().assertIsSelected()
        assertNull(generated)
        capture("review-coauthor.png", dialog = true)
        compose.onNodeWithTag("review-generate").performClick()
        assertEquals(7L, generated)
    }

    @Test fun exportedCardUsesFullTextAndAllTemplatesHaveContrast() {
        val entry = state.entries.first { it.annotation != null }
        ReviewCardStyle.entries.forEach { style ->
            val bitmap = renderReviewCard(entry, style)
            assertEquals(1080, bitmap.width)
            assertTrue(bitmap.height >= 920)
            File("build/reports/reading-review/export-${style.name.lowercase()}.png").apply { parentFile.mkdirs() }
                .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val long = entry.copy(annotation = requireNotNull(entry.annotation).copy(selectedText = entry.quote.repeat(10)))
        val bitmap = renderReviewCard(long, ReviewCardStyle.PAPER)
        assertTrue(bitmap.height > 1440)
        bitmap.recycle()
    }

    @Test fun exportPageUsesMiniatureCardsAndEditableDisplayOptions() {
        var chosen: ReviewExportOptions? = null
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AZURE)) {
                ReviewExportDialog(state.entries.first { it.annotation != null }, {}, {}, { _, options -> chosen = options })
            }
        }
        compose.waitUntil(10000) { compose.onAllNodesWithTag("review-export-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("日期").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("review-export-preview").fetchSemanticsNodes().isNotEmpty() }
        capture("review-export-page.png", dialog = true)
        compose.onNodeWithText("分享图片").performClick()
        assertFalse(requireNotNull(chosen).date)
        assertTrue(requireNotNull(chosen).book)
    }

    @Test fun customTemplateCanBeSavedReopenedDuplicatedAndDeletedFromExport() {
        var settings by mutableStateOf(ReaderSettings())
        var visible by mutableStateOf(true)
        var chosen: ReviewExportOptions? = null
        compose.setContent {
            root = LocalView.current.rootView
            MoReadTheme(AppearanceSettings(themeMode = ThemeMode.LIGHT, accent = AccentPreset.AZURE)) {
                CompositionLocalProvider(LocalReviewReaderSettings provides settings) {
                    if (visible) ReviewExportDialog(state.entries.first { it.annotation != null }, { visible = false }, {}, { _, options -> chosen = options },
                        onSaveTemplate = { saved -> settings = settings.copy(reviewShareTemplates = settings.reviewShareTemplates.filterNot { it.id == saved.id } + saved) },
                        onDeleteTemplate = { id -> settings = settings.copy(reviewShareTemplates = settings.reviewShareTemplates.filterNot { it.id == id }) })
                }
            }
        }
        compose.onNodeWithContentDescription("新建自定义模板").performClick()
        compose.onNodeWithTag("review-template-name").performTextReplacement("我的纸页")
        capture("review-template-editor.png", dialog = true)
        compose.waitUntil(10000) { compose.onAllNodesWithText("保存").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { settings.reviewShareTemplates.size == 1 }
        compose.runOnIdle { visible = false }
        compose.runOnIdle { visible = true }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("我的纸页模板").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("我的纸页模板").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("review-export-preview").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("分享图片").performClick()
        assertEquals("我的纸页", chosen?.template?.name)
        compose.onNodeWithContentDescription("管理自定义模板").performClick()
        compose.onNodeWithText("另存为新模板").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("保存").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { settings.reviewShareTemplates.size == 2 }
        compose.onNodeWithContentDescription("管理自定义模板").performClick()
        compose.onNodeWithText("删除模板").performClick()
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.waitUntil(10000) { settings.reviewShareTemplates.size == 1 }
        assertEquals("我的纸页", settings.reviewShareTemplates.single().name)
    }

    private fun scroll() = compose.onNodeWithTag("review-grid").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun selectSource(source: String) {
        compose.onNodeWithTag("review-filter-button").performClick()
        compose.onNodeWithTag("review-choose-source").performClick()
        compose.onNodeWithTag("review-source-$source").performClick()
        compose.onNodeWithTag("review-filter-button").performClick()
    }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        compose.runOnIdle {
            val target = if (dialog) WindowInspector.getGlobalWindowViews().last { it.isShown && it !== root } else root
            val popup = target.width < root.width
            val bitmap = Bitmap.createBitmap(if (popup) root.width else target.width, if (popup) root.height else target.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (popup) {
                root.draw(canvas)
                val position = target.layoutParams as android.view.WindowManager.LayoutParams
                canvas.translate(position.x.toFloat(), position.y.toFloat())
            }
            target.draw(canvas)
            File("build/reports/reading-review/$name").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

class ReviewVisualApplication : Application(), SingletonImageLoader.Factory {
    // The repository's Windows native visual tests use BitmapFactory instead of ImageDecoder.
    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context).imageDecoderEnabled(false).build()
}
