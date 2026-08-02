package com.mozhi.reader.feature.importer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Size
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.importer.BookImportGateway
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.library.BookImageInput
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.ChapterDraft
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LegacyLocatorConverter
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.readium.ReadiumServices
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val epubGenerator: EpubGenerator,
    private val textExtractor: EpubTextExtractor,
    private val mediaStore: BookMediaStore,
    private val sessionStore: ImportSessionStore,
    private val readium: ReadiumServices,
    private val libraryRepository: LibraryRepository
) : BookImportGateway {
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

    override suspend fun prepare(uri: Uri): PreparedImport = withContext(Dispatchers.IO) {
        takeReadPermission(uri)
        val displayName = queryDisplayName(uri) ?: "未命名书籍"
        val mimeType = context.contentResolver.getType(uri).orEmpty()

        when {
            displayName.endsWith(".txt", ignoreCase = true) || mimeType.startsWith("text/") ->
                prepareTxt(uri, displayName)
            displayName.endsWith(".epub", ignoreCase = true) ||
                mimeType == "application/epub+zip" ->
                PreparedImport.BookImported(importEpub(uri, displayName))
            else -> error("仅支持 TXT 与 EPUB 文件")
        }
    }

    suspend fun preview(sessionId: String): TxtImportPreview? = withContext(Dispatchers.IO) {
        sessionStore.get(sessionId)?.toPreview()
    }

    suspend fun selectRule(sessionId: String, ruleId: Long): TxtImportPreview =
        withContext(Dispatchers.Default) {
            val session = requireNotNull(sessionStore.get(sessionId)) { "导入会话已失效" }
            val rule = requireNotNull(session.rules.firstOrNull { it.id == ruleId }) { "规则不存在" }
            val split = chapterSplitter.splitWithRule(session.text, rule)
                ?: error("该规则未识别到足够的章节")
            session.copy(splitResult = split).also(sessionStore::update).toPreview()
        }

    suspend fun applyCustomRegex(sessionId: String, regex: String): TxtImportPreview =
        withContext(Dispatchers.Default) {
            val session = requireNotNull(sessionStore.get(sessionId)) { "导入会话已失效" }
            val split = chapterSplitter.splitWithCustomRegex(session.text, regex)
                ?: error("自定义正则未识别到章节，请检查表达式")
            session.copy(splitResult = split).also(sessionStore::update).toPreview()
        }

    suspend fun confirmTxt(
        sessionId: String,
        title: String,
        author: String,
        onProgress: (ImportProgress) -> Unit = {}
    ): Long = withContext(Dispatchers.IO) {
        val session = requireNotNull(sessionStore.get(sessionId)) { "导入会话已失效" }
        require(title.isNotBlank()) { "书名不能为空" }
        require(title.length <= 200) { "书名不能超过 200 个字符" }
        require(author.length <= 120) { "作者不能超过 120 个字符" }
        require(session.splitResult.chapters.isNotEmpty()) { "没有可导入的章节" }

        val output = File(booksDirectory(), "${UUID.randomUUID()}.epub")
        try {
            onProgress(ImportProgress("正在生成 EPUB", total = session.splitResult.chapters.size))
            val generated = epubGenerator.generate(
                outputFile = output,
                title = title.trim(),
                author = author.trim(),
                chapters = session.splitResult.chapters
            ) { completed, total ->
                onProgress(ImportProgress("正在生成 EPUB", completed, total))
            }

            onProgress(
                ImportProgress(
                    message = "正在校验 EPUB",
                    completed = generated.chapters.size,
                    total = generated.chapters.size
                )
            )
            val publication = try {
                readium.open(generated.file)
            } catch (error: Throwable) {
                throw IllegalStateException("生成的 EPUB 校验失败", error)
            }
            publication.close()

            onProgress(
                ImportProgress(
                    message = "正在写入书架",
                    completed = generated.chapters.size,
                    total = generated.chapters.size
                )
            )
            val bookId = libraryRepository.insertBook(
                book = BookEntity(
                    title = title.trim(),
                    author = author.trim(),
                    coverPath = null,
                    epubPath = generated.file.absolutePath,
                    sourceType = BookSourceType.TXT,
                    importedAt = System.currentTimeMillis(),
                    totalChapters = generated.chapters.size
                ),
                chapters = generated.chapters
            )

            onProgress(
                ImportProgress(
                    message = "正在写入文本",
                    completed = generated.chapters.size,
                    total = generated.chapters.size
                )
            )
            libraryRepository.materializeBookText(
                bookId = bookId,
                chapters = session.splitResult.chapters.map { chapter ->
                    ChapterTextInput(index = chapter.index, body = chapter.content)
                }
            )
            sessionStore.remove(sessionId)
            // 向量索引不再随导入自动建立；伴读首次检索该书时按需触发
            bookId
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
    }

    private fun prepareTxt(uri: Uri, displayName: String): PreparedImport {
        val declaredSize = queryDocumentSize(uri)
        require(declaredSize == null || declaredSize <= MAX_TXT_BYTES) {
            "TXT 文件超过 200 MB，暂不支持导入"
        }
        val bytes = readBytesWithLimit(uri, MAX_TXT_BYTES)
        require(bytes.isNotEmpty()) { "TXT 文件为空" }

        val detected = encodingDetector.decode(bytes)
        val split = chapterSplitter.chooseBest(detected.text, ruleLoader.rules)
        val session = sessionStore.create(
            sourceName = displayName,
            suggestedTitle = displayName.substringBeforeLast('.').ifBlank { "未命名书籍" },
            charsetName = detected.charsetName,
            text = detected.text,
            rules = ruleLoader.rules,
            splitResult = split
        )
        return PreparedImport.PreviewReady(session.id)
    }

    private suspend fun importEpub(uri: Uri, displayName: String): Long {
        val bookKey = UUID.randomUUID().toString()
        val target = File(booksDirectory(), "$bookKey.epub")
        var coverFile: File? = null
        var insertedBookId: Long? = null
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().buffered().use(input::copyTo)
        } ?: error("无法读取 EPUB 文件")

        try {
            val publication = readium.open(target)
            try {
                coverFile = savePublicationCover(publication, bookKey)
                val titleByHref = flatten(publication.tableOfContents)
                    .mapNotNull { link ->
                        link.title?.let { link.href.toString().withoutFragment() to it }
                    }
                    .toMap()
                val chapters = publication.readingOrder.mapIndexed { index, link ->
                    val href = link.href.toString()
                    ChapterDraft(
                        index = index,
                        title = titleByHref[href.withoutFragment()]
                            ?: link.title
                            ?: "第 ${index + 1} 章",
                        href = href,
                        charCount = 0
                    )
                }
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
                        totalChapters = chapters.size
                    ),
                    chapters = chapters
                )
                insertedBookId = bookId
                val spine = extractSpine(publication)
                libraryRepository.materializeBookText(
                    bookId = bookId,
                    chapters = spine.chapters,
                    markReady = false
                )
                mediaStore.replace(bookId, spine.images)
                libraryRepository.markTextReady(bookId)
                // 向量索引改为按需触发，见 BookEmbeddingScheduler.enqueueForBook
                return bookId
            } finally {
                publication.close()
            }
        } catch (error: Throwable) {
            val inserted = insertedBookId?.let { libraryRepository.getBook(it) }
            if (inserted != null) {
                libraryRepository.deleteBook(inserted)
            } else {
                coverFile?.delete()
                target.delete()
            }
            throw IllegalStateException("EPUB 解析失败或文件已损坏", error)
        }
    }

    /**
     * Backfills `text.mz` for a book imported before plain text was stored, and converts its saved
     * Readium locators to character offsets.
     *
     * Books imported from TXT also go through the EPUB spine here: the generated EPUB is the only
     * remaining copy of their text, and it holds exactly one resource per chapter.
     */
    suspend fun materializeLegacyBook(book: BookEntity): Boolean = withContext(Dispatchers.IO) {
        val epubFile = File(book.epubPath).takeIf(File::isFile) ?: return@withContext false
        val publication = runCatching { readium.open(epubFile) }.getOrNull() ?: return@withContext false
        try {
            // Version flips only after positions are migrated: a reader polling textVersion must
            // never open the book in the window where offsets are still the un-migrated defaults.
            val spine = extractSpine(publication)
            libraryRepository.materializeBookText(
                bookId = book.id,
                chapters = spine.chapters,
                markReady = false
            )
            mediaStore.replace(book.id, spine.images)
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
    private suspend fun extractSpine(publication: Publication): ExtractedSpine {
        val chapters = ArrayList<ChapterTextInput>(publication.readingOrder.size)
        val images = ArrayList<BookImageInput>()
        val resourceCache = mutableMapOf<String, ByteArray?>()
        publication.readingOrder.forEachIndexed { index, link ->
            val bytes = publication.get(link)?.use { resource -> resource.read().getOrNull() }
            val extracted = bytes?.let {
                textExtractor.extractWithImages(it, link.href.toString())
            } ?: ExtractedEpubText("", emptyList())
            // 正文始终保留同长度的「［图片］」token。资源缺失时它直接作为降级文本，
            // 资源可用时 sidecar 在同一 UTF-16 偏移把该行替换为真实图片。
            extracted.images.forEach { reference ->
                val imageBytes = if (resourceCache.containsKey(reference.href)) {
                    resourceCache[reference.href]
                } else {
                    readImageResource(publication, reference.href)
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
        }
        return ExtractedSpine(chapters, images)
    }

    /** Kept for text-only migration callers and tests. */
    suspend fun extractSpineText(publication: Publication): List<ChapterTextInput> =
        extractSpine(publication).chapters

    private suspend fun readImageResource(publication: Publication, href: String): ByteArray? {
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
        val resourceHref = Href(href) ?: return null
        return publication.get(Link(href = resourceHref))?.use { resource ->
            val length = resource.length().getOrNull()
            if (length != null && length > MAX_INLINE_IMAGE_BYTES) return@use null
            resource.read(0L..MAX_INLINE_IMAGE_BYTES.toLong())
                .getOrNull()
                ?.takeIf { it.size <= MAX_INLINE_IMAGE_BYTES }
        }
    }

    private data class ExtractedSpine(
        val chapters: List<ChapterTextInput>,
        val images: List<BookImageInput>
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

    private fun flatten(links: List<Link>): List<Link> = buildList {
        links.forEach { link ->
            add(link)
            addAll(flatten(link.children))
        }
    }

    private fun booksDirectory(): File = File(context.filesDir, "books").apply { mkdirs() }

    private fun coversDirectory(): File = File(context.filesDir, "covers").apply { mkdirs() }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }

    private fun queryDocumentSize(uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getLong(0)
            }

    private fun readBytesWithLimit(uri: Uri, maxBytes: Int): ByteArray {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取文件")
        return input.buffered().use { stream ->
            val output = ByteArrayOutputStream(DEFAULT_READ_BUFFER_SIZE)
            val buffer = ByteArray(DEFAULT_READ_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= maxBytes) { "TXT 文件超过 200 MB，暂不支持导入" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun takeReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun String.withoutFragment(): String = substringBefore('#')

    private companion object {
        const val MAX_TXT_BYTES = 200 * 1024 * 1024
        const val MAX_INLINE_IMAGE_BYTES = 30 * 1024 * 1024
        const val MAX_INLINE_IMAGE_BASE64_CHARS = MAX_INLINE_IMAGE_BYTES * 4 / 3 + 8
        const val DEFAULT_READ_BUFFER_SIZE = 64 * 1024
        const val MAX_COVER_WIDTH = 1_200
        const val MAX_COVER_HEIGHT = 1_800
        const val COVER_JPEG_QUALITY = 90
        const val COVER_BACKFILL_MARKER = ".epub-cover-backfill-v1"
    }
}
