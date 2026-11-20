package com.mozhi.reader.core.stage

/** 人物档案里的一个人：[key] 是定妆图挂的人物键，[name] 是正名。 */
data class StagePortraitSubject(val key: String, val name: String, val aliases: List<String> = emptyList())

/**
 * 说话人头像的归属：把原文此处的称呼对到人物档案里的人。
 *
 * 名牌写的是原文此处的称呼，头像却是「这个人是谁」。档案常把「黑衣人」「那位大人」这类代称并到某个人名下，
 * 照着别称给头像，就会在原文揭晓之前把人认出来。所以正名直接算；别称只认与正名共用字的那些
 * （「林晚」的「晚晚」「林姑娘」），其余一律不给。同一个称呼对得上两个人时，谁都不给。
 */
object StagePortraits {
    fun index(subjects: List<StagePortraitSubject>): Map<String, String> {
        val owners = HashMap<String, MutableSet<String>>()
        for (subject in subjects) {
            val name = subject.name.trim()
            if (name.isEmpty() || subject.key.isBlank()) continue
            owners.getOrPut(name) { mutableSetOf() } += subject.key
            for (alias in subject.aliases) {
                val label = alias.trim()
                if (label.isNotEmpty() && label != name && sharesName(name, label)) {
                    owners.getOrPut(label) { mutableSetOf() } += subject.key
                }
            }
        }
        return owners.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }

    /** 别称是不是从正名来的：汉字名看有没有共用的字，拼写名看有没有共用的词。 */
    internal fun sharesName(name: String, alias: String): Boolean {
        val ideographs = name.filter(::isIdeograph)
        if (ideographs.isNotEmpty()) return alias.any { it in ideographs }
        val words = words(name)
        return words(alias).any { it in words }
    }

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private fun isIdeograph(char: Char): Boolean = Character.UnicodeScript.of(char.code).let {
        it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA ||
            it == Character.UnicodeScript.KATAKANA || it == Character.UnicodeScript.HANGUL
    }
}

/** 一幅已经生成的插图和它在原文里的位置（章内 UTF-16 下标）。 */
data class StageArtAnchor(val offset: Int, val image: StageImage)

/**
 * 把读者自己生成或伴读配的插图排进场记：演到那一句，图就在那一句之后出现。
 *
 * 插图不是原文，所以单独成拍、不带文字，原文区间是长度为零的一个点；它跟在整句对白、整个版式容器
 * 或整组特写之后，不把正在累积的画面拆开。
 */
object StageGeneratedArt {
    fun merge(script: StageScript, art: List<StageArtAnchor>): StageScript {
        if (art.isEmpty() || script.beats.none { it.fullRange != null }) return script
        val beats = script.beats
        val after = HashMap<Int, MutableList<StageBeat>>()
        val seen = HashSet<String>()
        for (anchor in art.sortedBy { it.offset }) {
            if (!seen.add(anchor.image.path)) continue
            var host = beats.indexOfFirst { beat -> beat.fullRange?.let { it.end > anchor.offset } == true }
            if (host < 0) host = beats.indexOfLast { it.fullRange != null }
            while (host < beats.lastIndex && beats[host].staysWith(beats[host + 1])) host++
            val end = (host downTo 0).firstNotNullOfOrNull { beats[it].fullRange?.end } ?: continue
            after.getOrPut(host) { mutableListOf() } +=
                StageBeat(BeatKind.IMAGE, "", SourceRange(end, end), image = anchor.image.copy(generated = true))
        }
        if (after.isEmpty()) return script
        // 场景分段和「阶梯、叠句」都用拍序认位置，前面插了图就得跟着后移。
        val shift = IntArray(beats.size)
        var inserted = 0
        for (index in beats.indices) {
            shift[index] = inserted
            inserted += after[index]?.size ?: 0
        }
        fun moved(index: Int) = index + shift.getOrElse(index) { inserted }
        return script.copy(
            beats = buildList(beats.size + inserted) {
                beats.forEachIndexed { index, beat ->
                    add(beat.figureGroup?.let { beat.copy(figureGroup = moved(it)) } ?: beat)
                    after[index]?.let(::addAll)
                }
            },
            scenes = script.scenes.map { it.copy(fromBeat = moved(it.fromBeat)) }
        )
    }

    private fun StageBeat.staysWith(next: StageBeat): Boolean =
        continued || (containerId != null && containerId == next.containerId) ||
            (figureGroup != null && figureGroup == next.figureGroup) || next.overlayOn != null && next.overlayOn == containerId
}
