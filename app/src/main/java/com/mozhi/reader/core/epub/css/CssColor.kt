package com.mozhi.reader.core.epub.css

import kotlin.math.abs
import kotlin.math.roundToInt

object CssColor {
    /** Parser sentinel; declarations store currentColor as CssValue.Keyword("currentcolor"). */
    const val CURRENT_COLOR: Int = Int.MIN_VALUE

    fun parse(raw: String): Int? {
        val value = raw.trim().lowercase()
        if (value == "transparent") return 0
        if (value == "currentcolor") return CURRENT_COLOR
        if (value.startsWith('#')) return parseHex(value.drop(1))
        if (value.startsWith("rgb(") || value.startsWith("rgba(")) return parseRgb(value)
        if (value.startsWith("hsl(") || value.startsWith("hsla(")) return parseHsl(value)
        return named[value]?.let { 0xFF000000.toInt() or it }
    }

    private fun parseHex(hex: String): Int? = when (hex.length) {
        3 -> hex.toIntOrNull(16)?.let { rgb ->
            argb(255, (rgb shr 8 and 0xF) * 17, (rgb shr 4 and 0xF) * 17, (rgb and 0xF) * 17)
        }
        4 -> hex.toIntOrNull(16)?.let { rgba ->
            argb((rgba and 0xF) * 17, (rgba shr 12 and 0xF) * 17, (rgba shr 8 and 0xF) * 17, (rgba shr 4 and 0xF) * 17)
        }
        6 -> hex.toIntOrNull(16)?.let { 0xFF000000.toInt() or it }
        8 -> hex.toLongOrNull(16)?.let { rgba ->
            argb((rgba and 0xFF).toInt(), (rgba shr 24 and 0xFF).toInt(), (rgba shr 16 and 0xFF).toInt(), (rgba shr 8 and 0xFF).toInt())
        }
        else -> null
    }

    private fun parseRgb(raw: String): Int? {
        val body = raw.substringAfter('(').substringBeforeLast(')').trim()
        val slash = body.split('/', limit = 2)
        val components = slash[0].replace(',', ' ').split(Regex("\\s+")).filter(String::isNotBlank)
        val alphaText = slash.getOrNull(1)?.trim()
            ?: if (components.size == 4) components.last() else null
        val rgb = if (components.size == 4 && slash.size == 1) components.dropLast(1) else components
        if (rgb.size != 3) return null
        val channels = rgb.map { component ->
            if (component.endsWith('%')) {
                (component.dropLast(1).toFloatOrNull()?.coerceIn(0f, 100f)?.times(2.55f))?.roundToInt()
            } else {
                component.toFloatOrNull()?.coerceIn(0f, 255f)?.roundToInt()
            }
        }
        if (channels.any { it == null }) return null
        return argb(parseAlpha(alphaText) ?: 255, channels[0]!!, channels[1]!!, channels[2]!!)
    }

    private fun parseHsl(raw: String): Int? {
        val body = raw.substringAfter('(').substringBeforeLast(')').trim()
        val slash = body.split('/', limit = 2)
        val components = slash[0].replace(',', ' ').split(Regex("\\s+")).filter(String::isNotBlank)
        val alphaText = slash.getOrNull(1)?.trim()
            ?: if (components.size == 4) components.last() else null
        val hsl = if (components.size == 4 && slash.size == 1) components.dropLast(1) else components
        if (hsl.size != 3 || !hsl[1].endsWith('%') || !hsl[2].endsWith('%')) return null
        val hue = parseHue(hsl[0]) ?: return null
        val saturation = hsl[1].dropLast(1).toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return null
        val lightness = hsl[2].dropLast(1).toFloatOrNull()?.div(100f)?.coerceIn(0f, 1f) ?: return null
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val section = ((hue % 360f) + 360f) % 360f / 60f
        val x = chroma * (1f - abs(section % 2f - 1f))
        val (r1, g1, b1) = when (section.toInt()) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val m = lightness - chroma / 2f
        return argb(
            parseAlpha(alphaText) ?: 255,
            ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        )
    }

    private fun parseHue(raw: String): Float? = when {
        raw.endsWith("deg") -> raw.dropLast(3).toFloatOrNull()
        raw.endsWith("grad") -> raw.dropLast(4).toFloatOrNull()?.times(.9f)
        raw.endsWith("rad") -> raw.dropLast(3).toFloatOrNull()?.times(180f / Math.PI.toFloat())
        raw.endsWith("turn") -> raw.dropLast(4).toFloatOrNull()?.times(360f)
        else -> raw.toFloatOrNull()
    }

