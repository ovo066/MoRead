package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.retrieval.ReadingScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Requested search bounds are not permissions; always intersect them with the reading scope. */
internal data class ChapterSearchBounds(val fromChapter: Int? = null, val toChapter: Int? = null) {
    init {
        require(fromChapter == null || fromChapter > 0) { "from_chapter 必须大于 0" }
        require(toChapter == null || toChapter > 0) { "to_chapter 必须大于 0" }
        require(fromChapter == null || toChapter == null || fromChapter <= toChapter) { "章节范围无效" }
    }
    fun resolve(totalChapters: Int, allowed: ReadingScope): ChapterSearchRange {
        require(totalChapters > 0) { "当前书籍尚无可检索的章节" }
        val first = (fromChapter ?: 1) - 1
        val lastAllowed = allowed.clampLastChapter(totalChapters)
        val last = minOf((toChapter ?: totalChapters) - 1, lastAllowed)
        require(first <= lastAllowed) { "第 ${first + 1} 章超出当前可检索范围（最多第 ${lastAllowed + 1} 章）" }
        require(first <= last) { "章节范围无效：${first + 1}-${last + 1}" }
        return ChapterSearchRange(first, allowed.intersect(ReadingScope.upto(last, Int.MAX_VALUE)))
    }
}

internal data class ChapterSearchRange(val firstIndex: Int, val scope: ReadingScope) {
    val lastIndex: Int get() = scope.maxChapterIndex
}

internal fun parseChapterSearchBounds(arguments: JsonObject): ChapterSearchBounds {
    fun read(name: String): Int? {
        if (name !in arguments) return null
        val value = arguments[name] as? JsonPrimitive
        require(value != null && !value.isString && value.intOrNull != null && value.intOrNull!! > 0) {
            "$name 必须是从 1 开始的整数字段"
        }
        return value.intOrNull
    }
    val from = read("from_chapter")
    val to = read("to_chapter")
    require(from == null || to == null || from <= to) { "章节范围无效：$from-$to" }
    return ChapterSearchBounds(from, to)
}
