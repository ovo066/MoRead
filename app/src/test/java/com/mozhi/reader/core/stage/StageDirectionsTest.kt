package com.mozhi.reader.core.stage

import org.junit.Assert.*
import org.junit.Test

class StageDirectionsTest {
    private fun script(body: String, parts: List<Pair<String, Int>>): StageScript = StageScript(
        0, "", listOf(StageBeat(BeatKind.TITLE, "", null)) + parts.map { (text, start) ->
            StageBeat(BeatKind.NARRATION, text, SourceRange(start, start + text.length))
        }, emptyList(), null, emptyList(), scenes = listOf(StageScene(0, StageWeatherCue.SNOW, StageMood.WARM, StageSetting.MOUNTAIN, StageTime.DAY, StageLight.LAMP))
    )

    @Test fun `非空段映射到首拍且空字段沿用各拍规则`() {
        val body = "前半。后半。\n\n  木门打开。\n永不回头"
        val parts = listOf("前半。", "后半。", "木门打开。", "永不回头").map { it to body.indexOf(it) }
        val source = script(body, parts).copy(scenes = listOf(
            StageScene(0, StageWeatherCue.SNOW, StageMood.WARM, StageSetting.MOUNTAIN, StageTime.DAY, StageLight.LAMP),
            StageScene(4, StageWeatherCue.RAIN, null, StageSetting.WATER, StageTime.DUSK, null)
        ))
        val result = StageDirectorResult(listOf(StageDirection(1, setting = StageSetting.INTERIOR)), emptyList())
        val merged = StageDirections.merge(source, body, result)
        assertEquals(StageSetting.MOUNTAIN, StageAtmosphere.sceneAt(merged.scenes, 2)?.setting)
        val scene = requireNotNull(StageAtmosphere.sceneAt(merged.scenes, 3))
        assertEquals(StageSetting.INTERIOR, scene.setting)
        assertEquals(StageWeatherCue.SNOW, scene.weather)
        assertEquals(StageMood.WARM, scene.mood)
        val after = requireNotNull(StageAtmosphere.sceneAt(merged.scenes, 4))
        assertEquals(StageSetting.INTERIOR, after.setting)
        assertEquals(StageTime.DUSK, after.time)
        assertEquals(StageWeatherCue.RAIN, after.weather)
        assertNull(after.light)
    }

    @Test fun `异步合并保留已演拍且下一变化点的空字段回到规则`() {
        val body = "甲\n乙\n丙\n丁"
        val source = script(body, listOf("甲", "乙", "丙", "丁").map { it to body.indexOf(it) })
        val result = StageDirectorResult(listOf(StageDirection(0, setting = StageSetting.CITY), StageDirection(3, time = StageTime.NIGHT)), emptyList())
        val merged = StageDirections.merge(source, body, result, fromBeat = 3)
        for (i in 0..2) assertEquals(StageAtmosphere.sceneAt(source.scenes, i)?.copy(fromBeat = i), StageAtmosphere.sceneAt(merged.scenes, i)?.copy(fromBeat = i))
        assertEquals(StageSetting.CITY, StageAtmosphere.sceneAt(merged.scenes, 3)?.setting)
        assertEquals(StageSetting.MOUNTAIN, StageAtmosphere.sceneAt(merged.scenes, 4)?.setting)
        assertEquals(StageTime.NIGHT, StageAtmosphere.sceneAt(merged.scenes, 4)?.time)
        assertEquals(source.beats, merged.beats)
    }

    @Test fun `情绪只覆盖十四拍并在到期后沿用规则`() {
        val body = (0 until 20).joinToString("\n") { "第${it}段" }
        val source = script(body, StageDirections.paragraphs(body).map { it.text to it.start })
        val result = StageDirectorResult(listOf(StageDirection(0, mood = StageMood.EERIE)), emptyList())
        val merged = StageDirections.merge(source, body, result, fromBeat = 5)
        assertEquals(StageMood.WARM, StageAtmosphere.sceneAt(merged.scenes, 4)?.mood)
        assertEquals(StageMood.EERIE, StageAtmosphere.sceneAt(merged.scenes, 5)?.mood)
        assertEquals(StageMood.EERIE, StageAtmosphere.sceneAt(merged.scenes, 14)?.mood)
        assertEquals(StageMood.WARM, StageAtmosphere.sceneAt(merged.scenes, 15)?.mood)
    }

    @Test fun `海报要求去首尾标点后完全逐字相同且不覆盖已有特写容器或已演拍`() {
        val body = "永不回头。\n永不回头，还有明天。\n此生无悔。\n我会回来。\n留在这里。"
        val source = script(body, StageDirections.paragraphs(body).map { it.text to it.start })
        val beats = source.beats.mapIndexed { i, beat -> when (i) {
            3 -> beat.copy(containerId = 7)
            4 -> beat.copy(figure = StageFigure.STACK)
            else -> beat
        } }
        val result = StageDirectorResult(listOf(StageDirection(99, setting = StageSetting.CITY)), listOf(
            StageKeyLine(0, "永不回头"), StageKeyLine(1, "永不回头"), StageKeyLine(2, "此生无悔"),
            StageKeyLine(3, "我会回来"), StageKeyLine(4, "留在这儿")
        ))
        val merged = StageDirections.merge(source.copy(beats = beats), body, result)
        assertEquals(StageFigure.POSTER, merged.beats[1].figure)
        assertEquals(StageSource.DIRECTOR, merged.beats[1].sources["figure"])
        assertNull(merged.beats[2].figure)
        assertNull(merged.beats[3].figure)
        assertEquals(StageFigure.STACK, merged.beats[4].figure)
        assertNull(merged.beats[5].figure)
        assertNull(StageDirections.merge(source, body, result, 2).beats[1].figure)
        assertEquals(source.beats.map { it.text }, merged.beats.map { it.text })
    }
    @Test fun `空结果与章末返回不变且没有场景的已演拍不会被补事件`() {
        val body = "甲\n乙"
        val source = script(body, listOf("甲" to 0, "乙" to 2)).copy(scenes = emptyList())
        assertEquals(source, StageDirections.merge(source, body, StageDirectorResult(emptyList(), emptyList())))
        val result = StageDirectorResult(listOf(StageDirection(0, setting = StageSetting.CITY)), emptyList())
        assertEquals(source, StageDirections.merge(source, body, result, source.beats.size))
        val merged = StageDirections.merge(source, body, result, 2)
        assertNull(StageAtmosphere.sceneAt(merged.scenes, 1))
        assertEquals(StageSetting.CITY, StageAtmosphere.sceneAt(merged.scenes, 2)?.setting)
    }

}
