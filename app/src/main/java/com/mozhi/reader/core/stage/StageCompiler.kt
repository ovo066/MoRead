package com.mozhi.reader.core.stage

private val systemSpeech = Regex("系统[^，。]{0,6}道[：:]")
private val bookTitleTerm = Regex("《[^》]{1,20}》|『[^』]{1,20}』")
private val shortDashSignature = Regex("^——+[^，。]{1,8}$")
private val leadingDashes = Regex("^——+")
private val trailingColonOrComma = Regex("[：:，,]$")
private val systemVoice = Regex("系统|电子音")
private val forumTitle = Regex("^[【\\[]|[？?。!！]$")
private val parallelVerse = Regex("^(.{3,9})[，,](.{3,9})$")
private val letterSignature = Regex("^——|^—|^落款")
private val letterClosing = Regex("^此致|^敬礼")
private val trailingColon = Regex("[：:]$")
private val statusKv = Regex("^[◆◇■□●○▶▷★☆·•\\s]*([^：:\\s]{1,8})[：:]\\s*(.+)$")
private const val StageBookImageText = "［图片］"

/** 「名字：内容」里的名字在标签位置以外至少出现这么多次，才当作人名收进说话人词表。 */
private const val SCRIPT_NAME_MIN_MENTIONS = 3
/** 引号后单独剩下的标点（“我信”。）：并进引语，不单独成拍。 */
private const val TRAILING_PUNCTUATION = "。，、！？；：…—～,.!?;:~ "
/** 上一段以「某人 + 说话动作」收尾、下一段直接是引号：这句话多半是他说的。 */
private val crossCueEnd = Regex("(?:开口|开了口|开了腔|解释|说道|说|问道|问|答道|回答|喊道|喊|叫道|补充|补了一句|追问|嘟囔|嘀咕|低语|宣告|宣布|插话|附和|打断|提醒|吩咐|招呼|安慰|劝道|笑道|骂道|叹道|反问|反驳|抗议|挽留|应声|接话|自语|喃喃|道)了?[。：:]?$")
private const val CROSS_CUE_CONFIDENCE = .72f

object StageCompiler {
    fun compile(chapterIndex: Int, title: String, body: String, context: StageCompileContext = StageCompileContext()): StageScript =
        Compilation(chapterIndex, title, body, context).compile()

    private class Compilation(val chapterIndex: Int, val title: String, val body: String, val context: StageCompileContext) {
        val lines = StageLines.readLines(body, context.book?.joinRanges.orEmpty())
        val grouped = StageGrouping.groupLines(lines)
        val lexicon = StageNames.mineNames(context.history + body)
        val known = linkedMapOf<String, StageKnownName>()
        val beats = mutableListOf<StageBeat>()
        val containers = mutableListOf<StageContainer>()
        val heads = linkedMapOf<Int, StageAside>()
        val consumedHeads = mutableSetOf<Int>()
        val names: List<String>
        val colorNames: Set<String>
        val protagonist: String?

        init {
            if ("系统" !in lexicon && (body.contains("系统道") || body.contains("系统：") || systemSpeech.containsMatchIn(body))) lexicon["系统"] = 4
            val scriptNames = linkedMapOf<String, Int>()
            for (l in lines) if (l.type == "chatline" && !l.bracket && l.name.length in 2..4 && !StageRules.pronounStart.containsMatchIn(l.name) && !StageRules.functionChars.containsMatchIn(l.name)) scriptNames[l.name] = (scriptNames[l.name] ?: 0) + 1
            for ((n, c) in scriptNames) {
                // 「名字：」的名字在标签位置以外的正文里也常出现，才算人名（「简介：」「主材料：」只出现在标签位置）。
                val outsideLabels = StageRules.count(body, n) - c
                if (n in lexicon || outsideLabels >= SCRIPT_NAME_MIN_MENTIONS) lexicon[n] = (lexicon[n] ?: 0) + 3 * c + outsideLabels
            }
            for (person in context.knownNames) for (n in listOf(person.name) + person.aliases) if (n.isNotBlank()) {
                known[n] = person
                lexicon[n] = (lexicon[n] ?: 0) + 100
            }
            names = lexicon.keys.sortedByDescending { it.length }
            colorNames = lexicon.entries.filter { it.key != "系统" }.sortedByDescending { it.value }.take(10).map { it.key }.toSet()
            protagonist = context.knownNames.firstOrNull { it.protagonist }?.name ?: colorNames.maxByOrNull { StageRules.count(body, it) }?.takeIf { StageRules.count(body, it) > 0 }
        }

