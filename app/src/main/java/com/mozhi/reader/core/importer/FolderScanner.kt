package com.mozhi.reader.core.importer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** 文件夹扫描到的一本候选书。 */
data class ScannedBookFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    /** 相对所选文件夹的目录路径，`""` 表示就在根目录；界面按它分组。 */
    val relativeDirectory: String
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

/**
 * SAF 文件夹递归扫描。走 [DocumentsContract] 原始查询而不是 androidx.documentfile：
 * 后者每个节点都要单独建对象并二次查询，几百个文件的目录会明显卡；这里一次查询拿整层。
 */
@Singleton
class FolderScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun scan(treeUri: Uri): List<ScannedBookFile> = withContext(Dispatchers.IO) {
        val rootDocumentId = runCatching {
            DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrNull() ?: return@withContext emptyList()

        val found = ArrayList<ScannedBookFile>()
        // 广度优先：浅层的书先出现，达到上限时截断的也是最深处的目录。
        val queue = ArrayDeque(listOf(rootDocumentId to ""))
        var depth = 0
        while (queue.isNotEmpty() && found.size < MAX_FILES && depth <= MAX_DEPTH) {
            repeat(queue.size) {
                if (found.size >= MAX_FILES) return@repeat
                val (documentId, relative) = queue.removeFirst()
                coroutineContext.ensureActive()
                readChildren(treeUri, documentId, relative, found, queue)
            }
            depth++
        }
        found.sortedWith(
            compareBy(ScannedBookFile::relativeDirectory).thenBy { it.name.lowercase(Locale.ROOT) }
        )
    }

    private fun readChildren(
        treeUri: Uri,
        documentId: String,
        relative: String,
        found: MutableList<ScannedBookFile>,
        queue: ArrayDeque<Pair<String, String>>
    ) {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        }.getOrNull() ?: return
        val cursor = runCatching {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null,
                null,
                null
            )
        }.getOrNull() ?: return

        cursor.use {
            while (it.moveToNext()) {
                if (found.size >= MAX_FILES) return
                val childId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mimeType = it.getString(2).orEmpty()
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (isSkippableDirectory(name)) continue
                    queue.addLast(childId to joinPath(relative, name))
                } else if (isSupportedBook(name)) {
                    found += ScannedBookFile(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                        name = name,
                        sizeBytes = if (it.isNull(3)) 0L else it.getLong(3),
                        relativeDirectory = relative
                    )
                }
            }
        }
    }

    companion object {
        const val MAX_FILES = 500
        const val MAX_DEPTH = 8
        val SUPPORTED_EXTENSIONS = setOf("txt", "epub")

        fun isSupportedBook(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS

        /** 隐藏目录与各家阅读器的缓存目录里全是垃圾文件，扫了只会污染结果。 */
        fun isSkippableDirectory(name: String): Boolean =
            name.startsWith(".") || name.lowercase(Locale.ROOT) in SKIPPED_DIRECTORIES

        private val SKIPPED_DIRECTORIES = setOf(
            "android", "cache", "caches", "temp", "tmp", "log", "logs", "thumbnails"
        )

        /**
         * 是否看起来已在书架里：按去掉扩展名的文件名与书名比对。只作灰态提示，
         * 用户仍可勾选重导（同名不同书是真实存在的情况，不能替用户拦下）。
         */
        fun looksImported(fileName: String, existingTitles: Set<String>): Boolean =
            fileName.substringBeforeLast('.').trim() in existingTitles

        /** 泛型是为了让排序规则能脱离 android.net.Uri 单测；调用方一律传 ScannedBookFile。 */
        fun <T> groupByDirectory(
            files: List<T>,
            directoryOf: (T) -> String
        ): List<Pair<String, List<T>>> = files
            .groupBy(directoryOf)
            .toList()
            .sortedBy { it.first }

        private fun joinPath(parent: String, name: String): String =
            if (parent.isEmpty()) name else "$parent/$name"
    }
}
