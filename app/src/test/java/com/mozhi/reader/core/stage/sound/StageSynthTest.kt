package com.mozhi.reader.core.stage.sound

import kotlin.math.abs
import kotlin.math.sqrt
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import org.junit.Assert.*
import org.junit.Test

class StageSynthTest {
    private val rate = 32000
    private val all = StageSoundLayer.entries.associateWith { 1f }
    private data class Stats(val rms: Double, val peak: Float, val jump: Float, val audibleSeconds: Int)

    private fun measure(synth: StageSynth, seconds: Int, retrigger: Boolean = false): Stats {
        val out = FloatArray(1024 * 2)
        var sum = 0.0; var secondSum = 0.0; var count = 0
        var peak = 0f; var jump = 0f; var previousL = 0f; var previousR = 0f
        var audible = 0; var frame = 0
        while (frame < rate * seconds) {
            val n = minOf(1024, rate * seconds - frame)
            if (retrigger && frame % 8192 == 0) for (cue in StageSoundCue.entries) synth.trigger(cue)
            synth.render(out, n)
            for (i in 0 until n) {
                val l = out[i * 2]; val r = out[i * 2 + 1]
                assertTrue("非有限采样", l.isFinite() && r.isFinite())
                peak = maxOf(peak, abs(l), abs(r))
                jump = maxOf(jump, abs(l - previousL), abs(r - previousR)); previousL = l; previousR = r
                val energy = (l.toDouble() * l + r.toDouble() * r) / 2
                sum += energy; secondSum += energy; count++
                if (count % rate == 0) { if (sqrt(secondSum / rate) > .002) audible++; secondSum = 0.0 }
            }
            frame += n
        }
        return Stats(sqrt(sum / count), peak, jump, audible)
    }

    @Test fun `全层各配乐连发提示音二十秒无溢出`() {
        for (music in StageMusic.entries) {
            val synth = StageSynth(seed = 37)
            synth.setScene(StageSoundScene(all, music))
            val stats = measure(synth, 20, retrigger = true)
            assertTrue("$music $stats", stats.peak <= .99f)
        }
    }

    @Test fun `各环境层与配乐的电平处于可听范围`() {
        for (layer in StageSoundLayer.entries) {
            val synth = StageSynth(seed = 19)
            synth.setScene(StageSoundScene(mapOf(layer to .7f)), .1f)
            val stats = measure(synth, 10)
            println("LEVEL layer=$layer $stats")
            assertTrue("$layer $stats", stats.rms in .004.. .3)
        }
        for (music in StageMusic.entries) {
            val synth = StageSynth(seed = 19)
            synth.setScene(StageSoundScene(music = music))
            val stats = measure(synth, 45)
            println("LEVEL music=$music $stats")
            assertTrue("$music $stats", stats.rms in .012.. .03 && stats.audibleSeconds >= 3)
        }
    }

    /** 与试听分析一致：单声道折叠、汉宁窗、四千零九十六点频谱功率累积。 */
    private data class Spectrum(val low: Double, val fireBand: Double, val bright: Double, val centroid: Double, val alias: Double)

