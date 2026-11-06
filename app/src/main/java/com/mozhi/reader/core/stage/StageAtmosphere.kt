package com.mozhi.reader.core.stage

/** 原文写到的天气：演出给背景下雨、下雪、起雾、飘花；雷雨另有闪电与雷声。 */
enum class StageWeatherCue { RAIN, STORM, SNOW, FOG, PETALS, LEAVES, FIREFLIES, DUST }

/** 原文写到的情绪基调：演出据此换颜料（色调）与配乐。 */
enum class StageMood { EERIE, WARM, TENSE, TENDER, SORROW }

/** 布景：原文写到的地点类型，演出据此画水墨远景并配环境声。 */
enum class StageSetting { MOUNTAIN, WATER, SEA, FOREST, BAMBOO, CITY, INTERIOR, CLASSICAL, CAMPUS, VILLAGE, DESERT }

/** 时段：天色、日月与星。 */
enum class StageTime { DAWN, DAY, DUSK, NIGHT }

/** 原文写到的光源：烛火、灯光、篝火、霓虹。 */
enum class StageLight { CANDLE, LAMP, FIRE, NEON }

/** 整本书的年代感：古风还是现代，决定窗格、建筑与配乐的样式。 */
enum class StageEra { ANCIENT, MODERN }

/** 从第 [fromBeat] 拍起生效的场景（直到下一条）：天气、情绪、布景、时段、光源。 */
data class StageScene(
    val fromBeat: Int,
    val weather: StageWeatherCue?,
    val mood: StageMood?,
    val setting: StageSetting? = null,
    val time: StageTime? = null,
    val light: StageLight? = null
)

/**
 * 场景氛围逐拍更新：环境按各自的重置规则持续，情绪在固定拍数后衰减。
 * 不前瞻——所有场景线索只由当前拍、之前的原文和章首承接值决定。
 */
