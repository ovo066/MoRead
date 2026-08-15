package com.mozhi.reader.core.backup

import android.content.Context
import com.mozhi.reader.BuildConfig
import com.mozhi.reader.core.database.MoReadDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupManifest(
    val formatVersion: Int = 1,
    val createdAt: Long,
    val appVersion: String,
    val databaseVersion: Int,
    val packageName: String = BuildConfig.APPLICATION_ID
)

/** 完整数据包：Room + DataStore + 用户书籍/封面/插图/附件；API 密钥与可重建向量索引除外。 */
@Singleton
class BackupArchiveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MoReadDatabase
) {
    suspend fun create(): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.cacheDir, "backups").apply { mkdirs() }
        val output = File(outputDir, backupFileName())
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(FULL)").close()
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            zip.writeText(
                MANIFEST_ENTRY,
                JSON.encodeToString(
                    BackupManifest.serializer(),
                    BackupManifest(
                        createdAt = System.currentTimeMillis(),
                        appVersion = BuildConfig.VERSION_NAME,
                        databaseVersion = db.version
                    )
                )
            )
            database.runInTransaction {
                zip.addFile(context.getDatabasePath(DATABASE_NAME), DATABASE_ENTRY)
            }
            val dataStore = File(context.filesDir, "datastore/reader_settings.preferences_pb")
            zip.addFile(dataStore, DATASTORE_ENTRY)
            MANAGED_FILE_DIRECTORIES.forEach { directoryName ->
                zip.addTree(File(context.filesDir, directoryName), "files/$directoryName")
            }
        }
        output
    }

    suspend fun stageRestore(input: InputStream): BackupManifest = withContext(Dispatchers.IO) {
        val pending = pendingRestoreFile(context)
        pending.parentFile?.mkdirs()
        val temporary = File(pending.parentFile, "${pending.name}.part")
        input.use { source ->
            temporary.outputStream().buffered().use { sink -> source.copyTo(sink) }
        }
        val manifest = validate(temporary)
        if (pending.exists()) pending.delete()
        check(temporary.renameTo(pending)) { "无法准备恢复文件" }
        manifest
    }

    suspend fun validate(file: File): BackupManifest = withContext(Dispatchers.IO) {
        validateArchive(file)
    }

    private fun validateArchive(file: File): BackupManifest {
        require(file.isFile) { "备份文件不存在" }
        var entryCount = 0
        var expandedBytes = 0L
        var manifest: BackupManifest? = null
        var hasDatabase = false
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "备份文件包含过多条目" }
                require(isSafeBackupEntry(entry.name)) { "备份文件包含非法路径" }
                entry.size.takeIf { it > 0 }?.let {
                    expandedBytes += it
                    require(expandedBytes <= MAX_EXPANDED_BYTES) { "备份文件过大" }
                }
                if (entry.name == DATABASE_ENTRY) hasDatabase = true
                if (entry.name == MANIFEST_ENTRY) {
                    manifest = zip.getInputStream(entry).bufferedReader().use { reader ->
                        JSON.decodeFromString(BackupManifest.serializer(), reader.readText())
                    }
                }
            }
        }
        val value = manifest ?: error("不是墨知备份：缺少清单")
        require(value.formatVersion <= CURRENT_FORMAT_VERSION) { "备份格式来自更新版本，请先升级应用" }
        require(value.databaseVersion <= CURRENT_DATABASE_VERSION) { "数据库来自更新版本，请先升级应用" }
        require(value.packageName == BuildConfig.APPLICATION_ID) { "备份不属于墨知" }
        require(hasDatabase) { "备份缺少数据库" }
        return value
    }

    private fun ZipOutputStream.writeText(path: String, value: String) {
        putNextEntry(ZipEntry(path))
        write(value.toByteArray())
        closeEntry()
    }

    private fun ZipOutputStream.addFile(file: File, path: String) {
        if (!file.isFile) return
        putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
        file.inputStream().buffered().use { it.copyTo(this) }
        closeEntry()
    }

    private fun ZipOutputStream.addTree(root: File, archiveRoot: String) {
        if (!root.isDirectory) return
        root.walkTopDown().filter(File::isFile).forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            addFile(file, "$archiveRoot/$relative")
        }
    }

    companion object {
        const val DATABASE_NAME = "moread.db"
        const val DATABASE_ENTRY = "database/moread.db"
        const val DATASTORE_ENTRY = "datastore/reader_settings.preferences_pb"
        const val MANIFEST_ENTRY = "manifest.json"
        const val CURRENT_FORMAT_VERSION = 1
        // 必须与 MoReadDatabase 的 version 同步升：低于实际库版本会让本机刚导出的备份
        // 在 validate() 里被判成「来自更新版本」而无法恢复。
        const val CURRENT_DATABASE_VERSION = 17
        const val PENDING_RESTORE_NAME = "pending-restore.moread.zip"
        val MANAGED_FILE_DIRECTORIES = listOf(
            "books",
            "book-text",
            "book-media",
            "covers",
            "illustrations",
            "attachments",
            "avatars",
            "reader-custom"
            // 故意不含 speech-cache：它可再生、体量大，而且已有自己的 WebDAV 同步通道，
            // 塞进备份包只会让整包膨胀到难以上传。
        )
        private const val MAX_ENTRIES = 100_000
        private const val MAX_EXPANDED_BYTES = 16L * 1024 * 1024 * 1024
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun pendingRestoreFile(context: Context): File =
            File(context.noBackupFilesDir, "restore/$PENDING_RESTORE_NAME")

        fun backupFileName(now: Long = System.currentTimeMillis()): String {
            val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply {
                timeZone = TimeZone.getDefault()
            }
            return "backup-${formatter.format(Date(now))}${WebDavClient.BACKUP_EXTENSION}"
        }
    }
}

