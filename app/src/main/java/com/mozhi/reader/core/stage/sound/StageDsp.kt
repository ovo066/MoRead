package com.mozhi.reader.core.stage.sound

import kotlin.math.*

/** 每个声源各持随机状态，开关另一层不会改变它的随机序列。 */
internal class SoundRandom(seed: Long) {
    // 先混合种子，避免常用的小整数种子的首批随机数都挤在零附近。
    private var state = mix(seed)
    fun unit(): Float {
        var x = state
        x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
        state = x
        return (x ushr 40).toFloat() * (1f / 16777216f)
    }
    fun noise() = unit() * 2f - 1f
    fun between(lo: Float, hi: Float) = lo + unit() * (hi - lo)
    fun wait(rate: Float, sampleRate: Int) = maxOf(1, (-ln(maxOf(unit(), 1e-7f)) * sampleRate / rate).toInt())
    private companion object {
        fun mix(seed: Long): Long {
            var x = seed - 7046029254386353131L
            x = (x xor (x ushr 30)) * -4658895280553007687L
            x = (x xor (x ushr 27)) * -7723592293110705685L
            x = x xor (x ushr 31)
            return if (x == 0L) 1L else x
        }
    }
}

/** 查表避免音频内环的三角函数开销；插值后没有量化台阶。 */
internal object SoundMath {
    private const val SIZE = 16384
    private val sine = FloatArray(SIZE + 1) { sin(it * (2.0 * PI / SIZE)).toFloat() }
    fun sin(phase: Float): Float {
        val p = (phase - floor(phase)) * SIZE
        val i = p.toInt()
        return sine[i] + (sine[i + 1] - sine[i]) * (p - i)
    }
    fun wrap(phase: Float) = phase - floor(phase)
    fun lowpass(hz: Float, rate: Int) = (1.0 - exp(-2.0 * PI * hz / rate)).toFloat()
    fun decay(seconds: Float, rate: Int) = exp(-1.0 / (seconds * rate)).toFloat()
    fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    fun hz(midi: Int) = (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()
}

/** 常数峰值增益的双二阶带通，系数只在控制频率上更新。 */
internal class SoundBandpass(private val rate: Int) {
    private var b = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x2 = 0f
    private var x1 = 0f
    private var y2 = 0f
    private var y1 = 0f
    fun tune(hz: Float, q: Float) {
        val w = 2.0 * PI * hz.coerceIn(20f, rate * .45f) / rate
        val alpha = sin(w) / (2 * q)
        b = (alpha / (1 + alpha)).toFloat()
        a1 = (-2 * cos(w) / (1 + alpha)).toFloat()
        a2 = ((1 - alpha) / (1 + alpha)).toFloat()
    }
    fun next(x: Float): Float {
        val y = b * (x - x2) - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** Paul Kellet 粉红噪声；左右声道分别保存滤波记忆。 */
internal class SoundPink {
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    private var b3 = 0f; private var b4 = 0f; private var b5 = 0f; private var b6 = 0f
    fun next(w: Float): Float {
        b0 = .99886f * b0 + w * .0555179f
        b1 = .99332f * b1 + w * .0750759f
        b2 = .969f * b2 + w * .153852f
        b3 = .8665f * b3 + w * .3104856f
        b4 = .55f * b4 + w * .5329522f
        b5 = -.7616f * b5 - w * .016898f
        val out = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * .5362f) * .11f
        b6 = w * .115926f
        return out
    }
}

/** Freeverb 的八梳状、四全通结构；延迟长度随采样率缩放，左右错开。 */
internal class StageReverb(rate: Int) {
    private class Comb(length: Int) {
        val data = FloatArray(length)
        var at = 0
        var damp = 0f
        fun next(x: Float): Float {
            val y = data[at]
            damp = y * .72f + damp * .28f
            data[at] = x + damp * .79f
            if (++at == data.size) at = 0
            return y
        }
    }
    private class Allpass(length: Int) {
        val data = FloatArray(length)
        var at = 0
        fun next(x: Float): Float {
            val z = data[at]
            val y = z - x
            data[at] = x + z * .5f
            if (++at == data.size) at = 0
            return y
        }
    }
    private val lengths = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allLengths = intArrayOf(556, 441, 341, 225)
    private val left = Array(8) { Comb(maxOf(1, lengths[it] * rate / 44100)) }
    private val right = Array(8) { Comb(maxOf(1, (lengths[it] + 23) * rate / 44100)) }
    private val allLeft = Array(4) { Allpass(maxOf(1, allLengths[it] * rate / 44100)) }
    private val allRight = Array(4) { Allpass(maxOf(1, (allLengths[it] + 23) * rate / 44100)) }
    var l = 0f; private set
    var r = 0f; private set
    fun next(inL: Float, inR: Float) {
        val input = (inL + inR) * .035f
        var a = 0f; var b = 0f
        for (i in 0..7) { a += left[i].next(input); b += right[i].next(input) }
        for (i in 0..3) { a = allLeft[i].next(a); b = allRight[i].next(b) }
        l = inL * .6f + a * .4f; r = inR * .6f + b * .4f
    }
}
