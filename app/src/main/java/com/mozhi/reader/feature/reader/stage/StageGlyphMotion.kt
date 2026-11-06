package com.mozhi.reader.feature.reader.stage

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** 一个字形簇在某一帧的姿态；位移以 em（字号）为单位，由绘制处换算成像素。 */
internal data class GlyphPose(
    val dxEm: Float = 0f,
    val dyEm: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    /** 错位时的色差偏移（em），0 表示不画。 */
    val splitEm: Float = 0f,
    /** 墨晕：洇开的半径（em）与浓淡；0 表示不画。 */
    val bleedEm: Float = 0f,
    val bleedAlpha: Float = 0f
)

/**
 * 逐字动效：纯函数，只依赖揭示进度、时间、字序与种子，同样的输入总是同样的姿态，
 * 绘制时每帧调用，不触发重组（逐帧值只在 draw 阶段读）。
 */
internal object StageGlyphMotion {
    const val SHAKE = 1
    const val WAVE = 1 shl 1
    const val GLITCH = 1 shl 2
    const val TREMBLE = 1 shl 3
    const val POP = 1 shl 4

    /** 一个字从开始出现到完全清晰的时长。 */
    const val REVEAL_MILLIS = 340f

    /** 墨晕一个字从洇开到收清的时长；比淡入长，但整句一起走，不拖慢阅读。 */
    const val INK_MILLIS = 560f

    /** 墨晕时整句从左到右洇过去的总时长上限：短句几乎同时，长段落也不超过这么久。 */
    const val INK_WAVE_MILLIS = 380f

    /** 墨晕时第 [index] 个字（共 [count] 个）晚多少毫秒开始。 */
    fun inkDelay(index: Int, count: Int): Int = if (count <= 1) 0 else (index * minOf(16f, INK_WAVE_MILLIS / count)).toInt()

    /**
     * 墨晕：字先是一团洇开的淡墨，随即收拢成清晰的笔画——像墨落在宣纸上。
     * 晕在前半程最浓、半径一路收小；字形在 18%–90% 之间显出来，几乎不位移。
     */
    fun ink(progress: Float, pop: Boolean): GlyphPose {
        val p = progress.coerceIn(0f, 1f)
        if (p >= 1f) return GlyphPose()
        if (pop) return reveal(progress, true)
        val t = ((p - 0.18f) / 0.72f).coerceIn(0f, 1f)
        val glyph = t * t * (3f - 2f * t)
        val halo = sin((minOf(1f, p * 1.6f) * PI).toFloat()).coerceAtLeast(0f) * (1f - p)
        val eased = 1f - (1f - p) * (1f - p)
        return GlyphPose(
            dyEm = (1f - eased) * 0.04f,
            alpha = glyph,
            bleedEm = 0.06f + 0.42f * (1f - p) * (1f - p),
            bleedAlpha = (0.55f * halo).coerceIn(0f, 0.9f)
        )
    }

    /** 揭示：淡入并从下方 0.22em 落到基线；[pop] 的标点从 1.7 倍回弹。 */
    fun reveal(progress: Float, pop: Boolean): GlyphPose {
        val p = progress.coerceIn(0f, 1f)
        if (p >= 1f) return GlyphPose()
        val eased = 1f - (1f - p) * (1f - p) * (1f - p)
        if (pop) {
            val scale = if (p < 0.6f) 1.7f - 0.78f * (p / 0.6f) else 0.92f + 0.08f * ((p - 0.6f) / 0.4f)
            return GlyphPose(scale = scale, alpha = (p / 0.6f).coerceAtMost(1f))
        }
        return GlyphPose(dyEm = (1f - eased) * 0.22f, alpha = eased)
    }

    /** 整句淡入：位移更小、更快到位，读起来不拖。 */
    fun soft(progress: Float, pop: Boolean): GlyphPose {
        val p = (progress * 1.25f).coerceIn(0f, 1f)
        if (p >= 1f) return GlyphPose()
        if (pop) return reveal(progress, true)
        val eased = 1f - (1f - p) * (1f - p)
        return GlyphPose(dyEm = (1f - eased) * 0.12f, alpha = eased)
    }

    /**
     * 揭示完成后的持续效果。[seed] 是 0..1 的每字随机相位；[reduceMotion] 时一律静止。
     * 颤动按 12 帧/秒取样（steps），比连续正弦更像手抖；错位每 2.4 秒爆一次、持续约 0.2 秒。
     */
    fun sustain(flags: Int, timeMillis: Long, index: Int, seed: Float, reduceMotion: Boolean): GlyphPose {
        if (reduceMotion || flags == 0) return GlyphPose()
        var dx = 0f
        var dy = 0f
        var alpha = 1f
        var split = 0f
        if (flags and (SHAKE or TREMBLE) != 0) {
            val amplitude = if (flags and SHAKE != 0) 0.055f else 0.028f
            val step = floor(timeMillis / 83.0 + seed * 7.0).toLong()
            dx += amplitude * noise(step * 2 + index)
            dy += amplitude * noise(step * 2 + index + 91)
        }
        if (flags and WAVE != 0) {
            dy += 0.09f * sin((timeMillis / 1800.0 * 2 * PI + index * 0.5).toFloat())
        }
        if (flags and GLITCH != 0) {
            val period = 2400.0
            val phase = ((timeMillis + seed * period) % period) / period
            if (phase > 0.88) {
                val burst = floor(phase * 50).toInt()
                dx += if (burst % 2 == 0) -0.09f else 0.09f
                alpha = if (burst % 3 == 0) 0.6f else 1f
                split = 0.07f
            }
        }
        return GlyphPose(dxEm = dx, dyEm = dy, alpha = alpha, splitEm = split)
    }

    /** 每字的随机相位：确定性散列，重组或重进不会跳变。 */
    fun seed(index: Int, salt: Int): Float = ((noise(index * 31 + salt) + 1f) / 2f)

    private fun noise(n: Long): Float {
        var x = n * 0x9E3779B97F4A7C15uL.toLong()
        x = x xor (x ushr 31)
        x *= 0xBF58476D1CE4E5B9uL.toLong()
        x = x xor (x ushr 29)
        return ((x and 0xFFFF).toFloat() / 0xFFFF.toFloat()) * 2f - 1f
    }

    private fun noise(n: Int): Float = noise(n.toLong())
}