    private fun parseAlpha(raw: String?): Int? {
        if (raw == null) return null
        val value = if (raw.endsWith('%')) {
            raw.dropLast(1).toFloatOrNull()?.div(100f)
        } else {
            raw.toFloatOrNull()
        } ?: return null
        return (value.coerceIn(0f, 1f) * 255f).roundToInt()
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private val named: Map<String, Int> = buildMap {
        NAMED_COLOR_DATA.lineSequence().flatMap { it.trim().split(' ').asSequence() }
            .filter(String::isNotBlank)
            .forEach { entry ->
                val (name, hex) = entry.split('=', limit = 2)
                put(name, hex.toInt(16))
            }
    }

    private const val NAMED_COLOR_DATA = """
aliceblue=f0f8ff antiquewhite=faebd7 aqua=00ffff aquamarine=7fffd4
azure=f0ffff beige=f5f5dc bisque=ffe4c4 black=000000
blanchedalmond=ffebcd blue=0000ff blueviolet=8a2be2 brown=a52a2a
burlywood=deb887 cadetblue=5f9ea0 chartreuse=7fff00 chocolate=d2691e
coral=ff7f50 cornflowerblue=6495ed cornsilk=fff8dc crimson=dc143c
cyan=00ffff darkblue=00008b darkcyan=008b8b darkgoldenrod=b8860b
darkgray=a9a9a9 darkgreen=006400 darkgrey=a9a9a9 darkkhaki=bdb76b
darkmagenta=8b008b darkolivegreen=556b2f darkorange=ff8c00 darkorchid=9932cc
darkred=8b0000 darksalmon=e9967a darkseagreen=8fbc8f darkslateblue=483d8b
darkslategray=2f4f4f darkslategrey=2f4f4f darkturquoise=00ced1 darkviolet=9400d3
deeppink=ff1493 deepskyblue=00bfff dimgray=696969 dimgrey=696969
dodgerblue=1e90ff firebrick=b22222 floralwhite=fffaf0 forestgreen=228b22
fuchsia=ff00ff gainsboro=dcdcdc ghostwhite=f8f8ff gold=ffd700
goldenrod=daa520 gray=808080 green=008000 greenyellow=adff2f
grey=808080 honeydew=f0fff0 hotpink=ff69b4 indianred=cd5c5c
indigo=4b0082 ivory=fffff0 khaki=f0e68c lavender=e6e6fa
lavenderblush=fff0f5 lawngreen=7cfc00 lemonchiffon=fffacd lightblue=add8e6
lightcoral=f08080 lightcyan=e0ffff lightgoldenrodyellow=fafad2 lightgray=d3d3d3
lightgreen=90ee90 lightgrey=d3d3d3 lightpink=ffb6c1 lightsalmon=ffa07a
lightseagreen=20b2aa lightskyblue=87cefa lightslategray=778899 lightslategrey=778899
lightsteelblue=b0c4de lightyellow=ffffe0 lime=00ff00 limegreen=32cd32
linen=faf0e6 magenta=ff00ff maroon=800000 mediumaquamarine=66cdaa
mediumblue=0000cd mediumorchid=ba55d3 mediumpurple=9370db mediumseagreen=3cb371
mediumslateblue=7b68ee mediumspringgreen=00fa9a mediumturquoise=48d1cc mediumvioletred=c71585
midnightblue=191970 mintcream=f5fffa mistyrose=ffe4e1 moccasin=ffe4b5
navajowhite=ffdead navy=000080 oldlace=fdf5e6 olive=808000
olivedrab=6b8e23 orange=ffa500 orangered=ff4500 orchid=da70d6
palegoldenrod=eee8aa palegreen=98fb98 paleturquoise=afeeee palevioletred=db7093
papayawhip=ffefd5 peachpuff=ffdab9 peru=cd853f pink=ffc0cb
plum=dda0dd powderblue=b0e0e6 purple=800080 rebeccapurple=663399
red=ff0000 rosybrown=bc8f8f royalblue=4169e1 saddlebrown=8b4513
salmon=fa8072 sandybrown=f4a460 seagreen=2e8b57 seashell=fff5ee
sienna=a0522d silver=c0c0c0 skyblue=87ceeb slateblue=6a5acd
slategray=708090 slategrey=708090 snow=fffafa springgreen=00ff7f
steelblue=4682b4 tan=d2b48c teal=008080 thistle=d8bfd8
tomato=ff6347 turquoise=40e0d0 violet=ee82ee wheat=f5deb3
white=ffffff whitesmoke=f5f5f5 yellow=ffff00 yellowgreen=9acd32
"""
}
