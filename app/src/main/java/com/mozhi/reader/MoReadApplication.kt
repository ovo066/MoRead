package com.mozhi.reader

import android.app.Application
import com.mozhi.reader.core.backup.BackupRestoreBootstrap
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.text.ChineseTextConverter
import com.mozhi.reader.feature.importer.BookTextMaterializeWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class MoReadApplication : Application() {
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    lateinit var chineseTextConverter: ChineseTextConverter

    override fun onCreate() {
        // 待恢复包必须先于 Hilt component 创建；否则 Room/DataStore 可能已经持有旧文件句柄。
        BackupRestoreBootstrap.applyPending(this)
        super.onCreate()
        applicationScope.launch { chineseTextConverter.warmUp() }
        // 正文补齐仍需启动兜底；向量索引改为按需（首次检索时按书触发），不再全库补扫。
        BookTextMaterializeWorker.enqueueStartup(this)
    }
}
