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
import kotlinx.coroutines.CancellationException

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
        if (requestId != null && storedRequest == null) {
            return Result.failure(summaryData(0, 1, "批量导入任务清单已丢失，请重新选择文件"))
        }

        // 保留旧字段读取，避免升级时丢掉已经排入 WorkManager 的历史任务。
        val uris = storedRequest?.entries?.map(BatchImportRequestEntry::uri)
            ?: inputData.getStringArray(KEY_URIS).orEmpty().toList()
        val groupPaths = storedRequest?.entries?.map(BatchImportRequestEntry::groupPath)
            ?: inputData.getStringArray(KEY_GROUP_PATHS).orEmpty().toList()
        // 局域网收件箱里的临时文件导完就删；SAF 选来的用户文件当然不能碰。
        val deleteAfterImport = storedRequest?.deleteSourceAfterImport
            ?: inputData.getBoolean(KEY_DELETE_SOURCE, false)
        if (uris.isEmpty()) {
            requestId?.let(requestStore::delete)
            return Result.success(summaryData(0, 0, ""))
        }

        return try {
            setForeground(foregroundInfo(0, uris.size, ""))
            var succeeded = 0
            val failures = mutableListOf<String>()

            uris.forEachIndexed { index, raw ->
                val uri = Uri.parse(raw)
                val label = displayNameOf(uri)
                setProgress(progressData(index, uris.size, label))
                setForegroundAsync(foregroundInfo(index, uris.size, label))
                try {
                    val bookId = gateway.importDirectly(uri)
                    groupPaths.getOrNull(index)?.takeIf(String::isNotBlank)?.let { path ->
                        shelfRepository.createOrGetGroupPath(path)?.let { groupId ->
                            shelfRepository.setBookGroup(listOf(bookId), groupId)
                        }
                    }
                    succeeded++
                    if (deleteAfterImport) {
                        runCatching { uri.path?.let { File(it).delete() } }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures += "$label：${error.message ?: "导入失败"}"
                }
            }

            Result.success(
                summaryData(
                    succeeded,
                    failures.size,
                    failures.take(MAX_REPORTED_FAILURES).joinToString("\n")
                )
            )
        } finally {
            requestId?.let(requestStore::delete)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val storedCount = inputData.getString(KEY_REQUEST_ID)
            ?.let(requestStore::read)
            ?.entries
            ?.size
        return foregroundInfo(0, storedCount ?: inputData.getStringArray(KEY_URIS)?.size ?: 0, "")
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
        const val UNIQUE_WORK_NAME = "batch-book-import"

        private const val CHANNEL_ID = "book_import"
        private const val NOTIFICATION_ID = 4_200
        private const val MAX_REPORTED_FAILURES = 8
        private const val MAX_DETAIL_CHARS = 1_200

        fun request(requestId: String) =
            OneTimeWorkRequestBuilder<BatchImportWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .build()
    }
}
