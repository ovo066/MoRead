package com.mozhi.reader.core.stage.sound

import kotlin.math.*

/** 环境层各自持有滤波、事件与左右声道状态，不在采样循环里创建对象。 */
internal class StageAmbienceSynth(private val kind: StageSoundLayer, private val rate: Int, seed: Long) {
    private val random = SoundRandom(seed)
    private val pink = Array(2) { SoundPink() }
    private val low = FloatArray(2)
    private val high = FloatArray(2)
    private val brown = FloatArray(2)
    private val rumble = FloatArray(2)
    private val band = Array(2) { SoundBandpass(rate).also { it.tune(if (kind == StageSoundLayer.FIRE) 2800f else if (kind == StageSoundLayer.WAVES) 1000f else 500f, .8f) } }
    private val stream = Array(16) { SoundBandpass(rate).also { f -> f.tune(800f + (it % 8) * 300f, 12f) } }
    private val drops = Array(16) { Drop(rate) }
    private val phases = FloatArray(5) { random.unit() }
    private val carrier = FloatArray(5) { random.between(3800f, 5200f) / rate }
    private val insectAt = IntArray(5) { (random.unit() * rate).toInt() }
    private val insectPulses = IntArray(5) { 3 + (random.unit() * 3).toInt() }
    private val pans = FloatArray(5) { random.between(.1f, .9f) }
    private val chimes = StageVoices(rate, seed + 71, 6)
    private var clock = 0L
    private var event = 1
    private var chimeFollowup = false
    private var gust = .5f
    private var gustTarget = .5f
    private var drift = .5f
    private var driftTarget = .5f
    private var control = 0
    private var gustWait = rate * 5
    private var driftWait = rate * 7
    private var waveA = 0f
    private var waveB = .5f
    private var waveStepA = 1f / (rate * 8)
    private var waveStepB = 1f / (rate * 10)
    private var birdRemaining = 0
    private var birdTotal = 1
    private var birdCount = 0
    private var birdPhase = 0f
    private var birdHz = 3000f
    private var birdEnd = 5000f
    private var birdStep = 0f
    private var birdRatio = 1f
    private var birdPan = .5f
    private var car = 0
    private var carLength = 1
    private var pop = 0f
    private var popDecay = .9f
    private val lpCrackle = SoundMath.lowpass(5000f, rate)
    private val hpRain = SoundMath.lowpass(500f, rate)
    private val lpRain = SoundMath.lowpass(if (kind == StageSoundLayer.HEAVY_RAIN) 8000f else 6000f, rate)
    private val lpBrown = SoundMath.lowpass(300f, rate)
    private val lpWave = SoundMath.lowpass(800f, rate)
    private val hpHiss = SoundMath.lowpass(2000f, rate)
    private val hpLeaves = SoundMath.lowpass(1500f, rate)
    private val slow = SoundMath.lowpass(.1f, rate)
    var l = 0f; private set
    var r = 0f; private set

    private class Drop(rate: Int) {
        val filter = SoundBandpass(rate)
        var amplitude = 0f
        var decay = .9f
        var remaining = 0
        var pan = .5f
    }

    fun next() {
        clock++
        gust += slow * (gustTarget - gust)
        drift += slow * (driftTarget - drift)
        if (--gustWait <= 0) { gustTarget = random.between(.15f, 1f); gustWait = (rate * random.between(6.7f, 14f)).toInt() }
        if (--driftWait <= 0) { driftTarget = random.unit(); driftWait = (rate * random.between(5f, 20f)).toInt() }
        l = 0f; r = 0f
        when (kind) {
            StageSoundLayer.RAIN, StageSoundLayer.HEAVY_RAIN -> rain()
            StageSoundLayer.WIND -> wind()
            StageSoundLayer.WAVES -> waves()
            StageSoundLayer.STREAM -> stream()
            StageSoundLayer.INSECTS -> insects()
            StageSoundLayer.BIRDS -> birds()
            StageSoundLayer.FIRE -> fire()
            StageSoundLayer.CITY -> city()
            StageSoundLayer.LEAVES -> leaves()
            StageSoundLayer.CHIMES -> {
                if (--event <= 0) {
                    val notes = CHIME_NOTES
                    chimes.play(SoundVoice.BELL, SoundMath.hz(notes[(random.unit() * notes.size).toInt()]), .22f, random.between(2f, 4f))
                    if (!chimeFollowup && random.unit() < .3f) {
                        chimeFollowup = true
                        event = (rate * random.between(.2f, .5f)).toInt()
                    } else {
                        chimeFollowup = false
                        event = (rate * random.between(4f, 12f)).toInt()
                    }
                }
                chimes.next(); l = chimes.l; r = chimes.r
            }
        }
    }