    private fun spectrum(synth: StageSynth, seconds: Int = 20): Spectrum {
        val size = 4096
        val samples = FloatArray(size * 2)
        val real = DoubleArray(size)
        val imaginary = DoubleArray(size)
        val power = DoubleArray(size / 2 + 1)
        repeat(synth.sampleRate * seconds / size) {
            synth.render(samples, size)
            for (i in 0 until size) {
                real[i] = (samples[i * 2] + samples[i * 2 + 1]) * .5 *
                    (.5 - .5 * kotlin.math.cos(2 * Math.PI * i / (size - 1)))
                imaginary[i] = 0.0
            }
            var j = 0
            for (i in 1 until size) {
                var bit = size shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) { val value = real[i]; real[i] = real[j]; real[j] = value }
            }
            var width = 2
            while (width <= size) {
                val angle = -2 * Math.PI / width
                val wr = kotlin.math.cos(angle); val wi = kotlin.math.sin(angle)
                for (start in 0 until size step width) {
                    var ar = 1.0; var ai = 0.0
                    for (k in 0 until width / 2) {
                        val a = start + k; val b = a + width / 2
                        val br = ar * real[b] - ai * imaginary[b]
                        val bi = ar * imaginary[b] + ai * real[b]
                        real[b] = real[a] - br; imaginary[b] = imaginary[a] - bi
                        real[a] += br; imaginary[a] += bi
                        val next = ar * wr - ai * wi
                        ai = ar * wi + ai * wr; ar = next
                    }
                }
                width *= 2
            }
            for (i in power.indices) power[i] += real[i] * real[i] + imaginary[i] * imaginary[i]
        }
        val total = power.sum()
        fun energy(from: Int, until: Int) = power.indices.filter { it * synth.sampleRate.toDouble() / size >= from && it * synth.sampleRate.toDouble() / size < until }.sumOf { power[it] } / total
        val centroid = power.indices.sumOf { power[it] * it * synth.sampleRate / size } / total
        return Spectrum(energy(0, 250), energy(1000, 8000), energy(4000, 48000), centroid, energy((synth.sampleRate * .4).toInt(), synth.sampleRate))
    }

    @Test fun `外放频谱配乐高频和鸟鸣上限回归`() {
        for (layer in listOf(StageSoundLayer.FIRE, StageSoundLayer.CITY, StageSoundLayer.WAVES)) {
            val synth = StageSynth(seed = 2026)
            synth.setScene(StageSoundScene(mapOf(layer to .7f)), .4f)
            val bands = spectrum(synth)
            assertTrue("$layer $bands", bands.low <= .60 && bands.centroid >= 350)
            if (layer == StageSoundLayer.FIRE) assertTrue("$bands", bands.fireBand >= .35)
        }
        for (cue in listOf(StageSoundCue.HEARTBEAT, StageSoundCue.IMPACT, StageSoundCue.THUNDER)) {
            val synth = StageSynth(seed = 2026)
            synth.trigger(cue)
            val bands = spectrum(synth, 7)
            assertTrue("$cue $bands", bands.low <= .60 && bands.centroid >= 350)
        }
        for (style in listOf(StageMusic.SUSPENSE, StageMusic.TENSION, StageMusic.LANDSCAPE, StageMusic.TENDER)) {
            val synth = StageSynth(seed = 2026)
            synth.setScene(StageSoundScene(music = style))
            val bands = spectrum(synth, 45)
            if (style == StageMusic.SUSPENSE || style == StageMusic.TENSION) assertTrue("$style $bands", bands.low <= .65)
            else assertTrue("$style $bands", bands.bright <= .03)
        }
        for (sampleRate in listOf(16000, 32000)) {
            val synth = StageSynth(sampleRate, seed = 2026)
            synth.setScene(StageSoundScene(mapOf(StageSoundLayer.BIRDS to .7f)), .4f)
            val bands = spectrum(synth)
            assertTrue("$sampleRate $bands", bands.alias < .001)
        }
    }

    @Test fun `淡出和总线静音无残留`() {
        val synth = StageSynth()
        synth.setScene(StageSoundScene(all, StageMusic.NOCTURNE))
        measure(synth, 10)
        synth.setScene(StageSoundScene())
        measure(synth, 18)
        assertTrue(measure(synth, 2).rms < 1e-4)
        synth.setScene(StageSoundScene(all, StageMusic.TENSION))
        measure(synth, 4)
        synth.setVolumes(0f, 0f)
        measure(synth, 2)
        assertTrue(measure(synth, 2, retrigger = true).rms < 1e-4)
    }

    @Test fun `确定性及分块无关且不越界写出`() {
        val a = StageSynth(seed = 123)
        val b = StageSynth(seed = 123)
        val outA = FloatArray(4096)
        val outB = FloatArray(4096)
        repeat(80) { block ->
            if (block % 10 == 0) {
                val scene = StageSoundScene(all, StageMusic.entries[(block / 10) % StageMusic.entries.size])
                a.setScene(scene); b.setScene(scene)
                a.trigger(StageSoundCue.entries[(block / 10) % StageSoundCue.entries.size]); b.trigger(StageSoundCue.entries[(block / 10) % StageSoundCue.entries.size])
                a.setVolumes(.7f, .4f); b.setVolumes(.7f, .4f)
            }
            a.render(outA, 2048)
            b.render(outB, 2048)
            assertArrayEquals(outA, outB, 0f)
        }
        val c = StageSynth(seed = 456); val d = StageSynth(seed = 456)
        c.setScene(StageSoundScene(all, StageMusic.TENDER)); d.setScene(StageSoundScene(all, StageMusic.TENDER))
        c.render(outA, 2048)
        val half = FloatArray(2048)
        d.render(half, 1024); half.copyInto(outB)
        d.render(half, 1024); half.copyInto(outB, 2048)
        assertArrayEquals(outA, outB, 0f)
        outA.fill(123f); a.render(outA, 1)
        assertEquals(123f, outA[2], 0f)
    }

    @Test fun `切场景和调音量连续且参数清洗不会产生非有限采样`() {
        val synth = StageSynth()
        for (style in StageMusic.entries) {
            synth.setScene(StageSoundScene(all, style), .4f)
            assertTrue(measure(synth, 3).jump < .5f)
            synth.setScene(StageSoundScene(), .1f)
            assertTrue(measure(synth, 2).jump < .5f)
        }
        synth.setVolumes(Float.NaN, Float.POSITIVE_INFINITY)
        synth.setScene(StageSoundScene(mapOf(StageSoundLayer.RAIN to Float.NaN)))
        assertTrue(measure(synth, 2).peak <= .99f)
    }

    @Test fun `全层配乐十秒渲染性能`() {
        val synth = StageSynth()
        synth.setScene(StageSoundScene(all, StageMusic.NOCTURNE), .1f)
        val buffer = FloatArray(2048)
        repeat(100) { synth.render(buffer, 1024) }
        val start = System.nanoTime()
        var remaining = rate * 10
        while (remaining > 0) { val n = minOf(1024, remaining); synth.render(buffer, n); remaining -= n }
        val ms = (System.nanoTime() - start) / 1e6
        println("PERFORMANCE full mix 10s = $ms ms")
        assertTrue("全层十秒耗时 $ms ms", ms < 800)
    }

    @Test fun `热身后的渲染内环不分配堆内存`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        org.junit.Assume.assumeTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val synth = StageSynth()
        synth.setScene(StageSoundScene(all, StageMusic.LANDSCAPE), .1f)
        val buffer = FloatArray(2048)
        repeat(400) { synth.render(buffer, 1024) }
        val threadId = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(threadId)
        repeat(400) { synth.render(buffer, 1024) }
        val allocated = bean.getThreadAllocatedBytes(threadId) - before
        assertEquals("音频内环分配字节数", 0L, allocated)
    }

    @Test fun `每种提示音独立可听且首尾有限`() {
        for (cue in StageSoundCue.entries) {
            val synth = StageSynth(seed = 103)
            synth.trigger(cue)
            val stats = measure(synth, 6)
            assertTrue("$cue $stats", stats.peak > .005f && stats.peak <= .99f)
        }
    }
}