        /** 行号 → 上一段旁白点名的说话人（「李智慧开了口。」后面紧跟的引语）。只认段中恰好一个人名的情形。 */
        val crossCue = HashMap<Int, String>()
        init {
            for (k in 0 until lines.lastIndex) {
                val l = lines[k]
                val next = lines[k + 1]
                if (l.type != "prose" || next.type != "prose" || StageRules.quoteMarks.containsMatchIn(l.text)) continue
                if (!crossCueEnd.containsMatchIn(l.text) || StageQuotes.findQuotes(next.text).firstOrNull()?.start != 0) continue
                val found = StageNames.namesIn(l.text, names).map { it.name }.distinct()
                if (found.size == 1) crossCue[k + 1] = found[0]
            }
        }

        fun nameSource(n: String): StageSource = known[n]?.source ?: StageSource.RULE
        fun speaker(n: String, confidence: Float, source: StageSource = nameSource(n)) = StageSpeaker(n, confidence, source, unknown = n == "？？？", isProtagonist = n == protagonist || known[n]?.name == protagonist)
        fun nameSpans(text: String): List<StageSpan> {
            val out = StageNames.namesIn(text, colorNames).map { StageSpan(it.at, it.at + it.name.length, setOf(SpanStyle.NAME), it.name, nameSource(it.name)) }.toMutableList()
            for (m in bookTitleTerm.findAll(text)) out += StageSpan(m.range.first, m.range.last + 1, setOf(SpanStyle.TERM))
            return out
        }
        fun narration(text: String, start: Int) {
            for (s in StageQuotes.splitSentences(text, start, StageRules.maxNarration)) {
                val shown = StageRules.clean(s.text)
                if (shown.isNotEmpty()) beats += StageBeat(BeatKind.NARRATION, shown, SourceRange(s.start, s.end), spans = nameSpans(shown))
            }
        }

        private data class UnitPart(
            val quote: Boolean, val start: Int, val end: Int, val bracket: Boolean = false, val open: Boolean = false,
            var before: StageAside? = null, var after: StageAside? = null, var used: Boolean = false,
            /** 引语的原文终点：引号后紧跟的句末标点（“我信”。）并进来，不单独成拍。 */
            var rangeEnd: Int = end,
            /** 旁白段开头并进前一句引语的标点字数。 */
            var skip: Int = 0
        )

