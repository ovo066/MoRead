package com.mozhi.reader.core.stage

/** AI 导演给的一段场景；段号从零开始，空字段沿用本地规则。 */
data class StageDirection(
    val paragraph: Int,
    val setting: StageSetting? = null,
    val time: StageTime? = null,
    val weather: StageWeatherCue? = null,
    val mood: StageMood? = null,
    val light: StageLight? = null
)

/** 已核对的场景变化点和原文摘录。 */
data class StageDirectorResult(val directions: List<StageDirection>, val keyLines: List<StageKeyLine>)
data class StageKeyLine(val paragraph: Int, val quote: String)

/** 非空段落的章内原文区间；展示给模型的文字只去首尾空白。 */
data class StageParagraph(val index: Int, val start: Int, val end: Int, val text: String)

object StageDirections {
    fun paragraphs(body: String): List<StageParagraph> {
        val result = mutableListOf<StageParagraph>()
        var start = 0
        for (line in body.split('\n')) {
            if (line.isNotBlank()) result += StageParagraph(result.size, start, start + line.length, line.trim())
            start += line.length + 1
        }
        return result
    }

    /**
     * 段落映射到起点之后的第一拍；已演过的拍不改。AI 变化点持续到下一条，
     * 空字段实时沿用规则，情绪只覆盖十四拍。海报只改元数据，绝不替换文字。
     */
    fun merge(script: StageScript, body: String, result: StageDirectorResult, fromBeat: Int = 0): StageScript {
        if (fromBeat >= script.beats.size || (result.directions.isEmpty() && result.keyLines.isEmpty())) return script
        val start = fromBeat.coerceAtLeast(0)
        val paragraphs = paragraphs(body)
        val points = result.directions.associateBy { it.paragraph }.values.sortedBy { it.paragraph }.mapNotNull { direction ->
            val paragraph = paragraphs.getOrNull(direction.paragraph) ?: return@mapNotNull null
            val beat = script.beats.indexOfFirst { (it.fullRange?.start ?: -1) >= paragraph.start }
            if (beat < 0) null else beat to direction
        }.sortedBy { it.first }.toMap()
        var active: StageDirection? = null
        var activeAt = 0
        val scenes = script.scenes.filter { it.fromBeat < start }.toMutableList()
        var rule: StageScene? = null
        val rules = script.scenes.associateBy { it.fromBeat }
        for (i in script.beats.indices) {
            rules[i]?.let { rule = it }
            points[i]?.let { active = it; activeAt = i }
            if (i < start) continue
            val base = rule?.copy(fromBeat = i) ?: StageScene(i, null, null)
            val ai = active.takeIf { i >= fromBeat }
            val scene = if (ai == null) base else base.copy(
                setting = ai.setting ?: base.setting,
                time = ai.time ?: base.time,
                weather = ai.weather ?: base.weather,
                mood = if (ai.mood != null && i - activeAt < StageAtmosphere.MOOD_SPAN) ai.mood else base.mood,
                light = ai.light ?: base.light
            )
            if (scenes.lastOrNull()?.copy(fromBeat = i) != scene) scenes += scene
        }
        val keys = result.keyLines.groupBy { it.paragraph }
        val beats = script.beats.mapIndexed { i, beat ->
            if (i < fromBeat || beat.containerId != null || beat.overlayOn != null || beat.figure != null) return@mapIndexed beat
            val range = beat.fullRange ?: return@mapIndexed beat
            val paragraph = paragraphs.lastOrNull { it.start <= range.start } ?: return@mapIndexed beat
            if (range.end > paragraph.end) return@mapIndexed beat
            val text = trimPunctuation(beat.text)
            if (trimPunctuation(paragraph.text) != text) return@mapIndexed beat
            val matched = keys[paragraph.index].orEmpty().any { key ->
                val quote = key.quote.trim()
                quote.isNotEmpty() && paragraph.text.contains(quote) && text == quote &&
                    quote.codePointCount(0, quote.length) <= StageFigures.POSTER_MAX
            }
            if (matched) beat.copy(figure = StageFigure.POSTER, sources = beat.sources + ("figure" to StageSource.DIRECTOR)) else beat
        }
        return script.copy(beats = beats, scenes = if (points.isEmpty()) script.scenes else scenes)
    }

    private fun trimPunctuation(text: String): String = text.trim { char ->
        char.isWhitespace() || Character.getType(char) in punctuationTypes
    }

    private val punctuationTypes = setOf(
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt()
    )
}
