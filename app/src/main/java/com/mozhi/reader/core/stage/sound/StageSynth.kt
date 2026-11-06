package com.mozhi.reader.core.stage.sound

import kotlin.math.abs
import kotlin.math.exp

/** 单一渲染线程拥有 DSP 状态；跨线程调用方须在音频块边界转交命令。 */
class StageSynth(val sampleRate: Int = 32_000, seed: Long = 1L) {
    init { require(sampleRate in 16000..96000) }
    private val layers = Array(StageSoundLayer.entries.size) { StageAmbienceSynth(StageSoundLayer.entries[it], sampleRate, seed + 104729L * (it + 1)) }
    private val target = FloatArray(layers.size)
    private val volume = FloatArray(layers.size)
    private val musicSynth = StageMusicSynth(sampleRate, seed + 49999)
    private val cues = StageVoices(sampleRate, seed + 99991, 24)
    private var fade = smoothing(2.5f)
    private val volumeFade = smoothing(.12f)
    private var ambienceTarget = 1f
    private var musicTarget = 1f
    private var ambience = 1f
    private var music = 1f
    private val dc = exp(-2.0 * Math.PI * 20 / sampleRate).toFloat()
    private var previousL = 0f; private var previousR = 0f
    private var dcL = 0f; private var dcR = 0f

    fun setScene(scene: StageSoundScene, fadeSeconds: Float = 2.5f) {
        require(fadeSeconds.isFinite() && fadeSeconds >= 0)
        fade = smoothing(fadeSeconds)
        for (i in layers.indices) target[i] = clean(scene.layers[StageSoundLayer.entries[i]] ?: 0f)
        musicSynth.setStyle(scene.music)
    }

    fun setVolumes(ambience: Float, music: Float) {
        ambienceTarget = clean(ambience); musicTarget = clean(music)
    }

    fun trigger(cue: StageSoundCue) {
        when (cue) {
            StageSoundCue.INK_DROP -> cues.play(SoundVoice.DROP, 1200f, .3f, .30f, .5f)
            StageSoundCue.PAGE -> cues.play(SoundVoice.PAGE, 3500f, .4f, .12f, .5f)
            StageSoundCue.THUNDER -> cues.play(SoundVoice.THUNDER, 60f, .8f, 4.5f, .5f)
            StageSoundCue.HEARTBEAT -> cues.play(SoundVoice.HEART, 60f, .35f, .55f, .5f)
            StageSoundCue.IMPACT -> cues.play(SoundVoice.IMPACT, 50f, .5f, .4f, .5f)
            StageSoundCue.CHIME -> cues.play(SoundVoice.BELL, SoundMath.hz(84), .23f, 3.5f, .5f)
        }
    }

    /** 交错立体声；容量检查后仅使用预分配的滤波器、延迟线与声部池。 */
    fun render(out: FloatArray, frames: Int) {
        require(frames >= 0 && frames <= out.size / 2)
        for (frame in 0 until frames) {
            var l = 0f; var r = 0f
            for (i in layers.indices) {
                var v = volume[i] + fade * (target[i] - volume[i])
                if (target[i] == 0f && v < 1e-7f) v = 0f
                volume[i] = v
                if (v != 0f) { val layer = layers[i]; layer.next(); l += layer.l * v; r += layer.r * v }
            }
            cues.next()
            ambience += volumeFade * (ambienceTarget - ambience)
            music += volumeFade * (musicTarget - music)
            musicSynth.next()
            l = (l + cues.l) * ambience + musicSynth.l * music
            r = (r + cues.r) * ambience + musicSynth.r * music
            dcL = l - previousL + dc * dcL; previousL = l
            dcR = r - previousR + dc * dcR; previousR = r
            // 单调软限幅在任意叠加下都不越过 -1 dBFS，常用电平仍近似线性。
            out[frame * 2] = limit(dcL * .8f)
            out[frame * 2 + 1] = limit(dcR * .8f)
        }
    }

    private fun limit(x: Float) = .8912509f * x / (1 + abs(x))
    private fun smoothing(seconds: Float) = if (seconds <= 0) 1f else (1.0 - exp(-3.0 / (sampleRate * seconds))).toFloat()
    private fun clean(x: Float) = if (x.isFinite()) x.coerceIn(0f, 1f) else 0f
}
