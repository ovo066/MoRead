package com.mozhi.reader.core.stage

import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.epub.style.StyledDomNode

/** 一张书内图片：章内原文偏移 + 资源路径（阅读页插图同一套）。 */
data class StageBookImageSource(val offset: Int, val path: String, val width: Int = 0, val height: Int = 0)

/**
 * 把 EPUB 精排结构（已解析、带原文坐标的 DOM）读成演出线索。
 *
 * 精排书的排版者已经把聊天框、楼层、弹幕、报纸、档案、属性面板、引文框标在 class 上；
 * 这里按 class 词根认结构，认不出就什么都不给，交回本地规则。只读结构，不读样式表。
 */
object StageBookLayoutReader {
    private val avatarClass = Regex("avatar|touxiang|head-?img|tx\\b", RegexOption.IGNORE_CASE)
    private val iconClass = Regex("icon|emoji|biaoqing", RegexOption.IGNORE_CASE)
    private val logoClass = Regex("^logo|title-?img|chapter-?img|toutu|headpic", RegexOption.IGNORE_CASE)
    private val separatorClass = Regex("fengexian|separator|divider|fgx|hr-?img", RegexOption.IGNORE_CASE)
    private val captionClass = Regex("ziti|caption|tuzhu|maintitle|subtitle|pic-?txt|img-?txt", RegexOption.IGNORE_CASE)
    private val imageBoxClass = Regex("duokan-image|image|img|pic|tu\\b|illus", RegexOption.IGNORE_CASE)

    private val chatClass = Regex("^(lt|wx|qq|chat|wechat|im|msg)[_-]?(div|box|wrap|area|container|window)$|^chat|wechat", RegexOption.IGNORE_CASE)
    private val chatRowClass = Regex("^(lt|wx|qq|chat|msg)[_-]?(divzu|row|item|line|group)\\d*$|^(message|msg)$", RegexOption.IGNORE_CASE)
    private val chatNameClass = Regex("name", RegexOption.IGNORE_CASE)
    private val chatBubbleClass = Regex("bubble|border|content|text|qipao", RegexOption.IGNORE_CASE)
    private val chatTitleClass = Regex("title|header|top", RegexOption.IGNORE_CASE)
    private val chatTimeClass = Regex("time", RegexOption.IGNORE_CASE)
    private val rightClass = Regex("right|righ$|\\bme\\b|self|mine", RegexOption.IGNORE_CASE)
    private val voiceClass = Regex("yuyin|voice|audio", RegexOption.IGNORE_CASE)

    private val danmakuClass = Regex("danmaku|danmu|barrage", RegexOption.IGNORE_CASE)
    private val forumClass = Regex("forum|thread|tieba|bbs", RegexOption.IGNORE_CASE)
    private val replyClass = Regex("reply-?post|^op-?post|floor-?item|^post$|^reply$", RegexOption.IGNORE_CASE)
    private val floorClass = Regex("floor|op-?label|louceng", RegexOption.IGNORE_CASE)
    private val authorClass = Regex("author|user|nick", RegexOption.IGNORE_CASE)
    private val postTitleClass = Regex("post-?title|thread-?title", RegexOption.IGNORE_CASE)
    private val socialClass = Regex("^weibo$|moments|pyq|friend-?circle|^tweet", RegexOption.IGNORE_CASE)
    private val accountClass = Regex("-id$|account|user|nick|name", RegexOption.IGNORE_CASE)
    private val newsClass = Regex("^newspaper$|^news$|^baozhi", RegexOption.IGNORE_CASE)
    private val mastheadClass = Regex("newspaper-?title|masthead|baotou", RegexOption.IGNORE_CASE)
    private val focusClass = Regex("focus|headline|big", RegexOption.IGNORE_CASE)
    private val statusClass = Regex("^hud|status|panel|shuxing|attr|mianban|^border9$", RegexOption.IGNORE_CASE)
    private val statusTitleClass = Regex("title|tit$", RegexOption.IGNORE_CASE)
    private val documentClass = Regex("^dang|^file|document|dossier|archive|^doc[_-]", RegexOption.IGNORE_CASE)
    private val notebookClass = Regex("yinwen|^quote|blockquote|^note|notebook|diary|riji|^border\\d*(-\\d+)?$|roundsolid|^kuang|^box\\d*$|^frame|^neirong|^author$", RegexOption.IGNORE_CASE)
    private val strikeClass = Regex("^del|delete|strike|shanchu", RegexOption.IGNORE_CASE)
    private val footnoteClass = Regex("footnote|zhushi|noteref|fn-?ref|aside-?fn", RegexOption.IGNORE_CASE)

