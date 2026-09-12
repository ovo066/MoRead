package com.mozhi.reader.core.media

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ExportableImage(val file: File, val mimeType: String, val extension: String) {
    val suggestedName: String get() = "MoRead-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$extension"
}

/** Only exports a user-selected local image. No network fetch or broad storage permission. */
object LocalImageExporter {
    fun source(context: Context, path: String): ExportableImage {
        val local = File(path)
        val file = if (local.isAbsolute) local.canonicalFile else {
            val uri = Uri.parse(path)
            require(uri.scheme == "file") { "请先将图片保存到应用内再导出" }
            File(requireNotNull(uri.path)).canonicalFile
        }
        val owned = listOf(context.filesDir, context.cacheDir).any {
            file.toPath().startsWith(it.canonicalFile.toPath())
        }
        require(owned && file.isFile && file.length() > 0) { "图片已不存在或不属于应用图片目录" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片文件不可用" }
        val mime = bounds.outMimeType ?: error("无法识别图片格式")
        val extension = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            else -> error("暂不支持导出此图片格式")
        }
        return ExportableImage(file, mime, extension)
    }

    suspend fun saveToGallery(context: Context, path: String): Uri = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= 29) { "此系统请使用“导出文件”选择保存位置" }
        val source = source(context, path)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, source.suggestedName)
            put(MediaStore.Images.Media.MIME_TYPE, source.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MoRead")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建相册图片")
        try {
            copyTo(context, source, uri)
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0) {
                "无法完成相册保存"
            }
            uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    suspend fun saveToDocument(context: Context, path: String, uri: Uri) = withContext(Dispatchers.IO) {
        copyTo(context, source(context, path), uri)
    }

    private suspend fun copyTo(context: Context, source: ExportableImage, uri: Uri) {
        val coroutine = currentCoroutineContext()
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            source.file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法写入所选位置")
    }
}
