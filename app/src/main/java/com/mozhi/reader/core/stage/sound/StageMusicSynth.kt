package com.mozhi.reader.core.stage.sound

/** 配乐只在事件到期时选音；两组铺底交叉淡化，所有旧声部继续走完余音。 */
internal class StageMusicSynth(private val rate: Int, seed: Long) {
    private val random = SoundRandom(seed)
    private val voices = StageVoices(rate, seed + 991, 24)
    private val pads = Array(2) { Pad(rate) }
    private val reverb = StageReverb(rate)
    private val transpose = intArrayOf(0, 2, 5, 7)[(random.unit() * 4).toInt()]
    private var style: StageMusic? = null
    private var padIndex = 0
    private var noteWait = 0
    private var harmonyWait = 0
    private var chord = 0
    private var note = 0
    private var phraseLeft = 0
    private var melody = 2
    private var bassPhase = 0f
    private var fifthPhase = 0f
    private var dissonancePhase = 0f
    private var drone = 0f
    private var droneTarget = 0f
    private var droneStep = 55f / rate
    private var droneLow = 0f
    private val droneLp = SoundMath.lowpass(800f, rate)
    private val noiseBand = SoundBandpass(rate).also { it.tune(650f, .8f) }
    private var noise = 0f
    private var noisePhase = 0f
    private val lp = SoundMath.lowpass(6000f, rate)
    private val slow = SoundMath.lowpass(.12f, rate)
    private var lowL = 0f; private var lowR = 0f
    var l = 0f; private set
    var r = 0f; private set

    fun setStyle(next: StageMusic?) {
        if (next == style) return
        style = next
        pads[padIndex].target = 0f
        padIndex = 1 - padIndex
        pads[padIndex].target = 0f
        chord = 0; note = 0; phraseLeft = 0; harmonyWait = 0
        noteWait = (rate * random.between(1f, 2f)).toInt()
        droneTarget = if (next == StageMusic.SUSPENSE || next == StageMusic.TENSION) .026f else 0f
        droneStep = SoundMath.hz(33 + transpose) / rate
    }

    fun next() {
        val s = style
        if (s != null) {
            if (--harmonyWait <= 0) {
                val root = when (s) {
                    StageMusic.SORROW -> 45 + transpose + MINOR_ROOTS[chord % 4]
                    StageMusic.TENDER -> 48 + transpose + ROOTS[chord % 4]
                    StageMusic.SUSPENSE, StageMusic.TENSION -> 36 + transpose
                    else -> 48 + transpose + ROOTS[chord % 4]
                }
                pads[padIndex].tune(root, if (s == StageMusic.SORROW) 3 else 7)
                pads[padIndex].target = when (s) {
                    StageMusic.SUSPENSE -> .005f
                    StageMusic.TENSION -> .006f
                    StageMusic.LANDSCAPE -> .04f
                    StageMusic.NOCTURNE -> .0432f
                    else -> .06f
                }
                chord++
                harmonyWait = (rate * when (s) {
                    StageMusic.LANDSCAPE -> random.between(16f, 24f)
                    StageMusic.NOCTURNE -> 8f
                    else -> 12f
                }).toInt()
            }
            if (--noteWait <= 0) schedule(s)
        }
        voices.next()
        var pad = 0f
        for (p in pads) pad += p.next()
        drone += slow * (droneTarget - drone)
        bassPhase = SoundMath.wrap(bassPhase + droneStep)
        fifthPhase = SoundMath.wrap(fifthPhase + droneStep * 1.5008f)
        dissonancePhase = SoundMath.wrap(dissonancePhase + droneStep * 1.05946f)
        noise = noiseBand.next(random.noise())
        noisePhase = SoundMath.wrap(noisePhase + .08f / rate)
        // 低音保留根音，中频泛音承担可听主体；噪声涌动低于有音高的铺底。
        val harmonics = .30f * SoundMath.sin(bassPhase) + .12f * SoundMath.sin(fifthPhase) +
            .55f * SoundMath.sin(bassPhase * 3) + .55f * SoundMath.sin(bassPhase * 4) +
            .60f * SoundMath.sin(bassPhase * 5) + .45f * SoundMath.sin(bassPhase * 7) +
            .06f * SoundMath.sin(dissonancePhase)
        droneLow += droneLp * (harmonics - droneLow)
        val d = drone * (droneLow + noise * .12f * (1 + .5f * SoundMath.sin(noisePhase)))
        reverb.next(voices.l + pad + d, voices.r + pad * .94f + d)
        lowL += lp * (reverb.l - lowL); lowR += lp * (reverb.r - lowR)
        l = lowL * 1.8f; r = lowR * 1.8f
    }

