package com.mozhi.reader.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import com.caverock.androidsvg.SVG
import com.mozhi.reader.core.library.EpubArchiveAsset
import com.mozhi.reader.core.library.EpubArchivePool
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Materializes only the image the user selected. EPUBs and their original assets are never edited. */
object EpubImageFiles {
    // Rapid rotation must not decode several full-resolution exports concurrently.
    private val exportMutex = Mutex()
    suspend fun materialize(context: Context, path: String): File = withContext(Dispatchers.IO) {
        val asset = EpubArchiveAsset.parse(path)
        val input = asset?.archive ?: File(path)
        require(input.isFile) { "图片源文件已不存在" }
        val key = MessageDigest.getInstance("SHA-256").digest(
            "$path:${input.length()}:${input.lastModified()}".toByteArray()
        ).joinToString("") { "%02x".format(it) }.take(32)
        val directory = File(context.cacheDir, "epub-image-editor").apply { mkdirs() }
        // Picker restoration needs these files to outlive the dialog; old copies are expendable cache.
        val oldest = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.isFile && it.lastModified() < oldest }?.forEach { it.delete() }
        val bytes = if (asset != null) {
            EpubArchivePool().use { it.read(asset) } ?: error("无法读取书内图片，文件可能损坏或过大")
        } else {
            require(input.length() in 1..EpubArchivePool.MAX_IMAGE_BYTES.toLong()) { "图片文件过大或已损坏" }
            input.readBytes()
        }
        coroutineContext.ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val extension = when (bounds.outMimeType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            else -> "svg"
        }
        val file = File(directory, "$key.$extension")
        if (!file.isFile) {
            val temp = File(directory, "${UUID.randomUUID()}.tmp")
            try {
                temp.writeBytes(bytes)
                check(temp.renameTo(file) || file.isFile) { "无法缓存图片" }
            } finally { temp.delete() }
        }
        file.setLastModified(System.currentTimeMillis())
        file
    }

    suspend fun preview(file: File): Bitmap {
        var decoded: Bitmap? = null
        try {
            return withContext(Dispatchers.IO) {
                decode(file, maxDimension = 4096, maxPixels = 4_000_000).also { decoded = it }
            }
        } catch (error: Throwable) {
            // withContext may cancel while handing the decoded bitmap back to the UI.
            decoded?.recycle()
            throw error
        }
    }

    /** Zero rotation copies the original raster bytes; edited/SVG exports are bounded PNG copies. */
    suspend fun export(file: File, quarterTurns: Int): File = withContext(Dispatchers.IO) {
        exportMutex.withLock {
            val turns = ((quarterTurns % 4) + 4) % 4
            if (turns == 0 && !file.extension.equals("svg", true)) return@withContext file
            val output = File(file.parentFile, "${file.nameWithoutExtension}-rotate-$turns.png")
            if (output.isFile) return@withContext output
            val bitmap = decode(file, maxDimension = 8192, maxPixels = 16_000_000)
            var rotated: Bitmap? = null
            val temp = File(file.parentFile, "${UUID.randomUUID()}.tmp")
            try {
                coroutineContext.ensureActive()
                rotated = if (turns == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width,
                    bitmap.height, Matrix().apply { postRotate(turns * 90f) }, true)
                temp.outputStream().use { check(rotated.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                coroutineContext.ensureActive()
                check(temp.renameTo(output) || output.isFile) { "无法保存旋转后的图片" }
                output
            } finally {
                if (rotated !== bitmap) rotated?.recycle()
                bitmap.recycle()
                temp.delete()
            }
        }
    }

    private fun decode(file: File, maxDimension: Int, maxPixels: Int): Bitmap {
        if (file.extension.equals("svg", true)) {
            val svg = file.inputStream().use { SVG.getFromInputStream(it) }
            val width = svg.documentWidth.takeIf { it.isFinite() && it > 0f }
                ?: svg.documentViewBox?.width()?.takeIf { it.isFinite() && it > 0f } ?: 1024f
            val height = svg.documentHeight.takeIf { it.isFinite() && it > 0f }
                ?: svg.documentViewBox?.height()?.takeIf { it.isFinite() && it > 0f } ?: 1024f
            val scale = min(1f, min(maxDimension / max(width, height), sqrt(maxPixels.toFloat() / (width * height))))
            val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                svg.setDocumentWidth(bitmap.width.toFloat())
                svg.setDocumentHeight(bitmap.height.toFloat())
                svg.renderToCanvas(Canvas(bitmap))
                return bitmap
            } catch (error: Exception) { bitmap.recycle(); throw error }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "图片已损坏或格式暂不支持" }
        options.inSampleSize = 1
        while (options.outWidth / options.inSampleSize > maxDimension ||
            options.outHeight / options.inSampleSize > maxDimension ||
            (options.outWidth / options.inSampleSize).toLong() * (options.outHeight / options.inSampleSize) > maxPixels) {
            options.inSampleSize *= 2
        }
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, options) ?: error("无法解码图片")
    }
}
