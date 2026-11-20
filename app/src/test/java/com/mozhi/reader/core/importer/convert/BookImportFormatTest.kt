package com.mozhi.reader.core.importer.convert

import org.junit.Assert.*
import org.junit.Test

class BookImportFormatTest {
    @Test fun mobiFamilyUsesExtensionsBeforeGenericOrIncorrectMime() {
        for (extension in listOf("mobi", "AZW", "aZw3", "PRC")) {
            for (mime in listOf("application/octet-stream", "text/plain", "application/epub+zip")) {
                assertEquals(BookImportFormat.MOBI, resolveBookImportFormat("Book.$extension", mime))
            }
            assertTrue(extension.lowercase() in BookImportFiles.extensions)
        }
        for (mime in listOf("application/x-mobipocket-ebook", "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook")) {
            assertEquals(BookImportFormat.MOBI, resolveBookImportFormat("Book", mime))
            assertTrue(mime in BookImportFiles.pickerMimeTypes)
        }
        assertNull(resolveBookImportFormat("book.azw3.bak", "application/octet-stream"))
        assertEquals(BookImportFormat.MOBI, resolveBookImportFormat("book.azw3", "application/octet-stream"))
    }

    @Test fun fileNameWinsOverMimeAndDocumentMimeWinsOverTextFallback() {
        assertEquals(BookImportFormat.MARKDOWN, resolveBookImportFormat("Book.MD", "text/plain"))
        assertEquals(BookImportFormat.MARKDOWN, resolveBookImportFormat("Book.markdown", "application/octet-stream"))
        assertEquals(BookImportFormat.HTML, resolveBookImportFormat("Book.html", "text/html"))
        assertEquals(BookImportFormat.HTML, resolveBookImportFormat("Book.XhTmL", "text/plain"))
        assertEquals(BookImportFormat.HTML, resolveBookImportFormat("Book.htm", "application/epub+zip"))
        assertEquals(BookImportFormat.TXT, resolveBookImportFormat("Book.txt", "text/html"))
        assertEquals(BookImportFormat.EPUB, resolveBookImportFormat("Book.epub", "text/plain"))
        assertEquals(BookImportFormat.MARKDOWN, resolveBookImportFormat("Book", "text/x-markdown"))
        assertEquals(BookImportFormat.HTML, resolveBookImportFormat("Book", "TEXT/HTML; charset=gbk"))
        assertEquals(BookImportFormat.HTML, resolveBookImportFormat("Book", "application/xhtml+xml"))
        assertEquals(BookImportFormat.TXT, resolveBookImportFormat("Book", "text/plain"))
        assertNull(resolveBookImportFormat("archive.zip", "application/zip"))
        assertNull(BookDocumentFormat.fromFileName("book.md.bak"))
        assertNull(BookDocumentFormat.fromMime("text/plain"))
        assertEquals(BookDocumentFormat.MARKDOWN, BookDocumentFormat.fromFileName("Book.MarkDown"))
        assertEquals(BookDocumentFormat.HTML, BookDocumentFormat.fromMime("text/html; charset=utf-8"))
        assertTrue(BookImportFiles.pickerMimeTypes.containsAll(listOf("text/plain", "application/octet-stream", "text/markdown", "text/x-markdown", "text/html", "application/xhtml+xml")))
    }
}
