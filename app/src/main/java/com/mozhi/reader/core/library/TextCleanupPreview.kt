package com.mozhi.reader.core.library

import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.compileRegex

data class TextCleanupChange(val chapterIndex: Int, val title: String, val before: String, val after: String)
data class TextCleanupPreview(
    val rules: List<ReaderTextReplacementRule>, val sourceRevision: String, val matches: Int,
    val changedChapters: Int, val examples: List<TextCleanupChange>
)

/** Bound Java-regex work even for pathological expressions imported or proposed by a model. */
private class TimedText(private val text: CharSequence, private val deadline: Long) : CharSequence {
    override val length: Int get() = text.length
    override fun get(index: Int): Char {
        check(System.nanoTime() < deadline) { "规则匹配超时，请缩小匹配范围或简化表达式" }
        return text[index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TimedText(text.subSequence(startIndex, endIndex), deadline)
    override fun toString(): String = text.toString()
}

internal fun cleanText(body: String, rules: List<ReaderTextReplacementRule>): Pair<String, Int> {
    var text = body
    var count = 0
    for (rule in rules.filter { it.enabled && !it.forListenOnly }) {
        try {
            val regex = rule.compileRegex()
            val replacement = if (rule.isRegex) rule.replacement else Regex.escapeReplacement(rule.replacement)
            val bounded = TimedText(text, System.nanoTime() + 300_000_000L)
            val matcher = regex.toPattern().matcher(bounded)
            val result = StringBuffer()
            while (matcher.find()) {
                count++
                matcher.appendReplacement(result, replacement)
                check(result.length <= body.length * 4L + 100_000L) { "替换结果过长，请检查规则" }
            }
            matcher.appendTail(result)
            text = result.toString()
        } catch (_: StackOverflowError) {
            throw IllegalArgumentException("规则「${rule.name}」回溯过深，请简化表达式")
        }
    }
    return text to count
}

internal fun cleanupRevision(chapters: List<Pair<Int, String>>): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    chapters.forEach { (index, text) ->
        digest.update("$index:${text.length}:".toByteArray())
        digest.update(text.toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
