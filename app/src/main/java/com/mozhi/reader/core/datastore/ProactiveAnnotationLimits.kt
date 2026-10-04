package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 随读段评的数量与频控。原先这些数字硬编码在 [ProactiveAnnotationQuota] 里
 * （每章 ≤2 条、每日 ≤10 条、语音/插图各 ≤3 条），用户既看不见也改不了。
 *
 * 上限一律支持 [UNLIMITED]（沿用仓库的负数哨兵约定，如 `ReaderSettings.screenBrightness` 的 -1）；
 * 下限只是写进提示词的请求——找不到值得回应的原文时模型仍然可以少给，强求只会逼出凑数的批注。
 */
@Serializable
enum class ProactiveAnnotationTiming { AFTER_CHAPTER_COMPLETE, ON_CHAPTER_ENTRY }

@Serializable
data class ProactiveAnnotationLimits(
    val minPerChapter: Int = 1,
    val maxPerChapter: Int = 2,
    val dailyMax: Int = 10,
    val dailyVoiceMax: Int = 3,
    val dailyImageMax: Int = 3,
    val timing: ProactiveAnnotationTiming = ProactiveAnnotationTiming.AFTER_CHAPTER_COMPLETE,
    val aheadChapters: Int = 0,
    val context: ProactiveAnnotationContextSettings = ProactiveAnnotationContextSettings()
) {
    val chapterUnlimited: Boolean get() = maxPerChapter == UNLIMITED
    val dailyUnlimited: Boolean get() = dailyMax == UNLIMITED

    /** 落库/读回都过一遍：负值只允许 [UNLIMITED]，下限不得超过上限。 */
    fun normalized(): ProactiveAnnotationLimits {
        val max = if (maxPerChapter == UNLIMITED) UNLIMITED else maxPerChapter.coerceIn(1, MAX_PER_CHAPTER)
        val ceiling = if (max == UNLIMITED) MAX_PER_CHAPTER else max
        return ProactiveAnnotationLimits(
            minPerChapter = minPerChapter.coerceIn(0, ceiling),
            maxPerChapter = max,
            dailyMax = if (dailyMax == UNLIMITED) UNLIMITED else dailyMax.coerceIn(1, MAX_DAILY),
            dailyVoiceMax = if (dailyVoiceMax == UNLIMITED) UNLIMITED else dailyVoiceMax.coerceIn(0, MAX_DAILY),
            dailyImageMax = if (dailyImageMax == UNLIMITED) UNLIMITED else dailyImageMax.coerceIn(0, MAX_DAILY),
            timing = timing,
            aheadChapters = aheadChapters.coerceIn(0, MAX_AHEAD_CHAPTERS),
            context = context.normalized()
        )
    }

    /**
     * 调下限时把有限的上限一并推高：用户明说「每章至少 5 条」，不该被旧上限 3 悄悄压回去。
     * 反方向（调低上限）交给 [normalized] 把下限压下来。
     */
    fun withMinPerChapter(value: Int): ProactiveAnnotationLimits {
        val min = value.coerceIn(0, MAX_PER_CHAPTER)
        val max = if (chapterUnlimited || maxPerChapter >= min) maxPerChapter else min
        return copy(minPerChapter = min, maxPerChapter = max).normalized()
    }

    fun timingSummary(): String = when (timing) {
        ProactiveAnnotationTiming.AFTER_CHAPTER_COMPLETE -> "读完一章后生成"
        ProactiveAnnotationTiming.ON_CHAPTER_ENTRY -> "进入章节时预生成（本章+$aheadChapters 章）"
    }

    /** 设置页与开关副标题共用的一行摘要。 */
    fun summary(): String = buildString {
        append("每章 ")
        when {
            chapterUnlimited -> append("不限条数")
            minPerChapter >= maxPerChapter -> append("${maxPerChapter} 条")
            else -> append("$minPerChapter–$maxPerChapter 条")
        }
        append(" · 每日 ")
        if (dailyUnlimited) append("不限") else append("$dailyMax 条")
    }

    companion object {
        const val UNLIMITED = -1
        // 设置页改为直接填数字后放宽：旧上界 10/50 是滑条格数的妥协，不是成本上的硬约束。
        const val MAX_PER_CHAPTER = 99
        const val MAX_DAILY = 999
        const val MAX_AHEAD_CHAPTERS = 5
    }
}

/** 某一本书的段评额度覆盖。[enabled] 关＝跟随全局，和 [BookReaderTheme] 同一套语义。 */
@Serializable
data class BookProactiveAnnotationLimits(
    val enabled: Boolean = false,
    val limits: ProactiveAnnotationLimits = ProactiveAnnotationLimits()
)

/** 本书单独设置优先，否则跟随全局。 */
fun resolveProactiveAnnotationLimits(
    global: ProactiveAnnotationLimits,
    perBook: BookProactiveAnnotationLimits?
): ProactiveAnnotationLimits =
    (perBook?.takeIf(BookProactiveAnnotationLimits::enabled)?.limits ?: global).normalized()

object ProactiveAnnotationLimitsCodec {
    private val json = Json { ignoreUnknownKeys = true }


    fun encodeGlobal(limits: ProactiveAnnotationLimits): String =
        json.encodeToString(limits.normalized())

    fun decodeGlobal(raw: String?): ProactiveAnnotationLimits {
        if (raw.isNullOrBlank()) return ProactiveAnnotationLimits()
        return runCatching { json.decodeFromString<ProactiveAnnotationLimits>(raw) }
            .getOrDefault(ProactiveAnnotationLimits())
            .normalized()
    }

    fun encodeBooks(values: Map<Long, BookProactiveAnnotationLimits>): String =
        json.encodeToString(values.filterKeys { it > 0L })

    fun decodeBooks(raw: String?): Map<Long, BookProactiveAnnotationLimits> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<Long, BookProactiveAnnotationLimits>>(raw)
        }
            .getOrDefault(emptyMap())
            .filterKeys { it > 0L }
            .mapValues { (_, value) -> value.copy(limits = value.limits.normalized()) }
    }
}