        fun emitProse(l: StageLine, lineIndex: Int) {
            val t = l.text
            val base = l.start
            val qs = StageQuotes.findQuotes(t)
            if (qs.isEmpty()) {
                if (t.startsWith("——") && t.length > 3 && !shortDashSignature.matches(t)) {
                    val inner = t.replace(leadingDashes, "")
                    beats += StageBeat(BeatKind.DIALOGUE, StageRules.clean(inner), l.range, voice = DialogueVoice.QUOTE, spans = nameSpans(inner))
                } else narration(t, base)
                return
            }
            val units = mutableListOf<UnitPart>()
            var cur = 0
            for (q in qs) {
                if (q.start > cur) units += UnitPart(false, cur, q.start)
                units += UnitPart(true, q.start, q.end, q.bracket, q.open)
                cur = q.end
            }
            if (cur < t.length) units += UnitPart(false, cur, t.length)
            if (units.first().quote) units.first().before = heads[lineIndex]
            for ((k, u) in units.withIndex()) {
                if (u.quote) continue
                val prev = units.getOrNull(k - 1)?.takeIf { it.quote }
                val next = units.getOrNull(k + 1)?.takeIf { it.quote }
                // 引号后紧跟的标点（“……”。这个问题……、“……”，大掌……）并进引语，旁白从标点后面起。
                val whole = t.substring(u.start, u.end)
                if (prev != null) {
                    val lead = whole.trimStart()
                    val punct = lead.takeWhile { it in TRAILING_PUNCTUATION }.length
                    if (punct > 0) {
                        u.skip = whole.length - lead.length + punct
                        prev.rangeEnd = u.start + u.skip
                    }
                }
                val raw = whole.substring(u.skip)
                val txt = raw.trim()
                if (txt.isEmpty()) { u.used = true; continue }
                val start = base + u.start + u.skip + raw.indexOf(txt)
                val aside = StageAside(StageRules.clean(txt), SourceRange(start, start + txt.length))
                // 连续传音共用中间的说话提示，挂在前句才能让后句继承说话人。
                if (prev?.bracket == true && next?.bracket == true && prev.after == null && txt.length <= 40 && StageRules.headEnd.containsMatchIn(txt)) { prev.after = aside; u.used = true }
                else if (next != null && next.before == null && txt.length <= 40 && (StageRules.headEnd.containsMatchIn(txt) || (prev == null && trailingColonOrComma.containsMatchIn(txt)))) { next.before = aside; u.used = true }
                else if (txt.length <= StageRules.maxAside && prev != null && prev.after == null) { prev.after = aside; u.used = true }
                else if (txt.length <= 40 && next != null && next.before == null && trailingColonOrComma.containsMatchIn(txt)) { next.before = aside; u.used = true }
                else if (prev != null && prev.after == null) {
                    // 「“错了。”他说。然后他继续往前走……」：只把开头那句说话提示挂成旁注，其余照常是旁白。
                    val end = firstSentenceEnd(txt)
                    if (end in 1 until txt.length && end <= StageRules.maxAside && StageRules.verb.containsMatchIn(txt.substring(0, end))) {
                        prev.after = StageAside(StageRules.clean(txt.substring(0, end)), SourceRange(start, start + end))
                        u.skip += raw.indexOf(txt) + end
                    }
                }
            }
            var lastSpeaker: StageSpeaker? = null
            var quoteNo = 0
            for (u in units) {
                if (!u.quote) { if (!u.used) narration(t.substring(u.start + u.skip, u.end), base + u.start + u.skip); continue }
                val raw = t.substring(u.start, u.end)
                val inner = if (u.open) raw.drop(1) else raw.substring(1, raw.length - 1)
                val asideText = u.before?.text.orEmpty() + u.after?.text.orEmpty()
                val cue = crossCue[lineIndex]?.takeIf { quoteNo == 0 && u.start == 0 && u.before == null }
                val sp = StageNames.inferSpeaker(u.before, names, true) ?: StageNames.inferSpeaker(u.after, names, false)
                    ?: cue?.let { StageSpeakerMatch(it, CROSS_CUE_CONFIDENCE) }
                var who: StageSpeaker? = null
                val sources = linkedMapOf<String, StageSource>()
                if (sp != null) { who = speaker(sp.name, sp.confidence); sources["speaker"] = who.source; lastSpeaker = who }
                else if (lastSpeaker != null && quoteNo > 0 && u.before == null && u.after == null) {
                    who = lastSpeaker.copy(confidence = .7f, source = nameSource(lastSpeaker.label))
                    sources["speaker"] = who.source
                }
                else if (u.before != null || u.after != null) lastSpeaker = null
                quoteNo++
                val thought = StageRules.thoughtCue.containsMatchIn(asideText)
                val voice = when {
                    // 全角方括号是系统与提示的声音（［星座‘…’对你的选择很满意。］）。
                    u.bracket && t[u.start] == '［' -> DialogueVoice.SYSTEM
                    u.bracket -> if (who != null && systemVoice.containsMatchIn(who.label + asideText)) DialogueVoice.SYSTEM else DialogueVoice.BRACKET
                    else -> DialogueVoice.QUOTE
                }
                val emo = StageQuotes.emotionFrom(listOf(u.before, u.after), inner)
                if (emo.emotion != null) sources["emotion"] = StageSource.RULE
                if (emo.instruction != null) sources["instruction"] = StageSource.RULE
                val chunks = StageQuotes.chunkDialogue(inner, base + u.start + 1, StageRules.maxDialogue)
                for ((n, c) in chunks.withIndex()) beats += StageBeat(
                    if (thought) BeatKind.THOUGHT else BeatKind.DIALOGUE, StageRules.clean(c.text),
                    when {
                        chunks.size == 1 -> SourceRange(base + u.start, base + u.rangeEnd)
                        n == chunks.lastIndex && u.rangeEnd > u.end -> SourceRange(c.start, base + u.rangeEnd)
                        else -> SourceRange(c.start, c.end)
                    },
                    asideBefore = u.before.takeIf { n == 0 }, asideAfter = u.after.takeIf { n == chunks.lastIndex },
                    speaker = who?.takeIf { it.confidence >= .65f && !thought }, voice = voice,
                    emotion = emo.emotion, intensity = emo.intensity, instruction = emo.instruction,
                    continued = n < chunks.lastIndex, sources = sources.toMap(), spans = nameSpans(StageRules.clean(c.text))
                )
            }
        }

