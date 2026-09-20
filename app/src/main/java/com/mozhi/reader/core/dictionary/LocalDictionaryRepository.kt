package com.mozhi.reader.core.dictionary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalDictionary(val id: String, val title: String, val resourceCount: Int, val enabled: Boolean = true)
data class DictionaryDefinition(val dictionaryId: String, val title: String, val html: String)
data class DictionaryImportResult(val dictionary: LocalDictionary, val duplicate: Boolean)
data class DictionaryResourceImportResult(val imported: Int, val duplicates: Int)

@Singleton
class LocalDictionaryRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutations = Mutex()
    private val root get() = File(context.filesDir, "reader-custom/dictionaries").apply { mkdirs() }
    private val readers = object : LinkedHashMap<String, MdictReader>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MdictReader>?) = size > 6
    }

    suspend fun list(): List<LocalDictionary> = withContext(Dispatchers.IO) {
        root.listFiles().orEmpty().filter { it.isDirectory && File(it, "main.mdx").isFile }.map { directory ->
            descriptor(directory)
        }.sortedBy { it.title }
    }

    suspend fun supportsMdx(uri: Uri, mimeType: String? = null): Boolean = withContext(Dispatchers.IO) {
        uri.scheme in setOf("content", "file") &&
            (documentName(uri).endsWith(".mdx", true) ||
                mimeType?.lowercase(java.util.Locale.ROOT) in MDX_MIME_TYPES ||
                runCatching { context.contentResolver.getType(uri) }.getOrNull()?.lowercase(java.util.Locale.ROOT) in MDX_MIME_TYPES)
    }

    suspend fun importMdx(uri: Uri, mimeType: String? = null): LocalDictionary = importMdxResult(uri, mimeType).dictionary

    suspend fun importMdxResult(uri: Uri, mimeType: String? = null): DictionaryImportResult = withContext(Dispatchers.IO) { mutations.withLock {
        val name = documentName(uri)
        require(supportsMdx(uri, mimeType)) { "请选择 MDX 词典文件" }
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).apply { mkdirs() }
        try {
            val staging = File(directory, "importing")
            val hash = copyBounded(uri, staging)
            val existing = root.listFiles().orEmpty().firstOrNull { candidate ->
                val mdx = File(candidate, "main.mdx")
                mdx.isFile && mdx.length() == staging.length() && fingerprint(mdx) == hash
            }
            if (existing != null) {
                // Older imports did not retain their original filename. Reimport repairs an
                // empty/placeholder title without discarding MDD files or enabled state.
                val source = File(existing, "source-name.txt")
                if (!source.exists()) source.writeText(name)
                val current = descriptor(existing)
                File(existing, "title.txt").writeText(current.title)
                return@withLock DictionaryImportResult(current, duplicate = true)
            }
            val reader = MdictReader(staging, resource = false)
            // Parsing and an actual query catch broken record compression before publishing the import.
            reader.definition("a")
            val title = dictionaryDisplayTitle(reader.declaredTitle, name)
            File(directory, "source-name.txt").writeText(name)
            File(directory, "title.txt").writeText(title)
            val published = File(directory, "main.mdx")
            check(staging.renameTo(published)) { "无法保存词典" }
            saveFingerprint(published, hash)
            DictionaryImportResult(LocalDictionary(id, title, 0), duplicate = false)
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        } finally {
            if (!File(directory, "main.mdx").exists()) directory.deleteRecursively()
        }
    } }

    suspend fun importResources(id: String, uris: List<Uri>): DictionaryResourceImportResult = withContext(Dispatchers.IO) { mutations.withLock {
        val directory = directory(id)
        require(File(directory, "main.mdx").isFile) { "请先导入 MDX 词典" }
        var imported = 0
        var duplicates = 0
        uris.forEach { uri ->
            require(documentName(uri).endsWith(".mdd", true)) { "资源文件必须是 MDD" }
            val staging = File(directory, "${UUID.randomUUID()}.tmp")
            try {
                val hash = copyBounded(uri, staging)
                if (directory.listFiles().orEmpty().any { it.extension == "mdd" && it.length() == staging.length() && fingerprint(it) == hash }) {
                    duplicates++
                    return@forEach
                }
                MdictReader(staging, resource = true)
                val published = File(directory, "${UUID.randomUUID()}.mdd")
                check(staging.renameTo(published)) { "无法保存资源包" }
                saveFingerprint(published, hash)
                imported++
            } finally { staging.delete() }
        }
        DictionaryResourceImportResult(imported, duplicates)
    } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutations.withLock {
        val directory = directory(id)
        synchronized(readers) { readers.keys.removeAll { it.startsWith(directory.absolutePath + File.separator) } }
        check(directory.deleteRecursively()) { "无法删除词典" }
    } }

    suspend fun lookup(word: String): List<DictionaryDefinition> = withContext(Dispatchers.IO) {
        list().filter { it.enabled }.mapNotNull { dictionary ->
            val reader = reader(File(directory(dictionary.id), "main.mdx"))
            val html = reader.definition(word) ?: return@mapNotNull null
            DictionaryDefinition(dictionary.id, dictionary.title, html)
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) { mutations.withLock {
        val dir = directory(id)
        require(File(dir, "main.mdx").isFile) { "词典不存在" }
        val disabled = File(dir, "disabled")
        if (enabled) check(!disabled.exists() || disabled.delete()) { "无法启用词典" }
        else if (!disabled.exists()) check(disabled.createNewFile()) { "无法停用词典" }
    } }

    /** Called on WebView's resource thread. Paths are keys inside MDD, never filesystem paths. */
    fun resource(id: String, path: String): ByteArray? {
        val dir = directory(id)
        return dir.listFiles().orEmpty().filter { it.extension == "mdd" }.firstNotNullOfOrNull { reader(it).lookup(path) }
    }

    private fun reader(file: File): MdictReader = synchronized(readers) {
        readers.getOrPut(file.absolutePath) { MdictReader(file) }
    }
    private fun descriptor(directory: File) = LocalDictionary(directory.name,
        dictionaryDisplayTitle(File(directory, "title.txt").takeIf(File::isFile)?.readText().orEmpty(),
            File(directory, "source-name.txt").takeIf(File::isFile)?.readText().orEmpty()),
        directory.listFiles().orEmpty().count { it.extension == "mdd" }, !File(directory, "disabled").exists())

    private suspend fun fingerprint(file: File): String {
        val prefix = "${file.length()}:${file.lastModified()}:"
        val cached = File(file.path + ".sha256").takeIf(File::isFile)?.readText().orEmpty()
        if (cached.startsWith(prefix)) cached.removePrefix(prefix).takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex().also { saveFingerprint(file, it) }
    }
    private fun saveFingerprint(file: File, hash: String) {
        File(file.path + ".sha256").writeText("${file.length()}:${file.lastModified()}:$hash")
    }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun directory(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "无效词典编号" }
        return File(root, id)
    }
    private fun documentName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
    private suspend fun copyBounded(uri: Uri, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取文件" }.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 4L * 1024 * 1024 * 1024) { "单个词典文件不能超过 4 GB" }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().toHex()
    }

    companion object {
        internal val MDX_MIME_TYPES = setOf("application/x-mdx", "application/x-mdict", "application/x-mdict-mdx", "application/mdx", "application/mdict")
    }
}

internal fun dictionaryDisplayTitle(header: String, filename: String): String {
    val title = org.jsoup.Jsoup.parse(header).text().trim()
    val normalized = title.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    val placeholder = normalized in setOf("title", "titlenohtmlcodeallowed", "untitled", "notitle")
    return title.takeIf { it.isNotBlank() && !placeholder }
        ?: filename.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim().ifBlank { "本地词典" }
}
