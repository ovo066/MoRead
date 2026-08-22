package com.mozhi.reader.feature.bookdetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDescriptionExtractorTest {
    @Test
    fun `prefers explicit introduction chapter`() {
        val result = BookDescriptionExtractor.extract(
            listOf(
                "第一章" to "这是正文开篇，主人公走进了一座城。",
                "内容简介" to "一段真正的作品简介，说明故事背景与核心冲突。"
            )
        )
        assertEquals("一段真正的作品简介，说明故事背景与核心冲突。", result)
    }

    @Test
    fun `keeps all paragraphs from introduction chapter`() {
        val result = BookDescriptionExtractor.extract(
            listOf("内容简介" to "第一段介绍。\n\n第二段介绍。\n\n第三段介绍。")
        )
        assertEquals("第一段介绍。\n\n第二段介绍。\n\n第三段介绍。", result)
    }
    @Test
    fun `extracts inline introduction`() {
        val result = BookDescriptionExtractor.extract(
            listOf("前言" to "内容简介：这是一本用于测试自动简介提取的书。\n\n第一章")
        )
        assertTrue(result.startsWith("这是一本用于测试"))
    }
}