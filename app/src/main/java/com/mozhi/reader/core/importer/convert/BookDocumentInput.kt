package com.mozhi.reader.core.importer.convert

import com.mozhi.reader.feature.importer.TextEncodingDetector
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import org.jsoup.Jsoup

object BookDocumentInput {
    const val MAX_SOURCE_BYTES = 32 * 1024 * 1024
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun checkSize(size: Long) {
        if (size > MAX_SOURCE_BYTES) throw BookConversionException(BookConversionException.Reason.SOURCE_TOO_LARGE)
    }

    /** 同时约束提供方上报大小和实际读取大小，不能依赖 SAF 的 SIZE 列。 */
    fun bounded(input: InputStream, maxBytes: Int = MAX_SOURCE_BYTES,
        reason: BookConversionException.Reason = BookConversionException.Reason.SOURCE_TOO_LARGE): InputStream = object : FilterInputStream(input) {
        private var count = 0L
        private fun checkLimit() { if (count > maxBytes) throw BookConversionException(reason) }
        override fun read(): Int = `in`.read().also { if (it >= 0) { count++; checkLimit() } }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, minOf(length.toLong(), maxBytes + 1L - count).toInt()).also {
                if (it > 0) { count += it; checkLimit() }
            }
    }

    fun read(source: File): ByteArray {
        checkSize(source.length())
        return bounded(source.inputStream()).use { it.readBytes() }
    }

    fun decode(bytes: ByteArray, html: Boolean): String {
        checkSize(bytes.size.toLong())
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size &&
            prefix.indices.all { (bytes[it].toInt() and 0xff) == prefix[it] }
        val bom = when {
            starts(0x00, 0x00, 0xfe, 0xff) -> "UTF-32BE"
            starts(0xff, 0xfe, 0x00, 0x00) -> "UTF-32LE"
            starts(0xef, 0xbb, 0xbf) -> "UTF-8"
            starts(0xff, 0xfe) -> "UTF-16LE"
            starts(0xfe, 0xff) -> "UTF-16BE"
            else -> null
        }
        // 编码声明本身是 ASCII；先读头部而不把 GBK 字节误当成 UTF-8。
        val declared = if (html && bom == null) {
            val head = Jsoup.parse(bytes.copyOfRange(0, minOf(bytes.size, 64 * 1024)).toString(Charsets.ISO_8859_1))
            head.select("meta").firstNotNullOfOrNull { meta ->
                meta.attr("charset").takeIf(String::isNotBlank)
                    ?: if (meta.attr("http-equiv").equals("content-type", true)) {
                        Regex("charset\\s*=\\s*['\"]?([^\\s;'\"]+)", RegexOption.IGNORE_CASE)
                            .find(meta.attr("content"))?.groupValues?.get(1)
                    } else null
            }
        } else null
        val charset = (bom ?: declared)?.let { runCatching { Charset.forName(it.trim()) }.getOrNull() }
        return (charset?.let { bytes.toString(it) } ?: TextEncodingDetector().decode(bytes).text)
            .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    }
}
