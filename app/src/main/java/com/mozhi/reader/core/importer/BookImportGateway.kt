package com.mozhi.reader.core.importer

import android.net.Uri

sealed interface PreparedImport {
    data class PreviewReady(val sessionId: String) : PreparedImport
    data class BookImported(val bookId: Long) : PreparedImport
}

interface BookImportGateway {
    /** 单本导入：TXT 停在分章预览等用户确认，EPUB 直接入库。 */
    suspend fun prepare(uri: Uri): PreparedImport

    /**
     * 批量导入的单本入口：不经预览直接入库（TXT 自动取最佳分章规则），返回书籍 id。
     * 失败抛异常，由调用方决定是跳过还是中断整批。
     */
    suspend fun importDirectly(uri: Uri): Long

    suspend fun backfillMissingCovers()

    /** Rebuilds hierarchical EPUB navigation for books imported before it was persisted. */
    suspend fun backfillMissingEpubToc()
}
