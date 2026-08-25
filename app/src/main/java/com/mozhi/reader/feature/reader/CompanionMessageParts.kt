package com.mozhi.reader.feature.reader

/**
 * AI 回复的气泡拆分与语音标记解析。
 *
 * 输出协议只有三条：
 * 1. **一行一个气泡**；
 * 2. 行首 `[语音]` 表示这一行以语音消息呈现；
 * 3. `[整段]` … `[/整段]` 之间的内容整块成一个气泡，里面的换行不拆——
 *    长分析、列表、表格、代码由模型自己圈出来，比逼它「写在同一行里」自然得多。
 *
 * 解析**永远执行**——否则关掉多气泡开关时，标记就会原样漏成正文里的方括号；
 * 开关只决定相邻文本行要不要合并回一段。
 *
 * 模型没打整段标记时还有一层兜底：围栏代码块、表格与列表行照样不会被逐行切碎。
 */
internal sealed interface CompanionBubblePart {
    val text: String

    data class Text(override val text: String) : CompanionBubblePart

    /** 行首带语音标记；[text] 已去掉标记本身，既用于合成也用于展开后阅读。 */
    data class Voice(override val text: String) : CompanionBubblePart
}

/**
 * @param multiBubble true = 每个文本段独立成气泡；false = 相邻文本段合并回一条，
 *   并**原样还原段间的空行**——单个换行在 Markdown 里不是段落分隔，
 *   合并时丢掉空行会让整条回复渲染成一大坨。
 *   语音段无论开关如何都独立成泡——它是一条语音消息，没法和文字挤在一个气泡里。
 */
internal fun parseCompanionParts(
    content: String,
    multiBubble: Boolean
): List<CompanionBubblePart> {
    val segments = splitSegments(content)
    if (segments.isEmpty()) return emptyList()
    if (multiBubble) return segments.map(Segment::part).capVoiceParts()
    // 合并态：连续文本压成一段，语音仍然切断合并链。
    val merged = mutableListOf<CompanionBubblePart>()
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty()) {
            merged += CompanionBubblePart.Text(buffer.toString())
            buffer.setLength(0)
        }
    }
    segments.forEach { segment ->
        when (val part = segment.part) {
            is CompanionBubblePart.Text -> {
                if (buffer.isNotEmpty()) {
                    buffer.append(if (segment.precededByBlankLine) "\n\n" else "\n")
                }
                buffer.append(part.text)
            }
            is CompanionBubblePart.Voice -> {
                flush()
                merged += part
            }
        }
    }
    flush()
    return merged.capVoiceParts()
}

private fun List<CompanionBubblePart>.capVoiceParts(): List<CompanionBubblePart> {
    var voiceCount = 0
    return map { part ->
        if (part is CompanionBubblePart.Voice && ++voiceCount > MAX_VOICE_PARTS) {
            CompanionBubblePart.Text(part.text)
        } else {
            part
        }
    }
}

private const val MAX_VOICE_PARTS = 2

/** 是否含至少一段语音——决定要不要为这条消息触发 TTS 合成。 */
internal fun List<CompanionBubblePart>.hasVoice(): Boolean =
    any { it is CompanionBubblePart.Voice }

/** [precededByBlankLine] 记住原文里这一段前面有没有空行，合并态据此还原段落间距。 */
private data class Segment(
    val part: CompanionBubblePart,
    val precededByBlankLine: Boolean
)

