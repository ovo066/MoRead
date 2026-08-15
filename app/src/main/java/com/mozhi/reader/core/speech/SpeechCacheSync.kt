package com.mozhi.reader.core.speech

import com.mozhi.reader.core.backup.BackupSettingsStore
import com.mozhi.reader.core.backup.WebDavClient
import com.mozhi.reader.core.backup.WebDavCredentials
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SpeechCacheSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val skipped: Int
) {
    val summary: String
        get() = buildString {
            append("上传 ").append(uploaded).append(" 个，下载 ").append(downloaded).append(" 个")
            if (skipped > 0) append("，跳过 ").append(skipped).append(" 个")
        }
}

/**
 * 语音缓存的 WebDAV 双向同步，复用备份用的那套账号与客户端。
 *
 * 文件名就是内容哈希（模型 + 音色 + 参数 + 文本），因此「同名即同内容」：
 * 两端各自补齐对方缺的文件即可，没有冲突可言，也就不需要任何合并策略。
 * 换设备、重装之后同一段文字不必再花一次钱合成——这正是要同步它的全部理由。
 */
@Singleton
class SpeechCacheSync @Inject constructor(
    private val cache: SpeechCacheStore,
    private val webDav: WebDavClient,
    private val backupSettings: BackupSettingsStore
) {
    /** 未配置 WebDAV 时返回 null，调用方据此显示引导而不是报错。 */
    suspend fun credentialsOrNull(): WebDavCredentials? = runCatching {
        val base = backupSettings.credentials()
        base.copy(remoteDirectory = "${base.remoteDirectory.trim('/')}/$REMOTE_SUBDIRECTORY")
    }.getOrNull()

    suspend fun sync(): SpeechCacheSyncResult = withContext(Dispatchers.IO) {
        val credentials = credentialsOrNull() ?: error("请先在「备份与同步」里配置 WebDAV")
        val remote = webDav.list(credentials, SpeechCacheStore.AUDIO_EXTENSIONS.map { ".$it" }.toSet())
            .associateBy { it.name }

        var uploaded = 0
        var downloaded = 0
        var skipped = 0

        // 上传：先按预算裁剪，别把本地已经该淘汰的东西推上云。
        cache.enforceBudget()
        // 两侧一律按远端命名（带书号）比对：本地文件名只是哈希，同一段语音可能同时属于
        // 两本书，只比哈希会把其中一份误判为「已存在」。
        val localRemoteNames = cache.audioFiles().mapTo(mutableSetOf(), ::remoteNameOf)
        cache.audioFiles().forEach { file ->
            val name = remoteNameOf(file)
            if (name in remote) return@forEach
            runCatching { webDav.upload(credentials, file, name) }
                .onSuccess { uploaded++ }
                .onFailure { skipped++ }
        }

        // 下载：受本地预算约束，装不下就不拉了——把手机塞满不是用户要的。
        var budgetLeft = cache.budgetBytes() - cache.audioFiles().sumOf(File::length)
        remote.values
            .filter { it.name !in localRemoteNames }
            .sortedByDescending { it.modifiedAt }
            .forEach { entry ->
                if (budgetLeft - entry.size < 0) {
                    skipped++
                    return@forEach
                }
                val target = File(cache.directoryFor(bookIdOf(entry.name)), fileNameOf(entry.name))
                runCatching { webDav.download(credentials, entry.name, target) }
                    .onSuccess {
                        downloaded++
                        budgetLeft -= entry.size
                    }
                    .onFailure {
                        target.delete()
                        skipped++
                    }
            }

        cache.markSynced()
        SpeechCacheSyncResult(uploaded, downloaded, skipped)
    }

    companion object {
        const val REMOTE_SUBDIRECTORY = "speech-cache"

        /**
         * 远端是平铺目录（WebDAV 建目录一次一层，为每本书建目录不划算），
         * 因此把书号编进文件名：`{bookId}__{hash}.mp3`。
         */
        fun remoteNameOf(file: File): String {
            val bookId = file.parentFile?.name?.toLongOrNull() ?: 0L
            return "$bookId$SEPARATOR${file.name}"
        }

        fun bookIdOf(remoteName: String): Long =
            remoteName.substringBefore(SEPARATOR).toLongOrNull() ?: 0L

        fun fileNameOf(remoteName: String): String =
            remoteName.substringAfter(SEPARATOR, remoteName)

        private const val SEPARATOR = "__"
    }
}
