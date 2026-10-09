package com.mozhi.reader.ai.knowledge

/**
 * 不调用模型的人物候选：按字面频次、对白归属与跨章分布给可能的人名打分。
 *
 * 只是给主 agent 的线索，不是结论——会混进「王爷」「张开」这类非人名，也会漏掉没有姓氏的称呼，
 * 主 agent 会再用检索核对。所有统计只基于调用方传入的、已截到阅读范围内的正文。
 */
object CharacterCandidates {
    data class Candidate(val name: String, val count: Int, val dialogue: Int, val chapters: Int, val firstChapter: Int) {
        val score: Double get() = count + dialogue * 3.0 + chapters * 2.0
    }

    private const val SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣翁荀羊于惠甄曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍却璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公岳帅缑亢况郈有琴归海晋楚闫法汝鄢涂钦商牟佘佴伯赏墨哈谯笪年爱阳佟"
    private val COMPOUND_SURNAMES = listOf("欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟", "公孙", "慕容", "长孙", "令狐", "宇文", "独孤", "南宫", "西门", "轩辕", "夏侯", "端木", "司徒", "百里")
    private val SPEECH = Regex("""([一-龥]{2,4}?)(?:笑道|说道|问道|叹道|喝道|骂道|答道|冷笑道|低声道|忙道|道|说)[：:，,「“"]""")
    private val NOT_NAMES = setOf(
        "王爷", "张开", "张口", "高兴", "高声", "白色", "白天", "黄色", "金色", "安静", "安排", "时候", "时间", "方向", "方才", "方面",
        "于是", "常常", "平时", "平常", "明白", "明天", "江湖", "江山", "林中", "林子", "云中", "石头", "马上", "马车", "司马", "东方",
        "万一", "万分", "和尚", "相公", "公子", "师父", "师傅", "老爷", "夫人", "将军", "皇上", "陛下", "太监", "管家", "家人", "宫中",
        "龙虎", "叶子", "毛病", "包括", "关系", "关上", "顾不", "席间", "海上", "楚楚", "成为", "成了", "全部", "全身", "向来", "向着"
    )
    private val LATIN_NAME = Regex("""(?<![.!?]\s)(?<!^)\b([A-Z][a-z]{2,15})\b""")
    private val LATIN_STOP = setOf("The", "And", "But", "Then", "When", "What", "This", "That", "There", "Mrs", "Miss", "Lord", "Lady", "Sir", "God")

    /** [chapters]：(章节序号, 已截到阅读范围内的正文)。 */
    fun rank(chapters: List<Pair<Int, String>>, limit: Int = 40): List<Candidate> {
        val counts = HashMap<String, Int>()
        val dialogue = HashMap<String, Int>()
        val seenIn = HashMap<String, MutableSet<Int>>()
        val latinish = chapters.sumOf { (_, text) -> text.count { it in 'A'..'Z' || it in 'a'..'z' } } >
            chapters.sumOf { (_, text) -> text.count { it in '一'..'龥' } }
        chapters.forEach { (index, text) ->
            if (latinish) {
                LATIN_NAME.findAll(text).forEach { match ->
                    val name = match.groupValues[1]
                    if (name !in LATIN_STOP) {
                        counts.merge(name, 1, Int::plus)
                        seenIn.getOrPut(name) { mutableSetOf() } += index
                    }
                }
                return@forEach
            }
            var i = 0
            while (i < text.length) {
                val compound = COMPOUND_SURNAMES.firstOrNull { text.startsWith(it, i) }
                if (compound != null || text[i] in SURNAMES) {
                    val surnameLength = compound?.length ?: 1
                    for (given in 1..2) {
                        val end = i + surnameLength + given
                        if (end > text.length) break
                        val name = text.substring(i, end)
                        if (name.all { it in '一'..'龥' } && name !in NOT_NAMES) {
                            counts.merge(name, 1, Int::plus)
                            seenIn.getOrPut(name) { mutableSetOf() } += index
                        }
                    }
                }
                i++
            }
            SPEECH.findAll(text).forEach { match ->
                val speaker = match.groupValues[1]
                // 「只听林冲道」这类前缀：取以姓氏开头的最长后缀，没有就取末两字。
                val name = (0 until speaker.length - 1).firstNotNullOfOrNull { start ->
                    speaker.substring(start).takeIf { it.first() in SURNAMES && it.length in 2..3 }
                } ?: speaker.takeLast(2)
                if (name !in NOT_NAMES) {
                    dialogue.merge(name, 1, Int::plus)
                    seenIn.getOrPut(name) { mutableSetOf() } += index
                }
            }
        }
        // 「林冲」与「林冲道」「林冲的」：三字串大多是二字名加虚词，只有它本身足够常见才保留。
        val names = (counts.keys + dialogue.keys).toSet().filter { name ->
            val count = counts[name] ?: 0
            val prefix = name.dropLast(1)
            when {
                latinish -> count >= 3
                name.length == 3 && (counts[prefix] ?: 0) > count * 2 -> false
                name.length == 2 && counts.keys.any { it.length == 3 && it.startsWith(name) && (counts[it] ?: 0) * 10 >= count * 8 } -> false
                else -> count + (dialogue[name] ?: 0) * 3 >= 4
            }
        }
        return names.map { name ->
            val chaptersSeen = seenIn[name].orEmpty()
            Candidate(name, counts[name] ?: 0, dialogue[name] ?: 0, chaptersSeen.size, chaptersSeen.minOrNull() ?: 0)
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.name }).take(limit)
    }
}
