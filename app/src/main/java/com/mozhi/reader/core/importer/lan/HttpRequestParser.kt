package com.mozhi.reader.core.importer.lan

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/**
 * 一次请求的头部（不含消息体）。[headers] 的键一律小写，方便大小写不敏感查找。
 */
data class HttpRequestHead(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap()
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** 缺失或非法一律 -1：调用方据此拒绝没有长度声明的上传。 */
    val contentLength: Long get() = header("content-length")?.trim()?.toLongOrNull() ?: -1L

    val contentType: String? get() = header("content-type")

    /** `multipart/form-data; boundary=xxx` 里的 boundary；不是 multipart 返回 null。 */
    val multipartBoundary: String?
        get() {
            val type = contentType ?: return null
            if (!type.startsWith("multipart/", ignoreCase = true)) return null
            return type.split(';')
                .asSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.trim('"')
                ?.takeIf(String::isNotEmpty)
        }
}

/**
 * 局域网传书用的最小 HTTP/1.1 请求解析。只需要覆盖自家页面与 curl 会发出的请求，
 * 因此不实现分块传输、管线化与续传——遇到就按 400 拒绝，不做半吊子支持。
 */
object HttpRequestParser {

    /** 请求头上限：正常请求 1KB 都用不到，超过即视为攻击或垃圾流量。 */
    const val MAX_HEAD_BYTES = 32 * 1024

    /**
     * 从流中读到首个 CRLFCRLF 为止并解析。返回 null 表示连接已关闭或头部非法。
     * 只消费头部，消息体留在流里交给调用方按 Content-Length 读取。
     */
    @Throws(IOException::class)
    fun readHead(input: InputStream, maxHeadBytes: Int = MAX_HEAD_BYTES): HttpRequestHead? {
        val raw = ByteArrayOutputStream(1024)
        var matched = 0
        while (true) {
            val value = input.read()
            if (value < 0) return null
            raw.write(value)
            if (raw.size() > maxHeadBytes) return null
            // 逐字节匹配 CRLFCRLF；HTTP 头部本身是 ASCII，不必担心多字节字符被切断。
            matched = when {
                value == CRLF_CRLF[matched].code -> matched + 1
                value == CRLF_CRLF[0].code -> 1
                else -> 0
            }
            if (matched == CRLF_CRLF.length) break
        }
        return parseHead(raw.toString(Charsets.ISO_8859_1.name()))
    }

    /** 纯解析，便于单测：[raw] 是含结尾空行的完整头部文本。 */
    fun parseHead(raw: String): HttpRequestHead? {
        val lines = raw.split("\r\n", "\n").takeWhile(String::isNotEmpty)
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(' ').filter(String::isNotEmpty)
        if (requestLine.size < 2) return null
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        if (!target.startsWith("/")) return null

        val headers = HashMap<String, String>(lines.size)
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        val path = target.substringBefore('?')
        return HttpRequestHead(
            method = method,
            path = decodeComponent(path),
            query = parseQuery(target.substringAfter('?', "")),
            headers = headers
        )
    }

    fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&')
            .asSequence()
            .filter(String::isNotBlank)
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decodeComponent(key) to decodeComponent(value)
            }
    }

    /** 百分号编码解码；非法编码原样返回，不因为一个坏字符丢掉整条请求。 */
    fun decodeComponent(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    private const val CRLF_CRLF = "\r\n\r\n"
}
