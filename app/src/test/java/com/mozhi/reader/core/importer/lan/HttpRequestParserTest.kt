package com.mozhi.reader.core.importer.lan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpRequestParserTest {

    @Test
    fun parsesMethodPathAndHeaders() {
        val head = HttpRequestParser.parseHead(
            "POST /upload HTTP/1.1\r\n" +
                "Host: 192.168.1.7:1122\r\n" +
                "Content-Length: 42\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n"
        )!!

        assertEquals("POST", head.method)
        assertEquals("/upload", head.path)
        assertEquals(42L, head.contentLength)
        assertEquals("application/octet-stream", head.contentType)
        // 头名大小写不敏感。
        assertEquals("192.168.1.7:1122", head.header("HOST"))
    }

    @Test
    fun decodesPercentEncodedPathAndQuery() {
        val head = HttpRequestParser.parseHead(
            "GET /%E4%B8%AD%E6%96%87?name=%E4%B8%89%E4%BD%93.txt&size=120 HTTP/1.1\r\n\r\n"
        )!!

        assertEquals("/中文", head.path)
        assertEquals("三体.txt", head.query["name"])
        assertEquals("120", head.query["size"])
    }

    @Test
    fun rejectsMalformedRequestLines() {
        assertNull(HttpRequestParser.parseHead(""))
        assertNull(HttpRequestParser.parseHead("GET\r\n\r\n"))
        // 绝对 URI 与畸形目标一律拒绝，服务只服务自家页面。
        assertNull(HttpRequestParser.parseHead("GET http://evil/ HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun missingContentLengthReportsNegative() {
        val head = HttpRequestParser.parseHead("POST /upload HTTP/1.1\r\n\r\n")!!
        assertEquals(-1L, head.contentLength)
    }

    @Test
    fun extractsMultipartBoundary() {
        val head = HttpRequestParser.parseHead(
            "POST /upload HTTP/1.1\r\n" +
                "Content-Type: multipart/form-data; boundary=\"----ABC123\"\r\n\r\n"
        )!!
        assertEquals("----ABC123", head.multipartBoundary)

        val plain = HttpRequestParser.parseHead(
            "POST /upload HTTP/1.1\r\nContent-Type: text/plain\r\n\r\n"
        )!!
        assertNull(plain.multipartBoundary)
    }

    @Test
    fun readHeadStopsAtBlankLineAndLeavesBodyInStream() {
        val payload = "POST /upload HTTP/1.1\r\nContent-Length: 5\r\n\r\nHELLO"
        val stream = ByteArrayInputStream(payload.toByteArray())

        val head = HttpRequestParser.readHead(stream)!!

        assertEquals("/upload", head.path)
        // 消息体必须原封不动留给调用方按 Content-Length 读取。
        val body = ByteArrayOutputStream()
        stream.copyTo(body)
        assertArrayEquals("HELLO".toByteArray(), body.toByteArray())
    }

    @Test
    fun readHeadRejectsOversizedHeaders() {
        val flood = "GET / HTTP/1.1\r\n" + "X-Pad: ${"a".repeat(2000)}\r\n".repeat(50) + "\r\n"
        assertNull(HttpRequestParser.readHead(ByteArrayInputStream(flood.toByteArray()), 4096))
    }

    @Test
    fun readHeadReturnsNullOnClosedConnection() {
        assertNull(HttpRequestParser.readHead(ByteArrayInputStream(ByteArray(0))))
        assertTrue(true)
    }
}
