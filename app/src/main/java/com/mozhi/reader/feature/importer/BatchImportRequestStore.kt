package com.mozhi.reader.feature.importer

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WorkManager 的 Data 上限只有 10 KiB，不能直接承载几十个 SAF URI。这里把完整批次清单
 * 写进应用私有目录，Worker 的 inputData 只保存一个短 ID；即使应用进程被系统回收，任务也
 * 能从磁盘恢复。清单不进入备份，消费结束后立即删除，遗留文件也会定期清理。
 */
@Singleton
class BatchImportRequestStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val files = BatchImportRequestFiles(
        File(context.noBackupFilesDir, DIRECTORY_NAME)
    )

    internal fun create(
        uris: List<Uri>,
        deleteSourceAfterImport: Boolean,
        groupPathsByUri: Map<Uri, String>
    ): String = files.create(
        BatchImportRequestPayload(
            entries = uris.map { uri ->
                BatchImportRequestEntry(
                    uri = uri.toString(),
                    groupPath = groupPathsByUri[uri].orEmpty()
                )
            },
            deleteSourceAfterImport = deleteSourceAfterImport
        )
    )

    internal fun read(requestId: String): BatchImportRequestPayload? = files.read(requestId)

    internal fun delete(requestId: String) = files.delete(requestId)

    private companion object {
        const val DIRECTORY_NAME = "batch-import-requests"
    }
}

@Serializable
internal data class BatchImportRequestPayload(
    val entries: List<BatchImportRequestEntry>,
    val deleteSourceAfterImport: Boolean = false
)

@Serializable
internal data class BatchImportRequestEntry(
    val uri: String,
    val groupPath: String = ""
)

/** 与 Android Context 解耦，便于验证大批量、长路径清单的磁盘往返。 */
internal class BatchImportRequestFiles(
    private val directory: File
) {
    fun create(payload: BatchImportRequestPayload): String {
        directory.mkdirs()
        check(directory.isDirectory) { "无法创建批量导入任务目录" }
        removeExpiredFiles()

        val requestId = UUID.randomUUID().toString()
        val target = fileFor(requestId)
        val temporary = File(directory, ".$requestId.tmp")
        temporary.writeText(JSON.encodeToString(payload), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return requestId
    }

    fun read(requestId: String): BatchImportRequestPayload? {
        val file = runCatching { fileFor(requestId) }.getOrNull() ?: return null
        return runCatching { JSON.decodeFromString<BatchImportRequestPayload>(file.readText()) }
            .getOrNull()
    }

    fun delete(requestId: String) {
        runCatching { fileFor(requestId).delete() }
    }

    private fun fileFor(requestId: String): File {
        require(REQUEST_ID.matches(requestId)) { "无效的批量导入任务 ID" }
        return File(directory, "$requestId.json")
    }

    private fun removeExpiredFiles(now: Long = System.currentTimeMillis()) {
        directory.listFiles()?.forEach { file ->
            if (now - file.lastModified() > MAX_AGE_MILLIS) runCatching { file.delete() }
        }
    }

    private companion object {
        val REQUEST_ID = Regex("[0-9a-fA-F-]{36}")
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
