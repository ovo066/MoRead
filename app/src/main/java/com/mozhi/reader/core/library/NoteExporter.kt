package com.mozhi.reader.core.library

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.mozhi.reader.core.database.entity.NoteEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读书笔记导出：拼 Markdown 文档，写到系统 Documents/墨知（MediaStore，无需权限）
 * 或经 FileProvider 分享给其他应用。
 */
@Singleton
class NoteExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 生成 Markdown 文本：书名为一级标题，剧情梗概在前，笔记按锚点章节排列。 */
    fun buildMarkdown(bookTitle: String, notes: List<NoteEntity>): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val (summaries, plainNotes) = notes.partition { it.kind == NoteRepository.KIND_PLOT_SUMMARY }
        return buildString {
            append("# ").append(bookTitle.ifBlank { "读书笔记" }).append("\n\n")
            append("> 由墨知 MoRead 导出 · ").append(formatter.format(Date())).append('\n')
            summaries.forEach { note ->
                append("\n## ").append(note.title.ifBlank { "剧情梗概" }).append("\n\n")
                append(note.contentMarkdown.trim()).append('\n')
            }
            if (plainNotes.isNotEmpty()) {
                append("\n## 笔记\n")
                plainNotes
                    .sortedWith(compareBy({ it.relatedChapterIndex ?: Int.MAX_VALUE }, { it.createdAt }))
                    .forEach { note ->
                        append("\n### ").append(note.title.ifBlank { "未命名笔记" })
                        note.relatedChapterIndex?.let { append("（第 ").append(it + 1).append(" 章）") }
                        append("\n\n")
                        append(note.contentMarkdown.trim()).append('\n')
                        append("\n*").append(formatter.format(Date(note.updatedAt))).append("*\n")
                    }
            }
        }
    }

    /** 写入 Documents/墨知/<书名>-笔记-<时间>.md，返回展示用路径；失败返回 null。 */
    suspend fun exportToDocuments(bookTitle: String, notes: List<NoteEntity>): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val markdown = buildMarkdown(bookTitle, notes)
                val fileName = exportFileName(bookTitle)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/markdown")
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOCUMENTS + "/墨知"
                        )
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values
                    ) ?: return@runCatching null
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(markdown.toByteArray(Charsets.UTF_8))
                    } ?: return@runCatching null
                    "Documents/墨知/$fileName"
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "墨知"
                    ).apply { mkdirs() }
                    val file = File(dir, fileName)
                    file.writeText(markdown, Charsets.UTF_8)
                    file.absolutePath
                }
            }.getOrNull()
        }

    /** 写到 cache 并返回可分享的 ACTION_SEND intent；失败返回 null。 */
    suspend fun buildShareIntent(bookTitle: String, notes: List<NoteEntity>): Intent? =
        withContext(Dispatchers.IO) {
            runCatching {
                val markdown = buildMarkdown(bookTitle, notes)
                val dir = File(context.cacheDir, "export").apply { mkdirs() }
                val file = File(dir, exportFileName(bookTitle))
                file.writeText(markdown, Charsets.UTF_8)
                val uri: Uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "《$bookTitle》读书笔记")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.getOrNull()
        }

    private fun exportFileName(bookTitle: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        val safeTitle = bookTitle.replace(Regex("[\\\\/:*?\"<>|]"), "").ifBlank { "读书笔记" }
        return "$safeTitle-笔记-$stamp.md"
    }
}