object StageAtmosphere {
    const val MOOD_SPAN = 14
    private val clear = Regex("(?:风雪|大雨|大雪|雨|雪)(?:已经|终于|渐渐|渐|也|都|刚刚)?(?:停了?|止住|止息|消散)|(?:不再|没有)下[雨雪]|雨过天晴|放晴|天晴了|太阳出来|雨势渐歇|雪霁|雾(?:渐渐|终于|慢慢)?散(?:去|了)?")
    private val weatherCues = listOf(
        StageWeatherCue.RAIN to Regex("下雨|下着雨|雨下|雨夜|雨水|大雨|小雨|暴雨|细雨|雨点|雨滴|雨幕|雨声|冒雨|雨中|淋雨|阴雨|春雨|秋雨|夜雨|雨丝|雨势|雷雨|梅雨|骤雨|雨帘|滂沱|淅淅沥沥|淅沥"),
        StageWeatherCue.STORM to Regex("雷雨|电闪雷鸣|雷声|打雷|闪电划破|暴风雨|惊雷|雷鸣"),
        StageWeatherCue.SNOW to Regex("下雪|下着雪|雪花|大雪|飘雪|风雪|暴雪|雪地|积雪|雪夜|白雪|雪片|落雪|雪下得|鹅毛大雪|雪粒"),
        StageWeatherCue.FOG to Regex("浓雾|大雾|雾气|薄雾|白雾|雾霭|晨雾|迷雾|雾蒙蒙"),
        StageWeatherCue.PETALS to Regex("落花|花瓣|飞花|花雨|樱花纷|桃花纷|梨花纷|樱花飘"),
        StageWeatherCue.LEAVES to Regex("落叶|枯叶|黄叶|秋叶|红叶纷|树叶纷|叶子纷"),
        StageWeatherCue.FIREFLIES to Regex("萤火虫|流萤|萤光点点"),
        StageWeatherCue.DUST to Regex("浮尘|尘埃|沙尘|风沙|尘土飞扬|灰尘飞")
    )
    private val moodCues = listOf(
        StageMood.EERIE to Regex("毛骨悚然|阴冷|诡异|阴森|恐怖|尸体|阴风|寒意|头皮发麻|脊背发凉|后背发凉|鬼影|鬼魂|血迹"),
        StageMood.WARM to Regex("暖意|温暖|炉火|暖洋洋|笑意盈盈|热闹"),
        StageMood.TENSE to Regex("杀意|杀气|厮杀|血光|危机四伏|千钧一发|剑拔弩张|生死关头"),
        StageMood.TENDER to Regex("温柔地|心动|脸红|耳根发烫|耳尖发红|亲吻|接吻|吻(?!合)|拥抱|抱住|相拥|牵起.{0,3}手|十指相扣|心跳漏了一拍|喜欢你"),
        StageMood.SORROW to Regex("泪水|眼泪|落泪|流泪|哭了|痛哭|哽咽|抽泣|泣不成声|心如刀割|心痛|悲伤|绝望|哀伤|眼眶发红|眼眶通红")
    )
    private val settingCues = listOf(
        StageSetting.MOUNTAIN to Regex("山间|山路|山顶|山脚|山腰|山谷|山崖|山峰|峰顶|群山|深山|高山|山林|山坳|悬崖|峭壁|山道|半山|山洞|崖边|山巅|山脉"),
        StageSetting.WATER to Regex("江边|河边|湖边|溪边|岸边|江面|湖面|河面|水面|码头|渡口|船头|船舱|小舟|扁舟|画舫|湖心|桥上|桥下|溪水|潭边|河岸|湖畔|江畔"),
        StageSetting.SEA to Regex("海边|海面|海浪|沙滩|海岸|海风|礁石|甲板|海水|浪花拍|海滩"),
        StageSetting.FOREST to Regex("树林|森林|林间|林中|林子|密林|丛林|松林|林荫|树下|林海"),
        StageSetting.BAMBOO to Regex("竹林|竹叶|竹影|竹海|翠竹|竹屋|竹楼"),
        StageSetting.CITY to Regex("街道|街上|街头|马路|路口|路灯下|城市|高楼|大厦|写字楼|商场|地铁|车站|公交|停车场|天桥|人行道|霓虹|小区|巷子|胡同|市中心|街角"),
        StageSetting.INTERIOR to Regex("房间|屋里|屋内|卧室|客厅|厨房|书房|浴室|卫生间|走廊|楼道|楼梯|阁楼|地下室|办公室|会议室|病房|包厢|车厢|电梯|门内|床边|床上|窗边|窗前|沙发|餐桌|公寓|出租屋|屋子里"),
        StageSetting.CLASSICAL to Regex("宫殿|大殿|殿内|殿前|宫门|宫墙|庭院|院子里|回廊|长廊|亭子|凉亭|楼阁|寺庙|寺里|庙里|祠堂|府中|府里|府门|城楼|城墙|客栈|茶楼|酒楼|花厅|厢房|正堂"),
        StageSetting.CAMPUS to Regex("教室|操场|校园|学校里|宿舍楼|图书馆|食堂|实验楼|讲台|课桌"),
        StageSetting.VILLAGE to Regex("村子|村口|村里|乡下|田埂|稻田|麦田|农家|院坝|山村|田野|小村"),
        StageSetting.DESERT to Regex("沙漠|戈壁|荒漠|大漠|沙丘|黄沙")
    )
    private val timeCues = listOf(
        StageTime.DAWN to Regex("清晨|黎明|拂晓|天亮了|天刚亮|晨光|日出|晨曦|破晓|一大早|早晨|早上|清早|天明"),
        StageTime.DAY to Regex("中午|正午|午后|白天|烈日|晌午|阳光明媚|上午|下午"),
        StageTime.DUSK to Regex("黄昏|傍晚|夕阳|日落|晚霞|暮色|落日|薄暮"),
        StageTime.NIGHT to Regex("深夜|夜色|夜里|夜晚|黑夜|午夜|月光|月色|星光|夜空|入夜|夜深|凌晨|夜幕|半夜|今夜|当晚|晚上|夜半|夜间")
    )
    private val campusCues = settingCues.filter { it.first == StageSetting.CAMPUS }
    private val lightCues = listOf(
        StageLight.CANDLE to Regex("烛光|烛火|蜡烛|油灯|灯笼|烛台|灯芯"),
        StageLight.LAMP to Regex("灯光|台灯|路灯|吊灯|手电筒?|灯亮|开了灯|打开灯|壁灯"),
        StageLight.FIRE to Regex("篝火|火堆|火光|火焰|大火|火把|熊熊|烈火|燃烧"),
        StageLight.NEON to Regex("霓虹")
    )
    private val extinguish = Regex("灯灭|熄灭|吹灭|灯光暗下|关了灯|关灯|灯熄|火灭")
    private val timeJump = Regex("翌日|次日|第二天|隔天|数日后|几天后|数月后|多年后")
    private val exit = Regex("走出|离开|出了|冲出|逃出")
    private val destination = Regex("前往|赶往|通往|想去|要去|望向|看向|望着|远处的|对面的|[去往向]$")
    private val unreal = Regex("仿佛|好像|犹如|宛如|如同|好似|想起|梦见|听说|去过|要去|想去")
    private val negated = Regex("没有|并无|并未|不曾|不会|并不|不是|并非|未曾|不再|未见|不见")
    private val recalled = Regex("想起|还记得|回忆|回想|梦见|梦到|梦中|梦里|如果|要是|假如|计划|打算|将会|将要|会和|剧情简介|作者有话|设定|小时候|曾经|照片|相片|照[的上里]|(?:电视|屏幕|电影|镜头|视频)[上里中]|画的是|雕件|玉牌|描绘|讲述|据说")
    private val figurative = Regex("像|犹如|有如|如同|好似|仿佛|宛如|宛若|如(?!今|此|何|果|愿|期|常|实|意|约)|心底|心中|脑海|逼至|逼到|仿若")
    private val borrowedSuffix = Regex("^(?:一般|一样|似的|般|裤|图案|七贤|仰止|流水|化|物业)")
    private val surname = Regex("[赵钱孙李周吴郑王冯陈卫蒋沈韩杨林刘何高白黄徐郭马唐宋苏夏陆顾叶许张]$")
    private val personSuffix = Regex("^(?:小姐|先生|姑娘|公子|公主|女士|师兄|师姐|同学|老师|殿下|姐姐|哥哥|姐|哥)")
    private val objectSuffix = Regex("^(?:膏|珠|纹|图案|装饰|一般|一样|似的|般|裤)")
    private val nonCampus = Regex("单位|公司|工厂|职工|员工|机关|军营|宣$")
    /** 「北京大学图书馆」「首都图书馆藏本」这类机构名与馆藏说明不是读者所在的校园。 */
    private val institution = Regex("大学|首都|国家|北京|上海|南京|天津|省立|市立|中央|故宫|北平|国立|公共")
    private val quotedTitle = Regex("《[^》]*》|“[^”]*”|「[^」]*」|\"[^\"]*\"")
    private val sectSuffix = Regex("^(?:诸|弟子|修士|同门|中人)")
    private val doorPresence = Regex("[进入在到]$")
    private val villageAttribute = Regex("^(?:男孩|女孩|少年|姑娘|小伙|亲戚|口音)")
    private val sandContext = Regex("漫天|遍地|满地|脚下|四周|一望无际")
    private val sandSuffix = Regex("^[中上里地]")
    private val modern = Regex("手机|电脑|微信|短信|地铁|出租车|公司|老板|学校|警察|医院|电梯|空调|外卖|网络|互联网|电视|汽车|轿车|公交|摄像头|身份证|银行卡")
    private val ancient = Regex("公子|殿下|王爷|皇上|陛下|娘娘|本宫|朕|宫女|侍卫|客栈|银两|府上|丫鬟|江湖|掌门|仙尊|朝堂|太监|皇宫|宰相|贫道|灵石")
    private val textKinds = setOf(BeatKind.NARRATION, BeatKind.CAPTION, BeatKind.DIALOGUE, BeatKind.THOUGHT)

