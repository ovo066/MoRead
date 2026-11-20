package com.mozhi.reader.feature.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mozhi.reader.MainActivity
import com.mozhi.reader.core.importer.BookImportGateway
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BatchImportWorkerEntryPoint {
    fun bookImportGateway(): BookImportGateway
    fun shelfOrganizationRepository(): ShelfOrganizationRepository
    fun batchImportRequestStore(): BatchImportRequestStore
}

/**
 * 多选 / 文件夹 / 局域网传书共用的批量导入。逐本导入、单本失败不影响其余，
 * 结束后汇总「成功 N 本，失败 M 本」。走前台服务是因为一次几十本可能跑好几分钟，
 * 用户多半会切走。
 */
class BatchImportWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    private val gateway: BookImportGateway by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            BatchImportWorkerEntryPoint::class.java
        ).bookImportGateway()
    }
    private val shelfRepository: ShelfOrganizationRepository by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            BatchImportWorkerEntryPoint::class.java
        ).shelfOrganizationRepository()
    }
    private val requestStore: BatchImportRequestStore by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            BatchImportWorkerEntryPoint::class.java
        ).batchImportRequestStore()
    }

    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID)
        val storedRequest = requestId?.let(requestStore::read)
        if (requestId != null && storedRequest == null)
            return Result.failure(summaryData(0, 1, "批量导入任务清单已丢失或损坏，请重新选择文件"))

        // Upgrade compatibility for jobs enqueued before request manifests were introduced.
        val request = storedRequest ?: BatchImportRequestPayload(
            inputData.getStringArray(KEY_URIS).orEmpty().mapIndexed { index, uri ->
                BatchImportRequestEntry(uri, inputData.getStringArray(KEY_GROUP_PATHS)?.getOrNull(index).orEmpty())
            }, inputData.getBoolean(KEY_DELETE_SOURCE, false)
        )
        val total = request.entries.size
        if (total == 0) {
            requestId?.let(requestStore::delete)
            return Result.success(summaryData(0, 0, ""))
        }

        setForeground(foregroundInfo(request.checkpoint.nextIndex, total, ""))
        val reporter = BatchImportProgressReporter(CoroutineScope(currentCoroutineContext())) { progress ->
            val label = displayNameOf(Uri.parse(progress.uri)).take(200)
            val stage = progress.stage.ifBlank { applicationContext.getString(com.mozhi.reader.R.string.import_preparing) }
            setProgress(workDataOf(
                KEY_PROGRESS_COMPLETED to progress.index, KEY_PROGRESS_TOTAL to total,
                KEY_PROGRESS_LABEL to label, KEY_PROGRESS_STAGE to stage,
                KEY_PROGRESS_BOOK_COMPLETED to progress.completed, KEY_PROGRESS_BOOK_TOTAL to progress.total
            ))
            val percent = if (progress.total > 0)
                " · ${(progress.completed.toLong() * 100 / progress.total).coerceIn(0, 100)}%" else ""
            setForeground(foregroundInfo(progress.index, total, "$label · $stage$percent"))
        }
        val result = try { BatchImportRunner(
            importBook = { raw, progress -> gateway.importDirectly(Uri.parse(raw), progress) },
            assignGroup = { bookId, path ->
                shelfRepository.createOrGetGroupPath(path)?.let { groupId ->
                    shelfRepository.setBookGroup(listOf(bookId), groupId)
                }
            },
            persist = { checkpoint ->
                requestId?.let { requestStore.checkpoint(it, checkpoint) }
            },
            deleteSource = { raw ->
                val uri = Uri.parse(raw)
                if (uri.scheme == "file") uri.path?.let { File(it).delete() }
            },
            progress = { index, raw, stage, completed, bookTotal ->
                reporter.update(BatchBookProgress(index, raw, stage, completed, bookTotal))
            },
            labelOf = { displayNameOf(Uri.parse(it)) },
            failureMessage = applicationContext.getString(com.mozhi.reader.R.string.import_failed),
            describeFailure = { error ->
                (error as? com.mozhi.reader.core.importer.ImportFailureException)?.uiText?.resolve(applicationContext.resources)
                    ?: error.message ?: applicationContext.getString(com.mozhi.reader.R.string.import_failed)
            }
        ).run(request) } finally {
            withContext(NonCancellable) { reporter.close() }
        }

        val detail = (result.failures + listOfNotNull(
            result.skipped.takeIf { it > 0 }?.let {
                applicationContext.getString(com.mozhi.reader.R.string.import_skipped_duplicates,
                    it, result.skippedLabels.joinToString(", "))
            }
        )).joinToString("\n")
        // Keep the completed checkpoint until expiry too: process death between this return and
        // WorkManager committing SUCCESS must not turn a finished batch into a missing-manifest failure.
        setProgress(progressData(total, total, ""))
        return Result.success(summaryData(result.succeeded, result.failed, detail))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val stored = inputData.getString(KEY_REQUEST_ID)?.let(requestStore::read)
        return foregroundInfo(stored?.checkpoint?.nextIndex ?: 0,
            stored?.entries?.size ?: inputData.getStringArray(KEY_URIS)?.size ?: 0, "")
    }

    private fun displayNameOf(uri: Uri): String {
        val fromPath = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        return fromPath?.takeIf(String::isNotBlank) ?: "书籍"
    }

    private fun foregroundInfo(completed: Int, total: Int, label: String): ForegroundInfo {
        ensureChannel()
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(completed, total, label),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun buildNotification(completed: Int, total: Int, label: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在批量导入书籍")
            .setContentText(
                if (label.isBlank()) "共 $total 本" else "第 ${completed + 1}/$total 本 · $label"
            )
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total.coerceAtLeast(1), completed, false)
            .build()
    }

    private fun ensureChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "批量导入",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "多选、文件夹与局域网传书的导入进度" }
            )
    }

    private fun progressData(completed: Int, total: Int, label: String): Data = workDataOf(
        KEY_PROGRESS_COMPLETED to completed,
        KEY_PROGRESS_TOTAL to total,
        KEY_PROGRESS_LABEL to label
    )

    private fun summaryData(succeeded: Int, failed: Int, detail: String): Data = workDataOf(
        KEY_SUCCEEDED to succeeded,
        KEY_FAILED to failed,
        KEY_FAILURE_DETAIL to detail.take(MAX_DETAIL_CHARS)
    )

    companion object {
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_URIS = "uris"
        const val KEY_DELETE_SOURCE = "delete_source"
        const val KEY_GROUP_PATHS = "group_paths"
        const val KEY_SUCCEEDED = "succeeded"
        const val KEY_FAILED = "failed"
        const val KEY_FAILURE_DETAIL = "failure_detail"
        const val KEY_PROGRESS_COMPLETED = "progress_completed"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_LABEL = "progress_label"
        const val KEY_PROGRESS_STAGE = "progress_stage"
        const val KEY_PROGRESS_BOOK_COMPLETED = "progress_book_completed"
        const val KEY_PROGRESS_BOOK_TOTAL = "progress_book_total"
        const val UNIQUE_WORK_NAME = "batch-book-import"

        private const val CHANNEL_ID = "book_import"
        private const val NOTIFICATION_ID = 4_200
        private const val MAX_DETAIL_CHARS = 1_200

        fun request(requestId: String) =
            OneTimeWorkRequestBuilder<BatchImportWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .build()
    }
}
