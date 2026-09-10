package com.mozhi.reader.core

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import com.mozhi.reader.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocalizationResourcesTest {
    private fun context(language: String): Context {
        val base = RuntimeEnvironment.getApplication()
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun englishSelectsLocalizedBookActionsBookmarksAndImportMessages() {
        val context = context("en-US")
        assertEquals("MoRead", context.getString(R.string.app_name))
        assertEquals("Remove from shelf", context.getString(R.string.book_action_remove))
        assertEquals("Release to bookmark", context.getString(R.string.reader_bookmark_release))
        assertEquals("Generating EPUB", context.getString(R.string.import_generating_epub))
    }

    @Test
    fun unsupportedLocalesUseTheCompleteDefaultResourceSet() {
        val context = context("fr-FR")
        assertEquals("墨知", context.getString(R.string.app_name))
        assertEquals("移除书架", context.getString(R.string.book_action_remove))
        assertEquals("已添加书签", context.getString(R.string.reader_bookmark_added))
        assertEquals("正在整理书籍", context.getString(R.string.import_preparing))
    }

    @Test
    fun indexedArgumentsPercentagesAndEnglishPluralsAreFormattedCorrectly() {
        val english = context("en-US")
        assertEquals("Reading · 42%", english.getString(R.string.book_state_reading_progress, 42))
        assertEquals("1 book", english.resources.getQuantityString(R.plurals.shelf_collection_book_count, 1, 1))
        assertEquals("3 books", english.resources.getQuantityString(R.plurals.shelf_collection_book_count, 3, 3))
        assertEquals("Unread · 1 chapter", english.resources.getQuantityString(R.plurals.book_state_unread_chapters, 1, 1))
        assertEquals("Unread · 12 chapters", english.resources.getQuantityString(R.plurals.book_state_unread_chapters, 12, 12))
        val chinese = context("zh-CN")
        assertEquals("在读 · 42%", chinese.getString(R.string.book_state_reading_progress, 42))
        assertEquals("3 本书", chinese.resources.getQuantityString(R.plurals.shelf_collection_book_count, 3, 3))
    }
}
