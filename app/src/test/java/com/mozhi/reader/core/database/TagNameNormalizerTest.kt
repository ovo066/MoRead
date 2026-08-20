package com.mozhi.reader.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagNameNormalizerTest {
    @Test
    fun `名称去首尾与连续空白`() {
        assertEquals("仙侠 世界", TagNameNormalizer.normalize("  仙侠   世界  "))
    }

    @Test
    fun `中英文逗号都能批量拆分`() {
        assertEquals(listOf("玄幻", "修仙", "待重读"), TagNameNormalizer.split("玄幻，修仙, 待重读"))
    }

    @Test
    fun `重名判定忽略大小写与多余空白`() {
        assertTrue(TagNameNormalizer.isSame(" Sci Fi ", "sci  fi"))
    }
}