        fun emitGroup(g: StageGroup) {
            val cid = containers.size
            val source = mapOf("container" to StageSource.RULE)
            when (g.type) {
                "chat" -> {
                    val others = g.items.filter { it.type != "sticker" }.map { it.name }.filter { it != protagonist }.distinct()
                    containers += StageContainer(cid, StageContainerType.CHAT, others.joinToString("、"))
                    var lastSender = ""
                    for (l in g.items) {
                        val sticker = l.type == "sticker"
                        if (!sticker) lastSender = l.name
                        beats += StageBeat(BeatKind.CHAT, StageRules.clean(if (sticker) l.text else l.content), l.range, containerId = cid,
                            item = StageContainerItem(sender = lastSender, isMe = protagonist != null && lastSender == protagonist, sticker = sticker),
                            sources = source, spans = if (sticker) emptyList() else nameSpans(l.content).filter { SpanStyle.TERM in it.styles })
                    }
                }
                "forum" -> {
                    var board = ""
                    var ftitle = ""
                    var titleLine: StageLine? = null
                    for (h in g.head) {
                        if (forumTitle.containsMatchIn(h.text) || h.text.length > 16) { ftitle = h.text; titleLine = h } else board = h.text
                    }
                    containers += StageContainer(cid, StageContainerType.FORUM, ftitle, board)
                    // 页眉定位到实际展示的一行，不能让正文坐标跨换行。
                    if (g.head.isNotEmpty()) beats += StageBeat(BeatKind.FORUM_HEAD, ftitle.ifEmpty { board }, (titleLine ?: g.head.last()).range, containerId = cid, sources = source)
                    for (f in g.floors) for ((n, it) in f.items.withIndex()) {
                        val embedded = n > 0 && (StageRules.pronounStart.containsMatchIn(it.text) || StageNames.namesIn(it.text.take(6), names).firstOrNull()?.at == 0)
                        beats += StageBeat(if (embedded) BeatKind.NARRATION else BeatKind.FORUM, StageRules.clean(it.text), it.line.range,
                            containerId = cid, embedded = embedded,
                            item = if (embedded) null else StageContainerItem(floor = f.floor, author = f.author, time = f.time, continuation = n > 0),
                            sources = if (embedded) emptyMap() else source, spans = if (embedded) nameSpans(StageRules.clean(it.text)) else emptyList())
                    }
                }
                else -> {
                    val type = StageContainerType.valueOf(g.type.uppercase())
                    containers += StageContainer(cid, type)
                    for ((k, l) in g.items.withIndex()) {
                        val item = when (type) {
                            StageContainerType.LETTER -> {
                                // 句号不属于下半句字数，否则七言对句会被算成七对八。
                                val m = parallelVerse.find(l.text.trimEnd('。', '！'))?.groupValues
                                val role = when {
                                    k == 0 && trailingColonOrComma.containsMatchIn(l.text) && l.text.length <= 14 -> LetterLineRole.SALUTATION
                                    letterSignature.containsMatchIn(l.text) || (k == g.items.lastIndex && k > 0 && letterClosing.containsMatchIn(g.items[k - 1].text) && l.text.length <= 12) -> LetterLineRole.SIGNATURE
                                    m != null && m[1].length == m[2].length -> LetterLineRole.VERSE
                                    else -> LetterLineRole.BODY
                                }
                                StageContainerItem(letterRole = role)
                            }
                            StageContainerType.STATUS -> StageContainerItem(label = l.name)
                            else -> null
                        }
                        beats += StageBeat(BeatKind.valueOf(g.type.uppercase()), StageRules.clean(if (type == StageContainerType.STATUS) l.content else l.text), l.range, containerId = cid, item = item, sources = source)
                    }
                }
            }
        }

