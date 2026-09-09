package com.mozhi.reader.core.datastore

import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnnotationSettingsPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    // Android FileStorage uses File.renameTo, which cannot replace an existing file on
    // Windows. Keep real protobuf persistence while using Okio's portable atomic move.
    private fun createStore(name: String, scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = { File(temporary.root, name).absolutePath.toPath() }
        ),
        scope = scope
    )

    @Test fun quotaIgnoresObsoleteChapterKeyAndPreservesDailyAccounting() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = createStore("quota.preferences_pb", scope)
            val obsoleteKey = stringSetPreferencesKey("proactive_annotation_attempted_chapters")
            val oldAttempts = setOf("7:3")
            store.edit { it[obsoleteKey] = oldAttempts }
            val quota = ProactiveAnnotationQuota(store)
            val limits = ProactiveAnnotationLimits(dailyMax = 2)
            assertTrue(quota.reserve(limits, true, true, today = 10).accepted)
            quota.recordCreated(2, 1, 1, today = 10)
            assertEquals(ProactiveAnnotationQuotaState(10, annotationCount = 2, voiceCount = 1, imageCount = 1),
                quota.snapshot(today = 10))
            assertFalse(quota.reserve(limits, true, true, today = 10).accepted)
            assertEquals(oldAttempts, store.data.first()[obsoleteKey])

            assertEquals(ProactiveAnnotationQuotaState(11), quota.snapshot(today = 11))
            assertTrue(quota.reserve(limits, true, true, today = 11).accepted)
            quota.recordCreated(1, 2, 3, today = 11)
            assertEquals(ProactiveAnnotationQuotaState(11, annotationCount = 1, voiceCount = 2, imageCount = 3),
                quota.snapshot(today = 11))
            quota.recordCreated(1, 0, 0, today = 12)
            assertEquals(ProactiveAnnotationQuotaState(12, annotationCount = 1), quota.snapshot(today = 12))
            // Old installations keep the unknown preference untouched; no migration/reset is necessary.
            assertEquals(oldAttempts, store.data.first()[obsoleteKey])
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun newKeysDefaultConservativelyAndRoundTrip() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = ReaderSettingsRepository(createStore("reader.preferences_pb", scope))
            assertFalse(repo.companionAutonomySettings.first().proactiveAnnotationsEnabled)
            assertEquals(ProactiveAnnotationNotice.BUILT_IN, repo.companionAutonomySettings.first().annotationNotice)
            assertEquals(ProactiveAnnotationTiming.AFTER_CHAPTER_COMPLETE, repo.companionAutonomySettings.first().annotationLimits.timing)
            assertEquals(WidePageLayout.SINGLE, repo.settings.first().widePageLayout)
            assertFalse(repo.settings.first().companionSidePaneEnabled)
            repo.setWidePageLayout(WidePageLayout.DUAL)
            repo.setCompanionSidePaneEnabled(true)
            repo.setCompanionAnnotationNotice(ProactiveAnnotationNotice.FAST_MODEL)
            val limits = ProactiveAnnotationLimits(timing = ProactiveAnnotationTiming.ON_CHAPTER_ENTRY, aheadChapters = 3)
            repo.setCompanionAnnotationLimits(limits)
            repo.setCompanionAnnotationLimitsForBook(7, BookProactiveAnnotationLimits(true, limits.copy(aheadChapters = 5)))
            assertEquals(WidePageLayout.DUAL, repo.settings.first().widePageLayout)
            assertTrue(repo.settings.first().companionSidePaneEnabled)
            assertEquals(ProactiveAnnotationNotice.FAST_MODEL, repo.companionAutonomySettings.first().annotationNotice)
            assertEquals(limits, repo.companionAutonomySettings.first().annotationLimits)
            assertEquals(5, repo.companionAutonomySettings.first().annotationLimitsFor(7).aheadChapters)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }
}