private fun splitSegments(content: String): List<Segment> {
    if (content.isBlank()) return emptyList()
    val parts = mutableListOf<Segment>()
    val block = StringBuilder()
    var inFence = false
    var fenceMarker = ""
    var inMarkedBlock = false
    var pendingBlankLine = false

    fun emit(part: CompanionBubblePart) {
        parts += Segment(part, pendingBlankLine)
        pendingBlankLine = false
    }

    fun flushBlock() {
        val text = block.toString().trim('\n')
        block.setLength(0)
        if (text.isNotBlank()) emit(CompanionBubblePart.Text(text))
    }

    content.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val fence = fenceOf(line)
        when {
            // 模型圈出来的整段：闭合标记之前一律原样收进同一个气泡，
            // 里面的围栏、语音标记、空行都不再解释。
            inMarkedBlock -> {
                if (isBlockClose(line)) {
                    inMarkedBlock = false
                    flushBlock()
                } else {
                    block.appendLine(line)
                }
            }
            isBlockOpen(line) -> {
                flushBlock()
                inMarkedBlock = true
            }
            inFence -> {
                block.appendLine(rawLine)
                // 收尾围栏必须与开头同种记号，否则 ``` 内出现的 ~~~ 会提前关掉代码块。
                if (fence != null && fence == fenceMarker) {
                    inFence = false
                    fenceMarker = ""
                    flushBlock()
                }
            }
            fence != null -> {
                // 代码块开始前把攒着的文字先送走，代码不和上文挤在一个气泡里。
                flushBlock()
                inFence = true
                fenceMarker = fence
                block.appendLine(rawLine)
            }
            line.isBlank() -> {
                flushBlock()
                pendingBlankLine = true
            }
            voiceMarkerLength(line) > 0 -> {
                flushBlock()
                val spoken = line.substring(voiceMarkerLength(line)).trim()
                // 只有标记没有内容的空行不产生哑气泡。
                if (spoken.isNotBlank()) emit(CompanionBubblePart.Voice(spoken))
            }
            // 表格行、引用、列表项是上一段的延续：粘在同一个气泡里，
            // 否则「引导句 + 三条列表」会被切成四个气泡，读起来是断的。
            isContinuationLine(line) -> block.appendLine(line)
            else -> {
                flushBlock()
                block.appendLine(line)
            }
        }
    }
    // 未闭合的围栏或整段标记（流式被截断）按已有内容收尾，不丢字。
    flushBlock()
    return parts
}

/**
 * 整段标记，宽容匹配：`[整段]` `[[整段]]` `[block]` `[[block]]`，大小写不敏感，
 * 允许模型顺手加个冒号或句号。必须独占一行——行内出现的方括号是正文。
 */
private fun isBlockOpen(line: String): Boolean = line.trim().normalizedMarker() in BLOCK_OPEN

private fun isBlockClose(line: String): Boolean = line.trim().normalizedMarker() in BLOCK_CLOSE

private fun String.normalizedMarker(): String =
    lowercase().trimEnd('：', ':', '。', '.').replace("[[", "[").replace("]]", "]")

private val BLOCK_OPEN = setOf("[整段]", "[block]")
private val BLOCK_CLOSE = setOf("[/整段]", "[/block]")

private fun fenceOf(line: String): String? {
    val trimmed = line.trimStart()
    return when {
        trimmed.startsWith("```") -> "```"
        trimmed.startsWith("~~~") -> "~~~"
        else -> null
    }
}

/** 表格行、引用与列表项：自身是上一段的延续，不该被切成独立气泡。 */
private fun isContinuationLine(line: String): Boolean {
    val trimmed = line.trimStart()
    return trimmed.startsWith("|") ||
        trimmed.startsWith("> ") ||
        trimmed.startsWith("- ") ||
        trimmed.startsWith("* ") ||
        trimmed.startsWith("+ ") ||
        ORDERED_ITEM.containsMatchIn(trimmed)
}

/**
 * 行首语音标记，返回标记长度（含其后紧跟的空白），0 = 不是语音行。
 * 宽容匹配四种写法，模型用哪种都认：`[语音]` `[[语音]]` `[voice]` `[[voice]]`（大小写不敏感）。
 */
private fun voiceMarkerLength(line: String): Int {
    VOICE_MARKERS.forEach { marker ->
        if (line.startsWith(marker, ignoreCase = true)) {
            var end = marker.length
            while (end < line.length && line[end].isWhitespace()) end++
            return end
        }
    }
    return 0
}

private val VOICE_MARKERS = listOf(
    "[[语音]]",
    "[[voice]]",
    "[语音]",
    "[voice]"
)

private val ORDERED_ITEM = Regex("^\\d{1,3}[.)]\\s")
