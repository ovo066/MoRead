package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubChapterFallback
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import com.mozhi.reader.core.epub.ExtractedEpubText
import com.mozhi.reader.core.importer.BookTitles
import com.mozhi.reader.core.importer.SourceFingerprints
import com.mozhi.reader.core.importer.SourceFingerprint
import com.mozhi.reader.core.importer.DuplicateBookException
import com.mozhi.reader.core.importer.ImportFailureException
import com.mozhi.reader.core.i18n.UiText
import com.mozhi.reader.core.importer.convert.BookConversionException
import com.mozhi.reader.core.importer.convert.BookDocumentInput
import com.mozhi.reader.core.importer.convert.BookImportFormat
import com.mozhi.reader.core.importer.convert.HtmlBookConverter
import com.mozhi.reader.core.importer.convert.MarkdownBookConverter
import com.mozhi.reader.core.importer.convert.MinimalEpubWriter
import com.mozhi.reader.core.importer.convert.resolveBookImportFormat
import com.mozhi.reader.core.importer.convert.mobi.MobiBookConverter
import android.content.Context
import com.mozhi.reader.R
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.util.Base64
import android.util.Size
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.importer.BookImportGateway
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.library.BookImageInput
import com.mozhi.reader.core.library.ChapterDraft
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.ChapterTextSlice
import com.mozhi.reader.core.library.EpubLayoutChapterInput
import com.mozhi.reader.core.library.EpubLayoutPackage
import com.mozhi.reader.core.library.EpubResourcePath
import com.mozhi.reader.core.library.LegacyLocatorConverter
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.readium.ReadiumServices
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.publication.Href
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.util.use

