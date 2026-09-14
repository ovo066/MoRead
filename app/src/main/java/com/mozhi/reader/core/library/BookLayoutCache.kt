package com.mozhi.reader.core.library

import android.content.Context
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Parsed chapters are expendable. The original EPUB and text coordinates remain the assets. */
internal class BookLayoutCache(
    private val context: Context,
    private val parser: EpubLayoutDocumentParser,
    private val bookBudget: Long = BOOK_BUDGET_BYTES,
    private val totalBudget: Long = TOTAL_BUDGET_BYTES
) {
    suspend fun read(
        bookId: Long,
        pkg: EpubLayoutPackage,
        reference: EpubLayoutChapterRef,
        expectedText: String?
    ): Pair<EpubLayoutChapter, EpubDomChapter>? = locks[(bookId and 15).toInt()].withLock {
        val archive = pkg.sourceArchive?.let { resolveArchivePath(context.filesDir, it) } ?: return@withLock null
        val sourceKey = "${archive.absolutePath}:${archive.length()}:${archive.lastModified()}"
        val root = directory(bookId).apply { mkdirs() }
        val file = File(root, "p${EpubLayoutPackage.CURRENT_PARSER_REVISION}-ch-${reference.chapterIndex}.json.gz")
        val expectedHash = expectedText?.let(::textHash)
        val cached = file.takeIf { it.isFile && it.length() <= bookBudget }?.let {
            runCatching { json.decodeFromString<CachedChapter>(GzipTextFiles.readText(it)) }.getOrNull()
        }
        if (cached != null && cached.parserRevision == EpubLayoutPackage.CURRENT_PARSER_REVISION &&
            cached.sourceKey == sourceKey && cached.dom.schemaVersion == EpubDomChapter.CURRENT_SCHEMA_VERSION &&
            cached.dom.chapterIndex == reference.chapterIndex && cached.dom.href == reference.href &&
            cached.document.chapterIndex == reference.chapterIndex && cached.document.textLength == reference.textLength &&
            cached.dom.textLength == reference.textLength && (expectedHash == null || cached.textHash == expectedHash)
        ) {
            file.setLastModified(System.currentTimeMillis())
            trim(bookId)
            return@withLock cached.document to cached.dom
        }
        file.delete()
        currentCoroutineContext().ensureActive()
        val archivePath = pkg.resources.firstOrNull { it.href == reference.href }?.archivePath ?: reference.href
        val bytes = EpubArchivePool().use { it.read(EpubArchiveAsset(archive, archivePath), MAX_CHAPTER_BYTES) }
            ?: return@withLock null
        val parsed = try {
            parser.parseWithText(bytes, reference.chapterIndex, reference.href, pkg.stylesheets.associate { it.href to it.css })
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return@withLock null
        }
        // A parser update may improve layout, but must never silently move saved text anchors.
        if (parsed.text.length != reference.textLength || expectedText != null && parsed.text != expectedText) return@withLock null
        val properties = pkg.spine.firstOrNull { it.href == reference.href }?.properties.orEmpty()
        val immersive = reference.immersivePage || properties.any {
            it.contains("cover", true) || it.contains("titlepage", true) || it.contains("page-fullscreen", true)
        }
        val document = parsed.document.copy(immersivePage = parsed.document.immersivePage || immersive,
            blocks = if (pkg.stylesheets.isEmpty()) parsed.document.blocks else emptyList())
        val result = CachedChapter(EpubLayoutPackage.CURRENT_PARSER_REVISION, sourceKey, textHash(parsed.text), document, parsed.dom)
        currentCoroutineContext().ensureActive()
        // Cache failure (including full storage) must not prevent reading an already parsed chapter.
        runCatching {
            GzipTextFiles.replaceWithGzip(file, json.encodeToString(result))
            trim(bookId)
        }
        document to parsed.dom
    }

    fun delete(bookId: Long) { directory(bookId).deleteRecursively() }

    fun trim(bookId: Long) = synchronized(trimLock) {
        fun prune(files: List<File>, budget: Long) {
            var bytes = files.sumOf(File::length)
            for (file in files.sortedBy(File::lastModified)) {
                if (bytes <= budget) break
                val size = file.length()
                if (file.delete()) bytes -= size
            }
        }
        prune(directory(bookId).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json.gz") }, bookBudget)
        val all = cacheRoot().listFiles().orEmpty().filter(File::isDirectory).flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json.gz") }
        }
        // A parser bump drops the old generation without reading or deserializing every chapter.
        val prefix = "p${EpubLayoutPackage.CURRENT_PARSER_REVISION}-"
        all.filterNot { it.name.startsWith(prefix) }.forEach(File::delete)
        prune(all.filter(File::isFile), totalBudget)
    }

    private fun directory(bookId: Long) = File(cacheRoot(), bookId.toString())
    private fun cacheRoot() = File(context.cacheDir, ROOT_DIRECTORY)

    @Serializable
    private data class CachedChapter(
        @Required val parserRevision: Int,
        val sourceKey: String,
        val textHash: String,
        val document: EpubLayoutChapter,
        val dom: EpubDomChapter
    )

    companion object {
        const val ROOT_DIRECTORY = "book-dom"
        const val BOOK_BUDGET_BYTES = 8L * 1024 * 1024
        const val TOTAL_BUDGET_BYTES = 64L * 1024 * 1024
        private const val MAX_CHAPTER_BYTES = 16 * 1024 * 1024
        private val locks = Array(16) { Mutex() }
        private val trimLock = Any()
        private val json = Json { ignoreUnknownKeys = true }
        private fun textHash(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
