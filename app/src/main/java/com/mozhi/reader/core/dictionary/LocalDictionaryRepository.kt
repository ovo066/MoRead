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
data class DictionaryImportResult(val dictionary: LocalDictionary, val duplicate: Boolean,
    val resourcesImported: Int = 0, val resourceWarnings: List<String> = emptyList())
data class DictionaryResourceImportResult(val imported: Int, val duplicates: Int)
data class DictionaryBatchImportResult(val imported: Int, val duplicates: Int, val resources: Int, val failures: List<String>)

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

    suspend fun importMdxResult(uri: Uri, mimeType: String? = null): DictionaryImportResult = withContext(Dispatchers.IO) {
        importWithCompanions(uri, mimeType, DictionaryCompanionFiles(context).siblings(uri))
    }

    suspend fun importFiles(uris: List<Uri>): DictionaryBatchImportResult = withContext(Dispatchers.IO) {
        val files = uris.map { DictionaryImportFile(it, documentName(it)) }
        importFilesNamed(files)
    }

    suspend fun importFolder(uri: Uri): DictionaryBatchImportResult = withContext(Dispatchers.IO) {
        importFilesNamed(DictionaryCompanionFiles(context).folder(uri))
    }

    private suspend fun importFilesNamed(files: List<DictionaryImportFile>): DictionaryBatchImportResult {
        val dictionaries = files.filter { it.extension == "mdx" }
        require(dictionaries.isNotEmpty()) { "请选择 MDX 词典，或包含 MDX 的文件夹" }
        var imported = 0
        var duplicates = 0
        var resources = 0
        val failures = mutableListOf<String>()
        dictionaries.forEach { source ->
            try {
                val parent = source.path.substringBeforeLast('/', "")
                val neighbors = files.filter { parent.isBlank() || it.path.startsWith("$parent/") }
                    .map { it.copy(path = if (parent.isBlank()) it.path else it.path.removePrefix("$parent/")) }
                val available = (neighbors + DictionaryCompanionFiles(context).siblings(source.uri)).distinctBy { it.uri }
                val explicit = if (dictionaries.size == 1) neighbors.filter { it.extension != "mdx" }.map { it.uri }.toSet() else emptySet()
                val result = importWithCompanions(source.uri, null, available, explicit)
                if (result.duplicate) duplicates++ else imported++
                resources += result.resourcesImported
                failures += result.resourceWarnings
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { failures += "${source.name}：${error.message ?: "导入失败"}" }
        }
        return DictionaryBatchImportResult(imported, duplicates, resources, failures)
    }

    private suspend fun importWithCompanions(uri: Uri, mimeType: String?, files: List<DictionaryImportFile>, explicit: Set<Uri> = emptySet()): DictionaryImportResult {
        val result = importMdxOnly(uri, mimeType)
        val stem = documentName(uri).substringBeforeLast('.').lowercase(java.util.Locale.ROOT)
        val available = files.filter { it.extension != "mdx" }.associateBy { it.path.lowercase(java.util.Locale.ROOT) }
        val selected = linkedSetOf<String>()
        available.forEach { (path, file) ->
            val name = file.name.lowercase(java.util.Locale.ROOT)
            val sibling = !file.path.contains('/')
            if (file.uri in explicit || sibling && (name == "$stem.mdd" || name.matches(Regex("${Regex.escape(stem)}\\.\\d+\\.mdd")) || name == "$stem.css")) selected += path
        }
        val reader = reader(File(directory(result.dictionary.id), "main.mdx"))
        val warnings = mutableListOf<String>()
        try {
            reader.sampleDefinitions().flatMap(::dictionaryHtmlResources).forEach { ref ->
                ref.lowercase(java.util.Locale.ROOT).takeIf { it in available }?.let(selected::add)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { warnings += "未能检查词条中的资源引用，可从文件夹补充导入" }
        // Follow CSS imports, backgrounds and font URLs with their original relative paths.
        val scanned = mutableSetOf<String>()
        while (true) {
            val next = selected.firstOrNull { it !in scanned } ?: break
            scanned += next
            val file = available.getValue(next)
            if (file.extension == "css") try {
                val css = context.contentResolver.openInputStream(file.uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 2_000_000) { "样式文件过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }.orEmpty()
                dictionaryCssResources(css, file.path).forEach { ref ->
                    ref.lowercase(java.util.Locale.ROOT).takeIf { it in available }?.let(selected::add)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { warnings += "${file.name}：${error.message ?: "资源读取失败"}" }
        }
        var imported = 0
        selected.forEach { path ->
            val file = available.getValue(path)
            try {
                val resourceResult = if (file.extension == "mdd") importResources(result.dictionary.id, listOf(file.uri))
                    else importLooseResources(result.dictionary.id, listOf(file))
                imported += resourceResult.imported
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { warnings += "${file.name}：${error.message ?: "资源导入失败"}" }
        }
        return result.copy(dictionary = descriptor(directory(result.dictionary.id)), resourcesImported = imported, resourceWarnings = warnings)
    }

    private suspend fun importMdxOnly(uri: Uri, mimeType: String?): DictionaryImportResult = withContext(Dispatchers.IO) { mutations.withLock {
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
            if (!documentName(uri).endsWith(".mdd", true)) {
                val name = documentName(uri)
                require(name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT) in DictionaryCompanionFiles.RESOURCE_EXTENSIONS) { "请选择 MDD、CSS、图片或字体资源" }
                val result = importLooseResourcesLocked(directory, listOf(DictionaryImportFile(uri, name)))
                imported += result.imported
                duplicates += result.duplicates
                return@forEach
            }
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

    private suspend fun importLooseResources(id: String, files: List<DictionaryImportFile>) = mutations.withLock {
        importLooseResourcesLocked(directory(id), files)
    }

    private suspend fun importLooseResourcesLocked(directory: File, files: List<DictionaryImportFile>): DictionaryResourceImportResult {
        var imported = 0
        var duplicates = 0
        val resourceRoot = File(directory, "resources").apply { mkdirs() }.canonicalFile
        files.forEach { file ->
            val path = requireNotNull(dictionaryResourcePath(file.path)) { "资源路径无效" }
            val target = File(resourceRoot, path.lowercase(java.util.Locale.ROOT)).canonicalFile
            require(target.toPath().startsWith(resourceRoot.toPath())) { "资源路径无效" }
            target.parentFile!!.mkdirs()
            val staging = File(directory, "${UUID.randomUUID()}.tmp")
            try {
                val hash = copyBounded(file.uri, staging, 64L * 1024 * 1024)
                if (target.isFile && target.length() == staging.length() && fingerprint(target) == hash) duplicates++
                else {
                    java.nio.file.Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    saveFingerprint(target, hash)
                    imported++
                }
            } finally { staging.delete() }
        }
        return DictionaryResourceImportResult(imported, duplicates)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) { mutations.withLock {
        val directory = directory(id)
        synchronized(readers) { readers.keys.removeAll { it.startsWith(directory.absolutePath + File.separator) } }
        check(directory.deleteRecursively()) { "无法删除词典" }
    } }

    suspend fun lookup(word: String): List<DictionaryDefinition> = withContext(Dispatchers.IO) {
        list().filter { it.enabled }.mapNotNull { dictionary ->
            val reader = reader(File(directory(dictionary.id), "main.mdx"))
            var html = reader.definition(word) ?: return@mapNotNull null
            val css = File(directory(dictionary.id), "source-name.txt").takeIf { it.isFile }?.readText()
                ?.substringBeforeLast('.')?.plus(".css")
            if (css != null && resource(dictionary.id, css) != null) {
                val doc = org.jsoup.Jsoup.parse(html)
                if (doc.select("link[href]").none { it.attr("href").equals(css, true) }) {
                    doc.head().prependElement("link").attr("rel", "stylesheet").attr("href", css)
                }
                html = doc.outerHtml()
            }
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
        val normalized = dictionaryResourcePath(path) ?: return null
        val root = File(dir, "resources").canonicalFile
        val file = File(root, normalized.lowercase(java.util.Locale.ROOT)).canonicalFile
        if (!file.toPath().startsWith(root.toPath())) return null
        if (file.isFile && file.length() <= 64L * 1024 * 1024) return file.readBytes()
        return dir.listFiles().orEmpty().filter { it.extension == "mdd" }.firstNotNullOfOrNull { reader(it).lookup(normalized) }
    }

    private fun reader(file: File): MdictReader = synchronized(readers) {
        readers.getOrPut(file.absolutePath) { MdictReader(file) }
    }
    private fun descriptor(directory: File) = LocalDictionary(directory.name,
        dictionaryDisplayTitle(File(directory, "title.txt").takeIf(File::isFile)?.readText().orEmpty(),
            File(directory, "source-name.txt").takeIf(File::isFile)?.readText().orEmpty()),
        directory.listFiles().orEmpty().count { it.extension == "mdd" } +
            File(directory, "resources").walkTopDown().count { it.isFile && !it.name.endsWith(".sha256") }, !File(directory, "disabled").exists())

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
    private fun documentName(uri: Uri): String {
        if (uri.scheme == "file") return File(requireNotNull(uri.path)).name
        return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
        }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
    }
    private suspend fun copyBounded(uri: Uri, target: File, limit: Long = 4L * 1024 * 1024 * 1024): String {
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
                    require(total <= limit) { "文件超过导入大小限制" }
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
