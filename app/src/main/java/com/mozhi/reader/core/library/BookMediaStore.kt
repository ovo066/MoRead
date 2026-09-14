package com.mozhi.reader.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 导入器交给媒体库的 EPUB 图片；[charOffset] 是 text.mz 章内 UTF-16 坐标。 */
data class BookImageInput(
    val chapterIndex: Int,
    val charOffset: Int,
    val sourceName: String,
    val altText: String,
    val bytes: ByteArray = byteArrayOf(),
    /** Exact ZIP entry name; only exceptional/data-URI images need a second file. */
    val archivePath: String? = null
)

/** 阅读器消费的行内图片元数据；imagePath 可以指向 EPUB 内的资源。 */
data class BookInlineImage(
    val chapterIndex: Int,
    val charOffset: Int,
    val imagePath: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val altText: String
)

/**
 * EPUB 行内媒体的可重建 sidecar。正文仍只有一个 text.mz；其中每张图片保留「［图片］」token，
 * sidecar 用 token 起点的字符偏移找到原图。换字号/翻页动画不改变锚点，也不混淆字节坐标。
 */
@Singleton
class BookMediaStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun replace(bookId: Long, images: List<BookImageInput>, epubFile: File? = null) = withContext(Dispatchers.IO) {
        val root = directory(bookId)
        val staging = File(root.parentFile, "${root.name}.tmp-${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        val stored = ArrayList<StoredImage>(images.size)
        val assetsBySource = HashMap<String, StoredAsset>()
        val archivePool = EpubArchivePool()
        try {
            images.forEachIndexed { index, input ->
                val sourceKey = input.archivePath ?: input.sourceName
                val existing = assetsBySource[sourceKey]
                val asset = if (existing != null) {
                    existing
                } else {
                    val archived = epubFile != null && input.archivePath != null
                    val bytes = if (archived) archivePool.read(EpubArchiveAsset(epubFile!!, input.archivePath!!))
                        ?: return@forEachIndexed else input.bytes
                    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return@forEachIndexed
                    val sourceExtension = imageExtension(input.sourceName, bytes)
                        ?: return@forEachIndexed
                    val extension = if (sourceExtension == "svg") "png" else sourceExtension
                    val fileName = "ch-${input.chapterIndex.toString().padStart(5, '0')}-" +
                        "${index.toString().padStart(4, '0')}.$extension"
                    val output = File(staging, fileName)
                    val dimensions = if (sourceExtension == "svg") {
                        if (archived) svgDimensions(bytes) ?: return@forEachIndexed
                        else renderSvgToPng(bytes, output) ?: return@forEachIndexed
                    } else {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        val decodedBounds = runCatching {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            true
                        }.getOrDefault(false)
                        if (!decodedBounds || options.outWidth <= 0 || options.outHeight <= 0) {
                            output.delete()
                            return@forEachIndexed
                        }
                        if (!archived) output.writeBytes(bytes)
                        options.outWidth to options.outHeight
                    }
                    StoredAsset(if (archived) "" else fileName, dimensions.first, dimensions.second,
                        if (archived) input.archivePath else null).also { value ->
                        assetsBySource[sourceKey] = value
                    }
                }
                stored += StoredImage(
                    chapterIndex = input.chapterIndex,
                    charOffset = input.charOffset,
                    fileName = asset.fileName,
                    pixelWidth = asset.pixelWidth,
                    pixelHeight = asset.pixelHeight,
                    altText = input.altText.take(MAX_ALT_CHARS),
                    archivePath = asset.archivePath
                )
            }
            File(staging, MANIFEST_NAME).writeText(json.encodeToString(stored))
            epubFile?.let {
                File(staging, ARCHIVE_FILE).writeText(json.encodeToString(StoredArchive(it.portableArchivePath(context.filesDir))))
                File(staging, ARCHIVE_MARKER).writeText("1")
            }
            root.deleteRecursively()
            if (!staging.renameTo(root)) {
                root.mkdirs()
                staging.copyRecursively(root, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        } finally { archivePool.close() }
    }

    suspend fun read(bookId: Long): List<BookInlineImage> = withContext(Dispatchers.IO) {
        val root = directory(bookId)
        val manifest = File(root, MANIFEST_NAME).takeIf(File::isFile) ?: return@withContext emptyList()
        val stored = runCatching {
            json.decodeFromString<List<StoredImage>>(manifest.readText())
        }.getOrElse { return@withContext emptyList() }
        val archive = readArchive(root)
        stored.mapNotNull { image ->
            if (image.archivePath != null) {
                val source = archive ?: return@mapNotNull null
                return@mapNotNull BookInlineImage(image.chapterIndex, image.charOffset,
                    EpubArchiveAsset(source, image.archivePath).encode(), image.pixelWidth, image.pixelHeight, image.altText)
            }
            if (image.fileName.isBlank()) return@mapNotNull null
            val file = File(root, image.fileName)
            val safe = runCatching {
                file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
            }.getOrDefault(false)
            if (!safe || !file.isFile) return@mapNotNull null
            BookInlineImage(
                chapterIndex = image.chapterIndex,
                charOffset = image.charOffset,
                imagePath = file.absolutePath,
                pixelWidth = image.pixelWidth,
                pixelHeight = image.pixelHeight,
                altText = image.altText
            )
        }
    }

    /** Match old copies by bytes before removing them; converted/edited exceptions stay on disk. */
    suspend fun adoptArchive(bookId: Long, epubFile: File): Long = withContext(Dispatchers.IO) {
        val root = directory(bookId)
        if (readArchive(root) != null && File(root, ARCHIVE_MARKER).isFile || !epubFile.isFile) return@withContext 0L
        val manifest = File(root, MANIFEST_NAME).takeIf(File::isFile) ?: return@withContext 0L
        val stored = runCatching { json.decodeFromString<List<StoredImage>>(manifest.readText()) }.getOrNull()
            ?: return@withContext 0L
        val matches = HashMap<String, String>()
        ZipFile(epubFile).use { zip ->
            val entriesBySize = zip.entries().asSequence().filter { !it.isDirectory && it.size in 1..MAX_IMAGE_BYTES }
                .groupBy { it.size }
            val hashes = HashMap<String, ByteArray>()
            stored.map { it.fileName }.filter(String::isNotBlank).distinct().forEach { name ->
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val local = safeImageFile(root, name) ?: return@forEach
                val candidates = entriesBySize[local.length()].orEmpty()
                if (candidates.isEmpty()) return@forEach
                val hash = local.inputStream().use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    digest.digest()
                }
                val match = candidates.firstOrNull { entry ->
                    val other = hashes.getOrPut(entry.name) {
                        zip.getInputStream(entry).use { stream ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val n = stream.read(buffer); if (n < 0) break
                                total += n
                                require(total <= MAX_IMAGE_BYTES) { "EPUB 图片超过读取上限" }
                                digest.update(buffer, 0, n)
                            }
                            digest.digest()
                        }
                    }
                    hash.contentEquals(other)
                }
                if (match != null) matches[name] = match.name
            }
        }
        // Commit references before deleting any copy. Interrupted upgrades remain readable.
        writeAtomic(File(root, ARCHIVE_FILE), json.encodeToString(StoredArchive(epubFile.portableArchivePath(context.filesDir))))
        val updated = stored.map { image ->
            matches[image.fileName]?.let { image.copy(fileName = "", archivePath = it) } ?: image
        }
        writeAtomic(manifest, json.encodeToString(updated))
        val referencedFiles = updated.mapTo(hashSetOf()) { it.fileName }
        var complete = true
        val freed = root.listFiles().orEmpty().filter { it.isFile && it.name !in referencedFiles &&
            it.name.matches(Regex("ch-\\d+-\\d+\\.[a-zA-Z0-9]+")) }.sumOf { file ->
            val bytes = file.length()
            if (file.delete()) bytes else { complete = false; 0L }
        }
        if (complete) writeAtomic(File(root, ARCHIVE_MARKER), "1")
        freed
    }