@Singleton
class ImportCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val encodingDetector: TextEncodingDetector,
    private val ruleLoader: TxtTocRuleLoader,
    private val chapterSplitter: TxtChapterSplitter,
    private val textExtractor: EpubTextExtractor,
    private val layoutParser: EpubLayoutDocumentParser,
    private val packageInspector: EpubPackageInspector,
    private val layoutStore: BookLayoutStore,
    private val mediaStore: BookMediaStore,
    private val sessionStore: ImportSessionStore,
    private val readium: ReadiumServices,
    private val libraryRepository: LibraryRepository
) : BookImportGateway {
    private val txtImportMutex = Mutex()
    private var txtStagingCleaned = false
    override suspend fun backfillMissingCovers(): Unit = withContext(Dispatchers.IO) {
        val marker = File(coversDirectory(), COVER_BACKFILL_MARKER)
        if (marker.isFile) return@withContext

        libraryRepository.getEpubBooksMissingCovers().forEach { book ->
            val epubFile = File(book.epubPath).takeIf(File::isFile) ?: return@forEach
            var extractedCover: File? = null
            runCatching {
                val publication = readium.open(epubFile)
                try {
                    extractedCover = savePublicationCover(publication, "existing-${book.id}")
                } finally {
                    publication.close()
                }
                extractedCover?.let { cover ->
                    libraryRepository.updateBookCover(book.id, cover.absolutePath)
                }
            }.onFailure {
                extractedCover?.delete()
            }
        }
        runCatching { marker.writeText("completed") }
    }

    override suspend fun backfillMissingEpubToc(): Unit = withContext(Dispatchers.IO) {
        libraryRepository.getEpubBooksMissingToc().forEach { book ->
            val epubFile = File(book.epubPath).takeIf(File::isFile) ?: return@forEach
            runCatching {
                val publication = readium.open(epubFile)
                try {
                    val structure = publication.toImportStructure()
                    libraryRepository.replaceBookToc(book.id, structure.tocEntries)
                } finally {
                    publication.close()
                }
            }
        }
        repairEpubChapterTitles()
    }

    /**
     * 旧版把目录未引用的 spine 文档命名为「第 N 章」；现在按目录延续命名。已导入的书
     * 不重扫 EPUB，只用库里的目录树把这类假章名改掉，一次性执行。
     */
    private suspend fun repairEpubChapterTitles() {
        val marker = File(booksDirectory(), CHAPTER_TITLE_REPAIR_MARKER)
        if (marker.isFile) return
        libraryRepository.getEpubBooks().forEach { book ->
            runCatching {
                val chapters = libraryRepository.getChapters(book.id)
                if (chapters.none { PLACEHOLDER_CHAPTER_TITLE.matches(it.title) }) return@forEach
                val tocEntries = libraryRepository.getTocEntries(book.id)
                    .filter { it.chapterIndex != null && it.title.isNotBlank() }
                if (tocEntries.isEmpty()) return@forEach
                val placeholderChapters = chapters.filter { PLACEHOLDER_CHAPTER_TITLE.matches(it.title) }
                val repaired = repairPlaceholderChapterTitles(
                    chapters = chapters.map { ChapterDraft(it.chapterIndex, it.title, it.href, it.charCount) },
                    tocTitles = tocEntries.map { requireNotNull(it.chapterIndex) to it.title },
                    chapterTextHints = placeholderChapters.associate { chapter ->
                        chapter.chapterIndex to libraryRepository.readChapterText(book.id, chapter).take(256)
                    }
                )
                repaired.forEach { (index, title) ->
                    libraryRepository.updateChapterTitle(book.id, index, title)
                }
            }
        }
        runCatching { marker.createNewFile() }
    }

    override suspend fun prepare(uri: Uri, allowDuplicate: Boolean): PreparedImport = withContext(Dispatchers.IO) {
        takeReadPermission(uri)
        val displayName = queryDisplayName(uri) ?: "未命名书籍"
        val mimeType = context.contentResolver.getType(uri).orEmpty()

        try {
            when (val format = resolveBookImportFormat(displayName, mimeType)) {
                BookImportFormat.MARKDOWN, BookImportFormat.HTML, BookImportFormat.MOBI ->
                    PreparedImport.BookImported(importDocument(uri, displayName, format, allowDuplicate))
                BookImportFormat.TXT ->
                    prepareTxt(uri, displayName, allowDuplicate)
                BookImportFormat.EPUB ->
                    PreparedImport.BookImported(importEpub(uri, displayName, allowDuplicate))
                null -> throw ImportFailureException(UiText.of(R.string.import_supported_formats))
            }
        } catch (duplicate: DuplicateBookException) {
            PreparedImport.Duplicate(uri, duplicate.bookId, duplicate.title, exact = true, removed = duplicate.removed)
        }
    }

    /**
     * 批量导入的单本入口：TXT 不再停下来等用户确认分章规则，直接用
     * [TxtChapterSplitter.chooseBest] 的最佳规则入库——一次导二十本却要点二十次确认，
     * 批量就没有意义了。分章不理想时用阅读页三点菜单里的「重新分章」补救。
     */
    override suspend fun importDirectly(uri: Uri): Long = importDirectly(uri) { _, _, _ -> }

    override suspend fun importDirectly(uri: Uri, onProgress: (String, Int, Int) -> Unit): Long = withContext(Dispatchers.IO) {
        takeReadPermission(uri)
        val displayName = queryDisplayName(uri) ?: "未命名书籍"
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        when (val format = resolveBookImportFormat(displayName, mimeType)) {
            BookImportFormat.MARKDOWN, BookImportFormat.HTML, BookImportFormat.MOBI -> importDocument(uri, displayName, format, allowDuplicate = false)
            BookImportFormat.EPUB -> importEpub(uri, displayName, allowDuplicate = false)
            BookImportFormat.TXT -> {
                importTxtStreaming(uri, displayName, onProgress)
            }
            null -> throw ImportFailureException(UiText.of(R.string.import_supported_formats))
        }
    }

    private suspend fun <T> withDecodedTxt(uri: Uri, allowDuplicate: Boolean,
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
        useDecoded: suspend (File, String, SourceFingerprint, () -> Unit) -> T): T = txtImportMutex.withLock {
        val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
        val checkActive = { coroutineContext.ensureActive() }
        val directory = File(context.cacheDir, "txt-import").apply { mkdirs() }
        if (!txtStagingCleaned) {
            directory.listFiles()?.filter { it.isFile && (it.name.startsWith("source-") || it.name.startsWith("decoded-")) }
                ?.forEach(File::delete)
            txtStagingCleaned = true
        }
        val source = File.createTempFile("source-", ".txt", directory)
        val decoded = File.createTempFile("decoded-", ".utf16", directory)
        val readingMessage = context.getString(R.string.import_reading_file)
        val encodingMessage = context.getString(R.string.import_detecting_encoding)
        try {
            val declaredSize = queryDocumentSize(uri)
            require(declaredSize == null || declaredSize <= MAX_TXT_BYTES) { context.getString(R.string.import_txt_too_large) }
            val input = context.contentResolver.openInputStream(uri) ?: error(context.getString(R.string.import_file_read_failed))
            val fingerprint = input.use { stream -> source.outputStream().buffered().use { output ->
                SourceFingerprints.copy(stream, output, MAX_TXT_BYTES.toLong(), checkActive) { completed ->
                    onProgress(readingMessage, completed.toInt(), declaredSize?.coerceAtMost(MAX_TXT_BYTES.toLong())?.toInt() ?: 0)
                }
            } }
            require(fingerprint.size > 0) { context.getString(R.string.import_txt_empty) }
            if (!allowDuplicate) findDuplicate(fingerprint, backfillLegacyEpub = false)?.let {
                throw DuplicateBookException(it.id, it.title, it.removedAt != 0L)
            }
            val charsetName = encodingDetector.decodeToFile(source, decoded, checkActive) { completed, total ->
                onProgress(encodingMessage, completed.toInt(), total.toInt())
            }
            source.delete() // Fingerprint and decoded text are ready; release the raw staging copy early.
            useDecoded(decoded, charsetName, fingerprint, checkActive)
        } finally {
            source.delete()
            decoded.delete()
        }
    }

    private suspend fun importTxtStreaming(uri: Uri, displayName: String, onProgress: (String, Int, Int) -> Unit): Long =
        withDecodedTxt(uri, allowDuplicate = false, onProgress) { decoded, _, fingerprint, checkActive ->
            val chaptersMessage = context.getString(R.string.import_detecting_chapters)
            val writingMessage = context.getString(R.string.import_writing_text)
            TxtFileText(decoded).use { text ->
                val metadata = TxtMetadataDetector.detect(displayName, text.subSequence(0, minOf(text.length, 64 * 1024)).toString())
                val split = chapterSplitter.indexBest(text, ruleLoader.rules, checkActive) { completed, total ->
                    onProgress(chaptersMessage, completed, total)
                }
                require(split.chapters.isNotEmpty()) { context.getString(R.string.import_no_chapters) }
                checkActive()
                onProgress(context.getString(R.string.import_writing_library), 0, split.chapters.size)
                libraryRepository.insertTextBook(
                    BookEntity(title = metadata.title.trim(), author = metadata.author.trim(), coverPath = null,
                        epubPath = "", sourceType = BookSourceType.TXT, importedAt = System.currentTimeMillis(),
                        totalChapters = split.chapters.size, sourceSha256 = fingerprint.sha256, sourceByteSize = fingerprint.size),
                    split.chapters.map { ChapterDraft(it.index, it.title, "", it.charCount) },
                    split.chapters.map { ChapterTextSlice(it.index, text.subSequence(it.bodyStart, it.bodyEnd)) },
                    checkActive
                ) { completed, total ->
                    onProgress(writingMessage, completed, total)
                }
            }
        }

    suspend fun preview(sessionId: String): TxtImportPreview? = withContext(Dispatchers.IO) {
        val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
        sessionStore.get(sessionId) { coroutineContext.ensureActive() }?.toPreview()
    }

    suspend fun selectRule(sessionId: String, ruleId: Long): TxtImportPreview =
        withContext(Dispatchers.IO) {
            val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
            val checkActive = { coroutineContext.ensureActive() }
            val session = requireNotNull(sessionStore.get(sessionId, checkActive)) { "导入会话已失效" }
            val rule = requireNotNull(session.rules.firstOrNull { it.id == ruleId }) { "规则不存在" }
            val split = session.openText().use { chapterSplitter.indexWithRule(it, rule, checkActive) }
                ?: error("该规则未识别到足够的章节")
            session.copy(splitResult = split).also(sessionStore::update).toPreview()
        }

    suspend fun applyCustomRegex(sessionId: String, regex: String): TxtImportPreview =
        withContext(Dispatchers.IO) {
            val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
            val checkActive = { coroutineContext.ensureActive() }
            val session = requireNotNull(sessionStore.get(sessionId, checkActive)) { "导入会话已失效" }
            // Reuse a known built-in rule when the pasted expression is identical. Large books
            // can keep this bounded path instead of allocating an arbitrary full-text regex input.
            val builtIn = session.rules.firstOrNull { it.id in -25L..-1L && it.rule == regex }
            val split = session.openText().use { text ->
                if (builtIn != null) chapterSplitter.indexWithRule(text, builtIn, checkActive)
                else chapterSplitter.indexWithCustomRegex(text, regex, checkActive)
            }
                ?: error("自定义正则未识别到章节，请检查表达式")
            session.copy(splitResult = split).also(sessionStore::update).toPreview()
        }

    suspend fun confirmTxt(
        sessionId: String,
        title: String,
        author: String,
        onProgress: (ImportProgress) -> Unit = {}
    ): Long = withContext(Dispatchers.IO) {
        val coroutineContext = kotlinx.coroutines.currentCoroutineContext()
        val checkActive = { coroutineContext.ensureActive() }
        val session = requireNotNull(sessionStore.get(sessionId, checkActive)) { "导入会话已失效" }
        require(title.isNotBlank()) { "书名不能为空" }
        require(title.length <= 200) { "书名不能超过 200 个字符" }
        require(author.length <= 120) { "作者不能超过 120 个字符" }
        require(session.splitResult.chapters.isNotEmpty()) { "没有可导入的章节" }

        val bookId = session.openText().use { text ->
            val chapters = session.splitResult.chapters.map { ChapterDraft(it.index, it.title, "", it.charCount) }
            onProgress(
                ImportProgress(
                    message = context.getString(R.string.import_writing_library),
                    completed = chapters.size,
                    total = chapters.size
                )
            )
            libraryRepository.insertTextBook(
                book = BookEntity(
                    title = title.trim(),
                    author = author.trim(),
                    coverPath = null,
                    epubPath = "",
                    sourceType = BookSourceType.TXT,
                    importedAt = System.currentTimeMillis(),
                    totalChapters = chapters.size,
                    sourceSha256 = session.sourceSha256,
                    sourceByteSize = session.sourceByteSize
                ),
                chapters = chapters,
                bodies = session.splitResult.chapters.map { chapter ->
                    ChapterTextSlice(chapter.index, text.subSequence(chapter.bodyStart, chapter.bodyEnd))
                },
                checkActive = checkActive
            ) { completed, total ->
                onProgress(ImportProgress(context.getString(R.string.import_writing_text), completed, total))
            }
        }
        sessionStore.remove(sessionId)
        bookId
    }

    private suspend fun prepareTxt(uri: Uri, displayName: String, allowDuplicate: Boolean): PreparedImport =
        withDecodedTxt(uri, allowDuplicate) { decoded, charsetName, fingerprint, checkActive ->
            val metadata = TxtFileText(decoded).use { text ->
                TxtMetadataDetector.detect(displayName, text.subSequence(0, minOf(text.length, 64 * 1024)).toString())
            }
            // 旧书没有指纹：只在单本导入时按书名作者提示「可能重复」，批量导入不会因此跳过。
            if (!allowDuplicate) likelyLegacyDuplicate(metadata.title, metadata.author)?.let { existing ->
                return@withDecodedTxt PreparedImport.Duplicate(uri, existing.id, existing.title, exact = false, removed = existing.removedAt != 0L)
            }
            val split = TxtFileText(decoded).use { chapterSplitter.indexBest(it, ruleLoader.rules, checkActive) }
            checkActive()
            val session = sessionStore.create(
                sourceName = displayName,
                suggestedTitle = metadata.title,
                suggestedAuthor = metadata.author,
                charsetName = charsetName,
                decodedFile = decoded,
                rules = ruleLoader.rules,
                splitResult = split,
                fingerprint = fingerprint
            )
            PreparedImport.PreviewReady(session.id)
        }

    /**
     * 先按指纹查；旧 EPUB 没有指纹，只对字节数相同的旧书补算一次（原包与导入源逐字节一致），
     * 补算结果写回，下次直接命中。正文已移除、只保留记录的书同样算重复。
     */
    private suspend fun findDuplicate(fingerprint: SourceFingerprint, backfillLegacyEpub: Boolean): BookEntity? {
        libraryRepository.findBookBySourceHash(fingerprint.sha256)?.let { return it }
        // 转换书虽然存为 EPUB，源文件并不是 EPUB；只能使用已记录的源指纹，不能拿生成包补算。
        if (!backfillLegacyEpub) return null
        libraryRepository.getEpubBooksWithoutSource().forEach { book ->
            val file = File(book.epubPath)
            if (!file.isFile || file.length() != fingerprint.size) return@forEach
            val legacy = runCatching { SourceFingerprints.of(file) }.getOrNull() ?: return@forEach
            libraryRepository.updateBookSource(book.id, legacy.sha256, legacy.size)
            if (legacy.sha256 == fingerprint.sha256) return book
        }
        return null
    }

    private suspend fun likelyLegacyDuplicate(title: String, author: String): BookEntity? {
        val key = BookTitles.normalize(title).takeIf { it.isNotEmpty() } ?: return null
        return libraryRepository.getAllBooksIncludingRemoved().firstOrNull { book ->
            book.sourceSha256 == null && book.sourceType == BookSourceType.TXT && BookTitles.normalize(book.title) == key &&
                (author.isBlank() || book.author.isBlank() || author.trim() == book.author.trim())
        }
    }

    private suspend fun importEpub(uri: Uri, displayName: String, allowDuplicate: Boolean): Long {
        val bookKey = UUID.randomUUID().toString()
        val target = File(booksDirectory(), "$bookKey.epub")
        val importContext = kotlinx.coroutines.currentCoroutineContext()
        try {
            val fingerprint = context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output ->
                    SourceFingerprints.copy(input, output, checkActive = { importContext.ensureActive() })
                }
            } ?: error("无法读取 EPUB 文件")
            if (!allowDuplicate) findDuplicate(fingerprint, backfillLegacyEpub = true)?.let { existing ->
                throw DuplicateBookException(existing.id, existing.title, existing.removedAt != 0L)
            }
            return importEpubFile(target, bookKey, displayName, fingerprint)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private suspend fun importDocument(uri: Uri, displayName: String, format: BookImportFormat, allowDuplicate: Boolean): Long {
        val bookKey = UUID.randomUUID().toString()
        val target = File(booksDirectory(), "$bookKey.epub")
        val staging = File(context.cacheDir, "document-import").apply { mkdirs() }
        val source = File(staging, "$bookKey.source")
        val importContext = kotlinx.coroutines.currentCoroutineContext()
        val checkActive = { importContext.ensureActive() }
        try {
            val maxBytes = if (format == BookImportFormat.MOBI) MobiBookConverter.MAX_SOURCE_BYTES else BookDocumentInput.MAX_SOURCE_BYTES
            val sizeReason = if (format == BookImportFormat.MOBI) BookConversionException.Reason.MOBI_TOO_LARGE else BookConversionException.Reason.SOURCE_TOO_LARGE
            if ((queryDocumentSize(uri) ?: 0) > maxBytes) throw BookConversionException(sizeReason)
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ImportFailureException(UiText.of(R.string.import_file_read_failed))
            val fingerprint = BookDocumentInput.bounded(input, maxBytes, sizeReason).use { stream ->
                source.outputStream().buffered().use { output -> SourceFingerprints.copy(stream, output, checkActive = checkActive) }
            }
            if (!allowDuplicate) findDuplicate(fingerprint, backfillLegacyEpub = false)?.let {
                throw DuplicateBookException(it.id, it.title, it.removedAt != 0L)
            }
            checkActive()
            val converted = when (format) {
                BookImportFormat.MARKDOWN -> MarkdownBookConverter().convert(source, displayName)
                BookImportFormat.HTML -> HtmlBookConverter().convert(source, displayName)
                BookImportFormat.MOBI -> MobiBookConverter().convert(source, displayName, checkActive)
                else -> error("Expected a convertible book document")
            }
            checkActive()
            MinimalEpubWriter.write(converted, fingerprint.sha256, target, checkActive)
            checkActive()
            return importEpubFile(target, bookKey, displayName, fingerprint)
        } catch (error: Throwable) {
            target.delete()
            when (error) {
                is kotlinx.coroutines.CancellationException, is DuplicateBookException, is ImportFailureException -> throw error
                is BookConversionException -> throw ImportFailureException(UiText.of(when (error.reason) {
                    BookConversionException.Reason.SOURCE_TOO_LARGE -> R.string.import_document_too_large
                    BookConversionException.Reason.IMAGE_TOO_LARGE -> R.string.import_document_image_too_large
                    BookConversionException.Reason.EMPTY_DOCUMENT -> R.string.import_document_empty
                    BookConversionException.Reason.DRM_PROTECTED -> R.string.import_mobi_drm_protected
                    BookConversionException.Reason.UNSUPPORTED_FORMAT -> R.string.import_mobi_unsupported
                    BookConversionException.Reason.CORRUPT_FILE -> R.string.import_mobi_corrupt
                    BookConversionException.Reason.MOBI_TOO_LARGE -> R.string.import_mobi_too_large
                    BookConversionException.Reason.TEXT_TOO_LARGE -> R.string.import_mobi_text_too_large
                }), error)
                is Exception -> throw ImportFailureException(UiText.of(
                    if (format == BookImportFormat.MOBI) R.string.import_mobi_failed else R.string.import_document_failed), error)
                else -> throw error
            }
        } finally {
            source.delete()
        }
    }

    /** 原始 EPUB 与转换包从这里共用 Readium、正文、精排、目录和封面入库流程。 */
    private suspend fun importEpubFile(target: File, bookKey: String, displayName: String, fingerprint: SourceFingerprint): Long {
        var coverFile: File? = null
        var insertedBookId: Long? = null

        try {
            val importContext = kotlinx.coroutines.currentCoroutineContext()
            EpubArchiveNormalizer.normalize(target) { importContext.ensureActive() }
            val publication = readium.open(target)
            try {
                coverFile = savePublicationCover(publication, bookKey)
                val layoutPackage = packageInspector.inspect(target)
                val spine = extractSpine(publication, layoutPackage, target)
                val structure = publication.toImportStructure(spine)
                val chapters = structure.chapters
                require(chapters.isNotEmpty()) { "EPUB 中没有可阅读内容" }

                // 元数据常是垃圾（WPS/Calibre 会写 Unknown / WPS_1532705572），先清洗再入库。
                val metadata = EpubMetadataResolver.resolve(
                    rawTitle = publication.metadata.title,
                    rawAuthor = publication.metadata.authors.firstOrNull()?.name,
                    identifier = publication.metadata.identifier,
                    navTitle = null,
                    displayName = displayName
                )

                val bookId = libraryRepository.insertBook(
                    book = BookEntity(
                        title = metadata.title,
                        author = metadata.author,
                        coverPath = coverFile?.absolutePath,
                        epubPath = target.absolutePath,
                        sourceType = BookSourceType.EPUB,
                        importedAt = System.currentTimeMillis(),
                        totalChapters = chapters.size,
                        sourceSha256 = fingerprint.sha256,
                        sourceByteSize = fingerprint.size
                    ),
                    chapters = chapters,
                    tocEntries = structure.tocEntries
                )
                insertedBookId = bookId
                require(spine.layouts.size == chapters.size) {
                    "EPUB 精排数据不完整：${spine.layouts.size}/${chapters.size} 章"
                }
                libraryRepository.materializeBookText(
                    bookId = bookId,
                    chapters = spine.chapters,
                    markReady = false
                )
                mediaStore.replace(bookId, spine.images, target)
                if (layoutPackage != null && spine.layouts.isNotEmpty()) {
                    layoutStore.initialize(bookId, target, layoutPackage, spine.layouts)
                }
                libraryRepository.markTextReady(bookId)
                // 向量索引改为按需触发，见 BookEmbeddingScheduler.enqueueForBook
                return bookId
            } finally {
                publication.close()
            }
        } catch (error: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val inserted = insertedBookId?.let { libraryRepository.getBook(it) }
                if (inserted != null) {
                    libraryRepository.deleteBook(inserted)
                } else {
                    coverFile?.delete()
                    target.delete()
                }
            }
            if (error is kotlinx.coroutines.CancellationException) throw error
            // 第三方解析异常可能携带正文片段，日志仅记录错误类型。
            Log.e(TAG, "EPUB import failed: ${error.javaClass.simpleName}")
            throw IllegalStateException("EPUB 解析失败：${error.userFacingCause()}", error)
        }
    }

    /**
     * Backfills `text.mz` for a book imported before plain text was stored, and converts its saved
     * Readium locators to character offsets.
     *
     * Old TXT imports that predate text.mz recover from their generated EPUB once. New TXT imports
     * already have canonical text and do not create an EPUB.
     */
    suspend fun materializeLegacyBook(book: BookEntity): Boolean =
        com.mozhi.reader.core.library.BookContentMutation.withBook(book.id) {
            val current = libraryRepository.getBook(book.id)
            if (current == null || current.removedAt > 0L) true else materializeLegacyBookContent(current)
        }

    /** Startup storage upgrade. None of these changes alter saved text/reading coordinates. */
    suspend fun optimizeStoredBook(book: BookEntity) {
        if (book.textVersion < LibraryRepository.CURRENT_TEXT_VERSION || book.removedAt > 0) return
        libraryRepository.compactBookText(book.id)
        if (book.sourceType == BookSourceType.TXT) {
            libraryRepository.discardGeneratedTxtEpub(book.id)
        } else {
            com.mozhi.reader.core.library.BookContentMutation.withBook(book.id) {
                val current = libraryRepository.getBook(book.id) ?: return@withBook
                if (current.removedAt > 0) return@withBook
                val archive = File(current.epubPath).takeIf(File::isFile) ?: return@withBook
                val lengths = libraryRepository.getChapters(book.id).sortedBy { it.chapterIndex }.map { it.charCount }
                layoutStore.adoptArchive(book.id, archive, lengths)
                mediaStore.adoptArchive(book.id, archive)
            }
        }
    }

    private suspend fun materializeLegacyBookContent(book: BookEntity): Boolean = withContext(Dispatchers.IO) {
        val epubFile = File(book.epubPath).takeIf(File::isFile) ?: return@withContext false
        val publication = runCatching { readium.open(epubFile) }.getOrNull() ?: return@withContext false
        try {
            // Version flips only after positions are migrated: a reader polling textVersion must
            // never open the book in the window where offsets are still the un-migrated defaults.
            val layoutPackage = if (book.sourceType == BookSourceType.EPUB) {
                packageInspector.inspect(epubFile)
            } else {
                null
            }
            val spine = extractSpine(publication, layoutPackage, epubFile)
            if (book.sourceType == BookSourceType.EPUB) {
                require(spine.layouts.size == spine.chapters.size) {
                    "EPUB 精排数据不完整：${spine.layouts.size}/${spine.chapters.size} 章"
                }
            }
            if (book.textVersion >= 1) {
                // Startup layout repair also reaches books with saved text and annotations.
                // A new fallback must never replace that canonical text or its UTF-16 coordinates.
                val saved = libraryRepository.getChapters(book.id).sortedBy { it.chapterIndex }
                check(saved.map { it.chapterIndex } == spine.chapters.map { it.index }) {
                    "Stored chapter index differs from EPUB spine"
                }
                saved.zip(spine.chapters).forEach { (chapter, rebuilt) ->
                    check(libraryRepository.readChapterText(book.id, chapter) == rebuilt.body) {
                        "Stored text differs from rebuilt EPUB chapter: ${chapter.chapterIndex}"
                    }
                }
            } else {
                libraryRepository.materializeBookText(
                    bookId = book.id,
                    chapters = spine.chapters,
                    markReady = false
                )
            }
            mediaStore.replace(book.id, spine.images, epubFile.takeIf { book.sourceType == BookSourceType.EPUB })
            if (layoutPackage != null && spine.layouts.isNotEmpty()) {
                layoutStore.initialize(book.id, epubFile, layoutPackage, spine.layouts)
            }
            // v0 没有正文字符轨，需要从旧 Readium locator 迁移；v1→v2 只补图片 sidecar，
            // 「［图片］」token 长度不变，必须保留已有的阅读/批注字符坐标。
            if (book.textVersion < 1) {
                migrateLegacyPositions(book, publication.readingOrder.map { it.href.toString() })
            }
            libraryRepository.markTextReady(book.id)
            true
        } finally {
            publication.close()
        }
    }

    private suspend fun migrateLegacyPositions(book: BookEntity, readingOrderHrefs: List<String>) {
        val chapters = libraryRepository.getChapters(book.id)
        if (chapters.isEmpty()) return

        fun positionOf(locatorJson: String, fallbackIndex: Int): Pair<Int, Int>? {
            val locator = LegacyLocatorConverter.parse(locatorJson) ?: return null
            val chapterIndex = LegacyLocatorConverter.resolveChapterIndex(
                locatorHref = locator.href,
                readingOrderHrefs = readingOrderHrefs,
                fallbackIndex = fallbackIndex
            ).coerceIn(chapters.indices)
            val charOffset = LegacyLocatorConverter.progressionToCharOffset(
                progression = locator.progression,
                charCount = chapters[chapterIndex].charCount
            )
            return chapterIndex to charOffset
        }

        book.lastReadLocator
            ?.takeIf(String::isNotBlank)
            ?.let { positionOf(it, book.lastReadChapterIndex) }
            ?.let { (chapterIndex, charOffset) ->
                libraryRepository.updateReadPosition(book.id, chapterIndex, charOffset)
            }

        libraryRepository.getBookmarks(book.id).forEach { bookmark ->
            positionOf(bookmark.locatorJson, book.lastReadChapterIndex)
                ?.let { (chapterIndex, charOffset) ->
                    libraryRepository.updateBookmarkPosition(bookmark.id, chapterIndex, charOffset)
                }
        }
    }

    /** Reads every spine resource and reduces it to text plus UTF-16 anchored inline images. */
    private suspend fun extractSpine(
        publication: Publication,
        layoutPackage: EpubLayoutPackage? = null,
        epubFile: File? = null
    ): ExtractedSpine {
        val chapters = ArrayList<ChapterTextInput>(publication.readingOrder.size)
        val images = ArrayList<BookImageInput>()
        val layouts = ArrayList<EpubLayoutChapterInput>(publication.readingOrder.size)
        val resourceCache = mutableMapOf<String, ByteArray?>()
        val stylesheets = if (layoutPackage != null && epubFile != null) {
            packageInspector.readStylesheets(epubFile, layoutPackage)
        } else {
            emptyMap()
        }
        val knownSpineHrefs = layoutPackage?.spine?.mapNotNull { it.href }.orEmpty()
        val indexedSpineHrefs = layoutPackage?.spine
            ?.filter { it.linear && it.href != null }
            ?.mapNotNull { it.href }
            ?.takeIf { it.size == publication.readingOrder.size }
            ?: layoutPackage?.spine
                ?.mapNotNull { it.href }
                ?.takeIf { it.size == publication.readingOrder.size }
        EpubArchiveImageReader(epubFile, layoutPackage).use { archiveImages ->
        publication.readingOrder.forEachIndexed { index, link ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val bytes = publication.get(link)?.use { resource -> resource.read().getOrNull() }
            val readiumHref = link.href.toString()
            val chapterHref = EpubResourcePath.matchKnown(readiumHref, knownSpineHrefs)
                ?: indexedSpineHrefs?.getOrNull(index)
                ?: EpubResourcePath.normalize(readiumHref)
                ?: readiumHref
            val parsedLayout = if (layoutPackage != null) {
                requireNotNull(bytes) { "无法读取 EPUB 第 ${index + 1} 章：$chapterHref" }
                try {
                    val parsed = EpubChapterFallback.parse(
                        parser = layoutParser,
                        bytes = bytes,
                        chapterIndex = index,
                        href = chapterHref,
                        stylesheets = stylesheets,
                        textExtractor = textExtractor
                    )
                    val spineProperties = layoutPackage.spine.getOrNull(index)?.properties.orEmpty()
                    val immersiveFromSpine = spineProperties.any { property ->
                        property.contains("cover", true) || property.contains("titlepage", true) ||
                            property.contains("page-fullscreen", true)
                    }
                    if (immersiveFromSpine && !parsed.document.immersivePage) {
                        parsed.copy(document = parsed.document.copy(immersivePage = true))
                    } else {
                        parsed
                    }
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    throw IllegalStateException(
                        "EPUB 第 ${index + 1} 章精排解析失败：$chapterHref",
                        error
                    )
                }
            } else {
                null
            }
            val extracted = parsedLayout?.let { parsed ->
                ExtractedEpubText(parsed.text, parsed.images)
            } ?: bytes?.let {
                textExtractor.extractWithImages(it, chapterHref)
            } ?: ExtractedEpubText("", emptyList())
            // 正文始终保留同长度的「［图片］」token。资源缺失时它直接作为降级文本，
            // 资源可用时 sidecar 在同一 UTF-16 偏移把该行替换为真实图片。
            extracted.images.forEach { reference ->
                val archivePath = archiveImages.resolve(reference.href)
                if (archivePath != null) {
                    images += BookImageInput(index, reference.charOffset, reference.href, reference.altText, archivePath = archivePath)
                    return@forEach
                }
                val imageBytes = if (resourceCache.containsKey(reference.href)) {
                    resourceCache[reference.href]
                } else {
                    readImageResource(publication, reference.href, layoutPackage, archiveImages)
                        ?.takeIf { it.size <= MAX_INLINE_IMAGE_BYTES }
                        .also { resourceCache[reference.href] = it }
                }
                if (imageBytes != null) {
                    images += BookImageInput(
                        chapterIndex = index,
                        charOffset = reference.charOffset,
                        sourceName = reference.href,
                        altText = reference.altText,
                        bytes = imageBytes
                    )
                }
            }
            chapters += ChapterTextInput(index = index, body = extracted.text)
            parsedLayout?.let { parsed ->
                layouts += EpubLayoutChapterInput(index, chapterHref, parsed.document.copy(blocks = emptyList()))
            }
        }
        }
        return ExtractedSpine(chapters, images, layouts)
    }

    /** Kept for text-only migration callers and tests. */
    suspend fun extractSpineText(publication: Publication): List<ChapterTextInput> =
        extractSpine(publication).chapters

    private suspend fun readImageResource(
        publication: Publication,
        href: String,
        layoutPackage: EpubLayoutPackage?,
        archiveImages: EpubArchiveImageReader
    ): ByteArray? {
        if (href.startsWith("data:", ignoreCase = true)) {
            val comma = href.indexOf(',')
            if (comma <= 0 || !href.substring(0, comma).contains(";base64", ignoreCase = true)) {
                return null
            }
            val encoded = href.substring(comma + 1)
            if (encoded.length > MAX_INLINE_IMAGE_BASE64_CHARS) return null
            return runCatching { Base64.decode(encoded, Base64.DEFAULT) }
                .getOrNull()
                ?.takeIf { it.size <= MAX_INLINE_IMAGE_BYTES }
        }
        val candidates = if (layoutPackage == null) {
            listOfNotNull(EpubResourcePath.normalize(href))
        } else {
            EpubResourcePath.packageAliases(href, layoutPackage.packageDocumentPath)
        }
        candidates.forEach { candidate ->
            val resourceHref = Href(candidate) ?: return@forEach
            val bytes = publication.get(Link(href = resourceHref))?.use { resource ->
                val length = resource.length().getOrNull()
                if (length != null && length > MAX_INLINE_IMAGE_BYTES) return@use null
                resource.read(0L..MAX_INLINE_IMAGE_BYTES.toLong())
                    .getOrNull()
                    ?.takeIf { it.size <= MAX_INLINE_IMAGE_BYTES }
            }
            if (bytes != null) return bytes
        }
        // Readium 对少数非规范包根 href、URL 编码路径或未列入 manifest 的图片会返回空。
        // 最后直接按规范化 archive path 从 EPUB 读取，仍受当前书包与大小上限约束。
        return archiveImages.read(href, MAX_INLINE_IMAGE_BYTES)
    }

    private data class ExtractedSpine(
        val chapters: List<ChapterTextInput>,
        val images: List<BookImageInput>,
        val layouts: List<EpubLayoutChapterInput>
    )

    private suspend fun savePublicationCover(publication: Publication, bookKey: String): File? {
        val bitmap = runCatching {
            publication.coverFitting(Size(MAX_COVER_WIDTH, MAX_COVER_HEIGHT))
        }.getOrNull() ?: return null
        val usesTransparency = bitmap.hasAlpha()
        val output = File(
            coversDirectory(),
            "$bookKey.${if (usesTransparency) "png" else "jpg"}"
        )
        return try {
            val compressed = output.outputStream().buffered().use { stream ->
                bitmap.compress(
                    if (usesTransparency) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    COVER_JPEG_QUALITY,
                    stream
                )
            }
            output.takeIf { compressed && it.isFile && it.length() > 0L }
                ?: run {
                    output.delete()
                    null
                }
        } finally {
            bitmap.recycle()
        }
    }

    private fun Publication.toImportStructure(spine: ExtractedSpine? = null): EpubImportStructure = buildEpubImportStructure(
        readingOrder = readingOrder.mapIndexed { index, link ->
            EpubReadingOrderItem(
                title = link.title ?: spine?.layouts?.getOrNull(index)?.document?.documentTitle,
                href = link.href.toString(),
                textSample = spine?.chapters?.getOrNull(index)?.body?.take(256)
            )
        },
        tableOfContents = tableOfContents.map { it.toNavigationNode() }
    )

    private fun Link.toNavigationNode(): EpubNavigationNode = EpubNavigationNode(
        title = title,
        href = href.toString(),
        children = children.map { it.toNavigationNode() }
    )

    private fun booksDirectory(): File = File(context.filesDir, "books").apply { mkdirs() }

    private fun coversDirectory(): File = File(context.filesDir, "covers").apply { mkdirs() }

    /**
     * 显示名优先取 SAF 的 DISPLAY_NAME；局域网收件箱交来的是 `file://` Uri，
     * ContentResolver 查不到列，此时退回路径末段——文件名决定按 TXT 还是 EPUB 分流，
     * 拿不到就会误判成「不支持的类型」。
     */
    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)

    private fun queryDocumentSize(uri: Uri): Long? =
        runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
            } }.getOrNull()

    private fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private companion object {
        const val TAG = "ImportCoordinator"
        const val MAX_TXT_BYTES = 200 * 1024 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 30 * 1024 * 1024
        const val MAX_INLINE_IMAGE_BASE64_CHARS = MAX_INLINE_IMAGE_BYTES * 4 / 3 + 8
        const val MAX_COVER_WIDTH = 1_200
        const val MAX_COVER_HEIGHT = 1_800
        const val COVER_JPEG_QUALITY = 90
        const val COVER_BACKFILL_MARKER = ".epub-cover-backfill-v1"
        const val CHAPTER_TITLE_REPAIR_MARKER = ".epub-chapter-title-repair-v1"
        private val PLACEHOLDER_CHAPTER_TITLE = Regex("""^第 \d+ 章$""")
    }
}

private fun Throwable.userFacingCause(): String {
    val deepest = generateSequence(this) { it.cause }.last()
    return deepest.message?.trim()?.takeIf(String::isNotEmpty)
        ?: deepest.javaClass.simpleName.takeIf(String::isNotEmpty)
        ?: "文件内容不受支持"
}
