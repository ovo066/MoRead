package com.mozhi.reader.core.i18n

import android.app.Application
import androidx.activity.ComponentActivity
import com.mozhi.reader.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppLocalesTest {
    @Test
    fun languageTagsMapToSupportedChoices() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromLocale(Locale.FRANCE))
    }

    @Test
    fun selectionRoundTripsThroughPerAppLocales() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertEquals(AppLanguage.SYSTEM, AppLocales.selected(activity))
        AppLocales.select(activity, AppLanguage.ENGLISH)
        assertEquals(AppLanguage.ENGLISH, AppLocales.selected(activity))
        AppLocales.select(activity, AppLanguage.SIMPLIFIED_CHINESE)
        assertEquals(AppLanguage.SIMPLIFIED_CHINESE, AppLocales.selected(activity))
        AppLocales.select(activity, AppLanguage.SYSTEM)
        assertEquals(AppLanguage.SYSTEM, AppLocales.selected(activity))
    }

    @Test
    fun uiTextResolvesArgumentsPluralsAndNestedText() {
        val resources = RuntimeEnvironment.getApplication().resources
        assertEquals(
            "Could not import the font: Unsupported file format",
            UiText.of(R.string.font_import_failed, UiText.of(R.string.font_import_unsupported)).resolve(resources)
        )
        assertEquals("3 books", UiText.plural(R.plurals.shelf_collection_book_count, 3).resolve(resources))
        assertEquals("《书名》", UiText.raw("《书名》").resolve(resources))
    }
}