    private fun readArchive(root: File): File? = runCatching {
        val stored = json.decodeFromString<StoredArchive>(File(root, ARCHIVE_FILE).readText())
        resolveArchivePath(context.filesDir, stored.path)
    }.getOrNull()

    private fun safeImageFile(root: File, name: String): File? = runCatching {
        File(root, name).canonicalFile.takeIf { it.isFile && it.toPath().startsWith(root.canonicalFile.toPath()) }
    }.getOrNull()

    private fun writeAtomic(file: File, text: String) {
        val temporary = File.createTempFile("media-", ".tmp", file.parentFile)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun delete(bookId: Long) {
        directory(bookId).deleteRecursively()
    }

    private fun directory(bookId: Long): File =
        File(context.filesDir, "$ROOT_DIRECTORY/$bookId")

    private fun imageExtension(sourceName: String, bytes: ByteArray): String? {
        val lower = sourceName.substringBefore('?').substringBefore('#').lowercase()
        return when {
            bytes.startsWith(PNG_SIGNATURE) -> "png"
            bytes.startsWith(JPEG_SIGNATURE) -> "jpg"
            bytes.startsWith(GIF87_SIGNATURE) || bytes.startsWith(GIF89_SIGNATURE) -> "gif"
            bytes.startsWith(WEBP_PREFIX) && bytes.size >= 12 &&
                bytes.copyOfRange(8, 12).contentEquals(WEBP_SUFFIX) -> "webp"
            lower.endsWith(".png") -> "png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "jpg"
            lower.endsWith(".gif") -> "gif"
            lower.endsWith(".webp") -> "webp"
            lower.endsWith(".svg") || bytes.take(256).toByteArray().decodeToString()
                .contains("<svg", ignoreCase = true) -> "svg"
            else -> null
        }
    }

    private fun renderSvgToPng(bytes: ByteArray, output: File): Pair<Int, Int>? {
        val svg = runCatching { SVG.getFromInputStream(bytes.inputStream()) }.getOrNull()
            ?: return null
        val viewBox = svg.documentViewBox
        var width = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
            ?: viewBox?.width()?.takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_VECTOR_WIDTH.toFloat()
        var height = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
            ?: viewBox?.height()?.takeIf { it.isFinite() && it > 0f }
            ?: DEFAULT_VECTOR_HEIGHT.toFloat()
        val scale = min(1f, min(MAX_VECTOR_DIMENSION / width, MAX_VECTOR_DIMENSION / height))
        width *= scale
        height *= scale
        val pixelWidth = width.roundToInt().coerceIn(1, MAX_VECTOR_DIMENSION)
        val pixelHeight = height.roundToInt().coerceIn(1, MAX_VECTOR_DIMENSION)
        val bitmap = runCatching {
            Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
        }.getOrNull() ?: return null
        return try {
            svg.setDocumentWidth(pixelWidth.toFloat())
            svg.setDocumentHeight(pixelHeight.toFloat())
            svg.renderToCanvas(Canvas(bitmap))
            val saved = output.outputStream().buffered().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            if (saved && output.length() > 0L) pixelWidth to pixelHeight else null
        } catch (_: Throwable) {
            output.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun svgDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        val width = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
            ?: svg.documentViewBox?.width()?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_VECTOR_WIDTH.toFloat()
        val height = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
            ?: svg.documentViewBox?.height()?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_VECTOR_HEIGHT.toFloat()
        width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
    }.getOrNull()

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && indices.take(prefix.size).all { this[it] == prefix[it] }

    private data class StoredAsset(
        val fileName: String,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val archivePath: String? = null
    )

    @Serializable
    private data class StoredImage(
        val chapterIndex: Int,
        val charOffset: Int,
        val fileName: String,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val altText: String,
        val archivePath: String? = null
    )

    @Serializable private data class StoredArchive(val path: String)

    private companion object {
        const val ROOT_DIRECTORY = "book-media"
        const val MANIFEST_NAME = "images.json"
        const val ARCHIVE_FILE = "archive.json"
        const val ARCHIVE_MARKER = ".archive-v1"
        const val MAX_IMAGE_BYTES = 30 * 1024 * 1024
        const val MAX_ALT_CHARS = 500
        const val DEFAULT_VECTOR_WIDTH = 1_200
        const val DEFAULT_VECTOR_HEIGHT = 800
        const val MAX_VECTOR_DIMENSION = 2_048
        val json = Json { ignoreUnknownKeys = true }
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val GIF87_SIGNATURE = "GIF87a".encodeToByteArray()
        val GIF89_SIGNATURE = "GIF89a".encodeToByteArray()
        val WEBP_PREFIX = "RIFF".encodeToByteArray()
        val WEBP_SUFFIX = "WEBP".encodeToByteArray()
    }
}
