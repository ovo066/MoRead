package com.mozhi.reader.feature.importer

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.mozhi.reader.core.importer.BatchImportScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用 WorkManager 唯一任务链承载批量导入：多次排队会依次执行而不是并发抢同一份磁盘，
 * APPEND_OR_REPLACE 让「选完一批又选一批」自然接在后面。
 */
@Singleton
class WorkBatchImportScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) : BatchImportScheduler {
    override fun enqueue(uris: List<Uri>, deleteSourceAfterImport: Boolean) {
        if (uris.isEmpty()) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            BatchImportWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            BatchImportWorker.request(uris, deleteSourceAfterImport)
        )
    }
}