    /** 引文 / 笔记框超过这么多行就不当卡片：那是排版用的大框，不是书里的「一张纸」。 */
    private const val MAX_CARD_LINES = 40

    /**
     * [styled] 是同一棵 DOM 算好级联后的样式树：有它时一并读出书里的字体、颜色与框（[StageBookStyleReader]），
     * [fonts] 是书内嵌字体的族名，[resourcePath] 把样式表里的图片地址换成本地路径。
     */
    fun read(
        body: EpubDomNode,
        text: String,
        images: List<StageBookImageSource>,
        styled: StyledDomNode? = null,
        fonts: Set<String> = emptySet(),
        resourcePath: (String) -> String? = { null },
        themeTextArgb: Int = DEFAULT_TEXT
    ): StageBookLayout {
        val styles = styled?.let { runCatching { StageBookStyleReader.read(it, text, fonts, resourcePath, themeTextArgb) }.getOrNull() } ?: StageBookStyles()
        /** 落在 [start, end) 里、最贴近它的书内框。 */
        fun boxAround(start: Int, end: Int, within: IntRange? = null): StageBox? = styles.boxes
            .filter { it.start <= start && it.end >= end && (within == null || (it.start >= within.first && it.end <= within.last + 1)) }
            .minByOrNull { it.end - it.start }?.box
        val byOffset = images.associateBy { it.offset }
        val regions = mutableListOf<StageBookRegion>()
        val stageImages = linkedMapOf<Int, StageImage>()
        val decorative = linkedSetOf<Int>()
        val separators = linkedSetOf<Int>()
        val spans = mutableListOf<StageBookSpan>()
        var titleArt: StageImage? = null
        var breakArt: StageImage? = null

        fun EpubDomNode.cls(): String = classes.joinToString(" ")
        fun EpubDomNode.has(re: Regex): Boolean = classes.any { re.containsMatchIn(it) }
        fun range(n: EpubDomNode): IntRange? {
            var a = Int.MAX_VALUE
            var b = -1
            fun walk(x: EpubDomNode) {
                if (x.textStart >= 0 && x.textEnd >= x.textStart) { a = minOf(a, x.textStart); b = maxOf(b, x.textEnd) }
                x.children.forEach(::walk)
            }
            walk(n)
            return if (b < 0) null else a until b
        }
        fun textOf(n: EpubDomNode): String = range(n)?.let { text.substring(it.first, it.last + 1).trim() }.orEmpty()
        fun descendants(n: EpubDomNode): Sequence<EpubDomNode> = sequence {
            for (c in n.children) { yield(c); yieldAll(descendants(c)) }
        }
        fun images(n: EpubDomNode) = (sequenceOf(n) + descendants(n)).filter { it.tag == "img" || it.tag == "image" }
        fun item(n: EpubDomNode, item: StageContainerItem): StageBookItem? =
            range(n)?.let { r -> trimmed(text, r.first, r.last + 1)?.let { (s, e) -> StageBookItem(s, e, item) } }
        fun imageOf(img: EpubDomNode): StageImage? =
            byOffset[img.textStart]?.let { StageImage(it.path, it.width, it.height) }

        fun chat(n: EpubDomNode): StageBookRegion? {
            val rows = descendants(n).filter { row -> row.has(chatRowClass) && row.children.any { it.has(chatBubbleClass) } }.toList()
            if (rows.isEmpty()) return null
            val items = mutableListOf<StageBookItem>()
            for (row in rows) {
                val name = row.children.firstOrNull { it.has(chatNameClass) }?.let(::textOf)?.takeIf { it.isNotEmpty() }
                val me = row.has(rightClass)
                val avatar = row.children.firstOrNull { (it.tag == "img") && it.has(avatarClass) }?.let(::imageOf)
                for (bubble in row.children.filter { it.has(chatBubbleClass) && !it.has(chatNameClass) }) {
                    val voice = images(bubble).any { it.has(voiceClass) } || descendants(bubble).any { it.has(voiceClass) }
                    item(bubble, StageContainerItem(sender = name, isMe = me, avatar = avatar, voice = voice))
                        ?.let { found -> found.copy(item = found.item.copy(bubble = boxAround(found.start, found.end, range(n)))) }
                        ?.let(items::add)
                }
            }
            if (items.isEmpty()) return null
            val title = n.children.firstOrNull { it.has(chatTitleClass) && !it.has(chatTimeClass) }?.let(::textOf)
            val r = range(n) ?: return null
            return StageBookRegion(StageContainerType.CHAT, r.first, r.last + 1, title?.takeIf { it.isNotEmpty() }, items, boxAround(r.first, r.last + 1))
        }

        fun forum(n: EpubDomNode): StageBookRegion? {
            val posts = descendants(n).filter { it.has(replyClass) }.toList().let { all -> all.filter { p -> all.none { it !== p && descendants(it).any { d -> d === p } } } }
            if (posts.isEmpty()) return null
            val items = mutableListOf<StageBookItem>()
            for (post in posts) {
                val metaNodes = descendants(post).filter { it.has(floorClass) || it.has(authorClass) || it.has(chatTimeClass) }.toList()
                val floor = metaNodes.firstOrNull { it.has(floorClass) }?.let(::textOf)
                val author = metaNodes.firstOrNull { it.has(authorClass) && !it.has(floorClass) }?.let(::textOf)
                val time = metaNodes.firstOrNull { it.has(chatTimeClass) }?.let(::textOf)
                val meta = metaNodes.mapNotNull(::range)
                val content = descendants(post).filter { it.tag == "p" || it.has(Regex("content", RegexOption.IGNORE_CASE)) }
                    .mapNotNull(::range).filter { r -> meta.none { m -> r.first <= m.first && m.last <= r.last || m.first <= r.first && r.last <= m.last } }
                    .toList()
                val first = content.minOfOrNull { it.first } ?: continue
                val last = content.maxOf { it.last } + 1
                trimmed(text, first, last)?.let { (s, e) -> items += StageBookItem(s, e, StageContainerItem(floor = floor, author = author, time = time)) }
            }
            if (items.isEmpty()) return null
            val title = descendants(n).firstOrNull { it.has(postTitleClass) }?.let(::textOf)
            val r = range(n) ?: return null
            return StageBookRegion(StageContainerType.FORUM, r.first, r.last + 1, title, items)
        }

        fun danmaku(n: EpubDomNode): StageBookRegion? {
            val items = n.children.filter { it.tag != "#text" }.mapNotNull { item(it, StageContainerItem()) }
            val r = range(n) ?: return null
            return if (items.isEmpty()) null else StageBookRegion(StageContainerType.DANMAKU, r.first, r.last + 1, null, items)
        }

        fun lines(n: EpubDomNode, role: (EpubDomNode) -> CardLineRole): List<StageBookItem> {
            val blocks = descendants(n).filter { it.tag in BLOCK_TAGS && descendants(it).none { d -> d.tag in BLOCK_TAGS && range(d) != null } }
            return blocks.mapNotNull { b -> item(b, StageContainerItem(cardRole = role(b))) }.toList()
        }

        fun card(n: EpubDomNode, type: StageContainerType): StageBookRegion? {
            val r = range(n) ?: return null
            val items = when (type) {
                StageContainerType.NEWS -> lines(n) { b -> when { b.has(mastheadClass) -> CardLineRole.MASTHEAD; descendants(b).any { it.has(focusClass) } -> CardLineRole.HEADLINE; else -> CardLineRole.BODY } }
                StageContainerType.SOCIAL -> lines(n) { b -> when { b.has(chatTimeClass) -> CardLineRole.META; b.has(accountClass) && textOf(b).startsWith("@") || b.has(Regex("-id$")) -> CardLineRole.ACCOUNT; else -> CardLineRole.BODY } }
                StageContainerType.STATUS -> lines(n) { b -> if (b.has(statusTitleClass)) CardLineRole.HEADLINE else CardLineRole.ITEM }
                StageContainerType.DOCUMENT -> lines(n) { b -> if (b.tag == "li") CardLineRole.ITEM else CardLineRole.BODY }
                else -> lines(n) { b -> if (b.has(Regex("tit|title", RegexOption.IGNORE_CASE))) CardLineRole.HEADLINE else CardLineRole.BODY }
            }
            if (items.isEmpty()) return null
            if (type == StageContainerType.NOTEBOOK && (items.size > MAX_CARD_LINES || items.size < 1)) return null
            val title = items.firstOrNull { it.item.cardRole == CardLineRole.MASTHEAD || it.item.cardRole == CardLineRole.HEADLINE }
                ?.takeIf { type == StageContainerType.NEWS || type == StageContainerType.STATUS || type == StageContainerType.NOTEBOOK }
                ?.let { text.substring(it.start, it.end) }
            return StageBookRegion(type, r.first, r.last + 1, title, items)
        }

        fun classify(n: EpubDomNode): StageBookRegion? = when {
            n.has(danmakuClass) && n.children.any { it.has(danmakuClass) } -> danmaku(n)
            n.has(chatClass) -> chat(n)
            n.has(forumClass) -> forum(n)
            n.has(socialClass) -> card(n, StageContainerType.SOCIAL)
            n.has(newsClass) -> card(n, StageContainerType.NEWS)
            n.has(statusClass) && descendants(n).count { it.tag in BLOCK_TAGS } >= 2 -> card(n, StageContainerType.STATUS)
            n.has(documentClass) -> card(n, StageContainerType.DOCUMENT)
            n.tag == "blockquote" || n.has(notebookClass) -> card(n, StageContainerType.NOTEBOOK)
            else -> null
        }

        // 行内图片（脚注号、表情、小图标）：原文里图片独占一行，把所在段落拆成了几行；
        // 记下这些段落让编译时合回一段，图片本身不成拍。
        val joinRanges = mutableListOf<SourceRange>()
        val inlineDecorative = HashSet<Int>()
        fun inline(n: EpubDomNode, block: EpubDomNode?, inLink: Boolean) {
            val textBlock = if (n.tag in TEXT_BLOCK_TAGS) n else block
            val link = inLink || n.tag == "a" || n.tag == "sup" || n.has(footnoteClass)
            if ((n.tag == "img" || n.tag == "image") && n.textStart >= 0 && textBlock != null) {
                val r = range(textBlock)
                val rest = r?.let { text.substring(it.first, it.last + 1).replace(IMAGE_TEXT, "").trim() }.orEmpty()
                val source = byOffset[n.textStart]
                val small = source == null || (source.width <= 0 && source.height <= 0) ||
                    source.width in 1..INLINE_IMAGE_MAX_PX || source.height in 1..INLINE_IMAGE_MAX_PX
                if (r != null && rest.isNotEmpty() && (link || small || n.has(iconClass))) {
                    if (joinRanges.lastOrNull()?.start != r.first) joinRanges += SourceRange(r.first, r.last + 1)
                    inlineDecorative += n.textStart
                }
            }
            n.children.forEach { inline(it, textBlock, link) }
        }
        inline(body, null, false)

        val noteRanges = mutableListOf<SourceRange>()
        fun visit(n: EpubDomNode, inRegion: Boolean, inNote: Boolean = false) {
            if (n.tag == "img" || n.tag == "image") {
                val offset = n.textStart.takeIf { it >= 0 } ?: return
                val image = imageOf(n)
                when {
                    offset in inlineDecorative || inNote -> decorative += offset
                    n.has(separatorClass) -> { separators += offset; if (breakArt == null) breakArt = image }
                    n.has(logoClass) -> { decorative += offset; if (titleArt == null && offset < TITLE_ART_WINDOW) titleArt = image }
                    n.has(avatarClass) || n.has(iconClass) || inRegion -> decorative += offset
                    image != null -> stageImages[offset] = image
                    else -> decorative += offset
                }
                return
            }
            val note = inNote || n.has(footnoteClass)
            if (!inNote && note && n.tag in NOTE_BLOCK_TAGS) range(n)?.let { noteRanges += SourceRange(it.first, it.last + 1) }
            if (n.has(strikeClass) || n.tag == "del" || n.tag == "s" || n.tag == "strike") {
                range(n)?.let { spans += StageBookSpan(it.first, it.last + 1, SpanStyle.STRIKE) }
            } else if (n.tag == "b" || n.tag == "strong") {
                range(n)?.let { if (it.last - it.first < 24) spans += StageBookSpan(it.first, it.last + 1, SpanStyle.STRESS) }
            }
            if (!inRegion && !note) {
                val region = classify(n)
                if (region != null) {
                    regions += region
                    n.children.forEach { visit(it, true) }
                    return
                }
            }
            // 插图框里的图注：挂到图片上，不再单独成拍。
            if (!inRegion && !note && n.has(imageBoxClass)) {
                val img = images(n).firstOrNull()
                val caption = descendants(n).firstOrNull { it.has(captionClass) || it.tag == "p" }
                if (img != null && caption != null && img.textStart >= 0) {
                    n.children.forEach { visit(it, false) }
                    val image = stageImages[img.textStart]
                    val captionText = textOf(caption)
                    val captionRange = range(caption)
                    if (image != null && captionText.isNotEmpty() && captionRange != null && captionText.length <= 40) {
                        stageImages[img.textStart] = image.copy(caption = captionText)
                        regions += StageBookRegion(StageContainerType.NOTEBOOK, captionRange.first, captionRange.last + 1, CAPTION_MARK, emptyList())
                    }
                    return
                }
            }
            n.children.forEach { visit(it, inRegion, note) }
        }
        visit(body, false)
        val sorted = regions.sortedBy { it.start }.fold(mutableListOf<StageBookRegion>()) { acc, r -> if (acc.lastOrNull()?.let { r.start < it.end } != true) acc += r; acc }
        // 区域根元素自己的框（笔记、信纸、系统框……）给容器用。
        val boxed = sorted.map { r -> if (r.box != null) r else r.copy(box = styles.boxes.firstOrNull { it.start <= r.start && it.end >= r.end && it.end - it.start <= (r.end - r.start) * 2 }?.box) }
        return StageBookLayout(boxed, stageImages, decorative, separators, titleArt, breakArt, spans, joinRanges, noteRanges, styles)
    }

    /** 被图注占用的区间（无项、标题为此标记）：编译时直接吞掉。 */
    const val CAPTION_MARK = "\u0000caption"
    private const val TITLE_ART_WINDOW = 200
    private val BLOCK_TAGS = setOf("p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "dt", "dd", "td")
    /** 行内图片所在的「一段文字」：div 不算（插图框里图片和图注是两段）。 */
    private val TEXT_BLOCK_TAGS = setOf("p", "li", "h1", "h2", "h3", "h4", "h5", "h6", "dt", "dd", "td", "th")
    private val NOTE_BLOCK_TAGS = setOf("ol", "ul", "li", "aside", "section", "div", "p", "dl", "dd", "dt")
    /** 宽或高不超过这么多像素的段内图片当作行内小图（表情、图标）。 */
    private const val INLINE_IMAGE_MAX_PX = 128
    private const val IMAGE_TEXT = "［图片］"
    private const val DEFAULT_TEXT = 0xFF000000.toInt()

    private fun trimmed(text: String, start: Int, end: Int): Pair<Int, Int>? {
        var s = start.coerceIn(0, text.length)
        var e = end.coerceIn(s, text.length)
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (s < e) s to e else null
    }
}
