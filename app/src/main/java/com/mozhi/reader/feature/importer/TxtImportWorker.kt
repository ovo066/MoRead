package com.mozhi.reader.feature.importer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mozhi.reader.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TxtImportWorkerEntryPoint {
    fun importCoordinator(): ImportCoordinator
}

class TxtImportWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val coordinator: ImportCoordinator by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            TxtImportWorkerEntryPoint::class.java
        ).importCoordinator()
    }

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
            ?: return Result.failure(errorData("缺少导入会话"))
        val title = inputData.getString(KEY_TITLE)
            ?: return Result.failure(errorData("缺少书名"))
        val author = inputData.getString(KEY_AUTHOR).orEmpty()

        val initialProgress = ImportProgress("准备后台导入")
        setForeground(createForegroundInfo(sessionId, initialProgress))
        setProgress(initialProgress.toData())

        return try {
            val bookId = coordinator.confirmTxt(
                sessionId = sessionId,
                title = title,
                author = author
            ) { progress ->
                setProgressAsync(progress.toData())
                setForegroundAsync(createForegroundInfo(sessionId, progress))
            }
            Result.success(workDataOf(KEY_BOOK_ID to bookId))
        } catch (error: Throwable) {
            Result.failure(errorData(error.message ?: "后台导入失败"))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val sessionId = inputData.getString(KEY_SESSION_ID).orEmpty()
        return createForegroundInfo(sessionId, ImportProgress("准备后台导入"))
    }

    private fun createForegroundInfo(
        sessionId: String,
        progress: ImportProgress
    ): ForegroundInfo {
        ensureNotificationChannel()
        val notificationId = NOTIFICATION_ID_BASE + (sessionId.hashCode() and 0x0FFF)
        val notification = buildNotification(progress)
        return ForegroundInfo(
            notificationId,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun buildNotification(progress: ImportProgress): Notification {
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val fraction = progress.fraction
        val percent = fraction?.let { (it * 100).toInt() }
        val content = if (percent == null) progress.message else "${progress.message} · $percent%"

        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("墨知正在导入书籍")
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(
                progress.total.coerceAtLeast(0),
                progress.completed.coerceAtLeast(0),
                progress.total <= 0
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "书籍导入",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示大型 TXT 与 EPUB 的后台导入进度"
            }
        )
    }

    private fun ImportProgress.toData(): Data = workDataOf(
        KEY_PROGRESS_MESSAGE to message,
        KEY_PROGRESS_COMPLETED to completed,
        KEY_PROGRESS_TOTAL to total
    )

    private fun errorData(message: String): Data =
        workDataOf(KEY_ERROR_MESSAGE to message.take(MAX_ERROR_LENGTH))

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_TITLE = "title"
        const val KEY_AUTHOR = "author"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_PROGRESS_COMPLETED = "progress_completed"
        const val KEY_PROGRESS_TOTAL = "progress_total"

        private const val NOTIFICATION_CHANNEL_ID = "book_import"
        private const val NOTIFICATION_ID_BASE = 4_100
        private const val MAX_ERROR_LENGTH = 300

        fun uniqueWorkName(sessionId: String): String = "txt-import-$sessionId"
    }
}