/** Zip Slip 防护；恢复只接受本格式定义的三个根目录。 */
internal fun isSafeBackupEntry(name: String): Boolean {
    if (name.isBlank() || name.startsWith('/') || name.startsWith('\\')) return false
    val normalized = name.replace('\\', '/')
    if (normalized.split('/').any { it == ".." }) return false
    return normalized == BackupArchiveManager.MANIFEST_ENTRY ||
        normalized == BackupArchiveManager.DATABASE_ENTRY ||
        normalized == BackupArchiveManager.DATASTORE_ENTRY ||
        normalized.startsWith("files/")
}

/** 在任何 Hilt/Room/DataStore 单例创建之前应用上次确认的恢复包。 */
object BackupRestoreBootstrap {
    fun applyPending(context: Context) {
        val pending = BackupArchiveManager.pendingRestoreFile(context)
        if (!pending.isFile) return
        val stage = File(context.noBackupFilesDir, "restore/stage")
        runCatching {
            stage.deleteRecursively()
            stage.mkdirs()
            var total = 0L
            ZipFile(pending).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    check(isSafeBackupEntry(entry.name)) { "恢复包路径非法" }
                    val target = File(stage, entry.name).canonicalFile
                    check(target.toPath().startsWith(stage.canonicalFile.toPath())) { "恢复包路径越界" }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { source ->
                        target.outputStream().buffered().use { sink ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                total += read
                                check(total <= 16L * 1024 * 1024 * 1024) { "恢复包解压后过大" }
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            val restoredDb = File(stage, BackupArchiveManager.DATABASE_ENTRY)
            check(restoredDb.isFile) { "恢复包缺少数据库" }
            val targetDb = context.getDatabasePath(BackupArchiveManager.DATABASE_NAME)
            targetDb.parentFile?.mkdirs()
            restoredDb.copyTo(targetDb, overwrite = true)
            File("${targetDb.absolutePath}-wal").delete()
            File("${targetDb.absolutePath}-shm").delete()

            BackupArchiveManager.MANAGED_FILE_DIRECTORIES.forEach { directoryName ->
                val restored = File(stage, "files/$directoryName")
                if (!restored.exists()) return@forEach
                val target = File(context.filesDir, directoryName)
                target.deleteRecursively()
                restored.copyRecursively(target, overwrite = true)
            }
            val restoredDataStore = File(stage, BackupArchiveManager.DATASTORE_ENTRY)
            if (restoredDataStore.isFile) {
                val target = File(context.filesDir, "datastore/reader_settings.preferences_pb")
                target.parentFile?.mkdirs()
                restoredDataStore.copyTo(target, overwrite = true)
            }
            pending.delete()
        }.onFailure { error ->
            File(pending.parentFile, "restore-error.txt").writeText(error.message ?: "恢复失败")
        }
        stage.deleteRecursively()
    }
}
