package com.mozhi.reader.core.datastore

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
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
class ReaderTitleStylePresetsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(scope: CoroutineScope) = ReaderSettingsRepository(PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
            producePath = { File(temporary.root, "styles.preferences_pb").absolutePath.toPath() }), scope = scope))

    @Test fun namedStylesPersistAndThemesRememberIndependentSelections() = runTest {
        val first = ReaderTitleStylePreset("one", "月下留白", ReaderTitleStyle(css = "margin-top: 5em;", font = ReaderSyntaxFont.SERIF))
        val second = ReaderTitleStylePreset("two", "细线章首", ReaderTitleStyle(borderWidthEm = .05f))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope)
            repo.saveTitleStylePreset(first)
            repo.saveTitleStylePreset(second)
            repo.updateBoundTypography(0, ReaderThemeSlot.DAY) { it.copy(titleStyle = first.style.copy(presetId = first.id)) }
            repo.updateBoundTypography(0, ReaderThemeSlot.NIGHT) { it.copy(titleStyle = second.style.copy(presetId = second.id)) }
            repo.saveTitleStylePreset(first.copy(name = "月下", style = first.style.copy(colorArgb = 0xff556677.toInt())))
            val updated = repo.settings.first()
            assertEquals("one", updated.resolveThemeSlot(ReaderThemeSlot.DAY).titleStyle.presetId)
            assertEquals(0xff556677.toInt(), updated.resolveThemeSlot(ReaderThemeSlot.DAY).titleStyle.colorArgb)
            assertEquals("two", updated.resolveThemeSlot(ReaderThemeSlot.NIGHT).titleStyle.presetId)
            assertEquals("月下", updated.titleStylePresets.single { it.id == "one" }.name)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restored = repository(reopenedScope).settings.first()
            assertEquals("one", restored.resolveForBook(22, ReaderThemeSlot.DAY).titleStyle.presetId)
            assertEquals("two", restored.resolveForBook(22, ReaderThemeSlot.NIGHT).titleStyle.presetId)
            assertEquals(5, restored.titleStylePresets.size)
        } finally { reopenedScope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun deletionKeepsLatestAppearanceForGlobalAndBookThemeReferences() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope)
            val preset = ReaderTitleStylePreset("shared", "共享", ReaderTitleStyle(css = "color: #123456;"))
            repo.saveTitleStylePreset(preset)
            repo.setTitleStyle(preset.style.copy(presetId = preset.id))
            repo.updateBoundTypography(0, ReaderThemeSlot.DAY) { it.copy(titleStyle = preset.style.copy(presetId = preset.id)) }
            repo.saveBookCustomTheme(55, CustomReaderTheme(88, "本书", -1, 0, 0,
                titleStyle = preset.style.copy(presetId = preset.id)), ReaderThemeSlot.NIGHT)
            val latest = preset.copy(style = preset.style.copy(css = "color: #987654; margin-top: 7em;"))
            repo.saveTitleStylePreset(latest)
            val before = repo.settings.first()
            assertEquals(latest.style.css, before.resolveForBook(55, ReaderThemeSlot.NIGHT).titleStyle.css)
            repo.deleteTitleStylePreset(preset.id)
            val after = repo.settings.first()
            assertEquals(latest.style, after.resolveThemeSlot(ReaderThemeSlot.DAY).titleStyle)
            assertEquals(latest.style, after.resolveForBook(55, ReaderThemeSlot.NIGHT).titleStyle)
            assertEquals(latest.style, after.titleStyle)
            assertFalse(after.titleStylePresets.any { it.id == preset.id })
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun oldHeadingAndDeliberatelyEmptyLibraryRemainIntact() {
        val old = ReaderTitleStyleCodec.decode("""{"css":"font-size: 1.8em;"}""")
        assertNull(old.presetId)
        assertEquals(old, ReaderSettings(titleStyle = old).resolveThemeSlot(ReaderThemeSlot.DAY).titleStyle)
        assertEquals(emptyList<ReaderTitleStylePreset>(), ReaderTitleStylePresetCodec.decode("[]"))
    }
}
