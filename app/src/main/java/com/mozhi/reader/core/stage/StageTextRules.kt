package com.mozhi.reader.core.stage

/** 界面会用到的几处原文判断，放在核心层，界面代码里不出现中文字面量。 */
object StageTextRules {
    /** 章号的前缀、可能的收尾字与认不出时印上刻的字（章名卡的印用）。 */
    const val ORDINAL_PREFIX = "第"
    const val ORDINAL_SUFFIXES = "章回节卷话幕集"
    const val ORDINAL_FALLBACK = "章"
    private val chapterTitle = Regex("^(第[^\\s]{1,8}?[章回节卷集幕])\\s*(.*)$")

    /** 「第十二章 雨夜来信」→（「第十二章」，「雨夜来信」）；没有章序时序号为空。 */
    fun splitChapterTitle(title: String): Pair<String, String> {
        val match = chapterTitle.find(title.trim()) ?: return "" to title.trim()
        val name = match.groupValues[2].trim()
        return match.groupValues[1] to name.ifEmpty { title.trim() }
    }

    /** 楼主层：徽标显示楼层名本身（原文写的「楼主」或「LZ」）。 */
    fun isOriginalPoster(floor: String?): Boolean =
        floor != null && (floor.contains("楼主") || floor.equals("LZ", ignoreCase = true))
}