    fun scan(beats: List<StageBeat>, opening: StageScene? = null, era: StageEra? = null): List<StageScene> {
        val out = mutableListOf<StageScene>()
        var weather = opening?.weather
        var mood: StageMood? = null
        var setting = opening?.setting
        var time = opening?.time
        var light = opening?.light
        var moodAt = -MOOD_SPAN
        var campusAt = -31
        var inheritedWeather = opening?.weather != null
        var inheritedSetting = opening?.setting != null
        var inheritedLight = opening?.light != null
        fun emit(i: Int) {
            val last = out.lastOrNull()
            val next = StageScene(i, weather, mood, setting, time, light)
            if (last != null && last.copy(fromBeat = i) == next) return
            out += next
        }
        for ((i, b) in beats.withIndex()) {
            if (i - moodAt >= MOOD_SPAN) mood = null
            // 容器文字不能把聊天、信件里的故事误当作当前环境。
            if (b.containerId != null) { emit(i); continue }
            if (b.kind == BeatKind.TITLE || b.kind == BeatKind.BREAK) {
                if (b.kind == BeatKind.BREAK || !inheritedWeather) weather = null
                if (b.kind == BeatKind.BREAK || !inheritedSetting) setting = null
                if (b.kind == BeatKind.BREAK || !inheritedLight) light = null
                if (b.kind == BeatKind.BREAK) {
                    time = null
                    inheritedWeather = false; inheritedSetting = false; inheritedLight = false
                }
                campusAt = -31
                mood = null
                emit(i)
                continue
            }
            if (b.kind !in textKinds) { emit(i); continue }
            val t = listOfNotNull(b.asideBefore?.text, b.text, b.asideAfter?.text).joinToString("\n")
            val w = latest(t, weatherCues)
            val clearAt = clear.findAll(t).lastOrNull()?.range?.first ?: -1
            if (clearAt >= 0 && clearAt >= (w?.second ?: -1)) { weather = null; inheritedWeather = false }
            else if (w != null) { weather = w.first; inheritedWeather = false }
            latest(t, moodCues)?.let { mood = it.first; moodAt = i }
            val narration = listOfNotNull(
                b.asideBefore?.text,
                b.text.takeIf { b.kind == BeatKind.NARRATION || b.kind == BeatKind.CAPTION },
                b.asideAfter?.text
            ).joinToString("\n").replace(quotedTitle) { " ".repeat(it.value.length) }
            if (latest(narration, campusCues, locations = true) != null) campusAt = i
            val s = latest(narration, settingCues, locations = true)
            if (s != null) {
                var found = s.first
                val word = settingCues.first { it.first == found }.second.findAll(narration).lastOrNull { it.range.first == s.second }?.value
                if (word == "阁楼" && era == StageEra.ANCIENT) found = StageSetting.CLASSICAL
                // 古风书里没有校园：讲堂、书院这些另有布景，不借用现代校园。
                if (found == StageSetting.CAMPUS && era == StageEra.ANCIENT) found = setting ?: StageSetting.CLASSICAL
                if (found == StageSetting.CAMPUS) campusAt = i
                if (word == "走廊" && era == StageEra.MODERN && i - campusAt <= 30) found = StageSetting.CAMPUS
                if (found != setting) { light = null; inheritedLight = false }
                setting = found; inheritedSetting = false
            }
            val jumpAt = timeJump.findAll(narration).lastOrNull()?.range?.first ?: -1
            val tm = latest(narration, timeCues)
            if (jumpAt >= 0) time = null
            if (tm != null) time = tm.first
            val l = latest(narration, lightCues)
            val offAt = extinguish.findAll(narration).lastOrNull()?.range?.first ?: -1
            // 本拍新写出的光源覆盖旧地点的灯；熄灯词不能被其中的「灯光」反向点亮。
            if (l != null) { light = l.first; inheritedLight = false }
            if (offAt >= 0 && offAt >= (l?.second ?: -1)) { light = null; inheritedLight = false }
            emit(i)
        }
        return out
    }

