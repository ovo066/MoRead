package com.mozhi.reader.core.stage.sound

import kotlin.math.*

internal enum class SoundVoice { PLUCK, EPIANO, BELL, PULSE, DROP, PAGE, THUNDER, HEART, IMPACT }

/** 固定声部池；尾音保留自己的包络，换曲时不会被新风格截断。 */
internal class StageVoices(private val rate: Int, seed: Long, count: Int) {
    private val random = SoundRandom(seed)
    private val voices = Array(count) { Voice(rate, SoundRandom(seed + it * 7919L)) }
    var l = 0f; private set
    var r = 0f; private set
    fun play(type: SoundVoice, hz: Float, amplitude: Float, seconds: Float, pan: Float = random.unit(), glide: Boolean = false) {
        var voice = voices[0]
        for (v in voices) {
            if (v.remaining == 0) { voice = v; break }
            if (v.level < voice.level) voice = v
        }
        voice.start(type, hz, amplitude, seconds, pan, glide)
    }
    fun next() {
        var a = 0f; var b = 0f
        for (v in voices) if (v.remaining > 0) {
            val x = v.next()
            a += x * v.panL; b += x * v.panR
        }
        l = a; r = b
    }
    private class Voice(val rate: Int, val random: SoundRandom) {
        private val delay = FloatArray(2048)
        private var write = 0
        private var length = 100f
        private var targetLength = 100f
        private var phase = 0f
        private var p2 = 0f; private var p3 = 0f; private var p4 = 0f
        private var step = 0f
        private var amp = 0f
        private var partial2 = 1f; private var partial3 = 1f; private var partial4 = 1f
        private var partialDecay2 = 1f; private var partialDecay3 = 1f; private var partialDecay4 = 1f
        private var decay = 0f
        private var age = 0
        private var total = 1
        private var brown = 0f
        private var low = 0f
        private var last = 0f
        private val band = SoundBandpass(rate).also { it.tune(3500f, .7f) }
        private val thunderMid = SoundBandpass(rate).also { it.tune(420f, .8f) }
        private val thunderCrack = SoundBandpass(rate).also { it.tune(2200f, .6f) }
        private var type = SoundVoice.BELL
        var remaining = 0
        var panL = 0f
        var panR = 0f
        val level get() = amp
        fun start(type: SoundVoice, hz: Float, amplitude: Float, seconds: Float, pan: Float, glide: Boolean) {
            this.type = type
            age = 0; total = maxOf(1, (seconds * rate).toInt()); remaining = total
            phase = 0f; p2 = 0f; p3 = 0f; p4 = 0f
            step = hz / rate; amp = amplitude
            decay = SoundMath.decay(seconds / 5f, rate)
            partial2 = 1f; partial3 = 1f; partial4 = 1f
            partialDecay2 = SoundMath.decay(seconds / 4f, rate)
            partialDecay3 = SoundMath.decay(seconds / 7f, rate)
            partialDecay4 = SoundMath.decay(seconds / 10f, rate)
            panL = sqrt(1 - pan); panR = sqrt(pan)
            brown = 0f; low = 0f; last = 0f
            if (type == SoundVoice.PLUCK) {
                targetLength = (rate / hz).coerceIn(4f, 2040f)
                length = targetLength * if (glide) 1.059463f else 1f
                var mean = 0f
                // 激励先低通，保留拨弦起音而压住过亮的白噪声。
                val excitation = SoundMath.lowpass((2500f + hz * 2).coerceAtMost(4000f), rate)
                var filteredNoise = 0f
                for (i in delay.indices) {
                    filteredNoise += excitation * (random.noise() - filteredNoise)
                    delay[i] = filteredNoise; mean += filteredNoise
                }
                mean /= delay.size
                for (i in delay.indices) delay[i] -= mean
                write = 0
            }
        }
        fun next(): Float {
            val t = age.toFloat() / rate
            val progress = age.toFloat() / total
            val x = when (type) {
                SoundVoice.PLUCK -> {
                    if (age < rate * .08f) length += (targetLength - length) * .003f
                    var read = write - length
                    if (read < 0) read += delay.size
                    val i = read.toInt()
                    val y = delay[i] + (delay[(i + 1) % delay.size] - delay[i]) * (read - i)
                    val filtered = (y + last) * .499f
                    last = y; delay[write] = filtered
                    if (++write == delay.size) write = 0
                    y * 1.8f
                }
                SoundVoice.EPIANO -> {
                    phase = SoundMath.wrap(phase + step * (1 + .0015f * SoundMath.sin(t * 4.7f)))
                    SoundMath.sin(phase) + .28f * partial2 * SoundMath.sin(phase * 2) +
                        .12f * partial3 * SoundMath.sin(phase * 3)
                }
                SoundVoice.BELL -> {
                    phase = SoundMath.wrap(phase + step)
                    p2 = SoundMath.wrap(p2 + step * 2.76f); p3 = SoundMath.wrap(p3 + step * 5.4f); p4 = SoundMath.wrap(p4 + step * 8.93f)
                    SoundMath.sin(phase) + .35f * partial2 * SoundMath.sin(p2) +
                        .15f * partial3 * SoundMath.sin(p3) + .06f * partial4 * SoundMath.sin(p4)
                }
                SoundVoice.PULSE, SoundVoice.IMPACT -> {
                    phase = SoundMath.wrap(phase + step * (1 + 1.6f * (1 - progress) * (1 - progress)))
                    // 二至四次泛音与短敲击让低鼓点在外放上仍能辨认。
                    val knock = SoundMath.sin(t * 1500f) * exp(-t * 65) * if (type == SoundVoice.IMPACT) 2.4f else 2f
                    .35f * SoundMath.sin(phase) + .22f * SoundMath.sin(phase * 2) +
                        .30f * SoundMath.sin(phase * 3) + .42f * SoundMath.sin(phase * 4) + knock
                }
                SoundVoice.DROP -> {
                    phase = SoundMath.wrap(phase + (500 + 700 * (1 - progress) * (1 - progress)) / rate)
                    SoundMath.sin(phase) * minOf(1f, t / .02f)
                }
                SoundVoice.PAGE -> band.next(random.noise()) * sin(PI * progress).toFloat()
                SoundVoice.THUNDER -> {
                    val w = random.noise()
                    brown = (brown + w * .045f) * .997f
                    low += .045f * (brown - low)
                    val roll = thunderMid.next(w) * .85f * (1f + .3f * SoundMath.sin(t * 2.3f))
                    val crack = thunderCrack.next(w) * .9f * exp(-t * 5).toFloat()
                    low * .20f + roll + crack
                }
                SoundVoice.HEART -> {
                    val second = t >= .26f
                    val local = if (second) t - .26f else t
                    if (local < .20f) {
                        phase = SoundMath.wrap(phase + (52f + 20f * (1 - local / .20f)) / rate)
                        val tone = .35f * SoundMath.sin(phase) + .22f * SoundMath.sin(phase * 2) +
                            .30f * SoundMath.sin(phase * 3) + .42f * SoundMath.sin(phase * 4)
                        val edge = minOf(1f, local / .004f)
                        val knock = SoundMath.sin(local * 1500) * exp(-local * 65).toFloat() * 2f * edge
                        (tone * exp(-local * 24).toFloat() + knock) * if (second) .65f else 1f
                    } else 0f
                }
            }
            val attack = minOf(1f, age.toFloat() / (rate * .003f))
            val release = minOf(1f, remaining.toFloat() / (rate * .015f))
            val result = x * amp * attack * release
            if (type != SoundVoice.HEART) amp *= decay
            partial2 *= partialDecay2; partial3 *= partialDecay3; partial4 *= partialDecay4
            age++; remaining--
            return result
        }
    }
}
