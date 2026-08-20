package com.mozhi.reader.ai.audiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mozhi.reader.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudiobookProductionEntryPoint {
    fun audiobookProducer(): AudiobookProducer
}

class AudiobookProductionWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val producer by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            AudiobookProductionEntryPoint::class.java
        ).audiobookProducer()
    }

    override suspend fun doWork(): Result {
        val bookId = inputData.getLong(KEY_BOOK_ID, -1L)
        val chapters = inputData.getIntArray(KEY_CHAPTERS)?.toList().orEmpty()
        if (bookId <= 0 || chapters.isEmpty()) return Result.failure(
            workDataOf(KEY_ERROR to "缺少有声书制作范围")
        )
        return try {
            setForeground(createForegroundInfo("准备合成", 0, 0))
            val summary = producer.produce(bookId, chapters) { progress ->
                val data = progress.toData()
                setProgress(data)
                setForeground(
                    createForegroundInfo(
                        "第 ${progress.chapterIndex + 1} 章 · ${progress.chapterTitle}",
                        progress.completedSegments,
                        progress.totalSegments
                    )
                )
            }
            Result.success(
                workDataOf(
                    KEY_COMPLETED to summary.completedSegments,
                    KEY_TOTAL to summary.totalSegments,
                    KEY_READY_CHAPTERS to summary.readyChapters
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: "有声书制作失败").take(300)))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo("准备合成", 0, 0)

    private fun AudiobookProductionProgress.toData() = workDataOf(
        KEY_CHAPTER_INDEX to chapterIndex,
        KEY_CHAPTER_TITLE to chapterTitle,
        KEY_COMPLETED to completedSegments,
        KEY_TOTAL to totalSegments
    )

    private fun createForegroundInfo(message: String, completed: Int, total: Int): ForegroundInfo {
        ensureNotificationChannel()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (inputData.getLong(KEY_BOOK_ID, 0L) % 1_000).toInt(),
            buildNotification(message, completed, total),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun buildNotification(message: String, completed: Int, total: Int): Notification {
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
            .setContentTitle("墨知正在制作有声书")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(total, completed.coerceAtMost(total), total <= 0)
            .build()
    }

    private fun ensureNotificationChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "有声书制作", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "显示 AI 有声书后台合成进度" }
            )
    }

    companion object {
        const val KEY_BOOK_ID = "book_id"
        const val KEY_CHAPTERS = "chapters"
        const val KEY_CHAPTER_INDEX = "chapter_index"
        const val KEY_CHAPTER_TITLE = "chapter_title"
        const val KEY_COMPLETED = "completed_segments"
        const val KEY_TOTAL = "total_segments"
        const val KEY_READY_CHAPTERS = "ready_chapters"
        const val KEY_ERROR = "error"
        private const val CHANNEL_ID = "audiobook_production"
        private const val NOTIFICATION_ID_BASE = 6_200

        fun uniqueWorkName(bookId: Long) = "audiobook-production-$bookId"

        fun enqueue(context: Context, bookId: Long, chapters: List<Int>) {
            val request = OneTimeWorkRequestBuilder<AudiobookProductionWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId, KEY_CHAPTERS to chapters.toIntArray()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(bookId),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun pause(context: Context, bookId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(bookId))
        }
    }
}
