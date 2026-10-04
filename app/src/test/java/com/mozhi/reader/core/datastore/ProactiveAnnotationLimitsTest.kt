package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveAnnotationLimitsTest {
    @Test fun timingCodecDefaultsAndBoundsAreMigrationSafe() {
        val legacy = ProactiveAnnotationLimitsCodec.decodeGlobal("{}")
        assertEquals(ProactiveAnnotationTiming.AFTER_CHAPTER_COMPLETE, legacy.timing)
        assertEquals(0, legacy.aheadChapters)
        assertEquals(0, legacy.copy(aheadChapters = -5).normalized().aheadChapters)
        val value = legacy.copy(timing = ProactiveAnnotationTiming.ON_CHAPTER_ENTRY, aheadChapters = 50).normalized()
        assertEquals(5, value.aheadChapters)
        assertEquals(value, ProactiveAnnotationLimitsCodec.decodeGlobal(ProactiveAnnotationLimitsCodec.encodeGlobal(value)))
    }


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
    fun raisingTheLowerBoundPushesAFiniteUpperBound() {
        val limits = ProactiveAnnotationLimits(minPerChapter = 1, maxPerChapter = 3)
        assertEquals(5 to 5, limits.withMinPerChapter(5).let { it.minPerChapter to it.maxPerChapter })
        // 下限回落不动上限；「不限」上限保持不限。
        assertEquals(0 to 3, limits.withMinPerChapter(0).let { it.minPerChapter to it.maxPerChapter })
        val open = limits.copy(maxPerChapter = ProactiveAnnotationLimits.UNLIMITED).withMinPerChapter(12)
        assertEquals(12, open.minPerChapter)
        assertTrue(open.chapterUnlimited)
        assertEquals(ProactiveAnnotationLimits.MAX_PER_CHAPTER, limits.withMinPerChapter(10_000).maxPerChapter)
    }

    @Test
    fun typedValuesAboveTheOldSliderCeilingsSurvive() {
        val limits = ProactiveAnnotationLimits(maxPerChapter = 25, dailyMax = 300, dailyVoiceMax = 120).normalized()
        assertEquals(25, limits.maxPerChapter)
        assertEquals(300, limits.dailyMax)
        assertEquals(120, limits.dailyVoiceMax)
        assertEquals(ProactiveAnnotationLimits.MAX_DAILY, limits.copy(dailyMax = 1_000_000).normalized().dailyMax)
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

    @Test fun oldSettingsGainBalancedContextWithoutResettingQuotas() {
        val limits = ProactiveAnnotationLimitsCodec.decodeGlobal("""{"maxPerChapter":5,"dailyMax":30}""")
        assertEquals(5, limits.maxPerChapter)
        assertEquals(30, limits.dailyMax)
        assertEquals(16_000, limits.context.budgetChars)
    }

    @Test fun contextBudgetsRoundTripAndBookOverrideIsIndependent() {
        val global = ProactiveAnnotationLimits(context = ProactiveAnnotationContextSettings(AnnotationContextMode.FULL))
        val book = ProactiveAnnotationLimits(context = ProactiveAnnotationContextSettings(AnnotationContextMode.CUSTOM, 21_000))
        assertEquals(global, ProactiveAnnotationLimitsCodec.decodeGlobal(ProactiveAnnotationLimitsCodec.encodeGlobal(global)))
        val overrides = mapOf(7L to BookProactiveAnnotationLimits(true, book))
        val restored = ProactiveAnnotationLimitsCodec.decodeBooks(ProactiveAnnotationLimitsCodec.encodeBooks(overrides))
        assertEquals(21_000, resolveProactiveAnnotationLimits(global, restored[7L]).context.budgetChars)
        assertEquals(32_000, resolveProactiveAnnotationLimits(global, restored[7L]!!.copy(enabled = false)).context.budgetChars)
        assertEquals(4_000, book.copy(context = book.context.copy(customChars = -1)).normalized().context.budgetChars)
        assertEquals(64_000, book.copy(context = book.context.copy(customChars = Int.MAX_VALUE)).normalized().context.budgetChars)
    }
}
