package com.mozhi.reader.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SpeechCacheSyncNamingTest {

    @Test
    fun remoteNameCarriesTheBookId() {
        val file = File(File("/data/speech-cache/42"), "abc123.mp3")

        assertEquals("42__abc123.mp3", SpeechCacheSync.remoteNameOf(file))
    }

    @Test
    fun remoteNameFallsBackToZeroForLooseFiles() {
        val file = File(File("/data/speech-cache"), "abc123.mp3")

        assertEquals("0__abc123.mp3", SpeechCacheSync.remoteNameOf(file))
    }

    @Test
    fun remoteNameRoundTrips() {
        val original = File(File("/data/speech-cache/7"), "deadbeef.wav")

        val remote = SpeechCacheSync.remoteNameOf(original)

        assertEquals(7L, SpeechCacheSync.bookIdOf(remote))
        assertEquals("deadbeef.wav", SpeechCacheSync.fileNameOf(remote))
    }

    /** 别人上传的、命名不合约定的文件不能把同步整段搞崩。 */
    @Test
    fun unexpectedRemoteNamesDegradeGracefully() {
        assertEquals(0L, SpeechCacheSync.bookIdOf("random.mp3"))
        assertEquals("random.mp3", SpeechCacheSync.fileNameOf("random.mp3"))
        assertEquals(0L, SpeechCacheSync.bookIdOf("__x.mp3"))
    }

    @Test
    fun cacheStatsReportUsageAgainstBudget() {
        val stats = SpeechCacheStats(
            fileCount = 3,
            totalBytes = 150L * 1024 * 1024,
            budgetBytes = 300L * 1024 * 1024
        )

        assertEquals(0.5f, stats.usedFraction, 0.001f)
    }

    @Test
    fun usageFractionNeverLeavesZeroToOne() {
        val over = SpeechCacheStats(totalBytes = 900, budgetBytes = 300)
        val noBudget = SpeechCacheStats(totalBytes = 900, budgetBytes = 0)

        assertEquals(1f, over.usedFraction, 0.001f)
        assertEquals(0f, noBudget.usedFraction, 0.001f)
    }

    @Test
    fun syncResultSummaryMentionsSkippedOnlyWhenItHappened() {
        assertEquals("上传 2 个，下载 1 个", SpeechCacheSyncResult(2, 1, 0).summary)
        assertTrue(SpeechCacheSyncResult(0, 0, 3).summary.contains("跳过 3 个"))
    }
}
