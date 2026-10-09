package com.mozhi.reader.core.dictionary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DictionaryImportFile(val uri: Uri, val path: String) {
    val name get() = path.substringAfterLast('/')
    val extension get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
}

/** A bounded inventory of an authorized directory. Providers may deny sibling access. */
internal class DictionaryCompanionFiles(private val context: Context) {
    suspend fun siblings(uri: Uri): List<DictionaryImportFile> = try {
        when {
            uri.scheme == "file" -> File(requireNotNull(uri.path)).parentFile?.let { files(it) }.orEmpty()
            DocumentsContract.isDocumentUri(context, uri) -> {
                val id = DocumentsContract.getDocumentId(uri)
                // Hierarchical IDs are exposed by ExternalStorageProvider and tree providers.
                // Opaque Downloads/Media IDs cannot be turned into a parent directory.
                if (!id.contains('/')) emptyList() else documents(uri, id.substringBeforeLast('/'))
            }
            else -> emptyList()
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) { emptyList() }

    suspend fun folder(uri: Uri): List<DictionaryImportFile> =
        if (uri.scheme == "file") files(File(requireNotNull(uri.path)))
        else documents(uri, DocumentsContract.getTreeDocumentId(uri))

    private suspend fun files(root: File): List<DictionaryImportFile> {
        val result = mutableListOf<DictionaryImportFile>()
        val canonicalRoot = root.canonicalFile
        suspend fun walk(directory: File, depth: Int) {
            if (depth > 8 || result.size >= 4096) return
            for (file in directory.listFiles().orEmpty().sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                val canonical = file.canonicalFile
                if (!canonical.toPath().startsWith(canonicalRoot.toPath())) continue
                if (file.isDirectory) walk(file, depth + 1)
                else if (file.isFile && supported(file.extension)) {
                    result += DictionaryImportFile(Uri.fromFile(file), file.relativeTo(root).invariantSeparatorsPath)
                    if (result.size >= 4096) break
                }
            }
        }
        walk(root, 0)
        return result
    }

    private suspend fun documents(base: Uri, rootId: String): List<DictionaryImportFile> {
        val result = mutableListOf<DictionaryImportFile>()
        val tree = DocumentsContract.isTreeUri(base)
        val authority = requireNotNull(base.authority)
        suspend fun walk(id: String, prefix: String, depth: Int) {
            if (depth > 8 || result.size >= 4096) return
            val children = if (tree) DocumentsContract.buildChildDocumentsUriUsingTree(base, id)
                else DocumentsContract.buildChildDocumentsUri(authority, id)
            val rows = mutableListOf<Triple<String, String, Boolean>>()
            context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext() && rows.size < 4096) {
                    currentCoroutineContext().ensureActive()
                    rows += Triple(cursor.getString(idColumn), cursor.getString(nameColumn),
                        cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
            for ((childId, name, directory) in rows.sortedBy { it.second }) {
                currentCoroutineContext().ensureActive()
                if (name.contains('/') || name.contains('\\') || name in setOf(".", "..")) continue
                val path = prefix + name
                if (directory) walk(childId, "$path/", depth + 1)
                else if (supported(name.substringAfterLast('.', ""))) {
                    val childUri = if (tree) DocumentsContract.buildDocumentUriUsingTree(base, childId)
                        else DocumentsContract.buildDocumentUri(authority, childId)
                    result += DictionaryImportFile(childUri, path)
                    if (result.size >= 4096) break
                }
            }
        }
        walk(rootId, "", 0)
        return result
    }

    companion object {
        val RESOURCE_EXTENSIONS = setOf("css", "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico",
            "woff", "woff2", "ttf", "otf", "mp3", "wav", "ogg", "mp4", "webm")
        fun supported(extension: String) = extension.lowercase(Locale.ROOT) in RESOURCE_EXTENSIONS + setOf("mdx", "mdd")
    }
}

/** Normalize archive/web paths without letting a resource escape its dictionary. */
internal fun dictionaryResourcePath(path: String, base: String = ""): String? {
    val raw = path.trim().replace('\\', '/').substringBefore('#').substringBefore('?')
    if (raw.isBlank() || raw.startsWith("//") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(raw)) return null
    val parts = mutableListOf<String>()
    val joined = if (raw.startsWith('/')) raw else base.substringBeforeLast('/', "").let { if (it.isBlank()) raw else "$it/$raw" }
    for (part in joined.split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
        else -> { if ('\u0000' in part) return null; parts += part }
    }
    return parts.joinToString("/").takeIf { it.isNotBlank() }
}

internal fun dictionaryHtmlResources(html: String): Set<String> {
    val doc = org.jsoup.Jsoup.parse(html)
    return doc.select("link[href],img[src],source[src],audio[src],video[src]").mapNotNull {
        dictionaryResourcePath(Uri.decode(if (it.hasAttr("href")) it.attr("href") else it.attr("src")))
    }.toSet()
}

internal fun dictionaryCssResources(css: String, path: String): Set<String> =
    Regex("url\\(\\s*['\"]?([^)'\"]+)['\"]?\\s*\\)|@import\\s+['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
        .findAll(css).mapNotNull { dictionaryResourcePath(Uri.decode(it.groupValues[1].ifBlank { it.groupValues[2] }), path) }.toSet()