    private fun schedule(s: StageMusic) {
        val base = 48 + transpose
        when (s) {
            StageMusic.LANDSCAPE -> {
                if (phraseLeft <= 0) phraseLeft = 2 + (random.unit() * 5).toInt()
                melody = (melody + if (random.unit() < .15f) 2 else if (random.unit() < .5f) -1 else 1).coerceIn(0, 9)
                val pitch = base + PENTATONIC[melody % 5] + 12 * (melody / 5)
                voices.play(SoundVoice.PLUCK, SoundMath.hz(pitch), .22f, 5f, glide = random.unit() < .2f)
                if (random.unit() < .12f) voices.play(SoundVoice.BELL, SoundMath.hz(minOf(84, pitch + 12)), .035f, 4f)
                phraseLeft--
                noteWait = (rate * if (phraseLeft == 0) random.between(3f, 9f) else random.between(.25f, 1.2f)).toInt()
            }
            StageMusic.NOCTURNE -> {
                val root = base + ROOTS[(chord - 1).coerceAtLeast(0) % 4]
                val pitch = root + MAJOR_NINE[note++ % MAJOR_NINE.size]
                voices.play(SoundVoice.EPIANO, SoundMath.hz(pitch), .072f, 4.5f)
                noteWait = (rate * random.between(.6f, 1.4f)).toInt()
            }
            StageMusic.SUSPENSE -> {
                val pitch = 72 + transpose + if (random.unit() < .5f) 1 else 6
                voices.play(SoundVoice.BELL, SoundMath.hz(minOf(84, pitch)), .065f, 5f)
                noteWait = (rate * random.between(5f, 12f)).toInt()
            }
            StageMusic.TENDER -> {
                val root = base + ROOTS[(chord - 1).coerceAtLeast(0) % 4]
                // vi 与 IV 改用五声相容的挂留音，避免循环里突然出现导音。
                val pitch = root + TENDER_CHORDS[(chord - 1).coerceAtLeast(0) % 4][note++ % 4]
                voices.play(if (note % 3 == 0) SoundVoice.PLUCK else SoundVoice.EPIANO, SoundMath.hz(pitch), .075f, 3f)
                noteWait = (rate * (60f / 72 / 2)).toInt()
            }
            StageMusic.SORROW -> {
                melody = (melody + if (random.unit() < .6f) -1 else 1).coerceIn(0, 9)
                val pitch = 45 + transpose + MINOR_PENTATONIC[melody % 5] + 12 * (melody / 5)
                voices.play(SoundVoice.PLUCK, SoundMath.hz(pitch), .16f, 6f, glide = random.unit() < .1f)
                noteWait = (rate * random.between(1f, 2.5f)).toInt()
            }
            StageMusic.TENSION -> {
                voices.play(SoundVoice.PULSE, SoundMath.hz(36 + transpose), if (note++ % 2 == 0) .15f else .065f, .32f)
                if (note % 12 == 0) voices.play(SoundVoice.BELL, SoundMath.hz(72 + transpose + (note % 3) * 2), .04f, 2f)
                noteWait = (rate * (60f / 90)).toInt()
            }
        }
    }

    private class Pad(rate: Int) {
        private val phases = FloatArray(8)
        private val steps = FloatArray(8)
        private val attack = SoundMath.lowpass(.12f, rate)
        private val release = SoundMath.lowpass(.2f, rate)
        private val lp = SoundMath.lowpass(1200f, rate)
        private val sampleRate = rate
        private var volume = 0f
        private var low = 0f
        var target = 0f
        fun tune(root: Int, interval: Int) {
            for (i in steps.indices) steps[i] = SoundMath.hz(root + if (i < 4) 0 else interval) * (1 + (i % 4 - 1.5f) * .0012f) / sampleRate
        }
        fun next(): Float {
            volume += (if (target > volume) attack else release) * (target - volume)
            var x = 0f
            for (i in phases.indices) {
                phases[i] = SoundMath.wrap(phases[i] + steps[i])
                x += 1 - 4 * kotlin.math.abs(phases[i] - .5f)
            }
            low += lp * (x * .125f - low)
            return low * volume
        }
    }

    private companion object {
        val ROOTS = intArrayOf(0, 9, 5, 7)
        val MINOR_ROOTS = intArrayOf(0, 5, 3, 7)
        val PENTATONIC = intArrayOf(0, 2, 4, 7, 9)
        val MINOR_PENTATONIC = intArrayOf(0, 3, 5, 7, 10)
        val MAJOR_NINE = intArrayOf(0, 7, 11, 14, 16, 14, 11, 7)
        val TENDER_CHORDS = arrayOf(intArrayOf(0, 4, 7, 12), intArrayOf(0, 3, 7, 10), intArrayOf(0, 2, 7, 11), intArrayOf(0, 2, 7, 12))
    }
}
