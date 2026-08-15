package com.mozhi.reader.core.importer.lan

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** multipart 中一个分部的头部；只保留传书需要的字段。 */
data class MultipartPart(
    /** 表单字段名，如 `file`。 */
    val name: String?,
    /** 文件名；为 null 表示这是普通表单字段而非文件。 */
    val fileName: String?,
    val contentType: String?
)

/**
 * `multipart/form-data` 流式读取器。只做传书需要的事：逐个分部推进、把文件体直接写进
 * 落盘流，全程不把整个文件读进内存（一本 200MB 的 TXT 不能占 200MB 堆）。
 *
 * 用法固定为「[nextPart] → [writeBodyTo] / [skipBody]」交替；跳过 [writeBodyTo]
 * 直接再调 [nextPart] 会自动丢弃当前分部的内容。
 */
class MultipartReader(
    private val source: InputStream,
    boundary: String,
    bufferSize: Int = DEFAULT_BUFFER
) {
    /**
     * 分隔符带前导 CRLF：这样正文里出现的 `--boundary` 只有真的处在行首才会被当成边界。
     * 首个边界前没有 CRLF，靠 [seeded] 在缓冲区里预置一对虚拟 CRLF 抹平这个差异。
     */
    private val delimiter = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
    private val buffer = ByteArray(maxOf(bufferSize, delimiter.size * 4))
    private var head = 0
    private var tail = 0
    private var seeded = false
    private var finished = false
    private var bodyPending = false

    /** 推进到下一个分部；返回 null 表示已到结束边界。 */
    @Throws(IOException::class)
    fun nextPart(): MultipartPart? {
        if (finished) return null
        if (bodyPending) skipBody()
        if (!seeded) {
            // 预置 CRLF，令首个 `--boundary` 与后续边界走同一条匹配路径。
            System.arraycopy(CRLF, 0, buffer, 0, CRLF.size)
            tail = CRLF.size
            seeded = true
            if (!seekDelimiter()) {
                finished = true
                return null
            }
        }
        // 边界之后要么是 `--`（结束）要么是 CRLF（还有分部）。
        if (!ensureBuffered(2)) {
            finished = true
            return null
        }
        if (buffer[head] == '-'.code.toByte() && buffer[head + 1] == '-'.code.toByte()) {
            finished = true
            return null
        }
        // 边界行尾允许有空白，逐字节跳到换行为止。
        while (true) {
            if (!ensureBuffered(1)) {
                finished = true
                return null
            }
            val value = buffer[head++]
            if (value == '\n'.code.toByte()) break
        }
        bodyPending = true
        return parseHeaders(readPartHeaders())
    }

    /** 把当前分部的内容写进 [sink]，返回字节数。 */
    @Throws(IOException::class)
    fun writeBodyTo(sink: OutputStream): Long = consumeBody(sink)

    @Throws(IOException::class)
    fun skipBody() {
        consumeBody(null)
    }

    private fun consumeBody(sink: OutputStream?): Long {
        if (!bodyPending) return 0L
        var written = 0L
        while (true) {
            val found = indexOfDelimiter()
            if (found >= 0) {
                val length = found - head
                sink?.write(buffer, head, length)
                written += length
                head = found + delimiter.size
                bodyPending = false
                return written
            }
            // 没命中时，尾部 delimiter.size-1 字节可能是被截断的边界，必须留到下一轮再判。
            val safe = tail - head - (delimiter.size - 1)
            if (safe > 0) {
                sink?.write(buffer, head, safe)
                written += safe
                head += safe
            }
            if (!fill()) throw IOException("上传数据在结束边界之前中断")
        }
    }

    /** 丢弃直到（含）下一个分隔符；用于跳过 multipart 前导说明文本。 */
    private fun seekDelimiter(): Boolean {
        while (true) {
            val found = indexOfDelimiter()
            if (found >= 0) {
                head = found + delimiter.size
                return true
            }
            val safe = tail - head - (delimiter.size - 1)
            if (safe > 0) head += safe
            if (!fill()) return false
        }
    }

    private fun readPartHeaders(): String {
        val raw = ByteArrayOutputStream(256)
        var matched = 0
        while (true) {
            if (!ensureBuffered(1)) return raw.toString(Charsets.UTF_8.name())
            val value = buffer[head++]
            raw.write(value.toInt())
            if (raw.size() > MAX_PART_HEAD_BYTES) {
                throw IOException("上传分部头部过长")
            }
            matched = when {
                value == HEADER_END[matched] -> matched + 1
                value == HEADER_END[0] -> 1
                else -> 0
            }
            if (matched == HEADER_END.size) break
        }
        // 分部头部按 UTF-8 解，中文文件名才不会变成乱码。
        return raw.toString(Charsets.UTF_8.name())
    }

    private fun indexOfDelimiter(): Int {
        val limit = tail - delimiter.size
        var index = head
        outer@ while (index <= limit) {
            for (offset in delimiter.indices) {
                if (buffer[index + offset] != delimiter[offset]) {
                    index++
                    continue@outer
                }
            }
            return index
        }
        return -1
    }

    /** 保证缓冲区里至少有 [count] 字节可读；到流尾返回 false。 */
    private fun ensureBuffered(count: Int): Boolean {
        while (tail - head < count) {
            if (!fill()) return false
        }
        return true
    }

    private fun fill(): Boolean {
        if (head > 0) {
            System.arraycopy(buffer, head, buffer, 0, tail - head)
            tail -= head
            head = 0
        }
        if (tail == buffer.size) throw IOException("上传分部边界异常")
        val read = source.read(buffer, tail, buffer.size - tail)
        if (read <= 0) return false
        tail += read
        return true
    }

    private companion object {
        const val DEFAULT_BUFFER = 64 * 1024
        const val MAX_PART_HEAD_BYTES = 8 * 1024
        val CRLF = "\r\n".toByteArray(Charsets.ISO_8859_1)
        val HEADER_END = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        fun parseHeaders(raw: String): MultipartPart {
            var name: String? = null
            var fileName: String? = null
            var contentType: String? = null
            raw.split("\r\n", "\n").forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                when (key) {
                    "content-disposition" -> {
                        name = value.parameter("name")
                        // filename* 是 RFC 5987 编码形式，优先级高于裸 filename。
                        fileName = value.parameter("filename*")?.decodeExtendedValue()
                            ?: value.parameter("filename")
                    }
                    "content-type" -> contentType = value
                }
            }
            return MultipartPart(name, fileName, contentType)
        }

        /** 取 `; key="value"` 或 `; key=value` 形式的参数。 */
        fun String.parameter(key: String): String? {
            split(';').drop(1).forEach { segment ->
                val trimmed = segment.trim()
                if (!trimmed.startsWith("$key=", ignoreCase = true)) return@forEach
                return trimmed.substringAfter('=').trim().trim('"').takeIf(String::isNotEmpty)
            }
            return null
        }

        /** `UTF-8''%E4%B8%AD%E6%96%87` → `中文`；格式不符时原样返回。 */
        fun String.decodeExtendedValue(): String {
            val parts = split('\'')
            if (parts.size < 3) return this
            val charset = parts[0].ifBlank { Charsets.UTF_8.name() }
            val encoded = parts.drop(2).joinToString("'")
            return runCatching { URLDecoder.decode(encoded, charset) }.getOrDefault(encoded)
        }
    }
}
