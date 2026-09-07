package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveAnnotationLimitsTest {

    @Test
    fun normalizeKeepsLowerBoundUnderUpperBound() {
        val limits = ProactiveAnnotationLimits(minPerChapter = 8, maxPerChapter = 3).normalized()
        assertEquals(3, limits.minPerChapter)
        assertEquals(3, limits.maxPerChapter)
    }

    @Test
    fun normalizeAllowsOnlyUnlimitedAsNegative() {
        val unlimited = ProactiveAnnotationLimits(maxPerChapter = ProactiveAnnotationLimits.UNLIMITED)
            .normalized()
        assertTrue(unlimited.chapterUnlimited)
        // 别的负数是脏数据（降级安装、手改备份），夹回合法区间而不是当成不限制。
        val garbage = ProactiveAnnotationLimits(maxPerChapter = -7, dailyMax = -9).normalized()
        assertEquals(1, garbage.maxPerChapter)
        assertEquals(1, garbage.dailyMax)
    }

    @Test
    fun unlimitedChapterStillAllowsALowerBound() {
        val limits = ProactiveAnnotationLimits(
            minPerChapter = 4,
            maxPerChapter = ProactiveAnnotationLimits.UNLIMITED
        ).normalized()
        assertEquals(4, limits.minPerChapter)
        assertTrue(limits.chapterUnlimited)
    }

    @Test
    fun perBookOverrideWinsOnlyWhenEnabled() {
        val global = ProactiveAnnotationLimits(minPerChapter = 1, maxPerChapter = 2, dailyMax = 10)
        val bookLimits = ProactiveAnnotationLimits(minPerChapter = 3, maxPerChapter = 5, dailyMax = 20)
        assertEquals(global, resolveProactiveAnnotationLimits(global, null))
        assertEquals(
            global,
            resolveProactiveAnnotationLimits(global, BookProactiveAnnotationLimits(false, bookLimits))
        )
        assertEquals(
            bookLimits,
            resolveProactiveAnnotationLimits(global, BookProactiveAnnotationLimits(true, bookLimits))
        )
    }

    @Test
    fun codecRoundTripsAndSurvivesGarbage() {
        val limits = ProactiveAnnotationLimits(2, ProactiveAnnotationLimits.UNLIMITED, 20, 0, 5)
        assertEquals(limits, ProactiveAnnotationLimitsCodec.decodeGlobal(
            ProactiveAnnotationLimitsCodec.encodeGlobal(limits)
        ))
        assertEquals(ProactiveAnnotationLimits(), ProactiveAnnotationLimitsCodec.decodeGlobal("{oops"))
        assertEquals(ProactiveAnnotationLimits(), ProactiveAnnotationLimitsCodec.decodeGlobal(null))

        val books = mapOf(7L to BookProactiveAnnotationLimits(true, limits))
        assertEquals(books, ProactiveAnnotationLimitsCodec.decodeBooks(
            ProactiveAnnotationLimitsCodec.encodeBooks(books)
        ))
        // 无效书 id 不该留在表里，否则会挂着永远匹配不到的覆盖项。
        assertTrue(
            ProactiveAnnotationLimitsCodec.decodeBooks(
                ProactiveAnnotationLimitsCodec.encodeBooks(
                    mapOf(0L to BookProactiveAnnotationLimits(true, limits))
                )
            ).isEmpty()
        )
    }

    @Test
    fun sliderStepsPutUnlimitedLast() {
        val steps = ProactiveAnnotationLimitSteps.steps(from = 1, to = 3, allowUnlimited = true)
        assertEquals(listOf(1, 2, 3, ProactiveAnnotationLimits.UNLIMITED), steps)
        assertEquals(3, ProactiveAnnotationLimitSteps.indexOf(steps, ProactiveAnnotationLimits.UNLIMITED))
        assertEquals(1, ProactiveAnnotationLimitSteps.indexOf(steps, 2))
        assertEquals("不限制", ProactiveAnnotationLimitSteps.label(ProactiveAnnotationLimits.UNLIMITED))
        assertEquals("2 条", ProactiveAnnotationLimitSteps.label(2))
        assertEquals(
            ProactiveAnnotationLimits.UNLIMITED,
            ProactiveAnnotationLimitSteps.valueAt(steps, 99)
        )
    }

    @Test
    fun summaryCollapsesEqualBounds() {
        assertEquals("每章 2 条 · 每日 10 条", ProactiveAnnotationLimits(2, 2, 10).summary())
        assertEquals("每章 1–3 条 · 每日 10 条", ProactiveAnnotationLimits(1, 3, 10).summary())
        assertEquals(
            "每章 不限条数 · 每日 不限",
            ProactiveAnnotationLimits(
                minPerChapter = 1,
                maxPerChapter = ProactiveAnnotationLimits.UNLIMITED,
                dailyMax = ProactiveAnnotationLimits.UNLIMITED
            ).summary()
        )
    }
}
