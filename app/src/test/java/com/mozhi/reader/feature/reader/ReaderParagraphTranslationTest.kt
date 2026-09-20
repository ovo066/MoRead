package com.mozhi.reader.feature.reader

import android.app.Application
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.ParagraphTranslation
import com.mozhi.reader.core.dictionary.englishParagraphs
import com.mozhi.reader.feature.reader.engine.*
import com.mozhi.reader.ui.theme.MoReadTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi", shadows = [SelectionMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderParagraphTranslationTest {
    @get:Rule val compose = createComposeRule()
    private val body = "A reader opens a book.\nThe window is open."
    private val translations = englishParagraphs(body).map { ParagraphTranslation(it.start, it.end, it.key, "读者翻开一本书。") }
    private lateinit var controller: ReaderContentController
    private lateinit var paged: ReaderPaneHolder
    private lateinit var scrolling: ScrollPaneHolder
    private var contentHook: ((Int) -> Unit)? = null
    private var selected: ReaderParagraphTranslation? = null
    private var action: String? = null

    private fun checkLongPress(scroll: Boolean, actionText: String) {
        compose.setContent {
            MoReadTheme {
                val scope = rememberCoroutineScope()
                var dialog by remember { mutableStateOf<ReaderParagraphTranslation?>(null) }
                val reader = remember {
                    ReaderContentController(scope, { ReaderChapterContent(body, translations = translations) }, object : ReaderContentController.Listener {
                        override fun onContentChanged(relativePosition: Int) { contentHook?.invoke(relativePosition) }
                        override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) = Unit
                    }).also {
                        it.setChapters(listOf(ChapterMeta(0, "", body.length)))
                        it.setTranslationsVisible(true)
                    }
                }
                controller = reader
                val onHold: (ReaderParagraphTranslation) -> Unit = { selected = it; dialog = it }
                val settings = ReaderSettings()
                val palette = companionChatPalette()
                if (scroll) {
                    scrolling = remember(reader) { ScrollPaneHolder(reader) }
                    DisposableEffect(scrolling) { onDispose { scrolling.release() } }
                    ReaderScrollPane(reader, scrolling, settings, palette, dialog == null, { contentHook = it }, {}, {},
                        onTranslationLongPress = onHold, onBoundary = {}, onNotice = {}, annotations = emptyList(),
                        onAiAction = { _, _, _ -> }, onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {},
                        onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null,
                        modifier = Modifier.size(360.dp, 640.dp).testTag("translation-reader"))
                } else {
                    paged = remember(reader) { ReaderPaneHolder(reader) }
                    DisposableEffect(paged) { onDispose { paged.release() } }
                    ReaderPane(reader, paged, settings, palette, dialog == null, { contentHook = it }, {},
                        onTranslationLongPress = onHold, onAddBookmark = {}, onBoundary = {}, onNotice = {}, annotations = emptyList(),
                        onAiAction = { _, _, _ -> }, onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {},
                        onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null,
                        modifier = Modifier.size(360.dp, 640.dp).testTag("translation-reader"))
                }
                dialog?.let { target ->
                    ParagraphTranslationActionsDialog(target.translation, true, false,
                        onRetranslate = { action = "retranslate"; dialog = null },
                        onDelete = { action = "delete"; dialog = null },
                        onToggle = { action = "toggle"; dialog = null }, onDismiss = { dialog = null })
                }
            }
        }
        compose.waitUntil(15_000) {
            ::controller.isInitialized && controller.isReady &&
                if (scroll) scrolling.visiblePages(0).isNotEmpty() else paged.curBitmap != null
        }
        var point = Offset.Zero
        val initialOffset = controller.charOffset
        compose.runOnIdle {
            val page = if (scroll) scrolling.visiblePages(0).first().page else (controller.curPage() as RenderPage.Laid).page
            val origin = if (scroll) scrolling.visiblePages(0).first().origin else paged.visiblePages().first().second
            val line = page.lines.first { it.paragraphTranslation != null }
            val column = line.columns.first()
            point = origin + Offset((column.start + column.end) / 2, (line.lineTop + line.lineBottom) / 2)
        }
        compose.onNodeWithTag("translation-reader").performTouchInput { longClick(point) }
        compose.onNodeWithText("本段译文").assertIsDisplayed()
        compose.onNodeWithText("重新翻译本段").assertIsDisplayed()
        compose.onNodeWithText("删除本段译文").assertIsDisplayed()
        compose.onNodeWithTag("reader-selection-toolbar").assertDoesNotExist()
        assertEquals(ReaderParagraphTranslation(0, translations.first()), selected)
        assertEquals(initialOffset, controller.charOffset)
        compose.onNodeWithText(actionText).performClick()
        compose.onNodeWithText("本段译文").assertDoesNotExist()
        assertEquals(if (actionText == "删除本段译文") "delete" else "retranslate", action)
    }

    @Test fun pagedTranslationLongPressOpensParagraphActions() = checkLongPress(false, "重新翻译本段")
    @Test fun scrollTranslationLongPressOpensParagraphActions() = checkLongPress(true, "删除本段译文")

    @Test fun pendingTranslationDisablesMutatingActions() {
        compose.setContent { MoReadTheme {
            ParagraphTranslationActionsDialog(translations.first(), true, true, {}, {}, {}, {})
        } }
        compose.onNodeWithText("重新翻译本段").assertIsNotEnabled()
        compose.onNodeWithText("删除本段译文").assertIsNotEnabled()
        compose.onNodeWithText("隐藏本段译文").assertIsNotEnabled()
        compose.onNodeWithText("关闭").assertIsEnabled()
    }
}
