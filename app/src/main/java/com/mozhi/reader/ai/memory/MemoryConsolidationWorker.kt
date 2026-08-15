package com.mozhi.reader.ai.memory

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mozhi.reader.ai.client.AiClientException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryConsolidationEntryPoint {
    fun memoryConsolidator(): MemoryConsolidator
    fun rollingSummarizer(): RollingSummarizer
}

class MemoryConsolidationWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            MemoryConsolidationEntryPoint::class.java
        )
    }

    override suspend fun doWork(): Result {
        val conversationId = inputData.getLong(KEY_CONVERSATION_ID, 0)
        if (conversationId == 0L) return Result.failure()
        // 固化在前、提要在后：固化推进的水位会让提要把已成为长期记忆的那段丢掉，
        // 否则同一段内容会既在记忆里又在提要里重复占用上下文。
        val outcome = entryPoint.memoryConsolidator().consolidateAvailable(
            conversationId = conversationId,
            forceOnClose = inputData.getBoolean(KEY_FORCE_ON_CLOSE, false)
        )
        // 提要是纯增益：它失败不该让固化的成功结果变成 retry。
        runCatching { entryPoint.rollingSummarizer().refresh(conversationId) }
        return when (outcome) {
            is MemoryConsolidationOutcome.Completed,
            MemoryConsolidationOutcome.NotReady,
            is MemoryConsolidationOutcome.Skipped -> Result.success()
            is MemoryConsolidationOutcome.Failed -> when (outcome.error) {
                is AiClientException.Network,
                is AiClientException.Timeout,
                is AiClientException.RateLimited -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_FORCE_ON_CLOSE = "force_on_close"

        fun enqueue(context: Context, conversationId: Long, forceOnClose: Boolean) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "memory-consolidation-$conversationId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<MemoryConsolidationWorker>()
                    .setInputData(
                        workDataOf(
                            KEY_CONVERSATION_ID to conversationId,
                            KEY_FORCE_ON_CLOSE to forceOnClose
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
    }
}

@Singleton
class MemoryConsolidationScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun afterTurn(conversationId: Long) {
        MemoryConsolidationWorker.enqueue(context, conversationId, forceOnClose = false)
    }

    fun onConversationClosed(conversationId: Long) {
        MemoryConsolidationWorker.enqueue(context, conversationId, forceOnClose = true)
    }
}
