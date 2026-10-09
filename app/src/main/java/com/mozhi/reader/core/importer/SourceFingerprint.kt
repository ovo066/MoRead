package com.mozhi.reader.core.importer

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** 导入源文件的内容指纹：判断「这本书是不是已经在书架上」只看字节，不看文件名。 */
data class SourceFingerprint(val sha256: String, val size: Long)

object SourceFingerprints {
    fun of(bytes: ByteArray): SourceFingerprint =
        SourceFingerprint(hex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes.size.toLong())

    fun of(file: File): SourceFingerprint = file.inputStream().use { input -> copy(input, null) }

    /** 边复制边计算，大文件不需要读两遍；[output] 为 null 时只计算。 */
    fun copy(input: InputStream, output: OutputStream?): SourceFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
            size += read
        }
        return SourceFingerprint(hex(digest.digest()), size)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

/**
 * 内容完全相同的书已经在库里（含只保留个人记录、正文已移除的书）。
 * 批量导入据此跳过；单本导入转成 [PreparedImport.Duplicate] 让用户选择。
 */
class DuplicateBookException(val bookId: Long, val title: String, val removed: Boolean) :
    IllegalStateException("已在书架上：《$title》")

/** 书名规范化：去空白、全角括号与常见后缀，用于旧书（没有指纹）的弱匹配。 */
object BookTitles {
    private val noise = Regex("""[\s　]+|[（(【\[].*?[)）】\]]|\.(txt|epub)$""", RegexOption.IGNORE_CASE)
    fun normalize(title: String): String = title.replace(noise, "").lowercase()
}
