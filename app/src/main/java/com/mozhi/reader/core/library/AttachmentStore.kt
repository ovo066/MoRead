package com.mozhi.reader.core.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 一条消息附件的清单项；path 是相对 filesDir 的路径，随消息 attachmentsJson 持久化。 */
@Serializable
data class MessageAttachment(
    /** image | text_file */
    val type: String,
    val path: String,
    val mime: String,
    /** 展示名（文本文件必有；图片可空）。 */
    val name: String? = null
) {
    companion object {
        const val TYPE_IMAGE = "image"
        const val TYPE_TEXT_FILE = "text_file"

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(attachments: List<MessageAttachment>): String? =
            attachments.takeIf { it.isNotEmpty() }
                ?.let { json.encodeToString(ListSerializer(serializer()), it) }

        fun decode(raw: String?): List<MessageAttachment> =
            raw?.let {
                runCatching { json.decodeFromString(ListSerializer(serializer()), it) }.getOrNull()
            }.orEmpty()
    }
}

/**
 * 消息附件落盘：filesDir/attachments/<conversationId>/。图片压到长边 ≤1568、JPEG q85 控 token；
 * 文本文件原样保存（≤200KB）。DB 只存相对路径，删会话时 best-effort 清目录。
 */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun saveImage(conversationId: Long, uri: Uri): MessageAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
                if (longEdge <= 0) return@runCatching null
                var sample = 1
                while (longEdge / (sample * 2) >= MAX_LONG_EDGE_PX) sample *= 2
                val decoded = BitmapFactory.decodeByteArray(
                    source, 0, source.size,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                ) ?: return@runCatching null
                val scaled = decoded.scaleToLongEdge(MAX_LONG_EDGE_PX)
                val file = newFile(conversationId, "img", "jpg")
                file.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (scaled !== decoded) decoded.recycle()
                MessageAttachment(
                    type = MessageAttachment.TYPE_IMAGE,
                    path = file.toRelativeString(context.filesDir),
                    mime = "image/jpeg"
                )
            }.getOrNull()
        }

    suspend fun saveTextFile(conversationId: Long, uri: Uri, displayName: String): MessageAttachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: return@runCatching null
                if (bytes.size > MAX_TEXT_FILE_BYTES) return@runCatching null
                val file = newFile(conversationId, "file", "txt")
                file.writeBytes(bytes)
                MessageAttachment(
                    type = MessageAttachment.TYPE_TEXT_FILE,
                    path = file.toRelativeString(context.filesDir),
                    mime = "text/plain",
                    name = displayName.ifBlank { file.name }
                )
            }.getOrNull()
        }

    suspend fun loadImageBase64(attachment: MessageAttachment): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                Base64.encodeToString(resolve(attachment).readBytes(), Base64.NO_WRAP)
            }.getOrNull()
        }

    suspend fun loadTextContent(attachment: MessageAttachment): String? =
        withContext(Dispatchers.IO) {
            runCatching { resolve(attachment).readText() }.getOrNull()
        }

    fun resolve(attachment: MessageAttachment): File = File(context.filesDir, attachment.path)

    suspend fun deleteFor(conversationId: Long) {
        withContext(Dispatchers.IO) {
            runCatching { conversationDir(conversationId).deleteRecursively() }
        }
    }

    private fun conversationDir(conversationId: Long): File =
        File(File(context.filesDir, ROOT_DIR), conversationId.toString())

    private fun newFile(conversationId: Long, prefix: String, ext: String): File {
        val dir = conversationDir(conversationId).apply { mkdirs() }
        var index = 0
        while (true) {
            val candidate = File(dir, "${prefix}_${System.currentTimeMillis()}_$index.$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun Bitmap.scaleToLongEdge(maxEdge: Int): Bitmap {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdge) return this
        val ratio = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            this,
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private companion object {
        const val ROOT_DIR = "attachments"
        const val MAX_LONG_EDGE_PX = 1568
        const val JPEG_QUALITY = 85
        const val MAX_TEXT_FILE_BYTES = 200 * 1024
    }
}
