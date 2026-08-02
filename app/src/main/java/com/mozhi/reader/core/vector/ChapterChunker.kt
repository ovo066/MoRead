package com.mozhi.reader.core.vector

/**
 * 章节正文 → RAG 切片。纯函数，长度一律按 UTF-16 字符数（与工程内字符坐标口径一致）。
 *
 * 规则：段落（非空行）为最小完整单元，贪心打包到 [TARGET_CHARS]；
 * 超过 [MAX_CHARS] 的段落先按句终符切句再打包；连句终符都没有的超长句按 [MAX_CHARS] 硬切。
 * 打包永不跨段截断，因此除硬切外，块边界总是段落边界或句子边界。
 */
object ChapterChunker {

    /** 打包目标：多段拼接不超过它。 */
    const val TARGET_CHARS = 480

    /** 单元硬上限：整段/整句超过才继续下切。 */
    const val MAX_CHARS = 640

    private const val TERMINATORS = "。！？…；!?;"
    private const val CLOSERS = "”』」》）)\"'"

    fun chunk(text: String): List<String> {
        val paragraphs = text.lines()
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (paragraphs.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        paragraphs
            .flatMap { paragraph ->
                if (paragraph.length <= MAX_CHARS) listOf(paragraph)
                else splitOversizedParagraph(paragraph)
            }
            .forEach { piece ->
                val projected = current.length + 1 + piece.length
                if (current.isNotEmpty() && projected > TARGET_CHARS) {
                    chunks += current.toString()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append('\n')
                current.append(piece)
            }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    /** 段内切句后贪心打包；句子直接拼接（不补分隔符），保持原文字符不增不减。 */
    private fun splitOversizedParagraph(paragraph: String): List<String> {
        val units = splitSentences(paragraph).flatMap { sentence ->
            if (sentence.length <= MAX_CHARS) listOf(sentence) else sentence.chunked(MAX_CHARS)
        }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        units.forEach { unit ->
            if (current.isNotEmpty() && current.length + unit.length > TARGET_CHARS) {
                out += current.toString()
                current.clear()
            }
            current.append(unit)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    /** 按句终符切分，终结符连同其后的收尾引号/括号归前句。 */
    private fun splitSentences(paragraph: String): List<String> {
        val sentences = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < paragraph.length) {
            val c = paragraph[i]
            current.append(c)
            if (c in TERMINATORS) {
                while (i + 1 < paragraph.length &&
                    (paragraph[i + 1] in TERMINATORS || paragraph[i + 1] in CLOSERS)
                ) {
                    i++
                    current.append(paragraph[i])
                }
                sentences += current.toString()
                current.clear()
            }
            i++
        }
        if (current.isNotEmpty()) sentences += current.toString()
        return sentences
    }
}
