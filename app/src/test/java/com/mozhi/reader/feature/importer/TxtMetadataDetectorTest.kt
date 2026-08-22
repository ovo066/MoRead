package com.mozhi.reader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class TxtMetadataDetectorTest {
    @Test
    fun `detects common filename author patterns`() {
        assertEquals(TxtMetadata("书名", "作者名"), TxtMetadataDetector.detect("《书名》作者名.txt", ""))
        assertEquals(TxtMetadata("书名", "作者名"), TxtMetadataDetector.detect("《书名》作者：作者名.txt", ""))
        assertEquals(TxtMetadata("书名", "作者名"), TxtMetadataDetector.detect("书名 by 作者名.txt", ""))
        assertEquals(TxtMetadata("书名", "作者名"), TxtMetadataDetector.detect("[作者名]书名.txt", ""))
    }

    @Test
    fun `opening metadata overrides filename and ignores edition tags`() {
        val result = TxtMetadataDetector.detect(
            displayName = "[精校]旧书名.txt",
            text = "书名：新书名\n作者：新作者\n\n正文"
        )
        assertEquals("新书名", result.title)
        assertEquals("新作者", result.author)
    }
}
