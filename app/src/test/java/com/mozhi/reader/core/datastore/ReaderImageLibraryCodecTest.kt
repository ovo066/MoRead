package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderImageLibraryCodecTest {
    @Test
    fun `多图片编码解码往返一致`() {
        val images = listOf(
            ReaderImageAsset("a", "山水", "/images/a.jpg", "a.jpg", 1200, 1800, 1L),
            ReaderImageAsset("b", "纸纹", "/images/b.png", "b.png", 1440, 2560, 2L)
        )

        assertEquals(images, ReaderImageLibraryCodec.decode(ReaderImageLibraryCodec.encode(images)))
    }

    @Test
    fun `旧阅读背景只迁移一次且使用稳定编号`() {
        val migrated = ReaderImageLibraryCodec.includeLegacyBackground(emptyList(), "/legacy/paper.jpg")
        val repeated = ReaderImageLibraryCodec.includeLegacyBackground(migrated, "/legacy/paper.jpg")

        assertEquals(1, repeated.size)
        assertEquals(ReaderImageLibraryCodec.legacyId("/legacy/paper.jpg"), repeated.single().id)
        assertEquals("原有阅读背景", repeated.single().displayName)
    }
}
