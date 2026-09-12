package com.mozhi.reader.core.storage

import android.content.Context
import com.mozhi.reader.BuildConfig
import com.mozhi.reader.ai.embedding.EmbeddingProgressTracker
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.datastore.BookEmbeddingSettingsStore
import com.mozhi.reader.core.library.BookRemovalCoordinator
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.speech.SpeechCacheStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class StorageCategory(val title: String, val description: String) {
    ORIGINALS("原书副本", "导入时保存的原书；重建与备份可能需要"),
    TEXT("阅读正文", "解析后的正文，不是可随意删除的临时缓存"),
    MEDIA("书内插图", "从原书提取的图片"),
    LAYOUT("精排与内嵌字体", "EPUB 排版、样式与字体；DOM 已压缩保存"),
    SPEECH("听书与语音", "清理后再次使用可能重新产生合成费用"),
    ILLUSTRATIONS("AI 插图", "用户生成的图片，建议先导出再删除"),
    ATTACHMENTS("聊天附件", "随对话保留，不参与安全缓存清理"),
    COVERS("书籍封面", "只允许批量清理能从原书重新提取的封面"),
    CUSTOM("图片与字体库", "背景、封面素材、应用及阅读字体"),
    DATABASE("个人记录数据库", "统计、进度、笔记和聊天；删除后空间可能留待复用"),
    VECTOR("AI 向量索引", "按书停用可删除索引；重新建立可能产生费用"),
    CACHE("临时与系统缓存", "含下载与导出文件；安全清理只处理已过期的明确项目"),
    BACKUP("备份与恢复准备", "不自动删除恢复中的数据"),
    OTHER("其他应用数据", "偏好、角色资料与诊断等，不作为垃圾自动删除")
}

data class StorageCategoryUsage(val category: StorageCategory, val bytes: Long)
data class BookStorageUsage(
    val book: BookEntity,
    val originals: Long, val text: Long, val media: Long, val layout: Long,
    val speech: Long, val illustrations: Long, val attachments: Long,
    val indexEnabled: Boolean
) {
    val total: Long get() = originals + text + media + layout + speech + illustrations + attachments
}
enum class StorageCleanup(val title: String, val explanation: String) {
    TEMPORARY("清理过期临时文件", "仅删除超过 24 小时的旧版本安装包和导出副本，不影响原书或个人记录。当前版本安装包、导入中间文件和恢复数据不会清理。"),
    COVERS("清理可重建封面", "仅清理原书仍在本机、且没有手动更换过的 EPUB 封面；下次启动自动提取。自选封面与图片库不受影响。"),
    ORPHANS("清理无关联文件", "删除超过 24 小时且不再关联任何书籍或会话的原书、解析数据、语音、AI 插图和附件。生成图片与附件无法撤销，建议先备份。保留记录的书仍视为有效关联。")
}
data class StorageSnapshot(
    val categories: List<StorageCategoryUsage>,
    val books: List<BookStorageUsage>,
    val cleanupBytes: Map<StorageCleanup, Long>,
    val scannedAt: Long
) {
    val totalBytes: Long get() = categories.sumOf { it.bytes }
    fun bytes(category: StorageCategory): Long = categories.firstOrNull { it.category == category }?.bytes ?: 0
}
data class StorageCleanupResult(val freedBytes: Long, val skippedFiles: Int = 0)

