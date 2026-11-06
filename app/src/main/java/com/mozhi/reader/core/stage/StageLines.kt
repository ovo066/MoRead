package com.mozhi.reader.core.stage

private val captionDisallowedChars = Regex("[：:“”「」\"？?！!（(]")
private val speechEnding = Regex("[道说问答]。?$")
private val captionPlace = Regex("^([一-龥0-9]{2,8})[，,]([一-龥0-9]{2,10})。?$")
private val timeSuffixChars = Regex("[之时分]")
/** 动作与声响：写了「发生了什么」就不是时空字幕。 */
private val captionActionChars = Regex("[响崩塌炸裂碎倒飞冲撞砸震喊叫哭笑]|一声")
private val imageOnly = Regex("^(?:[￼\\s]|［图片］)+$")
private val numericNote = Regex("^\\[\\d{1,3}\\]$|^【\\d{1,3}】$|^［\\d{1,3}］$")
private val decorativeBreak = Regex("^[\\s*＊※◇◆~～=＝—\\-·•○●☆★]+$")
private val parenthesizedLine = Regex("^[（(][^（()）]*[）)]$")
private val thoughtChars = Regex("[我他她你？！?!…]")
private val soundEffectSeparators = Regex("[\\s。！？!?…—\\-～~、，,.]+")
private val bracketedLine = Regex("^[【\\[［]([^【】\\[\\]［］]+)[】\\]］]$")
private val repeatedBrackets = Regex("^(?:[【\\[［][^【】\\[\\]［］]{1,8}[】\\]］]){2,}$")
private val systemPrefix = Regex("^[【\\[［]([^】\\]］]{1,8})[】\\]］](.+)$")
/** 全角方括号整行（［已完成更新。］）：网文里几乎都是系统与提示消息，里面可以再套引号与方括号。 */
private val fullWidthMessage = Regex("^［(.+)］$")
private val systemLabelPunctuation = Regex("[，。！？…,.!?]")
private val colonOnly = Regex("^[：:]?$")
private val leadingColonOrWhitespace = Regex("^[：:\\s]+")
private val forumLine = Regex("^(楼主|LZ|lz|沙发|板凳|地板|\\d{1,4}\\s*楼|#\\d{1,4})\\s*([^：:]{0,16})[：:]\\s*(.+)$")
private val floorLabel = Regex("^(楼主|LZ|lz|沙发|板凳|地板|\\d{1,4}\\s*楼|#\\d{1,4})$")
private val chatLine = Regex("^(?:【([^】]{1,12})】|([^\\s：:，,。\"“”「」【】（）()\\[\\]]{1,10}))\\s*[：:]\\s*(.+)$")
private val quoteOpening = Regex("^[\"“「『\\[【]")
private val keyValuePunctuation = Regex("[。！？!?，,]")
private val salutation = Regex("^[^\\s，。！？：:“”「」]{1,8}[：:]$")
/** 称呼里不该有的叙述字：「吕空昀的信息来了：」是旁白，不是信的抬头。 */
private val salutationNarrative = Regex("[了着过来去说道写想看听问答叫喊是在]")
private val dearSalutation = Regex("^亲爱的[^，。]{1,10}[：:，,]$")
private val greetingSalutation = Regex("^[^\\s，。！？：:“”「」]{1,10}[，,]\\s*(?:你好|您好)[：:！!，,。]?$")
private val inlineImageMark = Regex("\\s*(?:［图片］|￼)\\s*")

internal data class StageLine(
    val text: String, val start: Int, val end: Int, val gap: Int = 0,
    val type: String = "prose", val name: String = "", val content: String = "",
    val bracket: Boolean = false, val label: String = "", val body: String = "",
    val floor: String = "", val author: String = ""
) {
    val range: SourceRange get() = SourceRange(start, end)
}

