package com.mozhi.reader.feature.reader.render

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.dictionary.*
import com.mozhi.reader.feature.reader.readerPalette
import com.mozhi.reader.feature.reader.engine.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnglishReadingVisualTest {
    @Test fun nativeReaderShowsInlineGlossesPopupMarksAndBilingualParagraphs() {
        val body = "The quiet library stood beside a narrow river. I opened a book and paused at an unfamiliar word.\nReading slowly, I began to understand the story."
        val words = listOf(
            VocabularyWord("library", gloss = "图书馆", phonetic = "/ˈlaɪbrəri/"),
            VocabularyWord("narrow", gloss = "狭窄的", phonetic = "/ˈnærəʊ/"),
            VocabularyWord("paused", gloss = "停顿", phonetic = "/pɔːzd/"),
            VocabularyWord("unfamiliar", gloss = "不熟悉的", phonetic = "/ˌʌnfəˈmɪliə/"))
        val paragraph = englishParagraphs(body).first()
        val translation = ParagraphTranslation(paragraph.start, paragraph.end, paragraph.key, "安静的图书馆坐落在一条狭窄的河流旁。我打开一本书，读到一个陌生的单词时停了下来。")
        for (mode in WordAnnotationMode.entries) for (dark in listOf(false, true)) {
            val settings = ReaderSettings(theme = if (dark) ReaderTheme.DARK else ReaderTheme.PAPER,
                fontScale = 1.25f, englishLearningEnabled = true, vocabulary = words, wordAnnotationMode = mode,
                showHeader = false, showFooter = false, firstLineIndentEm = 0f, textJustification = false)
            val style = ReaderPageStyle.resolve(settings, readerPalette(settings.theme, dark, Color(0xff9c764b)), Density(2f), 820, 1420, 40f, 32f)
            val chapter = ChapterTypesetter(style.spec, style.measure).typeset(0, "", body, translations = listOf(translation))
            val lines = chapter.pages.flatMap { it.lines }
            assertEquals(mode == WordAnnotationMode.INLINE, lines.any { it.rubyPlacements.isNotEmpty() })
            assertEquals(mode == WordAnnotationMode.POPUP, lines.any { it.columns.any { column -> column.syntaxUnderline } })
            assertTrue(chapter.pages.all { it.lines.last().lineBottom <= style.contentHeight + 0.5f })
            val renderer = PageBitmapRenderer(style)
            try {
                val bitmap = renderer.render(RenderPage.Laid(0, "", 0, chapter.pageCount, chapter.pages.first()), null, 0f, "", 100)
                val destination = File("build/reports/ui-qa/english-${mode.name.lowercase()}-${if (dark) "dark" else "light"}.png")
                destination.parentFile!!.mkdirs()
                destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            } finally { renderer.release() }
        }
    }
}
