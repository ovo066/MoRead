package com.mozhi.reader.feature.reader.stage

import com.mozhi.reader.core.stage.StageEra
import com.mozhi.reader.core.stage.StageSetting
import com.mozhi.reader.core.stage.StageTime
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class StageSceneryPreviewTest {
    private val paperLight = 0xFFF6F2EA.toInt()
    private val inkLight = 0xFF282522.toInt()
    private val paperDark = 0xFF15171D.toInt()
    private val inkDark = 0xFFD9D3C5.toInt()

    private fun key(setting: StageSetting?, time: StageTime?, dark: Boolean, w: Int, h: Int, overcast: Boolean = false, era: StageEra? = null) = StageSceneryKey(
        setting = setting, time = time, overcast = overcast, era = era, dark = dark,
        ink = if (dark) inkDark else inkLight, paper = if (dark) paperDark else paperLight,
        width = w, height = h, seed = 7
    )

    @Test fun `同一个 key 永远画出同一幅布景且只在下方一角着墨`() {
        val k = key(StageSetting.MOUNTAIN, StageTime.DUSK, false, 72, 128)
        val (back1, front1) = StageSceneryPainter.paintPixels(k)
        val (back2, front2) = StageSceneryPainter.paintPixels(k)
        assertArrayEquals(back1, back2)
        assertArrayEquals(front1, front2)
        // 中间偏上的文字区（25%–55% 高度）几乎不着墨：留白给文字。
        val w = k.width
        var ink = 0f
        var count = 0
        for (y in (k.height * 0.25f).toInt() until (k.height * 0.55f).toInt()) for (x in 0 until w) {
            ink += ((front1[y * w + x] ushr 24) and 0xFF) / 255f
            count++
        }
        assertTrue("文字区不该被近景压住：${ink / count}", ink / count < 0.02f)
    }

    /** 本地出图核对：设置 MOREAD_STAGE_SCENERY_DIR 时，按布景 × 时段 × 明暗各出一张，并拼成联络表。 */
    @Test fun `布景预览出图`() {
        val dir = System.getenv("MOREAD_STAGE_SCENERY_DIR")
        assumeTrue("未配置布景预览目录", !dir.isNullOrBlank())
        val out = File(requireNotNull(dir)).apply { mkdirs() }
        val w = 360
        val h = 640
        val times = listOf<StageTime?>(null, StageTime.DAWN, StageTime.DAY, StageTime.DUSK, StageTime.NIGHT)
        for (dark in listOf(false, true)) {
            for (setting in listOf<StageSetting?>(null) + StageSetting.entries) {
                val sheet = BufferedImage(w * times.size / 2, h / 2, BufferedImage.TYPE_INT_RGB)
                for ((col, time) in times.withIndex()) {
                    val k = key(setting, time, dark, w, h, era = if (setting == StageSetting.INTERIOR && col % 2 == 1) StageEra.ANCIENT else StageEra.MODERN)
                    val image = composite(k)
                    ImageIO.write(image, "png", File(out, "${if (dark) "dark" else "light"}_${setting?.name ?: "NONE"}_${time?.name ?: "NONE"}.png"))
                    val g = sheet.createGraphics()
                    g.drawImage(image, col * w / 2, 0, w / 2, h / 2, null)
                    g.dispose()
                }
                ImageIO.write(sheet, "png", File(out, "sheet_${if (dark) "dark" else "light"}_${setting?.name ?: "NONE"}.png"))
            }
            // 阴天（雨雪）：不出日月、天色压灰。
            val rain = composite(key(StageSetting.CITY, StageTime.NIGHT, dark, w, h, overcast = true))
            ImageIO.write(rain, "png", File(out, "${if (dark) "dark" else "light"}_CITY_NIGHT_overcast.png"))
        }
    }

    private fun composite(k: StageSceneryKey): BufferedImage {
        val (back, front) = StageSceneryPainter.paintPixels(k)
        val image = BufferedImage(k.width, k.height, BufferedImage.TYPE_INT_RGB)
        val paper = k.paper
        for (i in 0 until k.width * k.height) {
            var r = ((paper shr 16) and 0xFF).toFloat()
            var g = ((paper shr 8) and 0xFF).toFloat()
            var b = (paper and 0xFF).toFloat()
            for (layer in arrayOf(back, front)) {
                val p = layer[i]
                val a = ((p ushr 24) and 0xFF) / 255f
                if (a <= 0f) continue
                r = r * (1 - a) + ((p shr 16) and 0xFF) * a
                g = g * (1 - a) + ((p shr 8) and 0xFF) * a
                b = b * (1 - a) + (p and 0xFF) * a
            }
            image.setRGB(i % k.width, i / k.width, (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255))
        }
        return image
    }
}
