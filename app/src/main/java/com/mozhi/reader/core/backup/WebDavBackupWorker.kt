package com.mozhi.reader.core.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mozhi.reader.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** WebDAV 后台备份 Worker；保留类名以兼容旧版已排队任务。 */
class WebDavBackupWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            BackupWorkerEntryPoint::class.java
        )
        val settings = entryPoint.settingsStore().current()
        val automatic = inputData.getBoolean(KEY_AUTOMATIC, false)
        if (automatic && (!settings.autoBackup || !settings.configured)) return Result.success()
        if (!settings.configured) return Result.failure(errorData("请先配置 WebDAV"))
        val mode = inputData.getString(KEY_MODE)
            ?.let { runCatching { BackupMode.valueOf(it) }.getOrNull() }
            ?: if (automatic) BackupMode.LIGHTWEIGHT else BackupMode.FULL

        setForeground(foregroundInfo("正在准备备份", 0, indeterminate = true))
        var lastPublishedPercent = -1
        var lastPublishedPhase = ""
        var lastPublishedAt = 0L
        return try {
            val remote = entryPoint.repository().backupToWebDav(mode) { progress ->
                val now = System.nanoTime()
                val phaseChanged = progress.phase != lastPublishedPhase
                val enoughTimePassed = now - lastPublishedAt >= PROGRESS_UPDATE_INTERVAL_NANOS
                if (phaseChanged || progress.percent == 100 ||
                    (progress.percent != lastPublishedPercent && enoughTimePassed)
                ) {
                    lastPublishedPercent = progress.percent
                    lastPublishedPhase = progress.phase
                    lastPublishedAt = now
                    setProgressAsync(
                        workDataOf(
                            KEY_PROGRESS to progress.percent,
                            KEY_PHASE to progress.phase,
                            KEY_COMPLETED_BYTES to progress.completedBytes,
                            KEY_TOTAL_BYTES to progress.totalBytes
                        )
                    )
                    setForegroundAsync(
                        foregroundInfo(
                            progress.phase,
                            progress.percent,
                            indeterminate = progress.totalBytes <= 0L
                        )
                    )
                }
            }
            Result.success(
                workDataOf(
                    KEY_REMOTE_NAME to remote.name,
                    KEY_MODE to mode.name,
                    KEY_PROGRESS to 100,
                    KEY_PHASE to "备份完成"
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (automatic) Result.retry() else Result.failure(errorData(error.message ?: "备份失败"))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo("正在准备备份", 0, indeterminate = true)

    private fun foregroundInfo(phase: String, progress: Int, indeterminate: Boolean): ForegroundInfo {
        ensureChannel()
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(phase, progress, indeterminate),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun buildNotification(phase: String, progress: Int, indeterminate: Boolean): Notification {
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("墨知 WebDAV 备份")
            .setContentText(phase)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()
    }

    private fun ensureChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "云备份",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "WebDAV 备份进度" }
            )
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "webdav-daily-backup"
        const val UNIQUE_MANUAL_WORK = "cloud-backup-now"
        const val KEY_MODE = "backup_mode"
        const val KEY_AUTOMATIC = "automatic"
        const val KEY_PROGRESS = "progress"
        const val KEY_PHASE = "phase"
        const val KEY_COMPLETED_BYTES = "completed_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_REMOTE_NAME = "remote_name"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "cloud_backup"
        private const val NOTIFICATION_ID = 4_310
        private const val PROGRESS_UPDATE_INTERVAL_NANOS = 250_000_000L

        private fun errorData(message: String) = workDataOf(KEY_ERROR to message.take(1_000))
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupWorkerEntryPoint {
    fun repository(): BackupRepository
    fun settingsStore(): BackupSettingsStore
}

object WebDavBackupScheduler {
    fun update(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WebDavBackupWorker.UNIQUE_PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(24, TimeUnit.HOURS)
            .setInputData(
                workDataOf(
                    WebDavBackupWorker.KEY_AUTOMATIC to true,
                    WebDavBackupWorker.KEY_MODE to BackupMode.LIGHTWEIGHT.name
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            WebDavBackupWorker.UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueNow(context: Context, mode: BackupMode) {
        val request = OneTimeWorkRequestBuilder<WebDavBackupWorker>()
            .setInputData(
                workDataOf(
                    WebDavBackupWorker.KEY_AUTOMATIC to false,
                    WebDavBackupWorker.KEY_MODE to mode.name
                )
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WebDavBackupWorker.UNIQUE_MANUAL_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