    private fun rain() {
        val heavy = kind == StageSoundLayer.HEAVY_RAIN
        for (c in 0..1) {
            val w = random.noise()
            val p = pink[c].next(w)
            high[c] += hpRain * (p - high[c])
            low[c] += lpRain * (p - high[c] - low[c])
            var value = low[c] * if (heavy) .95f else .7f
            if (heavy) {
                brown[c] = (brown[c] + w * .025f) * .998f
                rumble[c] += lpBrown * (brown[c] - rumble[c])
                value += rumble[c] * .4f
            }
            if (c == 0) l = value else r = value
        }
        if (--event <= 0) {
            for (d in drops) if (d.remaining == 0) {
                d.filter.tune(random.between(1500f, 5000f), 4f)
                val seconds = random.between(.002f, .008f)
                d.remaining = (seconds * rate).toInt(); d.decay = SoundMath.decay(seconds / 3, rate)
                d.amplitude = random.between(.15f, .5f); d.pan = random.unit()
                break
            }
            event = random.wait(if (heavy) 60f else 25f, rate)
        }
        for (d in drops) if (d.remaining > 0) {
            val v = d.filter.next(random.noise()) * d.amplitude
            l += v * (1 - d.pan); r += v * d.pan
            d.amplitude *= d.decay; d.remaining--
        }
    }
    private fun wind() {
        if (--control <= 0) {
            for (b in band) b.tune(250 + drift * 650, 1.5f)
            control = 128
        }
        phases[0] = SoundMath.wrap(phases[0] + (1200 + drift * 800) / rate)
        val whistle = SoundMath.sin(phases[0]) * maxOf(0f, gust - .6f) * .018f
        l = band[0].next(random.noise()) * (.18f + gust * .6f) + whistle
        r = band[1].next(random.noise()) * (.18f + gust * .6f) + whistle * .7f
    }
    private fun waves() {
        waveA += waveStepA; waveB += waveStepB
        if (waveA >= 1) { waveA -= 1; waveStepA = 1f / (rate * random.between(7f, 11f)) }
        if (waveB >= 1) { waveB -= 1; waveStepB = 1f / (rate * random.between(7f, 11f)) }
        fun envelope(p: Float) = if (p < .38f) SoundMath.smooth(p / .38f) else 1 - SoundMath.smooth((p - .38f) / .45f)
        val a = envelope(waveA); val b = envelope(waveB)
        for (c in 0..1) {
            val w = random.noise()
            brown[c] = (brown[c] + w * .035f) * .997f
            low[c] += lpWave * (brown[c] - low[c])
            high[c] += hpHiss * (w - high[c])
            val env = if (c == 0) a * .8f + b * .35f else a * .35f + b * .8f
            // 浪头的粉红沙声随浪涌起落，低隆只作陪衬。
            val wash = band[c].next(pink[c].next(w))
            val v = low[c] * env * .14f + wash * env * 2.2f
            if (c == 0) l = v else r = v
        }
    }
    private fun stream() {
        if (--control <= 0) {
            for (i in stream.indices) stream[i].tune(800 + (i % 8) * 300f + random.between(-50f, 50f), 10f)
            control = rate / 10
        }
        for (c in 0..1) {
            val w = random.noise()
            var sum = 0f
            for (j in 0..7) {
                val flicker = .7f + .3f * SoundMath.sin((clock % (rate * 100L)).toFloat() / rate * (8f + j * 1.6f) + j * .1f)
                sum += stream[c * 8 + j].next(w) * flicker
            }
            if (c == 0) l = sum * .42f else r = sum * .42f
        }
    }
    private fun insects() {
        for (i in 0..4) {
            val at = insectAt[i]++
            val pulse = (rate * .06f).toInt()
            if (at >= 0 && at < insectPulses[i] * pulse) {
                val local = at % pulse
                val width = rate * .025f
                if (local < width) {
                    phases[i] = SoundMath.wrap(phases[i] + carrier[i] * (1 + .003f * SoundMath.sin(at.toFloat() / rate * 37)))
                    val env = SoundMath.sin(local / width * .5f)
                    val x = SoundMath.sin(phases[i]) * env * .06f
                    l += x * (1 - pans[i]); r += x * pans[i]
                }
            } else if (at >= insectPulses[i] * pulse) {
                insectAt[i] = -(rate * random.between(.4f, 1.2f)).toInt()
                insectPulses[i] = 3 + (random.unit() * 3).toInt()
            }
        }
        l += random.noise() * .001f; r += random.noise() * .001f
    }
    private fun birds() {
        if (--event <= 0) {
            if (birdCount <= 0) { birdCount = 2 + (random.unit() * 4).toInt(); birdPan = random.unit() }
            birdTotal = (rate * random.between(.04f, .15f)).toInt(); birdRemaining = birdTotal
            birdHz = random.between(2000f, 3500f).coerceAtMost(rate * .4f); birdEnd = random.between(4000f, 6000f).coerceAtMost(rate * .4f)
            birdStep = birdHz / rate
            birdRatio = (birdEnd / birdHz).pow(1f / birdTotal)
            birdPhase = 0f
            birdCount--
            event = (rate * if (birdCount > 0) random.between(.15f, .28f) else random.between(2f, 8f)).toInt()
        }
        if (birdRemaining > 0) {
            val p = 1 - birdRemaining.toFloat() / birdTotal
            // 相位逐采样积分；扫频始终低于奈奎斯特的八成，不生成越界谐波。
            birdPhase = SoundMath.wrap(birdPhase + birdStep.coerceAtMost(.4f))
            birdStep *= birdRatio
            val edge = minOf(1f, (birdTotal - birdRemaining) / (rate * .004f), birdRemaining / (rate * .004f))
            val x = SoundMath.sin(birdPhase) * SoundMath.sin(p * .5f) * SoundMath.smooth(edge) * .22f
            l = x * (1 - birdPan); r = x * birdPan
            birdRemaining--
        }
    }
    private fun fire() {
        if (--event <= 0) {
            val power = random.unit()
            pop = .25f + power * power * power * .9f
            popDecay = SoundMath.decay(random.between(.004f, .009f), rate)
            event = random.wait(random.between(15f, 26f), rate)
        }
        for (c in 0..1) {
            val w = random.noise()
            brown[c] = (brown[c] + w * .04f) * .996f
            low[c] += lpBrown * (brown[c] - low[c])
            // 爆裂保留中高频，压住最尖锐的逐采样跳变。
            val crack = band[c].next(w) * (pop * 1.1f + .025f)
            high[c] += lpCrackle * (crack - high[c])
            val v = low[c] * .10f + high[c]
            if (c == 0) l = v else r = v
        }
        pop *= popDecay
    }
    private fun city() {
        if (--event <= 0) {
            carLength = (rate * random.between(3f, 6f)).toInt(); car = carLength
            event = (rate * random.between(6f, 15f)).toInt()
        }
        if (--control <= 0) { for (b in band) b.tune(900 + drift * 400, .9f); control = 128 }
        val env = if (car > 0) SoundMath.sin((1 - car.toFloat() / carLength) * .5f) else 0f
        for (c in 0..1) {
            val w = random.noise()
            brown[c] = (brown[c] + w * .025f) * .997f
            low[c] += lpBrown * (brown[c] - low[c])
            val v = low[c] * .13f + band[c].next(w) * (.20f + env * .24f)
            if (c == 0) l = v else r = v
        }
        if (car > 0) car--
    }
    private fun leaves() {
        if (--event <= 0) { pop = random.between(.15f, .8f) * gust; event = random.wait(2 + gust * 8, rate) }
        pop *= .9995f
        for (c in 0..1) {
            val w = random.noise()
            high[c] += hpLeaves * (w - high[c])
            val v = (w - high[c]) * (.025f + pop * .35f)
            if (c == 0) l = v else r = v
        }
    }
    private companion object { val CHIME_NOTES = intArrayOf(84, 86, 88, 91, 93) }
}
