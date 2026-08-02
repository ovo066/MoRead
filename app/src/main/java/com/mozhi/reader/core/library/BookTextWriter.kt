package com.mozhi.reader.core.library

import java.io.BufferedOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A chapter's slice of a book's `text.mz`.
 *
 * [byteOffset]/[byteLength] index the UTF-8 file. [charCount] counts UTF-16 code units in the
 * decoded string. The two spaces are never interchangeable — every read seeks by bytes, every
 * reading position is expressed in characters.
 */
data class ChapterTextRange(
    val index: Int,
    val byteOffset: Long,
    val byteLength: Int,
    val charCount: Int
)

data class ChapterTextInput(val index: Int, val body: String)

/**
 * Writes every chapter body of a book into one UTF-8 blob and returns the byte ranges to store on
 * the chapter rows. Normalization happens here, once at import, so the layout engine never has to
 * defend against source quirks.
 */
@Singleton
class BookTextWriter @Inject constructor() {

    fun write(target: File, chapters: List<ChapterTextInput>): List<ChapterTextRange> {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        val ranges = ArrayList<ChapterTextRange>(chapters.size)
        try {
            BufferedOutputStream(temporary.outputStream()).use { stream ->
                var offset = 0L
                chapters.sortedBy(ChapterTextInput::index).forEach { chapter ->
                    val body = normalize(chapter.body)
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    stream.write(bytes)
                    ranges += ChapterTextRange(
                        index = chapter.index,
                        byteOffset = offset,
                        byteLength = bytes.size,
                        charCount = body.length
                    )
                    offset += bytes.size
                }
            }
            if (target.exists() && !target.delete()) error("无法覆盖已有文本文件")
            if (!temporary.renameTo(target)) error("无法写入书籍文本文件")
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return ranges
    }

    /**
     * Leading ideographic spaces are stripped because the engine applies its own paragraph indent;
     * keeping the source's would double it.
     */
    fun normalize(body: String): String {
        val unified = body.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
        val paragraphs = unified.split('\n').map { line ->
            line.trimStart(*LEADING_BLANKS).trimEnd()
        }
        val result = StringBuilder(unified.length)
        var pendingBlank = false
        var wroteAny = false
        paragraphs.forEach { paragraph ->
            if (paragraph.isEmpty()) {
                if (wroteAny) pendingBlank = true
                return@forEach
            }
            if (wroteAny) result.append('\n')
            if (pendingBlank) {
                result.append('\n')
                pendingBlank = false
            }
            result.append(paragraph)
            wroteAny = true
        }
        return result.toString()
    }

    private companion object {
        /** Space, no-break space, ideographic space. */
        val LEADING_BLANKS = charArrayOf(' ', ' ', '　')
    }
}
