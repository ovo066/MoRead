package com.mozhi.reader.core.speech

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SpeechCacheSyncEntryPoint {
    fun speechCacheSync(): SpeechCacheSync
    fun speechCacheStore(): SpeechCacheStore
}

/**
 * 语音缓存的自动同步。只在不计费网络下跑：同步的目的是省钱，
 * 为此吃掉用户的流量套餐就本末倒置了。
 */
class SpeechCacheSyncWorker(
    appContext: Context,
    parameters: WorkerParameters
) : CoroutineWorker(appContext, parameters) {

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            SpeechCacheSyncEntryPoint::class.java
        )
    }

    override suspend fun doWork(): Result {
        // 用户可能在任务排队后关掉了开关；跑之前再确认一次。
        if (!entryPoint.speechCacheStore().settings.first().autoSyncOnWifi) {
            return Result.success()
        }
        return runCatching { entryPoint.speechCacheSync().sync() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        private const val UNIQUE_NAME = "speech-cache-sync"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SpeechCacheSyncWorker>(12, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.UNMETERED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