        val book = context.book
        /** 行号 → 覆盖它的书内结构区域。 */
        val regionAt = HashMap<Int, StageBookRegion>()
        init {
            if (book != null) for (region in book.regions) for ((k, l) in lines.withIndex()) {
                if (l.start < region.end && l.end > region.start && k !in regionAt) regionAt[k] = region
            }
        }

        /** 书里标好的结构：区域内每一项按行出拍，同一项的后续行算续段。 */
        fun emitBookRegion(region: StageBookRegion) {
            if (region.title == StageBookLayoutReader.CAPTION_MARK) return
            val cid = containers.size
            val type = region.type
            val chatOthers = region.items.mapNotNull { it.item.sender }.filter { it != protagonist }.distinct()
            containers += StageContainer(cid, type, if (type == StageContainerType.CHAT) region.title ?: chatOthers.joinToString("、") else region.title, fromBook = true, box = region.box)
            val source = mapOf("container" to StageSource.DIRECTOR)
            for (it in region.items) {
                var first = true
                for (l in lines) {
                    if (l.end <= it.start || l.start >= it.end) continue
                    val s = maxOf(l.start, it.start)
                    val e = minOf(l.end, it.end)
                    val raw = body.substring(s, e)
                    val shown = StageRules.clean(raw).replace(StageBookImageText, "").trim()
                    if (shown.isEmpty() && !it.item.voice) continue
                    val lead = raw.indexOf(shown.take(1)).coerceAtLeast(0)
                    // 展示文字可能去掉了段内的图片占位，原文区间按两端修剪，不能按展示长度截。
                    var tail = e
                    while (tail > s + lead + 1 && body[tail - 1].isWhitespace()) tail--
                    val r = if (shown.isEmpty()) SourceRange(s, e) else SourceRange(s + lead, tail)
                    val kind = when (type) {
                        StageContainerType.CHAT -> BeatKind.CHAT
                        StageContainerType.FORUM -> BeatKind.FORUM
                        StageContainerType.STATUS -> BeatKind.STATUS
                        StageContainerType.DANMAKU -> BeatKind.DANMAKU
                        StageContainerType.LETTER -> BeatKind.LETTER
                        StageContainerType.VERSE -> BeatKind.VERSE
                        else -> BeatKind.CARD
                    }
                    var item = it.item.copy(continuation = !first)
                    if (type == StageContainerType.CHAT) {
                        val sender = item.sender
                        item = item.copy(isMe = item.isMe || (sender != null && sender == protagonist))
                    }
                    if (type == StageContainerType.STATUS && item.label == null) {
                        val kv = statusKv.find(shown)
                        if (kv != null) item = item.copy(label = kv.groupValues[1])
                    }
                    val shownText = if (type == StageContainerType.STATUS && item.label != null) statusKv.find(shown)?.groupValues?.get(2) ?: shown else shown
                    beats += StageBeat(kind, shownText, r, containerId = cid, item = item, sources = source,
                        spans = if (type == StageContainerType.CHAT || type == StageContainerType.DANMAKU) emptyList() else nameSpans(shownText).filter { SpanStyle.TERM in it.styles })
                    first = false
                }
            }
        }

