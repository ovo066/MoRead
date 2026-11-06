package com.mozhi.reader.core.stage.sound

import com.mozhi.reader.core.stage.*

enum class StageSoundLayer { RAIN, HEAVY_RAIN, WIND, WAVES, STREAM, INSECTS, BIRDS, FIRE, CITY, LEAVES, CHIMES }
enum class StageMusic { LANDSCAPE, NOCTURNE, SUSPENSE, TENDER, SORROW, TENSION }
enum class StageSoundCue { INK_DROP, PAGE, THUNDER, HEARTBEAT, IMPACT, CHIME }

/** 目标电平是相对混音比例，实际声压由合成器统一约束。 */
data class StageSoundScene(val layers: Map<StageSoundLayer, Float> = emptyMap(), val music: StageMusic? = null)

object StageSoundMatcher {
    fun of(scene: StageScene?, era: StageEra?): StageSoundScene {
        if (scene == null) return StageSoundScene(music = StageMusic.LANDSCAPE)
        val layers = mutableMapOf<StageSoundLayer, Float>()
        fun add(layer: StageSoundLayer, volume: Float) { layers[layer] = maxOf(layers[layer] ?: 0f, volume) }
        val night = scene.time == StageTime.NIGHT
        val day = scene.time != null && !night
        fun wildlife(birds: Float, insects: Float) {
            if (night) add(StageSoundLayer.INSECTS, insects) else if (day) add(StageSoundLayer.BIRDS, birds)
        }
        when (scene.weather) {
            StageWeatherCue.RAIN -> add(StageSoundLayer.RAIN, .7f)
            StageWeatherCue.STORM -> { add(StageSoundLayer.HEAVY_RAIN, .8f); add(StageSoundLayer.WIND, .3f) }
            StageWeatherCue.SNOW -> add(StageSoundLayer.WIND, .25f)
            StageWeatherCue.FOG -> add(StageSoundLayer.WIND, .15f)
            StageWeatherCue.LEAVES -> { add(StageSoundLayer.WIND, .35f); add(StageSoundLayer.LEAVES, .4f) }
            StageWeatherCue.PETALS -> { add(StageSoundLayer.WIND, .2f); if (day) add(StageSoundLayer.BIRDS, .25f) }
            StageWeatherCue.DUST -> add(StageSoundLayer.WIND, .5f)
            else -> Unit
        }
        when (scene.setting) {
            StageSetting.MOUNTAIN -> { add(StageSoundLayer.WIND, .35f); wildlife(.3f, .35f) }
            StageSetting.WATER -> add(StageSoundLayer.STREAM, .5f)
            StageSetting.SEA -> add(StageSoundLayer.WAVES, .7f)
            StageSetting.FOREST, StageSetting.BAMBOO -> {
                add(StageSoundLayer.LEAVES, .35f); add(StageSoundLayer.WIND, .2f); wildlife(.45f, .5f)
            }
            StageSetting.CITY -> add(StageSoundLayer.CITY, .55f)
            StageSetting.INTERIOR -> when (scene.light) {
                StageLight.FIRE -> add(StageSoundLayer.FIRE, .35f)
                StageLight.CANDLE -> add(StageSoundLayer.FIRE, .15f)
                else -> Unit
            }
            StageSetting.CLASSICAL -> { add(StageSoundLayer.WIND, .15f); add(StageSoundLayer.CHIMES, .35f) }
            StageSetting.CAMPUS -> wildlife(.3f, .3f)
            StageSetting.VILLAGE -> wildlife(.4f, .55f)
            StageSetting.DESERT -> add(StageSoundLayer.WIND, .55f)
            null -> Unit
        }
        if (scene.light == StageLight.FIRE) add(StageSoundLayer.FIRE, .5f)
        val music = when (scene.mood) {
            StageMood.EERIE -> StageMusic.SUSPENSE
            StageMood.TENSE -> StageMusic.TENSION
            StageMood.TENDER, StageMood.WARM -> StageMusic.TENDER
            StageMood.SORROW -> StageMusic.SORROW
            null -> when {
                era == StageEra.ANCIENT || scene.setting in landscape -> StageMusic.LANDSCAPE
                era == StageEra.MODERN || scene.setting in urban -> StageMusic.NOCTURNE
                else -> StageMusic.LANDSCAPE
            }
        }
        return StageSoundScene(layers, music)
    }

    private val landscape = setOf(StageSetting.MOUNTAIN, StageSetting.WATER, StageSetting.BAMBOO, StageSetting.FOREST, StageSetting.CLASSICAL, StageSetting.VILLAGE, StageSetting.DESERT)
    private val urban = setOf(StageSetting.CITY, StageSetting.INTERIOR, StageSetting.CAMPUS)
}