    /** 只用上一章末尾完整段落；经编译器分开对白、提示语与容器，规则与本章一致。 */
    fun opening(history: List<String>): StageScene? {
        val body = history.lastOrNull() ?: return null
        val cut = (body.length - 2500).coerceAtLeast(0)
        val start = if (cut == 0) 0 else body.indexOf('\n', cut).let { if (it < 0) cut else it + 1 }
        val tail = body.substring(start)
        return StageCompiler.compile(0, "", tail).scenes.lastOrNull()?.copy(fromBeat = 0, mood = null)
            ?.takeIf { it.weather != null || it.setting != null || it.time != null || it.light != null }
    }

    /** 模糊称呼不计分，避免现代校园里的「师兄、小姐」把体裁翻成古风。 */
    fun era(body: String, history: List<String>): StageEra? {
        var m = modern.findAll(body).count()
        var a = ancient.findAll(body).count()
        for (chapter in history) { m += modern.findAll(chapter).count(); a += ancient.findAll(chapter).count() }
        return when {
            m >= 8 && m >= a * 2.5 -> StageEra.MODERN
            a >= 8 && a >= m * 2.5 -> StageEra.ANCIENT
            else -> null
        }
    }

    /** 一句里写到几种时，取最后写到的那种（「雨渐渐变成了雪」是雪）。 */
    private fun <T> latest(text: String, cues: List<Pair<T, Regex>>, locations: Boolean = false): Pair<T, Int>? {
        var best: T? = null
        var at = -1
        for ((value, re) in cues) {
            val m = re.findAll(text).lastOrNull {
                val prefix = text.substring((it.range.first - 4).coerceAtLeast(0), it.range.first)
                val before = text.substring(0, it.range.first)
                val clause = before.takeLast(40).substringAfterLast('。').substringAfterLast('\n')
                val after = text.substring(it.range.last + 1)
                !unreal.containsMatchIn(prefix) && !negated.containsMatchIn(prefix) && (!locations || (!exit.containsMatchIn(prefix) && !destination.containsMatchIn(prefix))) &&
                    !(value is StageLight && extinguish.containsMatchIn(prefix)) &&
                    !((locations || value is StageWeatherCue || value is StageLight || value is StageTime) && figurative.containsMatchIn(clause)) &&
                    !((value is StageWeatherCue || value is StageLight || value is StageTime) && recalled.containsMatchIn(before)) &&
                    !((locations || value is StageWeatherCue || value is StageLight) && (objectSuffix.containsMatchIn(after) || personSuffix.containsMatchIn(after))) &&
                    !(value is StageWeatherCue && surname.containsMatchIn(prefix)) &&
                    !(locations && (recalled.containsMatchIn(before) || borrowedSuffix.containsMatchIn(after) || ((it.value == "河边" || it.value == "树林") && surname.containsMatchIn(prefix)))) &&
                    !(value == StageSetting.INTERIOR && (after.startsWith("外") || (it.value == "门内" && sectSuffix.containsMatchIn(after)))) &&
                    !(value == StageSetting.INTERIOR && it.value == "门内" && !doorPresence.containsMatchIn(prefix)) &&
                    !(value == StageSetting.CAMPUS && nonCampus.containsMatchIn(clause)) &&
                    !(value == StageSetting.CAMPUS && it.value == "图书馆" && (after.startsWith("员") || after.startsWith("藏") || after.startsWith("所藏") || institution.containsMatchIn(prefix))) &&
                    !(value == StageSetting.MOUNTAIN && it.value == "山道" && prefix.endsWith("开门见")) &&
                    !(value == StageWeatherCue.SNOW && it.value == "下雪" && after.startsWith("亮")) &&
                    !(value == StageSetting.SEA && it.value == "海滩" && prefix.endsWith("上")) &&
                    !(value == StageSetting.VILLAGE && villageAttribute.containsMatchIn(after)) &&
                    !(value == StageSetting.DESERT && it.value == "黄沙" && !sandContext.containsMatchIn(clause) && !sandSuffix.containsMatchIn(after))
            } ?: continue
            // 雷雨与雨的同位置重叠由后列出的 STORM 胜出，其余仍按原文位置。
            if (m.range.first >= at) { at = m.range.first; best = value }
        }
        return best?.let { it to at }
    }

    /** 第 [index] 拍生效的场景。 */
    fun sceneAt(scenes: List<StageScene>, index: Int): StageScene? = scenes.lastOrNull { it.fromBeat <= index }
}
