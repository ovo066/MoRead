package com.mozhi.reader.feature.importer

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.library.BookLayoutStore
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
    fun bookLayoutStore(): BookLayoutStore
}

/**
 * Backfills plain text and repairs missing or stale native EPUB layout sidecars.
 *
 * Idempotent: complete books are skipped after checking both text and native-layout state, while an
 * interrupted run simply rematerializes the affected book on the next attempt.
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
        val layoutStore = entryPoint.bookLayoutStore()
        val pending = ArrayList<BookEntity>()
        for (book in repository.getBooks()) {
            val chapterLengths = if (book.sourceType == BookSourceType.EPUB) {
                repository.getChapters(book.id)
                    .sortedBy { it.chapterIndex }
                    .map { it.charCount }
            } else {
                emptyList()
            }
            val needsRepair = book.textVersion < LibraryRepository.CURRENT_TEXT_VERSION ||
                book.sourceType == BookSourceType.EPUB &&
                !layoutStore.hasCurrentLayout(book.id, chapterLengths)
            if (needsRepair) pending += book
        }
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

    private fun successResult(): Result = Result.success()

    companion object {
        private const val UNIQUE_WORK_NAME = "book-text-materialize"

        fun enqueueStartup(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BookTextMaterializeWorker>()
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