internal object StageRules {
    val ono = "轰砰咚嘭啪哗嗖铛叮咔嚓噗嗡呜哒咕噜嘀嗒扑通咣当哐唰吱嘎嘣叭咻铮锵噼滴答呼乓嗷".toSet()
    val sysWords = Regex("系统|叮|任务|奖励|宿主|提示|警告|获得|解锁|等级|属性|积分|经验|签到|技能|通知|主线|支线|副本|成就|惩罚")
    val stickerWords = Regex("表情|图片|动图|语音|视频|红包|转账|定位|链接|文件|拍了拍|撤回")
    val chatCue = Regex("手机|消息|微信|短信|私信|私聊|群聊|聊天记录|发来|通讯录|QQ|对话框|发信息|发消息")
    val letterLine = Regex("见字如面|亲启|敬启|此致|敬礼|顺颂|绝笔|展信佳|见信好|见信如晤")
    val letterPrev = Regex("书信|信件|来信|回信|信纸|信封|一封信|这封信|那封信|的信(?![息号用任心念])|信上|信中|字条|纸条|日记|遗书|便签|手札|家书|上面写着")
    val notSalutation = Regex("^(?:注|备注|注意|提示|PS|ps|作者有话说|作者的话|题外话|附|附录|简介|说明|规则)[：:]$|[说道问答喊叫回][：:]$")
    val narrativeChars = Regex("[了着过不又就是听看说道想走来去回到把被让给向对从和与也都还正将已再便却站坐起]")
    val termAfter = Regex("^(?:的|地|得|字|二字|两字|一词|之类|之一|般|一样|似的|三个字|两个字|这个词|这几个字)")
    val functionChars = Regex("[的是了在有和与就都也很]")
    val speechBefore = Regex("[道说问答喊叫吼嚷曰云声：:，,。！？!?…\\s—]$")
    val indefinite = Regex("有人|一人|众人|那人|此人|某人|对方|其中一人|另一人|旁人|有个声音|一个声音")
    val perception = Regex("^(?:见|看见|看到|听见|听到|闻言|听罢|听了|想到这里|说完|说罢|话音刚落|言罢)[^，,]{0,16}[，,]")
    const val surnames = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳酆鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常乐于时傅皮卞齐康伍余元卜顾孟平黄和穆萧尹姚邵湛汪祁毛禹狄米贝明臧计伏成戴谈宋茅庞熊纪舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍虞万支柯昝管卢莫经房裘缪干解应宗丁宣贲邓郁单杭洪包诸左石崔吉钮龚程嵇邢滑裴陆荣翁荀羊惠甄曲家封芮羿储靳汲邴糜松井段富巫乌焦巴弓牧隗山谷车侯宓蓬全郗班仰秋仲伊宫宁仇栾暴甘厉戎祖武符刘景詹束龙叶幸司韶郜黎蓟薄印宿白怀蒲邰从鄂索咸籍赖卓蔺屠蒙池乔阴胥能苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍却璩桑桂濮牛寿通边扈燕冀郏浦尚农温别庄晏柴瞿阎充慕连茹习宦艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧殳沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶空曾毋沙乜养鞠须丰巢关蒯相查后荆红游竺权逯盖益桓公岳帅缑亢况郈有琴归海晋楚闫法汝鄢涂钦商牟佘佴伯赏墨哈谯笪年爱阳佟纪虞"
    val compoundSurnames = listOf("欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟", "公孙", "慕容", "长孙", "令狐", "宇文", "独孤", "南宫", "西门", "轩辕", "夏侯", "端木", "司徒", "百里")
    val forumCue = Regex("论坛|贴吧|版块|帖子|发帖|回帖|楼主|生活版|热帖|置顶")
    val timeWord = Regex("年后|年前|月后|天后|日后|翌日|次日|当晚|当夜|深夜|清晨|黄昏|傍晚|午夜|凌晨|正午|拂晓|黎明|某日|某年|多年以后|多年后|许久之后|不久之后|同一时间|与此同时|\\d+年|[一二三四五六七八九十百千两几数]+(?:年|月|日|天|个月|周)(?:后|前|之后|之前)")
    val placeWord = Regex("街|路|巷|号|城|镇|村|山|峰|宫|府|院|楼|港|站|岛|国|州|郡|殿|寺|观|阁|洞|谷|公寓|大厦|医院|学校|酒店|别墅")
    val timeLine = Regex("^(?:刚刚|\\d+\\s*(?:秒|分钟|小时|天|周|个月|年)前|\\d{1,2}[:：]\\d{2}(?:[:：]\\d{2})?|\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?(?:\\s*\\d{1,2}[:：]\\d{2})?)$")
    val pronounStart = Regex("^(?:他们|她们|它们|他|她|它|我们|我|你们|你|您|有人|那人|对方|众人|大家|两人|二人|几人)")
    val pronounAny = Regex("他|她|它|我|你|您")
    val connective = Regex("^(?:然后|接着|随后|随即|于是|这时|此时|只见|却见|但见|紧接着|片刻后|半晌|良久|同时|最后|而|但|又|便|就|再)[，,]?")
    val speechVerbs = listOf("冷笑道", "苦笑道", "喃喃道", "低语道", "补充道", "解释道", "笑道", "说道", "问道", "叹道", "喝道", "骂道", "答道", "怒道", "喊道", "叫道", "吼道", "嚷道", "回道", "应道", "接道", "斥道", "回答", "开口", "道", "说", "问", "答", "喊", "叫", "吼", "嚷")
    val verb = Regex("(" + speechVerbs.joinToString("|") + ")(?=[：:，,。“「\"\\[【]|$)")
    val verbishName = Regex("(" + speechVerbs.joinToString("|") + "|笑|叹)$")
    val headEnd = Regex("(" + speechVerbs.joinToString("|") + ")[：:，,]$")
    val adverbSuffix = "淡淡 冷冷 轻轻 缓缓 慢慢 微微 沉声 低声 轻声 厉声 颤声 柔声 朗声 大声 小声 高声 冷声 怒声 急声 笑着 笑了笑 苦笑 冷笑 一笑 忽然 突然 连忙 赶忙 急忙 不禁 忍不住 随即 当即 顿时 淡然 漠然 点头 摇头 回头 转头 皱眉 沉吟 叹息 想了想 顿了顿 看了看 忙 又 也 便 就 才 却 只 再 还 都 亦 则 笑 冷 苦 怒 叹".split(' ')
    val notName = "众人 大家 有人 那人 这人 此人 对方 两人 二人 几人 他们 她们 我们 你们 自己 少年 少女 男人 女人 老者 青年 弟子 众弟子 旁人 心中 口中 嘴里 一边 接着 然后 随后 只见 于是 这时 此时 忽然 突然 笑着 声音 那边 这边 外面 里面 电话 屏幕 一声 一句 轻声 低声 沉声 老爷 夫人 公子 小姐 先生 师兄 师姐 师弟 师妹 师父 师傅".split(' ').toSet()
    val notSubject = "王爷 张开 张口 高兴 高声 白色 白天 黄色 金色 安静 安排 时候 时间 方向 方才 方面 于是 常常 平时 平常 明白 明天 江湖 江山 林中 林子 云中 石头 马上 马车 万一 万分 和尚 相公 公子 老爷 夫人 将军 皇上 陛下 太监 管家 家人 宫中 叶子 毛病 包括 关系 关上 顾不 席间 海上 成为 成了 全部 全身 向来 向着 原来 可是 正是 时而 连忙 连声 宁可 应该 应声 回头 程度 文字 甘心 路上 车上 山上 水中 云层 金光 雷声 万分 何况 何必 于此 谷中 殿中 殿内 洞中 洞内 阳光 欧阳 司马 上官 诸葛 东方 皇甫 公孙 慕容 令狐 宇文 独孤 南宫 西门 轩辕 夏侯 端木 司徒 百里 有人 能够 能否 相信 相比 相互 和他 和她 和我 和你 那人 那位 那名 那个 那边 那里 经过 经此 只是 只见 只听 只得 只好 向前 后来 后面 东西 公主 国师 曾经 常年 都是 都不 空中 那么 那时".split(' ').toSet()
    val clauseStopFirst = "也就这那其此每各某该本所如若虽但而且并或与和及在从对向把被让给为因由于以用按据经连除自当趁随沿往朝离距比跟同替将不没别未已曾正要会能可得一两几他她它我你您谁哪什怎只却便又再还都很太更最刚才是".toSet()
    val nameTitleTail = Regex("(?:爵士|先生|女士|夫人|小姐|博士|教授|大人|阁下|殿下|陛下|议员|医生|警官|主教|上校|少校|中尉|队长)$")
    val nameTailStop = Regex("[则是的在和与也都就便却又正已还说道问看笑叹点摇站走把被对向从将让给这那一不没有了着过地等]")
    val notNameExtra = "听见 听到 看见 看到 譬喻 严格来 严格 作者有话 作者 换句话 毫无疑 年轻 老实 缓慢 语带 灰眸 也就是 其实 突然 忽然 然而 因为 所以 如果 虽然 但是 可是 于是 原来 果然 居然 竟然 显然 当然 依然 仍然 自然 同样 一样 这样 那样 怎样 没有 不是 就是 还是 只是 正是 总是 似乎 好像 仿佛 难道 究竟 到底".split(' ').toSet()
    const val closers = "，。、！？；：,.!?;:」』”’）)》】]…"
    const val openers = "「『“‘（(《【["
    const val maxDialogue = 90
    const val maxNarration = 80
    const val maxAside = 24
    val thoughtCue = Regex("心想|暗想|暗道|心道|心中暗|心里想|心里道|默念|心中想|心说")

    val quoteMarks = Regex("[“”「」\"]")
    val sentencePunctuation = Regex("[，。！？,.!?…]")

    fun clean(s: String): String = s.replace(inlineImageMark, "").trim()
    fun count(text: String, part: String): Int {
        if (part.isEmpty()) return 0
        var n = 0
        var k = text.indexOf(part)
        while (k >= 0) { n++; k = text.indexOf(part, k + part.length) }
        return n
    }
}

internal object StageLines {
    private fun isCaption(t: String): Boolean {
        with(StageRules) {
            if (t.length > 18 || captionDisallowedChars.containsMatchIn(t) || pronounAny.containsMatchIn(t)) return false
            if (speechEnding.containsMatchIn(t) || captionActionChars.containsMatchIn(t)) return false
            val place = captionPlace.find(t)
            if (place != null && !narrativeChars.containsMatchIn(timeWord.replaceFirst(t, "")) && (placeWord.containsMatchIn(t) || timeWord.containsMatchIn(t))) return true
            return t.length <= 12 && timeWord.containsMatchIn(t) && !narrativeChars.containsMatchIn(timeWord.replaceFirst(t, "").replace(timeSuffixChars, ""))
        }
    }

    fun classifyLine(l: StageLine): StageLine = with(StageRules) {
        val t = l.text
        if (imageOnly.matches(t)) return l.copy(type = "image")
        if (numericNote.matches(t)) return l.copy(type = "note")
        if (decorativeBreak.matches(t) && t.length <= 24) return l.copy(type = "break")
        if (parenthesizedLine.matches(t)) {
            val inner = t.substring(1, t.length - 1)
            return l.copy(type = if (inner.length >= 6 || thoughtChars.containsMatchIn(inner)) "thought" else "note")
        }
        val core = t.replace(soundEffectSeparators, "")
        if (core.isNotEmpty() && core.length <= 10 && t.length <= 14 && core.count { it in ono }.toDouble() / core.length >= .6) return l.copy(type = "sfx")
        fullWidthMessage.find(t)?.let { m ->
            val inner = m.groupValues[1].trim()
            if (inner.isNotEmpty() && !numericNote.matches(t)) return l.copy(type = "system", body = inner)
        }
        val whole = bracketedLine.find(t)
        val repeated = repeatedBrackets.matches(t)
        if (whole != null || repeated) {
            val inner = whole?.groupValues?.get(1) ?: t
            if (sysWords.containsMatchIn(inner)) return l.copy(type = "system", body = inner)
            if (stickerWords.containsMatchIn(inner) || (inner.length <= 6 && !StageRules.sentencePunctuation.containsMatchIn(inner)) || inner == t) return l.copy(type = "sticker")
            return l.copy(type = "voiceLine", body = inner)
        }
        val br = systemPrefix.find(t)?.groupValues
        if (br != null && sysWords.containsMatchIn(br[1]) && !systemLabelPunctuation.containsMatchIn(br[1]) && !colonOnly.matches(br[2])) return l.copy(type = "system", label = br[1], body = br[2].replace(leadingColonOrWhitespace, ""))
        val fl = forumLine.find(t)?.groupValues
        if (fl != null) return l.copy(type = "forum", floor = fl[1], author = fl[2].trim(), body = fl[3])
        if (floorLabel.matches(t)) return l.copy(type = "floorLabel", floor = t)
        val ch = chatLine.find(t)?.groupValues
        if (ch != null) {
            val bracket = ch[1].isNotEmpty()
            val name = ch[1].ifEmpty { ch[2] }
            val content = ch[3]
            if (!verbishName.containsMatchIn(name) && !pronounStart.containsMatchIn(name) && !quoteOpening.containsMatchIn(content)) {
                return l.copy(type = if (!bracket && name.length <= 6 && content.length <= 24 && !keyValuePunctuation.containsMatchIn(content)) "kv" else "chatline", name = name, content = content, bracket = bracket)
            }
        }
        val addressed = (salutation.matches(t) && !salutationNarrative.containsMatchIn(t)) || dearSalutation.matches(t) || greetingSalutation.matches(t)
        if (addressed && !notSalutation.containsMatchIn(t)) return l.copy(type = "salutation")
        if (isCaption(t)) return l.copy(type = "caption")
        l
    }

