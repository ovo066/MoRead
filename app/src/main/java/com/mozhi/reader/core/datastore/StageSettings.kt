package com.mozhi.reader.core.datastore

import com.mozhi.reader.core.stage.StageIntensity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class StageTheme { XUAN, PAPER, THEATER }

/** 一拍文字怎么出现：直接显示（默认，最符合阅读直觉）、墨晕（字像墨落进纸里一样洇开再收清）、整句淡入、逐行浮现、逐字打出。 */
enum class StageTextReveal { NONE, INK, FADE, LINES, TYPE }

/** 版式：整页（旁白与对白在一页里累积，读满翻页）或一句一屏。 */
enum class StageLayoutMode { PAGE, SINGLE }

/** 背景光晕的位置；星点是主光晕加散落的小光点。 */
enum class StageHalo { NONE, TOP, CENTER, BOTTOM, STARRY }

/** 纸面质感：都是本地程序生成的纹理，不带图片资源。 */
enum class StagePaperTexture { NONE, GRAIN, FIBER, LINEN, SPECKLE, CRUMPLED }

/** 舞台字体：书宋（默认，更有书卷气）、跟随阅读页、黑体。 */
enum class StageTypeface { SONG, FOLLOW, SANS }

/** 天气：跟随原文（读到下雨就下雨）、关闭，或固定一种。 */
enum class StageWeather { AUTO, OFF, RAIN, SNOW, PETALS, LEAVES, FIREFLIES, DUST }

@Serializable
data class StageSettings(
    val theme: StageTheme = StageTheme.XUAN,
    val intensity: StageIntensity = StageIntensity.STANDARD,
    /** 打字速度倍率 0.5–2（只在逐字打出时生效）。 */
    val typingSpeed: Float = 1f,
    /** 自动播放速度：0.75 / 1 / 1.5。 */
    val autoSpeed: Float = 1f,
    /** 上一拍余影（一句一屏时）。 */
    val ghost: Boolean = true,
    /** 提示语旁注；关掉时只显示名牌。 */
    val showAsides: Boolean = true,
    val showPortraits: Boolean = true,
    /** 默认直接显示：点一下下一句就在那里；墨晕、淡入、逐行、逐字是可选的显示效果。 */
    val textReveal: StageTextReveal = StageTextReveal.NONE,
    val layout: StageLayoutMode = StageLayoutMode.PAGE,
    /** 整页时，之前的段落与气泡压淡，视线落在最新一拍。 */
    val focusDim: Boolean = true,
    /** 精排书沿用书里的字体、颜色与边框。 */
    val bookStyles: Boolean = true,
    /** 背景图：图片库里的图片 id；为空用纸色或暗场。 */
    val backgroundImageId: String? = null,
    /** 背景图压暗（纸页是向纸色褪），0–0.9。 */
    val backgroundDim: Float = 0.55f,
    /** 背景图模糊，0–1（Android 12 起生效）。 */
    val backgroundBlur: Float = 0.25f,
    val halo: StageHalo = StageHalo.NONE,
    /** 光晕颜色：预设色板里的序号。 */
    val haloColor: Int = 1,
    /** 光晕浓淡，0–1。 */
    val haloStrength: Float = 0.45f,
    val texture: StagePaperTexture = StagePaperTexture.GRAIN,
    /** 泛黄，0–1：整屏染成旧书页的奶黄。 */
    val aging: Float = 0f,
    val weather: StageWeather = StageWeather.AUTO,
    /** 粒子密度，0–1。 */
    val weatherDensity: Float = 0.5f,
    /** 按原文的昼夜与情绪给背景染一层淡色。 */
    val moodTint: Boolean = true,
    val typeface: StageTypeface = StageTypeface.SONG,
    /** 水墨布景：按原文写到的地点、时段与天气画远山、江水、城市、窗影…… */
    val scenery: Boolean = true,
    /** 书法题字：章名、海报、拟声与书信用内置的行书。 */
    val brush: Boolean = true,
    /** 声景：环境声与配乐（默认关，工具栏一键打开）。 */
    val sound: Boolean = false,
    /** 环境声音量，0–1。 */
    val ambienceVolume: Float = 0.7f,
    /** 配乐音量，0–1。 */
    val musicVolume: Float = 0.45f,
    /** 提示音：开章的水滴、雷声、心跳、冲击。 */
    val soundCues: Boolean = true,
    /** AI 导演：用廉价模型按章校准布景、天气与情绪并挑金句（只返回标签，费用走自带 Key，默认关）。 */
    val aiDirector: Boolean = false,
    /** 设置格式的版本：旧版本存下的默认值在读取时换成新的默认。 */
    val revision: Int = CURRENT_REVISION
) {
    fun normalized(): StageSettings = copy(
        typingSpeed = typingSpeed.finiteIn(.5f, 2f, 1f),
        autoSpeed = autoSpeed.finiteIn(.5f, 2f, 1f),
        backgroundDim = backgroundDim.finiteIn(0f, .9f, .55f),
        backgroundBlur = backgroundBlur.finiteIn(0f, 1f, .25f),
        haloColor = haloColor.coerceIn(0, HALO_COLORS - 1),
        haloStrength = haloStrength.finiteIn(0f, 1f, .45f),
        aging = aging.finiteIn(0f, 1f, 0f),
        weatherDensity = weatherDensity.finiteIn(0f, 1f, .5f),
        ambienceVolume = ambienceVolume.finiteIn(0f, 1f, .7f),
        musicVolume = musicVolume.finiteIn(0f, 1f, .45f),
        revision = CURRENT_REVISION
    )

    companion object {
        /** 光晕预设色的个数（颜色值在界面层）。 */
        const val HALO_COLORS = 6
        /** 2：墨戏改版，不再默认顶部光晕；3：文字显示效果默认关（直接显示）。 */
        const val CURRENT_REVISION = 3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): StageSettings =
            raw?.let { text ->
                runCatching {
                    val value = json.decodeFromString(serializer(), text)
                    // 旧版本存下的默认值换成新的默认：整句淡入 / 墨晕（旧默认）→ 直接显示，顶部光晕（旧默认）→ 无。
                    val revision = if ("\"revision\"" in text) value.revision else 1
                    var migrated = value
                    if (revision < 2 && value.halo == StageHalo.TOP) migrated = migrated.copy(halo = StageHalo.NONE)
                    if (revision < 3 && (value.textReveal == StageTextReveal.FADE || value.textReveal == StageTextReveal.INK)) {
                        migrated = migrated.copy(textReveal = StageTextReveal.NONE)
                    }
                    migrated.normalized()
                }.getOrNull()
            } ?: StageSettings()

        fun encode(value: StageSettings): String = json.encodeToString(serializer(), value.normalized())
    }
}

private fun Float.finiteIn(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback
