package com.mozhi.reader.core.importer

import android.net.Uri

sealed interface PreparedImport {
    data class PreviewReady(val sessionId: String) : PreparedImport
    data class BookImported(val bookId: Long) : PreparedImport

    /**
     * 已有同一本书：[exact] = 内容指纹完全一致；false = 旧书没有指纹，只是书名作者相同。
     * [removed] = 那本书正文已移除、只保留了个人记录。由用户决定打开已有、仍然导入或取消。
     */
    data class Duplicate(val uri: Uri, val bookId: Long, val title: String, val exact: Boolean, val removed: Boolean) : PreparedImport
}

interface BookImportGateway {
    /** 单本导入：TXT 停在分章预览等用户确认，EPUB 直接入库。 */
    suspend fun prepare(uri: Uri, allowDuplicate: Boolean = false): PreparedImport

    /**
     * 批量导入的单本入口：不经预览直接入库（TXT 自动取最佳分章规则），返回书籍 id。
     * 失败抛异常，由调用方决定是跳过还是中断整批；内容已在库里时抛 [DuplicateBookException]。
     */
    suspend fun importDirectly(uri: Uri): Long

    suspend fun backfillMissingCovers()

    /** Rebuilds hierarchical EPUB navigation for books imported before it was persisted. */
    suspend fun backfillMissingEpubToc()
}
