package com.mozhi.reader.core.datastore

import android.app.Application
import android.view.KeyEvent
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
class ReaderKeyBindingsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
            producePath = { File(temporary.root, "keys.preferences_pb").absolutePath.toPath() }), scope = scope)

    @Test fun legacySwitchKeepsDefaultDirectionsAndCustomBindingsSurviveReopening() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val key = ReaderPhysicalKey(KeyEvent.KEYCODE_PAGE_DOWN)
        val custom = ReaderKeyBindings.assign(ReaderKeyBindings.volumePreset(ReaderKeyBindings.DEFAULT, true), key, ReaderKeyAction.NEXT_PAGE)
        try {
            val data = store(scope)
            data.edit { it[booleanPreferencesKey("reader_volume_keys_page_turn")] = true }
            val repository = ReaderSettingsRepository(data)
            val old = repository.settings.first()
            assertTrue(old.volumeKeysPageTurn)
            assertEquals(ReaderKeyBindings.DEFAULT, old.physicalKeyBindings)
            repository.setPhysicalKeyBindings(custom)
            repository.setVolumeKeysPageTurn(false)
            assertEquals(custom, repository.settings.first().physicalKeyBindings)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ReaderSettingsRepository(store(reopenedScope))
            val restored = repository.settings.first()
            assertFalse(restored.volumeKeysPageTurn)
            assertEquals(custom, restored.physicalKeyBindings)
            repository.setPhysicalKeyBindings(emptyList())
            assertTrue(repository.settings.first().physicalKeyBindings.isEmpty())
        } finally { reopenedScope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun reassignmentIsUniqueAndVolumePresetsRetainOtherPhysicalKeys() {
        val up = ReaderPhysicalKey(KeyEvent.KEYCODE_VOLUME_UP)
        val remote = ReaderPhysicalKey(KeyEvent.KEYCODE_UNKNOWN, scanCode = 188)
        val chord = ReaderPhysicalKey(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.META_CTRL_ON)
        var bindings = ReaderKeyBindings.assign(ReaderKeyBindings.DEFAULT, remote, ReaderKeyAction.NEXT_PAGE)
        bindings = ReaderKeyBindings.assign(bindings, chord, ReaderKeyAction.PREVIOUS_PAGE)
        bindings = ReaderKeyBindings.assign(bindings, up, ReaderKeyAction.NEXT_PAGE)
        assertEquals(1, bindings.count { it.key == up })
        assertEquals(ReaderKeyAction.NEXT_PAGE, bindings.single { it.key == up }.action)
        val preset = ReaderKeyBindings.volumePreset(bindings, false)
        assertEquals(4, preset.size)
        assertTrue(preset.any { it.key == remote })
        assertTrue(preset.any { it.key == chord })
        assertEquals(preset, ReaderKeyBindings.decode(ReaderKeyBindings.encode(preset)))
    }

    @Test fun emptyAndInvalidConfigurationsAreHandledWithoutBindingReservedKeys() {
        assertEquals(ReaderKeyBindings.DEFAULT, ReaderKeyBindings.decode(null))
        assertEquals(ReaderKeyBindings.DEFAULT, ReaderKeyBindings.decode("broken"))
        assertTrue(ReaderKeyBindings.decode("[]").isEmpty())
        val data = """[{"key":{"keyCode":4},"action":"PREVIOUS_PAGE"},{"key":{"keyCode":93},"action":"NEXT_PAGE"},{"key":{"keyCode":94},"action":"FUTURE_ACTION"}]"""
        assertEquals(listOf(ReaderKeyBinding(ReaderPhysicalKey(93), ReaderKeyAction.NEXT_PAGE)), ReaderKeyBindings.decode(data))
    }
}
