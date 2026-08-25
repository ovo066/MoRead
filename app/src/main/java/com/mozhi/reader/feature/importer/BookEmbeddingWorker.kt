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
import com.mozhi.reader.ai.embedding.BookEmbeddingScheduler
import com.mozhi.reader.ai.embedding.BookEmbeddingPipeline
import com.mozhi.reader.ai.embedding.EmbedOutcome
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.vector.VectorQueries
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import io.objectbox.BoxStore

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BookEmbeddingEntryPoint {
    fun libraryRepository(): LibraryRepository
    fun embeddingPipeline(): BookEmbeddingPipeline
    fun embeddingSettingsStore(): BookEmbeddingSettingsStore
    fun vectorStore(): BoxStore
}

@Singleton
class WorkBookEmbeddingScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : BookEmbeddingScheduler {
    override fun enqueueForBook(bookId: Long, resetBookIndex: Boolean) =
        BookEmbeddingWorker.enqueueForBook(context, bookId, resetBookIndex)

    override fun cancelForBook(bookId: Long) =
        BookEmbeddingWorker.cancelForBook(context, bookId)
}

/**
 * 单书向量索引任务。每本书独立排队、取消和重建，不再隐式扫描整个书库。
 *
 * 幂等靠管线的按章续跑；未配置 embedding 等 Skipped 情形直接成功收工——
 * 「待处理」的标记就是切片本身的缺失，下次触发自然补上。网络类失败退避重试。
 * 长任务走 dataSync 前台通知；被系统禁止转前台时降级为普通后台跑（届时受
 * 10 分钟限额，靠重试续跑推进）。
 */
class BookEmbeddingWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            BookEmbeddingEntryPoint::class.java
        )
    }

    override suspend fun doWork(): Result {
        val targetBookId = inputData.getLong(INPUT_BOOK_ID, INVALID_BOOK_ID)
        if (targetBookId == INVALID_BOOK_ID) return Result.failure()
        if (!entryPoint.embeddingSettingsStore().isEnabled(targetBookId)) {
            VectorQueries.removeChunksForBook(entryPoint.vectorStore(), targetBookId)
            return Result.success()
        }
        val book = entryPoint.libraryRepository().getBook(targetBookId) ?: return Result.success()

        // 不同 embedding 模型的坐标系不可混用。模型分配变化后先清书籍切片，随后本轮
        // 从第 1 章完整重建；先清后建即使进程中途退出也只会暂时缺索引，不会错误召回。
        if (inputData.getBoolean(INPUT_RESET_INDEX, false)) {
            VectorQueries.removeChunksForBook(entryPoint.vectorStore(), book.id)
        }

        val pipeline = entryPoint.embeddingPipeline()
        runCatching { setForeground(createForegroundInfo("《${book.title}》")) }
        return when (pipeline.embedBook(book.id)) {
            is EmbedOutcome.Completed, is EmbedOutcome.Skipped -> Result.success()
            is EmbedOutcome.Failed -> Result.retry()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo("准备生成向量索引")

    private fun createForegroundInfo(message: String): ForegroundInfo {
        ensureNotificationChannel()
        return ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(message),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun buildNotification(message: String): Notification {
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("墨知正在生成向量索引")
            .setContentText(message)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()
    }

    private fun ensureNotificationChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AI 向量索引",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "为书籍内容建立 AI 检索索引的后台进度"
            }
        )
    }

    companion object {
        private const val INPUT_RESET_INDEX = "reset-index"
        private const val INPUT_BOOK_ID = "book-id"
        private const val INVALID_BOOK_ID = -1L
        private const val NOTIFICATION_CHANNEL_ID = "ai_index"
        private const val NOTIFICATION_ID = 4_200

        fun enqueueForBook(context: Context, bookId: Long, resetBookIndex: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName(bookId),
                if (resetBookIndex) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<BookEmbeddingWorker>()
                    .setInputData(
                        workDataOf(
                            INPUT_BOOK_ID to bookId,
                            INPUT_RESET_INDEX to resetBookIndex
                        )
                    )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )
        }

        fun cancelForBook(context: Context, bookId: Long) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
        }

        private fun workName(bookId: Long) = "embed-book-$bookId"
    }
}
