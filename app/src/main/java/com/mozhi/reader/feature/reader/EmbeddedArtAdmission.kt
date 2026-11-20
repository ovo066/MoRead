package com.mozhi.reader.feature.reader

import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark

/** 进入一章后的放行时间：页面进场与插图记录读库都在这段时间里完成。 */
internal const val EMBEDDED_ART_ENTRY_GRACE_MS = 2_500L

/**
 * 正在读的这一章里，哪些插图现在就可以排进正文。
 *
 * 往正文里插一幅图会把它后面的字整体往下推。读者眼前这一页、或者已经翻过去的地方多出一幅图，
 * 正在读的那行字就会在眼皮底下挪位置；所以这类图先不排，仍以插图按钮的样子留在原处，
 * 等读者换章时再一起排进去。排在前方（当前页之后）的图不会动到眼前的字，来了就排。
 *
 * 刚进入一章的头一小会儿（[fresh]）全部放行：那时页面还在进场，插图记录也可能刚从库里读出来。
 */
internal class EmbeddedArtAdmission {
    private var chapter = Int.MIN_VALUE
    private val admitted = HashSet<Long>()
    private val held = HashSet<Long>()

    /** 返回此刻应当排进正文的插图 id；其余的仍显示为按钮。 */
    fun admit(currentChapter: Int, readerOffset: Int, fresh: Boolean, marks: List<ReaderIllustrationMark>): Set<Long> {
        if (currentChapter != chapter) {
            chapter = currentChapter
            admitted.clear()
            held.clear()
        }
        val result = HashSet<Long>()
        for (mark in marks) {
            if (mark.chapterIndex != currentChapter) {
                result += mark.id
                continue
            }
            when {
                mark.id in admitted -> result += mark.id
                fresh || (mark.id !in held && mark.startCharOffset > readerOffset + PAGE_GUARD_CHARS) -> {
                    held -= mark.id
                    admitted += mark.id
                    result += mark.id
                }
                else -> held += mark.id
            }
        }
        return result
    }

    companion object {
        /** 「前方」的门槛：比一屏（双页时两页）正文的字数宽裕一些，插在这之后不会碰到眼前的页。 */
        const val PAGE_GUARD_CHARS = 1_600
    }
}
