package com.mozhi.reader.feature.stats

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.core.okio.OkioStorage
import com.mozhi.reader.core.datastore.StatsSettingsStore
import com.mozhi.reader.core.datastore.StatsWidget
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath
import okio.FileSystem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StatsSettingsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    // Use the same protobuf store with a portable atomic replacement on the Windows test host.
    private fun createStore(file: File, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer, producePath = { file.absolutePath.toPath() }), scope = scope)
    @Test fun visibilityAndOrderPersistAcrossStoreRecreationAndReset() = runBlocking {
        val file = File(temporary.root, "stats.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val first = StatsSettingsStore(createStore(file, scope))
        first.setVisible(StatsWidget.TAGS, false)
        first.move(StatsWidget.TREND, -1)
        assertEquals(StatsWidget.HEATMAP, first.widgets.first().order.first())
        assertEquals(StatsWidget.TREND, first.widgets.first().order[1])
        scope.coroutineContext.job.cancelAndJoin()
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val restored = StatsSettingsStore(createStore(file, nextScope))
            assertEquals(StatsWidget.HEATMAP, restored.widgets.first().order.first())
            assertEquals(StatsWidget.TREND, restored.widgets.first().order[1])
            assertTrue(StatsWidget.TAGS in restored.widgets.first().hidden)
            restored.reset()
            assertEquals(StatsWidget.entries.toList(), restored.widgets.first().order)
            assertTrue(restored.widgets.first().hidden.isEmpty())
        } finally { nextScope.coroutineContext.job.cancelAndJoin() }
    }
}
