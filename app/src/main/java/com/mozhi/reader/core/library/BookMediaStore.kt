package com.mozhi.reader.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.caverock.androidsvg.SVG
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
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
    val bytes: ByteArray
)

/** 阅读器消费的行内图片元数据；图片原文件保存在应用私有目录。 */
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
    suspend fun replace(bookId: Long, images: List<BookImageInput>) = withContext(Dispatchers.IO) {
        val root = directory(bookId)
        val staging = File(root.parentFile, "${root.name}.tmp-${System.nanoTime()}")
        staging.deleteRecursively()
        staging.mkdirs()
        val stored = ArrayList<StoredImage>(images.size)
        try {
            images.forEachIndexed { index, input ->
                if (input.bytes.isEmpty() || input.bytes.size > MAX_IMAGE_BYTES) return@forEachIndexed
                val sourceExtension = imageExtension(input.sourceName, input.bytes)
                    ?: return@forEachIndexed
                val extension = if (sourceExtension == "svg") "png" else sourceExtension
                val fileName = "ch-${input.chapterIndex.toString().padStart(5, '0')}-" +
                    "${index.toString().padStart(4, '0')}.$extension"
                val output = File(staging, fileName)
                val dimensions = if (sourceExtension == "svg") {
                    renderSvgToPng(input.bytes, output) ?: return@forEachIndexed
                } else {
                    output.writeBytes(input.bytes)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    val decodedBounds = runCatching {
                        BitmapFactory.decodeFile(output.absolutePath, options)
                        true
                    }.getOrDefault(false)
                    if (!decodedBounds || options.outWidth <= 0 || options.outHeight <= 0) {
                        output.delete()
                        return@forEachIndexed
                    }
                    options.outWidth to options.outHeight
                }
                val (pixelWidth, pixelHeight) = dimensions
                stored += StoredImage(
                    chapterIndex = input.chapterIndex,
                    charOffset = input.charOffset,
                    fileName = fileName,
                    pixelWidth = pixelWidth,
                    pixelHeight = pixelHeight,
                    altText = input.altText.take(MAX_ALT_CHARS)
                )
            }
            File(staging, MANIFEST_NAME).writeText(json.encodeToString(stored))
            root.deleteRecursively()
            if (!staging.renameTo(root)) {
                root.mkdirs()
                staging.copyRecursively(root, overwrite = true)
                staging.deleteRecursively()
            }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun read(bookId: Long): List<BookInlineImage> = withContext(Dispatchers.IO) {
        val root = directory(bookId)
        val manifest = File(root, MANIFEST_NAME).takeIf(File::isFile) ?: return@withContext emptyList()
        val stored = runCatching {
            json.decodeFromString<List<StoredImage>>(manifest.readText())
        }.getOrElse { return@withContext emptyList() }
        stored.mapNotNull { image ->
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

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && indices.take(prefix.size).all { this[it] == prefix[it] }

    @Serializable
    private data class StoredImage(
        val chapterIndex: Int,
        val charOffset: Int,
        val fileName: String,
        val pixelWidth: Int,
        val pixelHeight: Int,
        val altText: String
    )

    private companion object {
        const val ROOT_DIRECTORY = "book-media"
        const val MANIFEST_NAME = "images.json"
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
