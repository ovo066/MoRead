package com.mozhi.reader.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiProviderUrlTest {
    @Test
    fun `接受 https 与 http 并清理首尾空白和斜杠`() {
        assertEquals("https://api.example.com/v1", normalizeProviderBaseUrl("  https://api.example.com/v1/  "))
        assertEquals("http://192.168.1.8:11434/v1", normalizeProviderBaseUrl("http://192.168.1.8:11434/v1/"))
    }

    @Test
    fun `拒绝非 http 协议和无效地址`() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeProviderBaseUrl("ftp://example.com/api")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeProviderBaseUrl("example.com/api")
        }
    }
}
