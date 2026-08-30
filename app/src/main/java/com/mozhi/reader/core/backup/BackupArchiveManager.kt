package com.mozhi.reader.core.backup

import android.content.Context
import com.mozhi.reader.BuildConfig
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.datastore.ReaderImageImporter
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
enum class BackupMode { LIGHTWEIGHT, FULL }

@Serializable
data class BackupManifest(
    val formatVersion: Int = 1,
    val createdAt: Long,
    val appVersion: String,
    val databaseVersion: Int,
    val packageName: String = BuildConfig.APPLICATION_ID,
    /** 老备份没有该字段时按完整包处理。 */
    val mode: BackupMode = BackupMode.FULL
)

/**
 * 备份包：Room + DataStore 必含。自动同步使用轻量模式，只带小型个性化资源；
 * 手动完整备份再包含原始书籍、正文缓存、媒体、插图与附件。
 */
@Singleton
class BackupArchiveManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MoReadDatabase
) {
    suspend fun create(
        mode: BackupMode = BackupMode.FULL,
        onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.noBackupFilesDir, "backups").apply { mkdirs() }
        outputDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(WebDavClient.BACKUP_EXTENSION) }
            ?.forEach(File::delete)
        val output = File(outputDir, backupFileName(mode = mode)).apply { delete() }
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(FULL)").close()
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        val dataStore = File(context.filesDir, "datastore/reader_settings.preferences_pb")
        val directories = directoriesFor(mode)
        val sourceFiles = buildList {
            if (databaseFile.isFile) add(databaseFile)
            if (dataStore.isFile) add(dataStore)
            directories.forEach { directory ->
                val root = File(context.filesDir, directory)
                if (root.isDirectory) addAll(root.walkTopDown().filter(File::isFile))
            }
        }
        val totalBytes = sourceFiles.sumOf(File::length).coerceAtLeast(1L)
        val requiredBytes = totalBytes + MIN_FREE_SPACE_BYTES
        val usableBytes = outputDir.usableSpace
        require(usableBytes <= 0L || usableBytes >= requiredBytes) {
            "存储空间不足：完整生成备份约需 ${requiredBytes / (1024 * 1024)} MB 可用空间"
        }
        var processedBytes = 0L
        fun report(delta: Long) {
            processedBytes += delta
            onProgress(processedBytes.coerceAtMost(totalBytes), totalBytes)
        }

        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.writeText(
                    MANIFEST_ENTRY,
                    JSON.encodeToString(
                        BackupManifest.serializer(),
                        BackupManifest(
                            createdAt = System.currentTimeMillis(),
                            appVersion = BuildConfig.VERSION_NAME,
                            databaseVersion = db.version,
                            mode = mode
                        )
                    )
                )
                database.runInTransaction {
                    zip.addFile(databaseFile, DATABASE_ENTRY, ::report)
                }
                zip.addFile(dataStore, DATASTORE_ENTRY, ::report)
                directories.forEach { directoryName ->
                    zip.addTree(File(context.filesDir, directoryName), "files/$directoryName", ::report)
                }
            }
            onProgress(totalBytes, totalBytes)
            output
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    /**
     * 在用户确认重启前就于 IO 线程完成校验与解压。Application.onCreate 只负责同分区替换，
     * 避免旧实现把几百 MB zip 在主线程解压造成 ANR。
     */
    suspend fun stageRestore(input: InputStream): BackupManifest = withContext(Dispatchers.IO) {
        val root = restoreRoot(context).apply { mkdirs() }
        val archive = File(root, "incoming.moread.zip.part")
        input.use { source ->
            archive.outputStream().buffered().use { sink -> source.copyTo(sink) }
        }
        try {
            val manifest = validateArchive(archive)
            val preparing = File(root, "$PREPARED_RESTORE_DIRECTORY.part")
            preparing.deleteRecursively()
            preparing.mkdirs()
            extractArchive(archive, preparing)
            check(File(preparing, DATABASE_ENTRY).isFile) { "恢复包缺少数据库" }
            val prepared = preparedRestoreDirectory(context)
            prepared.deleteRecursively()
            check(preparing.renameTo(prepared) || preparing.copyRecursively(prepared, overwrite = true)) {
                "无法准备恢复数据"
            }
            preparing.deleteRecursively()
            archive.delete()
            manifest
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
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

    private fun ZipOutputStream.addFile(
        file: File,
        path: String,
        onBytes: (Long) -> Unit
    ) {
        if (!file.isFile) return
        putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                write(buffer, 0, count)
                onBytes(count.toLong())
            }
        }
        closeEntry()
    }

    private fun ZipOutputStream.addTree(
        root: File,
        archiveRoot: String,
        onBytes: (Long) -> Unit
    ) {
        if (!root.isDirectory) return
        root.walkTopDown().filter(File::isFile).forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            addFile(file, "$archiveRoot/$relative", onBytes)
        }
    }

    companion object {
        const val DATABASE_NAME = "moread.db"
        const val DATABASE_ENTRY = "database/moread.db"
        const val DATASTORE_ENTRY = "datastore/reader_settings.preferences_pb"
        const val MANIFEST_ENTRY = "manifest.json"
        const val CURRENT_FORMAT_VERSION = 1
        const val CURRENT_DATABASE_VERSION = 21
        const val PENDING_RESTORE_NAME = "pending-restore.moread.zip"
        const val PREPARED_RESTORE_DIRECTORY = "prepared"
        val LIGHTWEIGHT_FILE_DIRECTORIES = listOf(
            "covers",
            "avatars",
            "reader-custom",
            ReaderImageImporter.IMAGE_LIBRARY_DIRECTORY
        )
        val MANAGED_FILE_DIRECTORIES = listOf(
            "books",
            "book-text",
            "book-media",
            "covers",
            "illustrations",
            "attachments",
            "avatars",
            "reader-custom",
            ReaderImageImporter.IMAGE_LIBRARY_DIRECTORY
        )
        private const val MAX_ENTRIES = 100_000
        private const val MIN_FREE_SPACE_BYTES = 64L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 16L * 1024 * 1024 * 1024
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun directoriesFor(mode: BackupMode): List<String> = when (mode) {
            BackupMode.LIGHTWEIGHT -> LIGHTWEIGHT_FILE_DIRECTORIES
            BackupMode.FULL -> MANAGED_FILE_DIRECTORIES
        }

        fun pendingRestoreFile(context: Context): File =
            File(restoreRoot(context), PENDING_RESTORE_NAME)

        fun preparedRestoreDirectory(context: Context): File =
            File(restoreRoot(context), PREPARED_RESTORE_DIRECTORY)

        private fun restoreRoot(context: Context): File = File(context.noBackupFilesDir, "restore")

        fun backupFileName(
            now: Long = System.currentTimeMillis(),
            mode: BackupMode = BackupMode.FULL
        ): String {
            val formatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).apply {
                timeZone = TimeZone.getDefault()
            }
            val kind = if (mode == BackupMode.LIGHTWEIGHT) "lite" else "full"
            return "backup-$kind-${formatter.format(Date(now))}${WebDavClient.BACKUP_EXTENSION}"
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

private fun extractArchive(archive: File, stage: File) {
    var total = 0L
    ZipFile(archive).use { zip ->
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
}

/** 在任何 Hilt/Room/DataStore 单例创建之前应用上次确认的恢复数据。 */
object BackupRestoreBootstrap {
    fun applyPending(context: Context) {
        val root = File(context.noBackupFilesDir, "restore")
        val prepared = BackupArchiveManager.preparedRestoreDirectory(context)
        val legacyPending = BackupArchiveManager.pendingRestoreFile(context)
        val stage = when {
            prepared.isDirectory -> prepared
            legacyPending.isFile -> File(root, "legacy-stage").also {
                runCatching {
                    it.deleteRecursively()
                    it.mkdirs()
                    extractArchive(legacyPending, it)
                }.onFailure { error ->
                    File(root, "restore-error.txt").writeText(error.message ?: "恢复失败")
                    it.deleteRecursively()
                }
            }
            else -> return
        }
        if (!stage.isDirectory || !File(stage, BackupArchiveManager.DATABASE_ENTRY).isFile) return

        runCatching {
            val restoredDb = File(stage, BackupArchiveManager.DATABASE_ENTRY)
            val targetDb = context.getDatabasePath(BackupArchiveManager.DATABASE_NAME)
            replaceFile(restoredDb, targetDb)
            File("${targetDb.absolutePath}-wal").delete()
            File("${targetDb.absolutePath}-shm").delete()

            BackupArchiveManager.MANAGED_FILE_DIRECTORIES.forEach { directoryName ->
                val restored = File(stage, "files/$directoryName")
                if (!restored.exists()) return@forEach
                replaceDirectory(restored, File(context.filesDir, directoryName))
            }
            val restoredDataStore = File(stage, BackupArchiveManager.DATASTORE_ENTRY)
            if (restoredDataStore.isFile) {
                replaceFile(
                    restoredDataStore,
                    File(context.filesDir, "datastore/reader_settings.preferences_pb")
                )
            }
            legacyPending.delete()
            stage.deleteRecursively()
            File(root, "restore-error.txt").delete()
        }.onFailure { error ->
            File(root, "restore-error.txt").apply {
                parentFile?.mkdirs()
                writeText(error.stackTraceToString())
            }
        }
    }

    private fun replaceFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.restore-part")
        temporary.delete()
        if (!source.renameTo(temporary)) source.copyTo(temporary, overwrite = true)
        check(!target.exists() || target.delete()) { "无法替换 ${target.name}" }
        check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = true).isFile) {
            "无法写入 ${target.name}"
        }
        temporary.delete()
        source.delete()
    }

    private fun replaceDirectory(source: File, target: File) {
        target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            check(source.copyRecursively(target, overwrite = true)) { "无法恢复 ${target.name}" }
            source.deleteRecursively()
        }
    }
}
