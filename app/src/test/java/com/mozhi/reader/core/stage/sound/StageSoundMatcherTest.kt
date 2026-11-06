package com.mozhi.reader.core.stage.sound

import com.mozhi.reader.core.stage.*
import org.junit.Assert.*
import org.junit.Test

class StageSoundMatcherTest {
    private fun scene(weather: StageWeatherCue? = null, setting: StageSetting? = null, time: StageTime? = StageTime.DAY, light: StageLight? = null, mood: StageMood? = null) =
        StageScene(0, weather, mood, setting, time, light)
    private fun layers(vararg values: Pair<StageSoundLayer, Float>) = mapOf(*values)

    @Test fun `所有天气匹配且花瓣仅白天有鸟`() {
        val expected = listOf(
            layers(StageSoundLayer.RAIN to .7f),
            layers(StageSoundLayer.HEAVY_RAIN to .8f, StageSoundLayer.WIND to .3f),
            layers(StageSoundLayer.WIND to .25f), layers(StageSoundLayer.WIND to .15f),
            layers(StageSoundLayer.WIND to .2f, StageSoundLayer.BIRDS to .25f),
            layers(StageSoundLayer.WIND to .35f, StageSoundLayer.LEAVES to .4f),
            emptyMap(), layers(StageSoundLayer.WIND to .5f)
        )
        for ((i, weather) in StageWeatherCue.entries.withIndex()) assertEquals(weather.name, expected[i], StageSoundMatcher.of(scene(weather = weather), null).layers)
        assertEquals(layers(StageSoundLayer.WIND to .2f), StageSoundMatcher.of(scene(weather = StageWeatherCue.PETALS, time = StageTime.NIGHT), null).layers)
    }

    @Test fun `所有布景昼夜层与光源取最大值`() {
        val day = listOf(
            layers(StageSoundLayer.WIND to .35f, StageSoundLayer.BIRDS to .3f), layers(StageSoundLayer.STREAM to .5f),
            layers(StageSoundLayer.WAVES to .7f), layers(StageSoundLayer.LEAVES to .35f, StageSoundLayer.WIND to .2f, StageSoundLayer.BIRDS to .45f),
            layers(StageSoundLayer.LEAVES to .35f, StageSoundLayer.WIND to .2f, StageSoundLayer.BIRDS to .45f),
            layers(StageSoundLayer.CITY to .55f), emptyMap(), layers(StageSoundLayer.WIND to .15f, StageSoundLayer.CHIMES to .35f),
            layers(StageSoundLayer.BIRDS to .3f), layers(StageSoundLayer.BIRDS to .4f), layers(StageSoundLayer.WIND to .55f)
        )
        val insects = mapOf(StageSetting.MOUNTAIN to .35f, StageSetting.FOREST to .5f, StageSetting.BAMBOO to .5f, StageSetting.CAMPUS to .3f, StageSetting.VILLAGE to .55f)
        for ((i, setting) in StageSetting.entries.withIndex()) {
            assertEquals(setting.name, day[i], StageSoundMatcher.of(scene(setting = setting), null).layers)
            val atNight = day[i].toMutableMap().apply {
                remove(StageSoundLayer.BIRDS)
                insects[setting]?.let { put(StageSoundLayer.INSECTS, it) }
            }
            assertEquals(atNight, StageSoundMatcher.of(scene(setting = setting, time = StageTime.NIGHT), null).layers)
        }
        assertEquals(.15f, StageSoundMatcher.of(scene(setting = StageSetting.INTERIOR, light = StageLight.CANDLE), null).layers[StageSoundLayer.FIRE])
        assertEquals(.5f, StageSoundMatcher.of(scene(setting = StageSetting.INTERIOR, light = StageLight.FIRE), null).layers[StageSoundLayer.FIRE])
        assertEquals(.5f, StageSoundMatcher.of(scene(setting = StageSetting.SEA, light = StageLight.FIRE), null).layers[StageSoundLayer.FIRE])
        val mixed = StageSoundMatcher.of(scene(weather = StageWeatherCue.LEAVES, setting = StageSetting.FOREST), null)
        assertEquals(.35f, mixed.layers[StageSoundLayer.WIND]); assertEquals(.4f, mixed.layers[StageSoundLayer.LEAVES])
        val unknownTime = StageSoundMatcher.of(scene(setting = StageSetting.VILLAGE, time = null), null)
        assertTrue(unknownTime.layers.isEmpty())
    }

    @Test fun `情绪优先以及年代与布景配乐回落`() {
        val expected = listOf(StageMusic.SUSPENSE, StageMusic.TENDER, StageMusic.TENSION, StageMusic.TENDER, StageMusic.SORROW)
        for ((i, mood) in StageMood.entries.withIndex()) assertEquals(expected[i], StageSoundMatcher.of(scene(mood = mood), StageEra.ANCIENT).music)
        for (setting in listOf(StageSetting.MOUNTAIN, StageSetting.WATER, StageSetting.FOREST, StageSetting.BAMBOO, StageSetting.CLASSICAL, StageSetting.VILLAGE, StageSetting.DESERT)) {
            assertEquals(StageMusic.LANDSCAPE, StageSoundMatcher.of(scene(setting = setting), StageEra.MODERN).music)
        }
        for (setting in listOf(StageSetting.CITY, StageSetting.INTERIOR, StageSetting.CAMPUS)) assertEquals(StageMusic.NOCTURNE, StageSoundMatcher.of(scene(setting = setting), null).music)
        assertEquals(StageMusic.LANDSCAPE, StageSoundMatcher.of(scene(setting = StageSetting.CITY), StageEra.ANCIENT).music)
        assertEquals(StageMusic.NOCTURNE, StageSoundMatcher.of(scene(), StageEra.MODERN).music)
        assertEquals(StageMusic.LANDSCAPE, StageSoundMatcher.of(scene(), null).music)
        assertEquals(StageSoundScene(music = StageMusic.LANDSCAPE), StageSoundMatcher.of(null, StageEra.MODERN))
    }
}
