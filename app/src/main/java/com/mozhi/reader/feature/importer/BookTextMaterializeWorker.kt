package com.mozhi.reader.feature.importer

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BookTextMaterializeEntryPoint {
    fun importCoordinator(): ImportCoordinator
    fun libraryRepository(): LibraryRepository
}

/**
 * Backfills plain text for books imported before it was stored.
 *
 * Idempotent: a book is only touched while its `textVersion` is behind, and that flag is raised in
 * the same transaction that records the byte ranges, so an interrupted run simply redoes the book.
 */
class BookTextMaterializeWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            BookTextMaterializeEntryPoint::class.java
        )
    }

    override suspend fun doWork(): Result {
        val repository = entryPoint.libraryRepository()
        val coordinator = entryPoint.importCoordinator()
        val pending = repository.booksNeedingText()
        if (pending.isEmpty()) return successResult()

        var failed = false
        pending.forEach { book ->
            val materialized = runCatching { coordinator.materializeLegacyBook(book) }
                .getOrDefault(false)
            if (!materialized) failed = true
        }
        if (failed) return Result.retry()

        // 向量索引已改为按需（首次检索时按书触发），正文补齐后不再自动排全库扫描。
        return successResult()
    }

    private fun successResult(): Result {
        if (inputData.getBoolean(INPUT_STARTUP_SCAN, false)) {
            applicationContext.getSharedPreferences(
                MAINTENANCE_PREFERENCES,
                Context.MODE_PRIVATE
            ).edit()
                .putInt(PREFERENCE_STARTUP_SCAN_VERSION, LibraryRepository.CURRENT_TEXT_VERSION)
                .apply()
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "book-text-materialize"
        private const val INPUT_STARTUP_SCAN = "startup-scan"
        private const val MAINTENANCE_PREFERENCES = "app-maintenance"
        private const val PREFERENCE_STARTUP_SCAN_VERSION = "text-startup-scan-version"

        fun enqueueStartup(context: Context) {
            val targetVersion = LibraryRepository.CURRENT_TEXT_VERSION
            val completedVersion = context.getSharedPreferences(
                MAINTENANCE_PREFERENCES,
                Context.MODE_PRIVATE
            ).getInt(PREFERENCE_STARTUP_SCAN_VERSION, 0)
            if (completedVersion >= targetVersion) return

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BookTextMaterializeWorker>()
                    .setInputData(workDataOf(INPUT_STARTUP_SCAN to true))
                    .setInitialDelay(3, TimeUnit.SECONDS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresStorageNotLow(true)
                            .build()
                    )
                    .build()
            )
        }
    }
}
