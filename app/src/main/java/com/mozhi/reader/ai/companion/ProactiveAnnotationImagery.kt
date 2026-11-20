package com.mozhi.reader.ai.companion

/**
 * 本地判断哪一段「有画面」。
 *
 * 随读配图的时机由应用决定、模型只写画面内容：把「要不要画」交给模型，便宜的模型几乎总是不画，
 * 贵的模型又可能每段都画，两头都不是用户调得动的。这里用字面线索给段落打分——写景、写光、
 * 写颜色与人物姿态的加分，对白、心理和议论的减分——再由每章配图上限从高到低取。
 *
 * 只看目标段落自己的文字，不读后文，也不调用模型。
 */
internal object ProactiveAnnotationImagery {
    /** 低于这个分数的段落不配图：宁可这一章没有图，也不为一段对白硬画一张。 */
    const val MIN_SCORE = 32

    private const val HIT_POINTS = 8
    private const val MAX_HITS = 12

    private val visualWords = listOf(
        // 光与影
        "月光", "阳光", "日光", "灯光", "烛光", "烛火", "火光", "星光", "霞光", "晨曦", "暮色", "夜色", "余晖", "夕阳",
        "朝阳", "月色", "阴影", "影子", "光芒", "闪电", "灯火", "篝火", "火焰", "炊烟", "霓虹",
        // 天气与天空
        "大雨", "细雨", "雨幕", "雨水", "雨滴", "雪花", "大雪", "落雪", "积雪", "浓雾", "薄雾", "雾气", "狂风", "微风",
        "乌云", "云层", "云海", "天空", "夜空", "星空", "彩虹", "晚霞",
        // 地景与建筑
        "山峰", "山巅", "山谷", "悬崖", "峭壁", "河面", "湖面", "海面", "水面", "江水", "溪水", "瀑布", "海浪", "沙滩",
        "树林", "森林", "竹林", "花海", "草原", "荒原", "沙漠", "田野", "街道", "长街", "小巷", "屋檐", "城墙", "城楼",
        "宫殿", "大殿", "庭院", "长廊", "石阶", "台阶", "窗外", "窗边", "门口", "屋顶", "废墟", "码头", "桥上", "塔顶",
        "落叶", "花瓣", "枝头", "树梢", "青石", "石板",
        // 外貌与衣饰
        "长发", "黑发", "白发", "银发", "发丝", "眼眸", "瞳孔", "眉眼", "脸庞", "面容", "侧脸", "背影", "身影", "长袍",
        "衣袂", "衣角", "斗篷", "披风", "铠甲", "长裙", "面具", "长剑", "刀光", "剑光", "鲜血", "血迹",
        // 姿态与动势
        "站在", "坐在", "跪在", "立在", "立于", "倚着", "靠在", "转身", "回头", "抬头", "仰望", "俯视", "凝望", "眺望",
        "拔剑", "挥剑", "举起", "奔跑", "纵身", "跃起", "坠落", "飞舞", "飘落", "洒落", "笼罩", "映照", "倒映", "摇曳",
        "升起", "落下", "燃烧", "翻涌"
    )

    private val colorWord = Regex("[红橙黄绿青蓝紫黑白灰金银粉棕褐碧赤朱墨苍]色|血红|雪白|漆黑|苍白|赤红|猩红|碧绿|湛蓝|金黄|银白|灰蒙蒙")

    private val expositoryWords = listOf(
        "因为", "所以", "如果", "应该", "觉得", "认为", "知道", "明白", "其实", "也许", "或许", "心想", "想到", "意味着",
        "换句话说", "总之", "首先", "其次", "然而", "不过", "毕竟", "难道", "究竟", "原来", "显然"
    )

    private val latinVisualWord = Regex(
        "\\b(?:moonlight|sunlight|lantern|candle|shadow|shadows|mist|fog|rain|snow|storm|sky|stars|sea|river|lake|" +
            "forest|mountain|cliff|street|alley|window|doorway|tower|ruins|crimson|scarlet|golden|silver|pale|" +
            "cloak|sword|blade|blood|stood|knelt|turned|gazed|leaned|rose|fell|drifted|glowed|flickered)\\b",
        RegexOption.IGNORE_CASE
    )

    private val openQuotes = "“「『\""
    private val closeQuotes = "”」』\""

    /** 分数越高越值得画；[MIN_SCORE] 以下视为没有画面。 */
    fun score(text: String): Int {
        val body = text.trim()
        if (body.length < 24) return 0
        val hits = visualWords.count { it in body } +
            colorWord.findAll(body).map { it.value }.distinct().count() +
            latinVisualWord.findAll(body).map { it.value.lowercase() }.distinct().count()
        var score = minOf(hits, MAX_HITS) * HIT_POINTS
        // 对白占比越高越不该画：读者要的是场面，不是两个人站着说话。
        score -= quotedShare(body) * 60 / 100
        score -= expositoryWords.count { it in body } * 6
        if (body.length < 50) score -= 12
        // 面板、清单这类满是数字的段落不是画面。
        if (body.count(Char::isDigit) * 6 > body.length) score -= 24
        return score.coerceAtLeast(0)
    }

    /** 引号里的字占整段的百分比；没合上的引号按一直说到段尾算。 */
    private fun quotedShare(body: String): Int {
        var inside = false
        var quoted = 0
        for (char in body) {
            when {
                !inside && char in openQuotes -> inside = true
                inside && char in closeQuotes -> inside = false
                inside -> quoted++
            }
        }
        return quoted * 100 / body.length
    }

    /**
     * 从各个格子里挑出本章最值得配图的段落：每格取画面感最强的一段，再按分数取前 [limit] 格。
     * 返回这些段落的终点；分数相同时靠前的优先，结果只取决于正文与分格，续跑不会换目标。
     */
    fun targets(body: String, slots: List<List<ProactiveAnnotationParagraph>>, limit: Int): Set<Int> {
        if (limit <= 0) return emptySet()
        return slots.mapNotNull { slot ->
            slot.map { it to score(body.substring(it.start, it.end)) }
                .filter { it.second >= MIN_SCORE }
                .maxWithOrNull(compareBy<Pair<ProactiveAnnotationParagraph, Int>> { it.second }.thenByDescending { it.first.start })
        }.sortedWith(compareByDescending<Pair<ProactiveAnnotationParagraph, Int>> { it.second }.thenBy { it.first.start })
            .take(limit).map { it.first.end }.toSet()
    }
}
