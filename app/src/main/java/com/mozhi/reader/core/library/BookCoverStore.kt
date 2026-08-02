package com.mozhi.reader.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 把用户从相册选的图片压缩后存进应用私有目录，作为书籍封面。
 *
 * 压缩策略与导入器抽取 EPUB 封面时一致（有 alpha 走 PNG，否则 JPEG q90），
 * 长边限制在 1800px —— 再大对 128dp 的封面位没有意义，只占空间。
 */
@Singleton
class BookCoverStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * @return 落盘后的封面文件；读不出图片则抛异常交由调用方兜底。
     *
     * 文件名带随机后缀而不是固定用 bookId：Coil 按 File 缓存，同名覆盖后
     * 界面上会继续显示旧图。
     */
    fun save(bookId: Long, source: Uri): File {
        val bitmap = decodeScaled(source) ?: error("无法解码所选图片")
        val usesTransparency = bitmap.hasAlpha()
        val output = File(
            coversDirectory(),
            "book-$bookId-${UUID.randomUUID()}.${if (usesTransparency) "png" else "jpg"}"
        )
        return try {
            val compressed = output.outputStream().buffered().use { stream ->
                bitmap.compress(
                    if (usesTransparency) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                    COVER_JPEG_QUALITY,
                    stream
                )
            }
            if (compressed && output.isFile && output.length() > 0L) {
                output
            } else {
                output.delete()
                error("封面写入失败")
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** 先读边界再按 inSampleSize 解码，避免为一张大图分配整幅位图。 */
    private fun decodeScaled(source: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longEdge = max(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (longEdge / sampleSize > MAX_COVER_LONG_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun coversDirectory(): File = File(context.filesDir, "covers").apply { mkdirs() }

    private companion object {
        const val MAX_COVER_LONG_EDGE = 1_800
        const val COVER_JPEG_QUALITY = 90
    }
}