    /**
     * 按行读入。[join] 是被行内图片（脚注号、表情）拆成几行的段落：图片在原文里独占一行，
     * 这里把同一段的几行合回一行，行内的换行换成空格（长度不变，坐标照旧），图片占位由 [StageRules.clean] 去掉。
     */
    fun readLines(body: String, join: List<SourceRange> = emptyList()): List<StageLine> {
        val raw = mutableListOf<StageLine>()
        var pos = 0
        var gap = 0
        for (line in body.split('\n')) {
            val lead = line.length - line.trimStart().length
            val t = line.trim()
            if (t.isNotEmpty()) { raw += StageLine(t, pos + lead, pos + lead + t.length, gap); gap = 0 } else gap++
            pos += line.length + 1
        }
        if (join.isEmpty()) return raw.map(::classifyLine)
        val out = mutableListOf<StageLine>()
        var k = 0
        while (k < raw.size) {
            val first = raw[k]
            val range = join.firstOrNull { first.start >= it.start && first.start < it.end }
            var last = k
            if (range != null) while (last + 1 < raw.size && raw[last + 1].start < range.end) last++
            if (last == k) { out += classifyLine(first); k++; continue }
            val end = raw[last].end
            out += classifyLine(StageLine(body.substring(first.start, end).replace('\n', ' '), first.start, end, first.gap))
            k = last + 1
        }
        return out
    }
}