@Singleton
class StorageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val chats: ChatDao,
    private val speech: SpeechCacheStore,
    private val embeddingSettings: BookEmbeddingSettingsStore,
    private val embedding: EmbeddingProgressTracker,
    private val removal: BookRemovalCoordinator
) {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow<StorageSnapshot?>(null)
    val snapshot = mutableSnapshot.asStateFlow()

    suspend fun refresh() = mutex.withLock { refreshLocked() }

    private suspend fun allFiles(): List<StorageFile> = withContext(Dispatchers.IO) {
        linkedMapOf(
            "files" to context.filesDir, "cache" to context.cacheDir,
            "database" to context.getDatabasePath("moread.db").parentFile!!,
            "backups" to context.noBackupFilesDir,
            "preferences" to File(context.filesDir.parentFile, "shared_prefs")
        ).flatMap { (prefix, root) -> storageFiles(root, prefix) }.distinctBy { it.file.absolutePath }
    }

    private suspend fun refreshLocked() = withContext(Dispatchers.IO) {
        val files = allFiles()
        val books = library.getBooksIncludingRemoved()
        val owners = chats.getStorageOwners()
        val enabled = embeddingSettings.enabledBookIds.first()
        val now = System.currentTimeMillis()
        val bookIds = books.mapTo(hashSetOf()) { it.id }
        val conversationIds = owners.mapTo(hashSetOf()) { it.id }
        val originalPaths = books.mapTo(hashSetOf()) { File(it.epubPath).canonicalPath }
        val orphanFiles = files.filter { isOrphanStorageFile(it, bookIds, conversationIds, originalPaths, now) }
        val categories = files.groupBy { categoryOf(it.relativePath) }
        val fileSizes = files.associate { it.file.absolutePath to it.bytes }
        val conversationBooks = owners.associate { it.id to it.bookId }
        val byBook = HashMap<Pair<Long, StorageCategory>, Long>()
        files.forEach { file ->
            val parts = file.relativePath.split('/')
            val id = parts.getOrNull(2)?.toLongOrNull()
            if (id != null && (parts.firstOrNull() == "files" ||
                    (parts.firstOrNull() == "cache" && parts.getOrNull(1) == "agent-speech"))) {
                val category = categoryOf(file.relativePath)
                val owner = if (category == StorageCategory.ATTACHMENTS) conversationBooks[id] else id
                if (owner != null) {
                    val key = owner to category
                    byBook[key] = byBook.getOrDefault(key, 0L) + file.bytes
                }
            }
        }
        mutableSnapshot.value = StorageSnapshot(
            categories = StorageCategory.entries.map { StorageCategoryUsage(it, categories[it].orEmpty().sumOf(StorageFile::bytes)) },
            books = books.map { book ->
                fun size(category: StorageCategory) = byBook[book.id to category] ?: 0L
                BookStorageUsage(book,
                    originals = fileSizes[File(book.epubPath).canonicalPath] ?: 0L,
                    text = size(StorageCategory.TEXT), media = size(StorageCategory.MEDIA),
                    layout = size(StorageCategory.LAYOUT), speech = size(StorageCategory.SPEECH),
                    illustrations = size(StorageCategory.ILLUSTRATIONS), attachments = size(StorageCategory.ATTACHMENTS),
                    indexEnabled = book.id in enabled)
            }.sortedByDescending(BookStorageUsage::total),
            cleanupBytes = mapOf(
                StorageCleanup.TEMPORARY to files.filter { isSafeTemporaryFile(it, now, BuildConfig.VERSION_NAME) }.sumOf { it.bytes },
                StorageCleanup.COVERS to reExtractableCovers(books).sumOf { it.length() },
                StorageCleanup.ORPHANS to orphanFiles.sumOf { it.bytes }
            ), scannedAt = now
        )
    }

    suspend fun clean(kind: StorageCleanup): StorageCleanupResult = mutex.withLock {
        val result = withContext(Dispatchers.IO) {
            if (kind == StorageCleanup.COVERS) {
                val freed = library.clearReExtractableCovers()
                File(context.filesDir, "covers/.epub-cover-backfill-v1").delete()
                StorageCleanupResult(freed)
            } else {
                // Recompute eligibility after confirmation. Never execute a stale preview list.
                val files = allFiles()
                val books = library.getBooksIncludingRemoved()
                val ids = books.mapTo(hashSetOf()) { it.id }
                val conversations = chats.getAllConversationIds().toSet()
                val originals = books.mapTo(hashSetOf()) { File(it.epubPath).canonicalPath }
                val now = System.currentTimeMillis()
                val targets = files.filter { file -> when (kind) {
                    StorageCleanup.TEMPORARY -> isSafeTemporaryFile(file, now, BuildConfig.VERSION_NAME)
                    StorageCleanup.ORPHANS -> isOrphanStorageFile(file, ids, conversations, originals, now)
                    else -> false
                } }
                var freed = 0L
                var skipped = 0
                targets.forEach { if (deleteUnchangedStorageFile(it)) freed += it.bytes else skipped++ }
                StorageCleanupResult(freed, skipped)
            }
        }
        refreshLocked()
        result
    }

    suspend fun clearBookSpeech(bookId: Long): Long = mutex.withLock {
        removal.requireIdle(bookId)
        val freed = speech.clearBook(bookId)
        refreshLocked()
        freed
    }

    suspend fun disableBookIndex(bookId: Long) = mutex.withLock {
        removal.requireIdle(bookId)
        embedding.disable(bookId)
        refreshLocked()
    }

    suspend fun removeBook(book: BookEntity, deleteRecords: Boolean) = mutex.withLock {
        try { removal.remove(book, deleteRecords) }
        finally { refreshLocked() }
    }

    private fun reExtractableCovers(books: List<BookEntity>): List<File> = books.asSequence()
        .filter { it.removedAt == 0L && it.sourceType.name == "EPUB" && !it.metadataEdited && File(it.epubPath).isFile }
        .mapNotNull { it.coverPath?.let(::File)?.takeIf(File::isFile) }
        .filter { it.canonicalFile.toPath().startsWith(File(context.filesDir, "covers").canonicalFile.toPath()) }
        .distinctBy { it.canonicalPath }.toList()
}

internal fun categoryOf(path: String): StorageCategory = when {
    path.startsWith("database/") -> StorageCategory.DATABASE
    path.startsWith("cache/agent-speech/") -> StorageCategory.SPEECH
    path.startsWith("cache/") -> StorageCategory.CACHE
    path.startsWith("backups/") -> StorageCategory.BACKUP
    else -> when (path.split('/').getOrNull(1)) {
        "books" -> StorageCategory.ORIGINALS
        "book-text" -> StorageCategory.TEXT
        "book-media" -> StorageCategory.MEDIA
        "book-layout" -> StorageCategory.LAYOUT
        "speech-cache" -> StorageCategory.SPEECH
        "illustrations" -> StorageCategory.ILLUSTRATIONS
        "attachments" -> StorageCategory.ATTACHMENTS
        "covers" -> StorageCategory.COVERS
        "reader-custom", "reader-images" -> StorageCategory.CUSTOM
        "objectbox" -> StorageCategory.VECTOR
        else -> StorageCategory.OTHER
    }
}
