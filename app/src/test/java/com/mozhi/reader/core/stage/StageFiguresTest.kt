package com.mozhi.reader.core.stage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StageFiguresTest {
    private fun posters(body: String): List<String> =
        StageCompiler.compile(0, "", body).beats.filter { it.figure == StageFigure.POSTER }.map { it.text }

    @Test fun `只有三三四四的对仗才做海报`() {
        assertEquals(3, StageFigures.posterStrength("粗看离谱，细看合理。"))
        assertEquals(3, StageFigures.posterStrength("见一面，少一面。"))
        assertEquals(3, StageFigures.posterStrength("柜门关上，房门打开。"))
        assertEquals(2, StageFigures.posterStrength("潮水摇摆，青山不动。"))
        assertEquals(2, StageFigures.posterStrength("名满江湖，出人头地！"))
        // 惊叹、叙述长句、人名称呼、拟声、西文数字、问句与省略都不够格。
        for (text in listOf(
            "什么！", "快跑！", "天下第一！", "此仇不报非君子！", "我来了！", "你疯了吗！", "砰！砰！砰！", "滋滋滋滋滋！",
            "胖子脸色一变，快速往里走去。", "黄毛站起身，往小屋走去。", "傅文斐垂下眼，转头看向怀姣。",
            "邓恩·史密斯！", "足足100镑！", "tls123，欢迎加入！", "不行……必须……自救！", "真的假的？！",
            "现在想想，有点后悔。", "快跑，快跑。"
        )) assertEquals(text, 0, StageFigures.posterStrength(text))
    }

    @Test fun `对白与夹在段落里的句子不做海报`() {
        assertTrue(posters("“粗看离谱，细看合理。”").isEmpty())
        assertTrue(posters("他想了想，粗看离谱，细看合理。众人哗然。").isEmpty())
        assertEquals(listOf("潮水摇摆，青山不动。"), posters("他在岸边站了很久。\n潮水摇摆，青山不动。\n他终于转身离开。"))
    }

    @Test fun `每章至多一张海报只提对得最紧的`() {
        val lines = buildList {
            repeat(120) { add(if (it % 20 == 10) "潮水摇摆，青山不动。" else "他慢慢走过长街，雨还在下。") }
            add("粗看离谱，细看合理。")
        }
        val beats = StageCompiler.compile(0, "", lines.joinToString("\n")).beats
        assertTrue(beats.count { it.figure == StageFigure.POSTER } > 3)
        fun picked(b: List<StageBeat>, intensity: StageIntensity) = StageEffectBudget.figures(b, intensity).filter { b[it].figure == StageFigure.POSTER }
        assertEquals(listOf("粗看离谱，细看合理。"), picked(beats, StageIntensity.STANDARD).map { beats[it].text })
        assertEquals(listOf("粗看离谱，细看合理。"), picked(beats, StageIntensity.LAVISH).map { beats[it].text })
        assertTrue(picked(beats, StageIntensity.RESTRAINED).isEmpty())
        // 只是字数对得上的四四句分不出好坏：哪一档都不自己提，交给 AI 导演。
        val plain = StageCompiler.compile(0, "", List(60) { if (it == 30) "潮水摇摆，青山不动。" else "他慢慢走过长街，雨还在下。" }.joinToString("\n")).beats
        assertTrue(picked(plain, StageIntensity.LAVISH).isEmpty())
        // 两句都对得紧、相隔够远：标准档一张，华丽档两张。
        val two = StageCompiler.compile(0, "", List(90) {
            when (it) { 10 -> "见一面，少一面。"; 80 -> "粗看离谱，细看合理。"; else -> "他慢慢走过长街，雨还在下。" }
        }.joinToString("\n")).beats
        assertEquals(1, picked(two, StageIntensity.STANDARD).size)
        assertEquals(2, picked(two, StageIntensity.LAVISH).size)
    }

    /** 本地统计：真书里每章会出几张海报、是哪些句子（只打印到测试输出）。 */
    @Test fun `真书海报频率`() {
        val raw = System.getenv("MOREAD_STAGE_TEXT_DIRS")
        assumeTrue("未配置本地真书目录", !raw.isNullOrBlank())
        for (path in requireNotNull(raw).split(File.pathSeparator).filter(String::isNotBlank)) {
            val files = File(path).listFiles().orEmpty().filter { it.extension == "txt" }.sortedBy { it.name }
            for (intensity in listOf(StageIntensity.STANDARD, StageIntensity.LAVISH)) {
                var shown = 0
                val samples = mutableListOf<String>()
                for ((index, file) in files.withIndex()) {
                    val text = file.readText().removePrefix("﻿")
                    val script = StageCompiler.compile(index, text.substringBefore('\n'), text.substringAfter('\n', ""))
                    val picked = StageEffectBudget.figures(script.beats, intensity).filter { script.beats[it].figure == StageFigure.POSTER }
                    shown += picked.size
                    if (samples.size < 14) picked.forEach { samples += script.beats[it].text }
                }
                println("${File(path).name} $intensity: chapters=${files.size} posters=$shown samples=$samples")
            }
        }
    }
}
