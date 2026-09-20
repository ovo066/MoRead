package com.mozhi.reader.feature.review

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import com.mozhi.reader.core.datastore.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReviewTypographyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun bookThemeOverridesDefaultAndUnboundBooksUseReaderFont() {
        val theme = CustomReaderTheme(8, "书籍宋体", -1, -16777216, -1, font = ReaderFont.SERIF, fontWeight = 500)
        val settings = ReaderSettings(font = ReaderFont.SANS_SERIF, customThemes = listOf(theme),
            bookThemes = mapOf(1L to BookReaderTheme(enabled = true, dayCustomThemeId = 8)))
        assertEquals(ReviewFontSpec(ReaderFont.SERIF, weight = 500), reviewFontSpec(settings, 1, false))
        assertEquals(ReaderFont.SANS_SERIF, reviewFontSpec(settings, 2, false).font)
        assertEquals(ReaderFont.MONOSPACE, reviewFontSpec(settings.copy(reviewFont = "MONOSPACE"), 1, false).font)
        assertEquals(ReaderFont.SERIF, reviewFontSpec(settings.copy(reviewFont = "font:deleted"), 1, false).font)
    }

    @Test fun activeBookDayNightSlotAndCustomFileMatchTheReader() {
        val night = CustomReaderTheme(9, "夜间字体", -16777216, -1, -1, font = ReaderFont.CUSTOM,
            customFontPath = "/reader/font.ttf", customFontName = "自定义字体")
        val settings = ReaderSettings(font = ReaderFont.SERIF, dayNightThemeAuto = true, customThemes = listOf(night),
            bookThemes = mapOf(1L to BookReaderTheme(enabled = true, nightCustomThemeId = 9)))
        assertEquals(ReaderFont.SERIF, reviewFontSpec(settings, 1, false).font)
        assertEquals(ReviewFontSpec(ReaderFont.CUSTOM, "/reader/font.ttf"), reviewFontSpec(settings, 1, true))
    }

    @Test fun reviewFontPersistsIndependentlyAndCanReturnToBookDefaults() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
                producePath = { File(temporary.root, "review.preferences_pb").absolutePath.toPath() }), scope = scope)
            val repo = ReaderSettingsRepository(store)
            repo.setFont(ReaderFont.SERIF)
            assertEquals("", repo.settings.first().reviewFont)
            repo.setReviewFont("MONOSPACE")
            assertEquals("MONOSPACE", ReaderSettingsRepository(store).settings.first().reviewFont)
            assertEquals(ReaderFont.SERIF, repo.settings.first().font)
            repo.setReviewFont("")
            assertEquals(ReaderFont.SERIF, reviewFontSpec(repo.settings.first(), 1, false).font)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun customTemplatesReloadUpdateDuplicateAndDeleteWithoutChangingReadingStyles() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
                producePath = { File(temporary.root, "templates.preferences_pb").absolutePath.toPath() }), scope = scope)
            val repo = ReaderSettingsRepository(store)
            val original = repo.settings.first()
            val first = ReviewShareTemplate("paper", "我的纸页", backgroundArgb = 0xFFE7EFDD.toInt(), fontChoice = "SERIF",
                css = "padding: 2em; line-height: 1.8; letter-spacing: 0.02em;", syntaxEnabled = true,
                syntaxRules = listOf(ReaderSyntaxRule(1, "对白", "“", "”", 0xFF916872.toInt())))
            repo.saveReviewShareTemplate(first)
            assertEquals(listOf(first), ReaderSettingsRepository(store).settings.first().reviewShareTemplates)
            val edited = first.copy(name = "薄荷纸页", textArgb = 0xFF324532.toInt())
            repo.saveReviewShareTemplate(edited)
            val duplicate = edited.copy(id = "duplicate", name = "暖色副本")
            repo.saveReviewShareTemplate(duplicate)
            assertEquals(listOf(edited, duplicate), repo.settings.first().reviewShareTemplates)
            repo.deleteReviewShareTemplate("paper")
            val reloaded = ReaderSettingsRepository(store).settings.first()
            assertEquals(listOf(duplicate), reloaded.reviewShareTemplates)
            assertEquals(original.font, reloaded.font)
            assertEquals(original.syntaxHighlightRules, reloaded.syntaxHighlightRules)
            assertEquals(original.syntaxHighlightEnabled, reloaded.syntaxHighlightEnabled)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun templateCssValidatesDeclarationsAndCodecSurvivesOldOrDamagedSettings() {
        val valid = ReviewTemplateCss.parse("font-style: italic; text-decoration: underline; line-height: 1.8; letter-spacing: -0.01em;")
        assertTrue(valid.declarations.errors.isEmpty())
        assertEquals(1.8f, valid.lineHeight)
        assertEquals(true, valid.declarations.italic)
        listOf("width: 10px", "line-height: NaN", "letter-spacing: 3em", "padding: 20em", "background-image: url(https://example.com/pic.png)")
            .forEach { assertTrue(it, ReviewTemplateCss.parse(it).declarations.errors.isNotEmpty()) }
        assertEquals(emptyList<ReviewShareTemplate>(), ReviewShareTemplateCodec.decode("broken"))
        assertEquals("纸页", ReviewShareTemplateCodec.decode("""[{"id":"a","name":"纸页","futureSetting":true}]""").single().name)
    }
}
