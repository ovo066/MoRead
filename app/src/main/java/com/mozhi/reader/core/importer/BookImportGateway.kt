package com.mozhi.reader.core.importer

import android.net.Uri

sealed interface PreparedImport {
    data class PreviewReady(val sessionId: String) : PreparedImport
    data class BookImported(val bookId: Long) : PreparedImport
}

interface BookImportGateway {
    suspend fun prepare(uri: Uri): PreparedImport
    suspend fun backfillMissingCovers()
}
