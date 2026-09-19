package com.mozhi.reader.core.datastore

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mozhi.reader.ui.theme.*
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppearancePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
            producePath = { File(temporary.root, "appearance.preferences_pb").absolutePath.toPath() }), scope = scope)

    @Test fun freshAndInvalidPreferencesUseTheExistingAppearance() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope)
            val repo = ReaderSettingsRepository(store)
            assertEquals(AppearanceSettings(), repo.appearance.first())
            store.edit { prefs ->
                listOf("app_theme_mode", "accent_preset", "color_scheme_preset", "semantic_harmony", "surface_style", "nav_style", "shape_style")
                    .forEach { prefs[stringPreferencesKey(it)] = "unknown-future-value" }
            }
            assertEquals(AppearanceSettings(), repo.appearance.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun oldThemeAndAccentAreNotResetByNewKeys() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope)
            store.edit {
                it[stringPreferencesKey("app_theme_mode")] = ThemeMode.DARK.name
                it[stringPreferencesKey("accent_preset")] = AccentPreset.SEAL.name
            }
            assertEquals(AppearanceSettings(themeMode = ThemeMode.DARK, accent = AccentPreset.SEAL),
                ReaderSettingsRepository(store).appearance.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun choosingASchemeAtomicallyAppliesRecommendationsAndKeepsOtherChoices() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ReaderSettingsRepository(store(scope))
            repo.setNavStyle(NavStyle.BAR)
            repo.setSemanticHarmony(SemanticHarmony.MONO)
            repo.setThemeMode(ThemeMode.DARK)
            repo.setCustomAccent(0xFF123456.toInt())
            ColorSchemePreset.entries.forEach { preset ->
                repo.setNavStyle(NavStyle.BAR)
                repo.setSemanticHarmony(SemanticHarmony.MONO)
                repo.setColorScheme(preset)
                val appearance = repo.appearance.first()
                assertEquals(preset, appearance.colorScheme)
                assertEquals(AccentPreset.FOLLOW, appearance.accent)
                assertNull(appearance.customAccentArgb)
                assertEquals(preset.recommendedSurface, appearance.surfaceStyle)
                assertEquals(preset.recommendedShape, appearance.shapeStyle)
                assertEquals(if (preset == ColorSchemePreset.NEUTRAL) NavStyle.FLOATING_DOCK else NavStyle.BAR, appearance.navStyle)
                assertEquals(if (preset == ColorSchemePreset.NEUTRAL) SemanticHarmony.MULTI else SemanticHarmony.MONO, appearance.semanticHarmony)
                assertEquals(ThemeMode.DARK, appearance.themeMode)
            }
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun allDimensionsSurviveADataStoreRestart() = runTest {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val expected: AppearanceSettings
        try {
            val repo = ReaderSettingsRepository(store(firstScope))
            repo.setColorScheme(ColorSchemePreset.ROSE_DUST)
            repo.setSemanticHarmony(SemanticHarmony.MONO)
            repo.setSurfaceStyle(SurfaceStyle.GLASS)
            repo.setNavStyle(NavStyle.BAR)
            repo.setShapeStyle(ShapeStyle.STANDARD)
            expected = repo.appearance.first()
        } finally { firstScope.coroutineContext.job.cancelAndJoin() }
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try { assertEquals(expected, ReaderSettingsRepository(store(nextScope)).appearance.first()) }
        finally { nextScope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun originalPresetRestoresTheWholeClassicInterface() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ReaderSettingsRepository(store(scope))
            repo.setColorScheme(ColorSchemePreset.HAZE_BLUE)
            repo.setNavStyle(NavStyle.BAR)
            repo.setSemanticHarmony(SemanticHarmony.MONO)
            repo.setCustomAccent(0xFF0077FF.toInt())
            repo.setColorScheme(ColorSchemePreset.NEUTRAL)
            assertEquals(AppearanceSettings(), repo.appearance.first())
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test @Config(sdk = [30]) fun wallpaperPreferenceFallsBackOnOlderAndroid() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope)
            store.edit { it[stringPreferencesKey("color_scheme_preset")] = ColorSchemePreset.DYNAMIC.name }
            val repo = ReaderSettingsRepository(store)
            assertEquals(ColorSchemePreset.NEUTRAL, repo.appearance.first().colorScheme)
            repo.setColorScheme(ColorSchemePreset.DYNAMIC)
            assertEquals(SurfaceStyle.GLASS, repo.appearance.first().surfaceStyle)
            assertEquals(ShapeStyle.STANDARD, repo.appearance.first().shapeStyle)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