        fun emitImage(l: StageLine) {
            val b = book
            val at = (l.start until l.end).firstOrNull { b?.images?.containsKey(it) == true || b?.separatorImages?.contains(it) == true || b?.decorativeImages?.contains(it) == true }
            when {
                b == null || at == null -> beats += StageBeat(BeatKind.IMAGE, "", l.range)
                at in b.separatorImages -> beats += StageBeat(BeatKind.BREAK, "", l.range)
                at in b.decorativeImages -> Unit
                else -> beats += StageBeat(BeatKind.IMAGE, "", l.range, image = b.images[at])
            }
        }

        /** 书里标出的删除线、加粗：换算到各拍展示文字上。 */
        fun applyBookSpans(list: List<StageBeat>): List<StageBeat> {
            val spans = book?.spans.orEmpty()
            if (spans.isEmpty()) return list
            return list.map { beat ->
                val r = beat.range ?: return@map beat
                if (beat.text.isEmpty()) return@map beat
                val hits = spans.filter { it.start < r.end && it.end > r.start }
                if (hits.isEmpty()) return@map beat
                val at = body.indexOf(beat.text, r.start).takeIf { it >= 0 && it < r.end } ?: return@map beat
                val extra = hits.mapNotNull { sp ->
                    val a = (sp.start - at).coerceAtLeast(0)
                    val e = (sp.end - at).coerceAtMost(beat.text.length)
                    if (a < e) StageSpan(a, e, setOf(sp.style), source = StageSource.DIRECTOR) else null
                }
                beat.copy(spans = beat.spans + extra)
            }
        }

        /**
         * 精排书的字体、颜色与框落到各拍：整拍落在某个书内框里就带上这个框（容器自己的框留给容器）；
         * 文字样子取盖住整拍八成以上的那段，否则取所在段落的；只盖住几个字的换成行内样式。
         */
        fun applyBookStyles(list: List<StageBeat>): List<StageBeat> {
            val styles = book?.styles ?: return list
            if (styles.inks.isEmpty() && styles.boxes.isEmpty()) return list
            return list.map { beat ->
                val r = beat.range ?: return@map beat
                if (beat.text.isEmpty()) return@map beat
                val full = beat.fullRange ?: r
                val box = if (beat.containerId != null) null else styles.boxes
                    .filter { it.start <= full.start && it.end >= full.end }
                    .minByOrNull { it.end - it.start }?.box
                val covering = styles.inks.filter { it.start <= r.start && it.end >= r.end }
                val block = covering.filter { it.block }.minByOrNull { it.end - it.start }?.ink
                val wide = covering.filter { !it.block }.minByOrNull { it.end - it.start }?.ink
                val at = body.indexOf(beat.text, r.start).takeIf { it >= 0 && it < r.end }
                val inline = if (at == null) emptyList() else styles.inks.filter { !it.block && it.start < r.end && it.end > r.start && !(it.start <= r.start && it.end >= r.end) }
                    .mapNotNull { ink ->
                        val a = (ink.start - at).coerceAtLeast(0)
                        val e = (ink.end - at).coerceAtMost(beat.text.length)
                        if (a < e && e - a < beat.text.length * 4 / 5) StageSpan(a, e, emptySet(), source = StageSource.DIRECTOR, ink = ink.ink) else null
                    }
                val ink = wide ?: block ?: box?.ink
                if (box == null && ink == null && inline.isEmpty()) beat else beat.copy(box = box, ink = ink, spans = beat.spans + inline)
            }
        }

