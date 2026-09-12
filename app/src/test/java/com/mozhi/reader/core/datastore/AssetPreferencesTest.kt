package com.mozhi.reader.core.datastore

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository(scope: CoroutineScope) = ReaderSettingsRepository(PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
            producePath = { File(temporary.root, "assets.preferences_pb").absolutePath.toPath() }), scope = scope))

    @Test fun appFontAndReadingFontAreIndependentAndDeletionResetsOnlyItsReference() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope)
            val ui = ReaderFontAsset("ui", "界面", "/app/ui.ttf")
            val body = ReaderFontAsset("body", "正文", "/app/body.ttf")
            repo.setFont(ReaderFont.SYSTEM)
            repo.addCustomFont(ui, select = false)
            repo.selectAppFont(ui.id)
            assertEquals(ReaderFont.SYSTEM, repo.settings.first().font)
            repo.addCustomFont(body, select = true)
            assertEquals(ui, repo.appearance.first().appFont)
            assertEquals(body.id, repo.settings.first().selectedCustomFontId)
            repo.removeCustomFont(ui.id)
            assertNull(repo.appearance.first().appFont)
            assertEquals(body.id, repo.settings.first().selectedCustomFontId)
            assertEquals(ReaderFont.CUSTOM, repo.settings.first().font)
            repo.selectAppFont(body.id)
            repo.selectAppFont(null)
            assertEquals(ReaderFont.CUSTOM, repo.settings.first().font)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun classifyingImagesKeepsDayAndNightSelectionsAndLegacyAssets() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = repository(scope)
            val day = ReaderImageAsset("day", "背景", "/app/day.png", purpose = ReaderImagePurpose.BACKGROUND)
            val night = ReaderImageAsset("night", "夜间", "/app/night.png")
            repo.addReaderImage(day, selectAsBackground = true)
            repo.addReaderImage(night)
            repo.selectBackgroundImage(night.id, ReaderThemeSlot.NIGHT)
            repo.setReaderImagePurpose(day.id, ReaderImagePurpose.COVER)
            val settings = repo.settings.first()
            assertEquals(day.id, settings.selectedBackgroundImageId)
            assertEquals(night.id, settings.nightSelectedBackgroundImageId)
            assertEquals(ReaderImagePurpose.COVER, settings.imageLibrary.first { it.id == day.id }.purpose)
            assertEquals(day.filePath, settings.imageLibrary.first { it.id == day.id }.filePath)
            val legacy = ReaderImageLibraryCodec.decode("""[{"id":"old","displayName":"old","filePath":"/old.png"}]""").single()
            assertEquals(ReaderImagePurpose.GENERAL, legacy.purpose)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
