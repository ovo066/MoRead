package com.mozhi.reader.core.vector

/** A chapter chunk plus its exact UTF-16 source range. */
data class ChapterChunk(
    val text: String,
    val startCharOffset: Int,
    val endCharOffset: Int
)

/**
 * 章节正文 → RAG 切片。纯函数，长度一律按 UTF-16 字符数（与工程内字符坐标口径一致）。
 *
 * 规则：段落（非空行）为最小完整单元，贪心打包到 [TARGET_CHARS]；
 * 超过 [MAX_CHARS] 的段落先按句终符切句再打包；连句终符都没有的超长句按 [MAX_CHARS] 硬切。
 * 打包永不跨段截断，因此除硬切外，块边界总是段落边界或句子边界。
 */
object ChapterChunker {
    const val TARGET_CHARS = 480
    const val MAX_CHARS = 640

    private const val TERMINATORS = "。！？…；!?;"
    private const val CLOSERS = "”』」》）)\"'"

    fun chunk(text: String): List<String> = chunkWithOffsets(text).map(ChapterChunk::text)

    fun chunkWithOffsets(text: String): List<ChapterChunk> {
        val paragraphs = nonBlankLines(text)
        if (paragraphs.isEmpty()) return emptyList()
        val pieces = paragraphs.flatMap { paragraph ->
            if (paragraph.text.length <= MAX_CHARS) listOf(paragraph) else splitOversizedParagraph(paragraph)
        }
        val chunks = mutableListOf<ChapterChunk>()
        val current = StringBuilder()
        var currentStart = -1
        var currentEnd = -1
        pieces.forEach { piece ->
            val projected = current.length + 1 + piece.text.length
            if (current.isNotEmpty() && projected > TARGET_CHARS) {
                chunks += ChapterChunk(current.toString(), currentStart, currentEnd)
                current.clear()
                currentStart = -1
            }
            if (current.isEmpty()) currentStart = piece.startCharOffset else current.append('\n')
            current.append(piece.text)
            currentEnd = piece.endCharOffset
        }
        if (current.isNotEmpty()) chunks += ChapterChunk(current.toString(), currentStart, currentEnd)
        return chunks
    }

    private fun nonBlankLines(text: String): List<ChapterChunk> = buildList {
        Regex("[^\\r\\n]+").findAll(text).forEach { match ->
            val raw = match.value
            val leading = raw.indexOfFirst { !it.isWhitespace() }
            if (leading < 0) return@forEach
            val trailing = raw.indexOfLast { !it.isWhitespace() }
            val start = match.range.first + leading
            val end = match.range.first + trailing + 1
            add(ChapterChunk(text.substring(start, end), start, end))
        }
    }

    private fun splitOversizedParagraph(paragraph: ChapterChunk): List<ChapterChunk> {
        val sentences = splitSentences(paragraph).flatMap { sentence ->
            if (sentence.text.length <= MAX_CHARS) listOf(sentence) else hardSplit(sentence)
        }
        val out = mutableListOf<ChapterChunk>()
        val current = StringBuilder()
        var start = -1
        var end = -1
        sentences.forEach { sentence ->
            if (current.isNotEmpty() && current.length + sentence.text.length > TARGET_CHARS) {
                out += ChapterChunk(current.toString(), start, end)
                current.clear()
                start = -1
            }
            if (current.isEmpty()) start = sentence.startCharOffset
            current.append(sentence.text)
            end = sentence.endCharOffset
        }
        if (current.isNotEmpty()) out += ChapterChunk(current.toString(), start, end)
        return out
    }

    private fun splitSentences(paragraph: ChapterChunk): List<ChapterChunk> {
        val sentences = mutableListOf<ChapterChunk>()
        var sentenceStart = 0
        var i = 0
        while (i < paragraph.text.length) {
            if (paragraph.text[i] in TERMINATORS) {
                while (i + 1 < paragraph.text.length &&
                    (paragraph.text[i + 1] in TERMINATORS || paragraph.text[i + 1] in CLOSERS)
                ) i++
                val end = i + 1
                sentences += ChapterChunk(
                    paragraph.text.substring(sentenceStart, end),
                    paragraph.startCharOffset + sentenceStart,
                    paragraph.startCharOffset + end
                )
                sentenceStart = end
            }
            i++
        }
        if (sentenceStart < paragraph.text.length) {
            sentences += ChapterChunk(
                paragraph.text.substring(sentenceStart),
                paragraph.startCharOffset + sentenceStart,
                paragraph.endCharOffset
            )
        }
        return sentences
    }

    private fun hardSplit(chunk: ChapterChunk): List<ChapterChunk> =
        chunk.text.chunked(MAX_CHARS).mapIndexed { index, piece ->
            val start = chunk.startCharOffset + index * MAX_CHARS
            ChapterChunk(piece, start, start + piece.length)
        }
}
