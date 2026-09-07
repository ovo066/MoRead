package com.mozhi.reader.core.library

import com.mozhi.reader.core.database.entity.BookEntity
import kotlin.math.roundToInt

/**
 * 一本书「按字符」算进度所需的两个数：当前章之前累计的字符数与全书字符数。
 *
 * 数值取自 `chapters.charCount`（UTF-16 码元），与阅读页 `ReaderContentController.progressAt()`
 * 用的是同一批数字，所以书架、详情页与页脚三处显示能逐字对齐。
 */
data class BookReadSpan(
    val charsBeforeChapter: Long,
    val totalChars: Long
)

/**
 * 全 App 唯一的「全书阅读进度」算法。
 *
 * 旧口径是 `(lastReadChapterIndex + 1) / totalChapters`——一进本章就把整章算作读完，
 * 于是书架比阅读页页脚系统性偏高约「一章」（50 章的书正好 2%）。这里改为按字符累计，
 * 并保留按章公式作为兜底：老书或没落 `charCount` 的书 [span] 会给出 `totalChars == 0`。
 */
fun readFraction(book: BookEntity, span: BookReadSpan?): Float {
    if (book.lastReadAt == 0L) return 0f
    val total = span?.totalChars ?: 0L
    if (span == null || total <= 0L) {
        if (book.totalChapters <= 0) return 0f
        return ((book.lastReadChapterIndex + 1f) / book.totalChapters).coerceIn(0f, 1f)
    }
    val consumed = span.charsBeforeChapter + book.lastReadCharOffset.coerceAtLeast(0)
    return (consumed.toDouble() / total).toFloat().coerceIn(0f, 1f)
}

/**
 * 整数百分比标签。四舍五入而非向下取整——`floor` 会把 18.9% 显示成 18%，
 * 而阅读页页脚写的是 19.0%，看起来又不一致了。没读完的书封顶 99%，刚开始读的不显示 0%。
 */
fun readPercent(fraction: Float): Int {
    val clamped = fraction.coerceIn(0f, 1f)
    if (clamped >= 1f) return 100
    if (clamped <= 0f) return 0
    return (clamped * 100).roundToInt().coerceIn(1, 99)
}
