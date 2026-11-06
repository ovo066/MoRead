package com.mozhi.reader.core.stage.sound

import com.mozhi.reader.core.stage.*
import java.io.BufferedOutputStream
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StageSoundExportTest {
    @Test fun `导出所有环境配乐提示音与组合场景`() {
        val path = System.getenv("MOREAD_STAGE_SOUND_DIR")
        assumeTrue("未配置试听目录", !path.isNullOrBlank())
        val directory = File(requireNotNull(path))
        assertTrue(directory.isDirectory || directory.mkdirs())
        for (layer in StageSoundLayer.entries) export(directory, "layer_${layer.name.lowercase()}", 20, StageSoundScene(mapOf(layer to .7f)))
        for (music in StageMusic.entries) export(directory, "music_${music.name.lowercase()}", 45, StageSoundScene(music = music))
        for (cue in StageSoundCue.entries) export(directory, "cue_${cue.name.lowercase()}", if (cue == StageSoundCue.THUNDER) 7 else 5, StageSoundScene(), cue)
        export(directory, "scene_rain_city_nocturne", 45, StageSoundMatcher.of(StageScene(0, StageWeatherCue.RAIN, null, StageSetting.CITY, StageTime.NIGHT), StageEra.MODERN))
        export(directory, "scene_mountain_day_landscape", 45, StageSoundMatcher.of(StageScene(0, null, null, StageSetting.MOUNTAIN, StageTime.DAY), StageEra.ANCIENT))
        export(directory, "scene_eerie_interior_suspense", 45, StageSoundMatcher.of(StageScene(0, null, StageMood.EERIE, StageSetting.INTERIOR, StageTime.NIGHT), null))
    }

    private fun export(directory: File, name: String, seconds: Int, scene: StageSoundScene, cue: StageSoundCue? = null) {
        val rate = 32000
        val frames = rate * seconds
        val synth = StageSynth(seed = 2026)
        synth.setScene(scene, .4f)
        val samples = FloatArray(2048)
        val bytes = ByteArray(4096)
        val file = File(directory, "$name.wav")
        BufferedOutputStream(file.outputStream()).use { out ->
            fun le(value: Int, count: Int) { repeat(count) { out.write(value ushr (it * 8) and 255) } }
            fun tag(value: String) { out.write(value.toByteArray(Charsets.US_ASCII)) }
            tag("RIFF"); le(36 + frames * 4, 4); tag("WAVE"); tag("fmt "); le(16, 4)
            le(1, 2); le(2, 2); le(rate, 4); le(rate * 4, 4); le(4, 2); le(16, 2)
            tag("data"); le(frames * 4, 4)
            var at = 0
            while (at < frames) {
                if (cue != null && at == 0) synth.trigger(cue)
                val n = minOf(1024, frames - at)
                synth.render(samples, n)
                for (i in 0 until n * 2) {
                    assertTrue(samples[i].isFinite() && kotlin.math.abs(samples[i]) <= .99f)
                    val pcm = (samples[i] * 32767).toInt().coerceIn(-32768, 32767)
                    bytes[i * 2] = pcm.toByte(); bytes[i * 2 + 1] = (pcm shr 8).toByte()
                }
                out.write(bytes, 0, n * 4)
                at += n
            }
        }
        println("WAV ${file.name}: ${file.length()} bytes")
    }
}
