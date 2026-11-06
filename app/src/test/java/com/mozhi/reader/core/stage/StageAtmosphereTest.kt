package com.mozhi.reader.core.stage

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class StageAtmosphereTest {
    private fun beat(text: String, kind: BeatKind = BeatKind.NARRATION) = StageBeat(kind, text, null)
    private fun scene(text: String) = StageAtmosphere.scan(listOf(beat(text))).last()
    private val settings = listOf("山路", "江边", "海边", "森林", "竹林", "街道", "房间", "宫殿", "教室", "村口", "沙漠")
    private val times = listOf("清晨", "中午", "黄昏", "深夜")
    private val lights = listOf("烛光", "台灯", "篝火", "霓虹")
    private val weather = listOf("下雨", "雷雨", "雪花", "浓雾", "落花", "落叶", "萤火虫", "沙尘")
    private val moods = listOf("阴森", "暖意", "杀气", "亲吻", "泪水")

    @Test fun `各布景正例与对白心声反例`() {
        for ((i, text) in settings.withIndex()) {
            assertEquals(text, StageSetting.entries[i], scene("她站在$text。 ").setting)
            for (kind in listOf(BeatKind.DIALOGUE, BeatKind.THOUGHT)) {
                assertNull(StageAtmosphere.scan(listOf(beat("我们去$text 吧", kind))).last().setting)
            }
        }
        for (text in listOf("人山人海，开门见山，高山仰止。", "她开门见山道：这事怎么办。", "她走出房间。", "他离开了屋内。", "她冲出卧室。", "好像海边一样。", "图书馆员拿起书。")) assertNull(text, scene(text).setting)
        // 馆藏与机构名不是读者所在的校园；古风书里不出现校园。
        for (text in listOf("此本原藏北京大学图书馆。", "今存首都图书馆藏本。", "图书馆藏有一部抄本。")) assertNull(text, scene(text).setting)
        assertEquals(StageSetting.CAMPUS, scene("她走进图书馆。").setting)
        assertNotEquals(StageSetting.CAMPUS, StageAtmosphere.scan(listOf(beat("他坐在教室里。")), era = StageEra.ANCIENT).last().setting)
    }

    @Test fun `各时段光源正反例与同句末次命中`() {
        for ((i, text) in times.withIndex()) {
            assertEquals(StageTime.entries[i], scene(text).time)
            assertNull(StageAtmosphere.scan(listOf(beat(text, BeatKind.DIALOGUE))).last().time)
        }
        for ((i, text) in lights.withIndex()) {
            assertEquals(StageLight.entries[i], scene(text).light)
            assertNull(StageAtmosphere.scan(listOf(beat(text, BeatKind.THOUGHT))).last().light)
        }
        val last = scene("清晨走过竹林，黄昏到了江边，先点蜡烛再开台灯。")
        assertEquals(StageSetting.WATER, last.setting)
        assertEquals(StageTime.DUSK, last.time)
        assertEquals(StageLight.LAMP, last.light)
        assertNull(scene("眼神像春天里的一缕晨光。").time)
        assertNull(scene("她回忆往事，那时台灯照着窗前。").light)
    }

    @Test fun `各天气情绪正反例与雷雨优先`() {
        for ((i, text) in weather.withIndex()) assertEquals(text, StageWeatherCue.entries[i], scene(text).weather)
        for ((i, text) in moods.withIndex()) assertEquals(text, StageMood.entries[i], scene(text).mood)
        val neutral = scene("好冷，他翻云覆雨地说了几句，阳光照着笑容，大家纷纷点头。")
        assertNull(neutral.weather); assertNull(neutral.mood)
        assertNull(scene("吻合的答案").mood)
        assertEquals(StageWeatherCue.STORM, scene("雷雨").weather)
        assertEquals(StageWeatherCue.SNOW, scene("雨渐渐变成了雪花").weather)
        assertEquals(StageMood.SORROW, scene("亲吻后眼眶发红").mood)
        for (text in listOf("陈雪花拿起书。", "雪花姐拿起书。", "雪花膏摆在桌上。", "白雪公主坐下来。", "赵雨声点头。", "心情如雨水一般倾泻。", "他心下雪亮。")) assertNull(text, scene(text).weather)
        assertNull(scene("她抚摸火焰珠上的纹路。").light)
        assertNull(scene("没有下雨，也没有杀气。").weather)
        assertNull(scene("没有下雨，也没有杀气。").mood)
    }

    @Test fun `持续衰减与容器隔离`() {
        val beats = listOf(beat("深夜，房间里点了烛光，下雨，阴森。")) +
            List(15) { beat("她看了看他。") } + beat("海边", BeatKind.NARRATION).copy(containerId = 1)
        val scenes = StageAtmosphere.scan(beats)
        assertEquals(StageMood.EERIE, StageAtmosphere.sceneAt(scenes, 13)?.mood)
        assertNull(StageAtmosphere.sceneAt(scenes, 14)?.mood)
        assertEquals(StageSetting.INTERIOR, scenes.last().setting)
        assertEquals(StageTime.NIGHT, scenes.last().time)
        assertEquals(StageLight.CANDLE, scenes.last().light)
        assertEquals(StageWeatherCue.RAIN, scenes.last().weather)
        val reset = StageAtmosphere.scan(beats + beat("", BeatKind.BREAK)).last()
        assertEquals(StageScene(beats.size, null, null), reset)
        val renewed = StageAtmosphere.scan(beats.take(13) + beat("阴森") + List(14) { beat("她坐着") })
        assertEquals(StageMood.EERIE, StageAtmosphere.sceneAt(renewed, 26)?.mood)
        assertNull(StageAtmosphere.sceneAt(renewed, 27)?.mood)
    }

    @Test fun `重置按文字先后执行且换地点熄灯`() {
        fun last(vararg text: String) = StageAtmosphere.scan(text.map { beat(it) }).last()
        assertNull(last("下雨", "雨停了").weather)
        assertNull(last("风雪", "风雪渐停").weather)
        assertNull(last("下雨", "已经不再下雨").weather)
        assertEquals(StageWeatherCue.RAIN, last("雨停了，后来又下雨").weather)
        assertNull(last("深夜点起烛光", "第二天她起身").time)
        assertEquals(StageTime.DAWN, last("深夜", "翌日清晨").time)
        assertNull(last("屋内有台灯", "她走到江边").light)
        assertNull(last("灯光照着窗前", "灯光暗下").light)
        for (word in listOf("灯灭", "熄灭", "吹灭", "关灯")) assertNull(last("烛光", word).light)
        assertNull(last("烛光", "吹灭蜡烛").light)
        assertEquals(StageLight.LAMP, last("吹灭蜡烛后打开灯").light)
        assertEquals(StageLight.CANDLE, last("烛光照着房间").light)
        val title = StageAtmosphere.scan(listOf(beat("下雨，房间点起台灯"), beat("章名", BeatKind.TITLE))).last()
        assertNull(title.weather); assertNull(title.setting); assertNull(title.light)
    }

    @Test fun `提示语可认地点而容器里的标题与分隔不能重置`() {
        val b = beat("我们去海边吧", BeatKind.DIALOGUE).copy(asideBefore = StageAside("她在竹林里说：", SourceRange(0, 8)))
        assertEquals(StageSetting.BAMBOO, StageAtmosphere.scan(listOf(b)).last().setting)
        assertEquals(StageSetting.BAMBOO, StageAtmosphere.scan(listOf(b, beat("", BeatKind.BREAK).copy(containerId = 1))).last().setting)
    }

    @Test fun `比喻物品画面回忆计划与人名不触发布景`() {
        for (text in listOf(
            "情绪像海浪一般涌来。", "数据如潮涨的海水起伏。", "他穿着沙滩裤。", "桌上摆着竹林七贤的雕件。",
            "她画的是远处的竹林。", "纸上的玉牌图案描绘出楼阁。", "他梦到在沙漠奔跑。", "她想起昨夜，海面一片漆黑。",
            "他们计划明晚，到车站见面。", "他来自乡下男孩的家乡。", "单位食堂送来午饭。", "台前的宣讲台空着。",
            "赵河边寄来一封信。", "门内弟子聚齐。", "他从袋子里倒出黄沙。", "他翻开《上海滩》。",
            "他被逼到悬崖边缘。", "他讲述一个有关森林的故事。"
        )) assertNull(text, scene(text).setting)
        assertEquals(StageSetting.DESERT, scene("四周黄沙漫漫。").setting)
        assertEquals(StageSetting.BAMBOO, scene("他走进竹林。").setting)
        assertNull(scene("他门内声望甚高。").setting)
        assertEquals(StageSetting.INTERIOR, scene("她站在门内。").setting)
    }

    @Test fun `上一章承接不受章名影响且不承接情绪`() {
        val opening = StageAtmosphere.opening(listOf("海边。", "深夜，她在房间里点起烛光。\n雨声传来，她落泪了。"))!!
        assertEquals(StageWeatherCue.RAIN, opening.weather)
        assertEquals(StageSetting.INTERIOR, opening.setting)
        assertEquals(StageTime.NIGHT, opening.time)
        assertEquals(StageLight.CANDLE, opening.light)
        assertNull(opening.mood)
        val beats = listOf(beat("新的一章", BeatKind.TITLE), beat("她不说话。"), beat("她走到山路上。"), beat("", BeatKind.BREAK))
        val scan = StageAtmosphere.scan(beats, opening)
        assertEquals(opening, scan.first())
        assertEquals(StageSetting.MOUNTAIN, StageAtmosphere.sceneAt(scan, 2)?.setting)
        assertNull(scan.last().weather); assertNull(scan.last().time)
        assertNull(StageAtmosphere.opening(emptyList()))
        assertNull(StageAtmosphere.opening(listOf("海边", "普通文字。\n".repeat(500)))?.setting)
        assertNull(StageAtmosphere.opening(listOf("她说：“我们去海边吧。”"))?.setting)
    }

    @Test fun `年代阈值与歧义词不计分`() {
        assertEquals(StageEra.MODERN, StageAtmosphere.era("手机电脑微信短信地铁出租车公司老板", emptyList()))
        assertEquals(StageEra.ANCIENT, StageAtmosphere.era("殿下王爷皇上陛下娘娘本宫宫女侍卫", emptyList()))
        assertEquals(StageEra.MODERN, StageAtmosphere.era("手机", listOf("电脑微信短信地铁出租车公司老板")))
        assertNull(StageAtmosphere.era("师兄小姐姑娘".repeat(20), emptyList()))
        assertNull(StageAtmosphere.era("手机电脑微信短信", emptyList()))
        assertNull(StageAtmosphere.era("手机".repeat(8) + "殿下".repeat(4), emptyList()))
        assertNull(StageAtmosphere.era("手机".repeat(8) + "殿下".repeat(8), emptyList()))
        val attic = listOf(beat("阁楼"))
        assertEquals(StageSetting.CLASSICAL, StageAtmosphere.scan(attic, era = StageEra.ANCIENT).last().setting)
        assertEquals(StageSetting.INTERIOR, StageAtmosphere.scan(attic).last().setting)
        val campus = listOf(beat("教室")) + List(29) { beat("她坐着") } + beat("走廊")
        assertEquals(StageSetting.CAMPUS, StageAtmosphere.scan(campus, era = StageEra.MODERN).last().setting)
        assertEquals(StageSetting.CAMPUS, StageAtmosphere.scan(listOf(beat("教室旁的走廊")), era = StageEra.MODERN).last().setting)
        assertEquals(StageSetting.INTERIOR, StageAtmosphere.scan(campus + beat("走廊"), era = StageEra.MODERN).last().setting)
    }

    @Test fun `任意前缀与整章相同不前瞻`() {
        val random = Random(701)
        val words = settings + times + lights + weather + moods + listOf("第二天", "雨停了", "灯光暗下", "她坐着")
        repeat(30) {
            val beats = List(45) {
                beat(words.random(random), BeatKind.entries.take(9).random(random)).copy(containerId = if (random.nextInt(6) == 0) 1 else null)
            }
            val opening = StageScene(0, StageWeatherCue.RAIN, null, StageSetting.CITY, StageTime.NIGHT, StageLight.NEON)
            val full = StageAtmosphere.scan(beats, opening)
            for (n in 0..beats.size) assertEquals("前缀 $n", full.filter { it.fromBeat < n }, StageAtmosphere.scan(beats.take(n), opening))
        }
        val body = "她站在山路。\n清晨下雨。\n她说：“去海边吧。”\n黄昏雨停。"
        val compiled = StageCompiler.compile(1, "章名", body)
        for (n in compiled.beats.indices) assertEquals(compiled.scenes.filter { it.fromBeat < n }, StageAtmosphere.scan(compiled.beats.take(n)))
    }
}
