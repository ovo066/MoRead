package com.mozhi.reader.core.library

import android.content.Context
import com.mozhi.reader.core.epub.css.CssTokenType
import com.mozhi.reader.core.epub.css.CssTokenizer
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.dom.EpubV9DomAdapter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class BookLayoutStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val packageCache = object : LinkedHashMap<Long, EpubLayoutPackage>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, EpubLayoutPackage>): Boolean =
            size > PACKAGE_CACHE_SIZE
    }

    suspend fun replace(
        bookId: Long,
        epubFile: File,
        layoutPackage: EpubLayoutPackage,
        chapters: List<EpubLayoutChapterInput>
    ) = withContext(Dispatchers.IO) {
        require(layoutPackage.schemaVersion == EpubLayoutPackage.CURRENT_SCHEMA_VERSION) {
            "EPUB 布局版本不受支持"
        }
        val sortedChapters = chapters.sortedBy(EpubLayoutChapterInput::chapterIndex)
        require(sortedChapters.map(EpubLayoutChapterInput::chapterIndex) == sortedChapters.indices.toList()) {
            "EPUB 布局章节索引不连续"
        }
        val root = directory(bookId)
        val staging = File(root.parentFile, "${root.name}.tmp-${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val chapterDirectory = File(staging, CHAPTER_DIRECTORY).apply { mkdirs() }
            val legacyDirectory = File(staging, LEGACY_CHAPTER_DIRECTORY).apply { mkdirs() }
            val refs = sortedChapters.map { input ->
                val dom = requireNotNull(input.dom) { "EPUB v10 DOM 缺失：${input.chapterIndex}" }
                require(dom.schemaVersion == EpubLayoutPackage.CURRENT_SCHEMA_VERSION) {
                    "EPUB DOM 版本不受支持：${input.chapterIndex}"
                }
                require(dom.textLength >= 0 && dom.textLength == input.document.textLength) {
                    "EPUB 章节布局长度无效：${input.chapterIndex}"
                }
                val fileName = "ch-${input.chapterIndex.toString().padStart(5, '0')}.json"
                File(chapterDirectory, fileName).writeText(
                    json.encodeToString(dom.copy(chapterIndex = input.chapterIndex, href = input.href))
                )
                // Temporary P2 bridge. P3 reads DOM directly; P4 removes these compatibility files.
                File(legacyDirectory, fileName).writeText(json.encodeToString(input.document))
                EpubLayoutChapterRef(
                    chapterIndex = input.chapterIndex,
                    href = input.href,
                    textLength = input.document.textLength,
                    fileName = "$CHAPTER_DIRECTORY/$fileName"
                )
            }
            val storedPackage = layoutPackage.copy(chapters = refs)
            extractResources(
                epubFile = epubFile,
                layoutPackage = storedPackage,
                targetRoot = File(staging, RESOURCE_DIRECTORY)
            )
            File(staging, INDEX_FILE).writeText(json.encodeToString(storedPackage))
            root.deleteRecursively()
            if (!staging.renameTo(root)) {
                root.mkdirs()
                staging.copyRecursively(root, overwrite = true)
                staging.deleteRecursively()
            }
            synchronized(packageCache) { packageCache[bookId] = storedPackage }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun readChapter(bookId: Long, chapterIndex: Int): EpubLayoutChapterBundle? =
        withContext(Dispatchers.IO) {
            val layoutPackage = readPackage(bookId) ?: return@withContext null
            if (layoutPackage.schemaVersion !in setOf(9, EpubLayoutPackage.CURRENT_SCHEMA_VERSION)) {
                return@withContext null
            }
            val reference = layoutPackage.chapters.firstOrNull { it.chapterIndex == chapterIndex }
                ?: return@withContext null
            val root = directory(bookId)
            val chapterFile = safeChild(root, reference.fileName) ?: return@withContext null
            if (!chapterFile.isFile) return@withContext null
            val (chapter, dom) = if (layoutPackage.schemaVersion >= 10) {
                val parsedDom = runCatching {
                    json.decodeFromString(EpubDomChapter.serializer(), chapterFile.readText())
                }.getOrNull() ?: return@withContext null
                if (parsedDom.schemaVersion != EpubLayoutPackage.CURRENT_SCHEMA_VERSION ||
                    parsedDom.chapterIndex != chapterIndex || parsedDom.textLength != reference.textLength
                ) return@withContext null
                val legacyFile = safeChild(root, "$LEGACY_CHAPTER_DIRECTORY/${chapterFile.name}")
                val legacy = legacyFile?.takeIf(File::isFile)?.let { file ->
                    runCatching { json.decodeFromString(EpubLayoutChapter.serializer(), file.readText()) }.getOrNull()
                } ?: EpubLayoutChapter(
                    chapterIndex = chapterIndex,
                    href = parsedDom.href,
                    textLength = parsedDom.textLength
                )
                legacy to parsedDom
            } else {
                val legacy = runCatching {
                    json.decodeFromString(EpubLayoutChapter.serializer(), chapterFile.readText())
                }.getOrNull() ?: return@withContext null
                if (legacy.chapterIndex != chapterIndex || legacy.textLength != reference.textLength) {
                    return@withContext null
                }
                legacy to EpubV9DomAdapter.adapt(legacy)
            }
            val paths = buildMap {
                layoutPackage.resources.forEach { resource ->
                val file = safeChild(root, "$RESOURCE_DIRECTORY/${resource.archivePath}")
                    if (file?.isFile != true) return@forEach
                    EpubResourcePath.packageAliases(resource.href, layoutPackage.packageDocumentPath)
                        .forEach { alias -> putIfAbsent(alias, file.absolutePath) }
                    EpubResourcePath.packageAliases(resource.archivePath, layoutPackage.packageDocumentPath)
                        .forEach { alias -> putIfAbsent(alias, file.absolutePath) }
                }
            }
            val fontFaces = layoutPackage.fontFaces.mapNotNull { face ->
                paths[face.resourceHref]?.let { path ->
                    EpubResolvedFontFace(
                        family = face.family,
                        filePath = path,
                        weight = face.weight,
                        italic = face.italic
                    )
                }
            }
            val fonts = fontFaces
                .groupBy { it.family.lowercase() }
                .mapValues { (_, faces) ->
                    faces.minBy { face ->
                        kotlin.math.abs((face.weight ?: 400) - 400) + if (face.italic) 1000 else 0
                    }.filePath
                }
            EpubLayoutChapterBundle(
                document = chapter,
                resourcePaths = paths,
                fontPaths = fonts,
                fontFaces = fontFaces,
                dom = dom,
                stylesheets = layoutPackage.stylesheets
            )
        }

    suspend fun hasCurrentLayout(bookId: Long, expectedTextLengths: List<Int>): Boolean =
        withContext(Dispatchers.IO) {
            if (expectedTextLengths.isEmpty()) return@withContext false
            val index = runCatching { readPackage(bookId) }.getOrNull()
                ?: return@withContext false
            if (index.schemaVersion != EpubLayoutPackage.CURRENT_SCHEMA_VERSION ||
                index.chapters.size != expectedTextLengths.size
            ) {
                return@withContext false
            }
            val references = index.chapters.associateBy(EpubLayoutChapterRef::chapterIndex)
            if (references.size != expectedTextLengths.size) return@withContext false
            expectedTextLengths.indices.all { chapterIndex ->
                val reference = references[chapterIndex] ?: return@all false
                if (reference.textLength != expectedTextLengths[chapterIndex]) return@all false
                val root = directory(bookId)
                val chapterFile = safeChild(root, reference.fileName) ?: return@all false
                // The index is written atomically after every chapter file. Deserializing hundreds
                // of large AST JSON files here made app startup repair and reader entry almost as
                // expensive as importing the book again. Validate the cheap index/file contract;
                // readChapter() still performs full schema and coordinate validation on demand.
                chapterFile.isFile && chapterFile.length() > 0L
            }
        }

    fun delete(bookId: Long) {
        directory(bookId).deleteRecursively()
        synchronized(packageCache) { packageCache.remove(bookId) }
    }

    private fun readPackage(bookId: Long): EpubLayoutPackage? =
        synchronized(packageCache) { packageCache[bookId] } ?: readPackageBlocking(bookId)?.also { value ->
            synchronized(packageCache) { packageCache[bookId] = value }
        }

    private fun readPackageBlocking(bookId: Long): EpubLayoutPackage? {
        val file = File(directory(bookId), INDEX_FILE).takeIf(File::isFile) ?: return null
        return runCatching { json.decodeFromString(EpubLayoutPackage.serializer(), file.readText()) }.getOrNull()
    }

    private fun extractResources(
        epubFile: File,
        layoutPackage: EpubLayoutPackage,
        targetRoot: File
    ) {
        targetRoot.mkdirs()
        val referencedBackgrounds = buildSet {
            layoutPackage.stylesheets.forEach { stylesheet ->
                CssTokenizer.tokenize(stylesheet.css)
                    .filter { it.type == CssTokenType.URL }
                    .mapNotNull { token -> EpubResourcePath.normalize(token.text, stylesheet.href) }
                    .forEach { add(it.lowercase()) }
            }
        }
        fun EpubLayoutResource.isNeededAtRenderTime(): Boolean {
            if (kind == EpubLayoutResourceKind.FONT) return true
            if (referencedBackgrounds.isEmpty()) return false
            return EpubResourcePath.packageAliases(href, layoutPackage.packageDocumentPath)
                .any { it.lowercase() in referencedBackgrounds } ||
                EpubResourcePath.packageAliases(archivePath, layoutPackage.packageDocumentPath)
                    .any { it.lowercase() in referencedBackgrounds }
        }
        ZipFile(epubFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory)
                .associateBy { normalizeArchivePath(it.name).lowercase() }
            // XHTML/CSS/nav have already been compiled into chapter JSON. Inline images live in
            // BookMediaStore. Keeping only fonts and actual CSS background assets avoids a second
            // full EPUB extraction (and duplicate copies of every illustration) during import.
            layoutPackage.resources.asSequence()
                .filter { it.isNeededAtRenderTime() }
                .forEach { resource ->
                    val entry = entries[resource.archivePath.lowercase()] ?: return@forEach
                    if (entry.size > MAX_EXTRACTED_RESOURCE_BYTES) return@forEach
                    val output = safeChild(targetRoot, resource.archivePath) ?: return@forEach
                    output.parentFile?.mkdirs()
                    val digest = resource.sha256?.let { MessageDigest.getInstance("SHA-256") }
                    var copied = 0L
                    zip.getInputStream(entry).buffered().use { input ->
                        output.outputStream().buffered().use { stream ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                copied += count
                                require(copied <= MAX_EXTRACTED_RESOURCE_BYTES) { "EPUB 资源过大" }
                                digest?.update(buffer, 0, count)
                                stream.write(buffer, 0, count)
                            }
                        }
                    }
                    val actualHash = digest?.digest()?.joinToString("") { "%02x".format(it) }
                    if (resource.sha256 != null && actualHash != resource.sha256) {
                        output.delete()
                        error("EPUB 资源校验失败：${resource.href}")
                    }
                }
        }
    }

    private fun directory(bookId: Long): File = File(context.filesDir, "$ROOT_DIRECTORY/$bookId")

    private fun safeChild(root: File, relativePath: String): File? {
        val child = File(root, relativePath).canonicalFile
        return child.takeIf { it.toPath().startsWith(root.canonicalFile.toPath()) }
    }

    private fun normalizeArchivePath(path: String): String = path.replace('\\', '/').removePrefix("./")

    companion object {
        const val ROOT_DIRECTORY = "book-layout"
        const val INDEX_FILE = "index.json"
        const val CHAPTER_DIRECTORY = "chapters"
        const val LEGACY_CHAPTER_DIRECTORY = "legacy-v9"
        const val RESOURCE_DIRECTORY = "resources"
        const val MAX_EXTRACTED_RESOURCE_BYTES = 64L * 1024 * 1024
        private const val PACKAGE_CACHE_SIZE = 4
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
