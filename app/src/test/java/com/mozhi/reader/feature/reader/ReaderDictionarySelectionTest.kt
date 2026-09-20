package com.mozhi.reader.feature.reader

import android.app.Application
import android.widget.Magnifier
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.dictionary.DictionaryLookupHit
import com.mozhi.reader.feature.reader.engine.*
import com.mozhi.reader.ui.theme.MoReadTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-mdpi", shadows = [SelectionMagnifierShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderDictionarySelectionTest {
    @get:Rule val compose = createComposeRule()
    private val body = List(40) { "温故而知新，可以为师矣。学而时习之，不亦说乎。" }.joinToString("\n")
    private lateinit var controller: ReaderContentController
    private lateinit var paged: ReaderPaneHolder
    private lateinit var scrolling: ScrollPaneHolder
    private var contentHook: ((Int) -> Unit)? = null
    private var hit: DictionaryLookupHit? = null

    private fun checkSelection(scroll: Boolean) {
        compose.setContent {
            MoReadTheme {
                val scope = rememberCoroutineScope()
                val reader = remember {
                    ReaderContentController(scope, { ReaderChapterContent(body) }, object : ReaderContentController.Listener {
                        override fun onContentChanged(relativePosition: Int) { contentHook?.invoke(relativePosition) }
                        override fun onPositionChanged(chapterIndex: Int, charOffset: Int, pageIndex: Int, pageCount: Int, bookProgress: Float) = Unit
                    }).also { it.setChapters(listOf(ChapterMeta(0, "论语", body.length))) }
                }
                controller = reader
                val settings = ReaderSettings(englishLearningEnabled = false)
                val palette = companionChatPalette()
                if (scroll) {
                    scrolling = remember(reader) { ScrollPaneHolder(reader) }
                    DisposableEffect(scrolling) { onDispose { scrolling.release() } }
                    ReaderScrollPane(reader, scrolling, settings, palette, true, { contentHook = it }, {}, {},
                        onDictionaryLookup = { hit = it }, onBoundary = {}, onNotice = {}, annotations = emptyList(),
                        onAiAction = { _, _, _ -> }, onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {},
                        onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null,
                        modifier = Modifier.size(360.dp, 640.dp).testTag("dictionary-reader"))
                } else {
                    paged = remember(reader) { ReaderPaneHolder(reader) }
                    DisposableEffect(paged) { onDispose { paged.release() } }
                    ReaderPane(reader, paged, settings, palette, true, { contentHook = it }, {},
                        onDictionaryLookup = { hit = it }, onAddBookmark = {}, onBoundary = {}, onNotice = {}, annotations = emptyList(),
                        onAiAction = { _, _, _ -> }, onAnnotationAction = { _, _, _ -> }, onAnnotationClick = {},
                        onTtsAction = {}, onImageAction = { _, _, _ -> }, onEditText = null,
                        modifier = Modifier.size(360.dp, 640.dp).testTag("dictionary-reader"))
                }
            }
        }
        compose.waitUntil(15_000) {
            ::controller.isInitialized && controller.isReady && if (scroll) scrolling.visiblePages(0).isNotEmpty() else paged.curBitmap != null
        }
        var point = Offset.Zero
        compose.runOnIdle {
            val page = if (scroll) scrolling.visiblePages(0).first().page else (controller.curPage() as RenderPage.Laid).page
            val origin = if (scroll) scrolling.visiblePages(0).first().origin else paged.visiblePages().first().second
            val line = page.lines.first { !it.isTitle && it.charLength > 5 }
            val column = line.columns.first { it.sourceLength > 0 }
            point = origin + Offset((column.start + column.end) / 2, (line.lineTop + line.lineBottom) / 2)
        }
        compose.onNodeWithTag("dictionary-reader").performTouchInput { longClick(point) }
        compose.onNodeWithText("词典", substring = false).assertIsDisplayed().performClick()
        assertNotNull(hit)
        val selected = hit!!
        assertTrue(selected.word.any { it.code in 0x4e00..0x9fff })
        assertEquals(selected.word, body.substring(selected.offset, selected.offset + selected.word.length))
        assertTrue(selected.context.contains("温故而知新"))
        assertEquals(0, selected.chapterIndex)
        compose.onNodeWithTag("reader-selection-toolbar").assertDoesNotExist()
    }

    @Test fun pagedChineseSelectionQueriesDictionaryWithEnglishAidOff() = checkSelection(false)
    @Test fun scrollChineseSelectionQueriesDictionaryWithEnglishAidOff() = checkSelection(true)
}

// Robolectric has no native popup Surface for Magnifier. Keep real reader gestures/selection;
// only the platform lens drawing is replaced, so dismissal does not dereference a null Surface.
@Implements(Magnifier::class)
class SelectionMagnifierShadow {
    @Implementation fun show(x: Float, y: Float) = Unit
    @Implementation fun show(x: Float, y: Float, lensX: Float, lensY: Float) = Unit
    @Implementation fun update() = Unit
    @Implementation fun dismiss() = Unit
}