        fun compile(): StageScript {
            beats += StageBeat(BeatKind.TITLE, title, null)
            // 正文开头重复了章名（「第2章」「敲诈者」分两行）：标题卡已经演过，不再当旁白念一遍。
            val bare = title.filterNot(Char::isWhitespace)
            var seen = ""
            for (k in lines.indices) {
                // 精排书的章首题图常排在章名行前后：跳过图片行，不打断章名匹配。
                if (lines[k].type == "image") continue
                val t = lines[k].text.filterNot(Char::isWhitespace)
                if (t.isEmpty() || bare.isEmpty() || !bare.startsWith(seen + t)) break
                seen += t
                consumedHeads += k
                if (seen == bare) break
            }
            if (seen != bare) consumedHeads.removeAll((0 until lines.size).toSet())
            for (i in 0 until lines.lastIndex) {
                if (grouped.used[i] || grouped.used[i + 1]) continue
                val l = lines[i]
                val next = lines[i + 1]
                val q = if (next.type == "prose") StageQuotes.findQuotes(next.text).firstOrNull() else null
                if (l.type in listOf("prose", "salutation") && trailingColon.containsMatchIn(l.text) && l.text.length <= 60 && !StageRules.quoteMarks.containsMatchIn(l.text) && q?.start == 0) { heads[i + 1] = StageAside(StageRules.clean(l.text), l.range); consumedHeads += i }
            }
            var i = 0
            while (i < lines.size) {
                val region = regionAt[i]
                if (region != null) {
                    emitBookRegion(region)
                    while (i < lines.size && regionAt[i] === region) i++
                    continue
                }
                val g = grouped.groups[i]
                if (g != null) { emitGroup(g); i = g.end; continue }
                if (grouped.used[i] || i in consumedHeads) { i++; continue }
                val l = lines[i]
                if (book != null && book.noteRanges.any { l.start >= it.start && l.start < it.end }) {
                    // 脚注正文：小字注，不当旁白念出来。
                    val shown = StageRules.clean(l.text)
                    if (shown.isNotEmpty()) beats += StageBeat(BeatKind.NOTE, shown, l.range)
                    i++
                    continue
                }
                when (l.type) {
                    "image" -> emitImage(l)
                    "note", "sticker" -> beats += StageBeat(BeatKind.NOTE, StageRules.clean(l.text), l.range)
                    "break" -> beats += StageBeat(BeatKind.BREAK, l.text, l.range)
                    "thought" -> beats += StageBeat(BeatKind.THOUGHT, StageRules.clean(l.text.substring(1, l.text.length - 1)), l.range, spans = nameSpans(l.text.substring(1, l.text.length - 1)))
                    "sfx" -> beats += StageBeat(BeatKind.SFX, StageRules.clean(l.text), l.range, screen = ScreenEffect.SHAKE, sources = mapOf("screen" to StageSource.RULE))
                    "caption" -> beats += StageBeat(BeatKind.CAPTION, StageRules.clean(l.text), l.range)
                    "voiceLine" -> beats += StageBeat(BeatKind.DIALOGUE, StageRules.clean(l.body), l.range, voice = DialogueVoice.BRACKET, spans = nameSpans(l.body))
                    "system" -> {
                        val prev = beats.lastOrNull()
                        val overlay = prev?.containerId?.takeIf { i > 0 && grouped.used[i - 1] }
                        beats += StageBeat(BeatKind.SYSTEM, StageRules.clean(l.body.ifEmpty { l.text }), l.range, systemLabel = l.label.ifEmpty { null }, overlayOn = overlay)
                    }
                    "chatline" -> if (l.name !in lexicon) emitProse(l, i) else beats += StageBeat(BeatKind.DIALOGUE, StageRules.clean(l.content), l.range, speaker = speaker(l.name, .8f), voice = DialogueVoice.QUOTE, sources = mapOf("speaker" to nameSource(l.name)), spans = nameSpans(l.content))
                    else -> emitProse(l, i)
                }
                i++
            }
            val finished = StageFigures.apply(StageAmbience.apply(applyBookStyles(applyBookSpans(beats.toList()))), body)
            val era = StageAtmosphere.era(body, context.history)
            return StageScript(
                chapterIndex, title, finished, containers.toList(), protagonist, names, book?.titleArt, book?.breakArt,
                StageAtmosphere.scan(finished, StageAtmosphere.opening(context.history), era), book?.styles?.titleInk, era
            )
        }
    }
}

/** 第一句的终点（句末标点与紧随的收口符号之后）；没有句末标点返回 -1。 */
private fun firstSentenceEnd(text: String): Int {
    val i = text.indexOfFirst { it in "。！？!?" }
    if (i < 0) return -1
    var e = i + 1
    while (e < text.length && text[e] in "。！？!?…”」』’\"）)") e++
    return e
}
