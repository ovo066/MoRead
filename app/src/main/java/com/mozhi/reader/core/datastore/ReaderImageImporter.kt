package com.mozhi.reader.core.datastore

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PendingReaderImage(
    val cachePath: String,
    val originalFileName: String,
    val detectedName: String,
    val extension: String,
    val width: Int,
    val height: Int
)

/** 阅读背景与书籍封面共用的图片库导入器。 */
@Singleton
class ReaderImageImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: ReaderSettingsRepository
) {
    suspend fun prepare(uri: Uri): PendingReaderImage = withContext(Dispatchers.IO) {
        val originalName = queryDisplayName(uri)
            ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
            .ifBlank { "图片" }
        val extension = extensionFor(originalName, context.contentResolver.getType(uri))
        val target = File(pendingDirectory(), "${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.buffered()?.use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_IMAGE_BYTES) { "图片文件超过 40 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取图片文件")
            require(target.length() > 0L) { "图片文件为空" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "不是可用的图片文件" }
            require(
                bounds.outWidth <= MAX_IMAGE_DIMENSION &&
                    bounds.outHeight <= MAX_IMAGE_DIMENSION &&
                    bounds.outWidth.toLong() * bounds.outHeight <= MAX_IMAGE_PIXELS
            ) { "图片尺寸过大" }
            PendingReaderImage(
                cachePath = target.absolutePath,
                originalFileName = originalName,
                detectedName = originalName.substringBeforeLast('.').trim().take(48)
                    .ifBlank { "图片" },
                extension = extension,
                width = bounds.outWidth,
                height = bounds.outHeight
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun confirm(
        pending: PendingReaderImage,
        customName: String,
        selectAsBackground: Boolean = false
    ): ReaderImageAsset = withContext(Dispatchers.IO) {
        val source = checkedPendingFile(pending)
        val displayName = customName.trim().take(48).ifBlank { pending.detectedName }
        val directory = imageDirectory().canonicalFile
        val id = UUID.randomUUID().toString()
        val destination = File(directory, "image-$id.${pending.extension}")
        try {
            if (!source.renameTo(destination)) source.copyTo(destination, overwrite = false)
            val asset = ReaderImageAsset(
                id = id,
                displayName = displayName,
                filePath = destination.absolutePath,
                originalFileName = pending.originalFileName,
                width = pending.width,
                height = pending.height,
                importedAt = System.currentTimeMillis()
            )
            settingsRepository.addReaderImage(asset, selectAsBackground)
            if (source.exists()) source.delete()
            asset
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    suspend fun importImage(
        uri: Uri,
        selectAsBackground: Boolean = false
    ): ReaderImageAsset {
        val pending = prepare(uri)
        return try {
            confirm(pending, pending.detectedName, selectAsBackground)
        } catch (error: Throwable) {
            discard(pending)
            throw error
        }
    }

    suspend fun rename(imageId: String, displayName: String) {
        settingsRepository.renameReaderImage(imageId, displayName)
    }

    /** 调用方须先确认没有背景或封面引用。 */
    suspend fun delete(image: ReaderImageAsset) = withContext(Dispatchers.IO) {
        settingsRepository.removeReaderImage(image.id)
        val file = File(image.filePath).canonicalFile
        val legacyRoot = File(context.filesDir, "reader-custom").canonicalFile
        val ownedLibraryFile = file.parentFile == imageDirectory().canonicalFile
        val ownedLegacyBackground = file.parentFile == legacyRoot &&
            file.name.startsWith("background-") && file.extension == "image"
        if (ownedLibraryFile || ownedLegacyBackground) file.delete()
    }

    suspend fun discard(pending: PendingReaderImage) = withContext(Dispatchers.IO) {
        runCatching { checkedPendingFile(pending).delete() }
    }

    private fun checkedPendingFile(pending: PendingReaderImage): File {
        val root = pendingDirectory().canonicalFile
        val file = File(pending.cachePath).canonicalFile
        require(file.isFile && file.parentFile == root) { "待导入图片已失效" }
        return file
    }

    private fun pendingDirectory(): File =
        File(context.cacheDir, "reader-image-import").apply { mkdirs() }

    private fun imageDirectory(): File =
        File(context.filesDir, IMAGE_LIBRARY_DIRECTORY).apply { mkdirs() }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }

    private fun extensionFor(fileName: String, mime: String?): String {
        val byName = fileName.substringAfterLast('.', "").lowercase()
        if (byName in SUPPORTED_EXTENSIONS) return byName
        return when (mime?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic", "image/heif" -> "heic"
            "image/avif" -> "avif"
            else -> "jpg"
        }
    }

    companion object {
        const val IMAGE_LIBRARY_DIRECTORY = "reader-images"
        private const val MAX_IMAGE_BYTES = 40L * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 20_000
        private const val MAX_IMAGE_PIXELS = 80_000_000L
        private val SUPPORTED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif"
        )
    }
}
