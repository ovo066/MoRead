package com.mozhi.reader.core.datastore

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderMarginLimitsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer,
            producePath = { File(temporary.root, "margins.preferences_pb").absolutePath.toPath() }), scope = scope)

    private fun assertMargins(settings: ReaderSettings, left: Float, right: Float, top: Float, bottom: Float) {
        assertEquals(left, settings.pageMarginLeft, 0f)
        assertEquals(right, settings.pageMarginRight, 0f)
        assertEquals(top, settings.pageMarginTop, 0f)
        assertEquals(bottom, settings.pageMarginBottom, 0f)
    }

    @Test
    fun `边距允许新端点且越界与非有限值安全回落`() {
        listOf(-1.4f, -0.5f, 0f, 1f, 3f).forEach { assertEquals(it, ReaderMarginLimits.horizontal(it), 0f) }
        listOf(0f, 0.5f, 1f, 3f).forEach { assertEquals(it, ReaderMarginLimits.vertical(it), 0f) }
        assertEquals(-1.4f, ReaderMarginLimits.horizontal(-9f), 0f)
        assertEquals(3f, ReaderMarginLimits.horizontal(9f), 0f)
        assertEquals(0f, ReaderMarginLimits.vertical(-9f), 0f)
        assertEquals(3f, ReaderMarginLimits.vertical(9f), 0f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertEquals(1f, ReaderMarginLimits.horizontal(it), 0f)
            assertEquals(0f, ReaderMarginLimits.vertical(it), 0f)
        }
    }

    @Test
    fun `左右负值与上下零值及新上限持久化后可重新读取`() = runTest {
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ReaderSettingsRepository(store(firstScope))
            repository.setPageMarginLeft(-1.4f)
            repository.setPageMarginRight(3f)
            repository.setPageMarginTop(0f)
            repository.setPageMarginBottom(3f)
            assertMargins(repository.settings.first(), -1.4f, 3f, 0f, 3f)
        } finally {
            firstScope.coroutineContext.job.cancelAndJoin()
        }
        val reopenedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ReaderSettingsRepository(store(reopenedScope))
            assertMargins(repository.settings.first(), -1.4f, 3f, 0f, 3f)
            repository.setPageMarginLeft(3f)
            repository.setPageMarginRight(-1.4f)
            repository.setPageMarginTop(3f)
            repository.setPageMarginBottom(0f)
            assertMargins(repository.settings.first(), 3f, -1.4f, 3f, 0f)
        } finally {
            reopenedScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `设置写入与原始偏好读取均校验越界和非有限值`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = store(scope)
            val repository = ReaderSettingsRepository(data)
            val cases = listOf(
                -9f to (-1.4f to 0f), 9f to (3f to 3f),
                Float.NaN to (1f to 0f), Float.POSITIVE_INFINITY to (1f to 0f), Float.NEGATIVE_INFINITY to (1f to 0f)
            )
            for ((value, expected) in cases) {
                repository.setPageMarginLeft(value)
                repository.setPageMarginRight(value)
                repository.setPageMarginTop(value)
                repository.setPageMarginBottom(value)
                val stored = data.data.first()
                assertEquals(expected.first, stored[floatPreferencesKey("reader_page_margin_left")]!!, 0f)
                assertEquals(expected.first, stored[floatPreferencesKey("reader_page_margin_right")]!!, 0f)
                assertEquals(expected.second, stored[floatPreferencesKey("reader_page_margin_top")]!!, 0f)
                assertEquals(expected.second, stored[floatPreferencesKey("reader_page_margin_bottom")]!!, 0f)
                // 模拟备份恢复或旧版偏好中的原始值，确保读取端也有校验。
                data.edit { preferences ->
                    listOf("left", "right", "top", "bottom").forEach { edge ->
                        preferences[floatPreferencesKey("reader_page_margin_$edge")] = value
                    }
                }
                assertMargins(repository.settings.first(), expected.first, expected.first, expected.second, expected.second)
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `应用主题与绑定主题编辑都保留放宽边距`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = ReaderSettingsRepository(store(scope))
            val theme = CustomReaderTheme(7L, "边距", 1, 2, 3,
                pageMarginLeft = -1.4f, pageMarginRight = 3f, pageMarginTop = 0f, pageMarginBottom = 3f)
            repository.saveCustomTheme(theme)
            assertMargins(repository.settings.first(), -1.4f, 3f, 0f, 3f)
            repository.updateBoundTypography(11L, ReaderThemeSlot.DAY) { it.copy(
                pageMarginLeft = 3f, pageMarginRight = -1.4f, pageMarginTop = 3f, pageMarginBottom = 0f
            ) }
            assertMargins(repository.settings.first().resolveForBook(11L, ReaderThemeSlot.DAY), 3f, -1.4f, 3f, 0f)
            repository.saveCustomTheme(theme.copy(pageMarginLeft = -9f, pageMarginRight = 9f, pageMarginTop = Float.NaN, pageMarginBottom = Float.POSITIVE_INFINITY))
            assertMargins(repository.settings.first(), -1.4f, 3f, 0f, 0f)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun `旧统一边距迁移与页眉页脚偏移仍使用原范围`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = store(scope)
            data.edit { it[floatPreferencesKey("reader_page_margin")] = 1.6f }
            val repository = ReaderSettingsRepository(data)
            assertMargins(repository.settings.first(), 1.6f, 1.6f, 0f, 0f)
            repository.setPageMargin(9f)
            repository.setHeaderMarginTop(-1f)
            repository.setFooterMarginBottom(3f)
            val settings = repository.settings.first()
            assertEquals(2f, settings.pageMargin, 0f)
            assertMargins(settings, 2f, 2f, 2f, 2f)
            assertEquals(0f, settings.headerMarginTop, 0f)
            assertEquals(2f, settings.footerMarginBottom, 0f)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
