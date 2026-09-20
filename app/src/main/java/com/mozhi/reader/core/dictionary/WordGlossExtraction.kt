package com.mozhi.reader.core.dictionary

private val metadataNames = listOf("原形", "词元", "词头", "单词", "音标", "读音", "发音", "词性", "词形", "时态", "词源", "语法说明", "语法", "词根", "词缀", "变形", "双语例句", "例句", "用法")
private val metadataField = Regex("(?:${metadataNames.joinToString("|")})\\s*[:：]")
private val meaningFields = listOf(
    listOf("简短释义", "简明释义", "核心词义"),
    listOf("当前语境义", "语境义", "本句释义", "本句含义"),
    listOf("中文释义", "中文意思", "中文含义", "基本释义", "释义", "词义", "含义", "意思", "翻译")
)
private val chineseMeaning = Regex("[\\u4e00-\\u9fff][\\u4e00-\\u9fff，、；]{0,15}")
private val partOfSpeech = Regex("^(?:及物动词|不及物动词|动词|名词|形容词|副词|介词|连词|代词|感叹词|助动词)(?:[\\s·:：.。;；]+|$)")

/** Select the meaning, never the first Chinese field name in a formatted dictionary answer. */
fun briefWordGloss(definition: String): WordGloss {
    val text = glossText(definition)
    val lines = text.lines().map(::cleanGlossLine).filter(String::isNotBlank)
    val ipa = Regex("/[^/\\r\\n]{1,64}/|\\[[^\\[\\]\\r\\n]{1,64}]").findAll(text)
        .firstOrNull { it.value.any(Char::isLetter) && !chineseMeaning.containsMatchIn(it.value) && ':' !in it.value }
        ?.value.orEmpty()
    if (lines.size == 1 && text.trim() == lines.single() && Regex("[\\u4e00-\\u9fff，、；]{1,16}").matches(lines.single()))
        return WordGloss(lines.single(), ipa)
    // A dedicated short meaning is preferred; older answers can supply context or numbered senses.
    meaningFields.forEach { fields ->
        val label = Regex("(?:${fields.joinToString("|")})(?:\\s*[:：]\\s*|$)")
        lines.forEachIndexed { index, line ->
            val match = label.find(line) ?: return@forEachIndexed
            val rest = line.substring(match.range.last + 1).trim()
            val value = rest.ifBlank { lines.getOrNull(index + 1).orEmpty() }
            meaningFromLine(value)?.let { return WordGloss(it, ipa) }
        }
    }
    var metadataSection = false
    text.lines().forEach { raw ->
        val line = cleanGlossLine(raw)
        if (line in metadataNames) { metadataSection = true; return@forEach }
        if (raw.trimStart().startsWith('#') || Regex("^\\s*\\d+[.)、]").containsMatchIn(raw)) metadataSection = false
        if (!metadataSection) meaningFromLine(line)?.let { return WordGloss(it, ipa) }
    }
    return WordGloss("", ipa)
}

private fun meaningFromLine(source: String): String? {
    var line = cleanGlossLine(source)
    val field = metadataField.find(line)
    if (field != null) line = line.substring(0, field.range.first)
    line = line.trim().removePrefix("（").removePrefix("(")
    line = line.replace(Regex("^(?:及物动词|不及物动词|动词|名词|形容词|副词)[）)]\\s*"), "")
    line = line.replace(partOfSpeech, "")
    val value = chineseMeaning.find(line)?.value?.trimEnd('，', '、', '；') ?: return null
    return value.takeUnless { it in metadataNames || meaningFields.any { names -> it in names } }
}

private fun cleanGlossLine(line: String) = line.trim().trimStart('#', '>', '-', '*', ' ', '•')
    .replace(Regex("^\\d+[.)、]\\s*"), "").trim()

private fun glossText(source: String): String {
    val doc = org.jsoup.Jsoup.parseBodyFragment(source.take(32_000))
    doc.select("script,style").remove()
    doc.select("br").before("\n")
    doc.select("p,div,li,h1,h2,h3,h4,tr").forEach { it.prependText("\n"); it.appendText("\n") }
    return doc.body().wholeText()
        .replace(Regex("\\[([^]\\n]+)]\\([^\\n)]*\\)"), "$1")
        .replace(Regex("[*`_]"), "")
}

/** Repair only a legacy field-label extraction; preserve the user's actual short meaning. */
internal fun VocabularyWord.repairMetadataGloss(): VocabularyWord {
    if (!EnglishWords.pattern.matches(word)) return this
    val label = metadataNames.firstOrNull { gloss == it || gloss.startsWith("$it：") || gloss.startsWith("$it:") } ?: return this
    val text = glossText(definition)
    val usedAsLabel = Regex("${Regex.escape(label)}\\s*[:：]").containsMatchIn(text) ||
        text.lines().any { it.trimStart().startsWith('#') && cleanGlossLine(it) == label }
    if (!usedAsLabel) return this
    val corrected = briefWordGloss(definition)
    return copy(gloss = corrected.meaning, phonetic = phonetic.ifBlank { corrected.phonetic })
}
