package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把工具调用的原始参数 JSON 压成「过程」卡里那一行摘要。
 *
 * 目的是让用户一眼看出 agent 到底查了什么、写到了哪，而不是只看到「检索书中原文 · 已完成」。
 * 纯函数、不抛异常：参数缺失或不是合法 JSON 一律返回空串（界面据此只显示工具名）。
 */
object ToolCallSummary {

    fun summarize(toolName: String, argumentsJson: String?): String {
        val args = parse(argumentsJson) ?: return ""
        return when (toolName) {
            "search_book" -> withCount(args.text("query"), args.int("top_k"), "段")
            "recall_memory" -> args.text("query")
            "web_search" -> withCount(args.text("query"), args.int("limit"), "条")
            "web_scrape" -> args.text("url")
            "read_book_section" -> readSection(args)
            "add_annotation" -> annotation(args)
            "write_note" -> args.text("title")
            "save_plot_summary" -> plotSummary(args)
            "generate_image" -> args.text("prompt")
            "synthesize_speech" -> args.text("text")
            // 未知工具：挑第一个非空的字符串参数，总比什么都不显示好。
            else -> args.firstText()
        }.clip()
    }

    private fun readSection(args: JsonObject): String {
        val from = args.int("from_chapter") ?: return ""
        val to = args.int("to_chapter") ?: from
        val range = if (to > from) "第 $from-$to 章" else "第 $from 章"
        val start = args.int("start_char")?.takeIf { it > 0 } ?: return range
        return "$range · 从 $start 字续读"
    }

    private fun annotation(args: JsonObject): String {
        val style = when (args.text("style").lowercase()) {
            "wavy" -> "波浪"
            "underline" -> "直线"
            "highlight" -> "荧光"
            else -> ""
        }
        val where = args.int("chapter_number")?.let { "第 $it 章" }.orEmpty()
        val quote = args.text("quote")
        return listOf(where, style, quote).filter(String::isNotBlank).joinToString(" · ")
    }

    private fun plotSummary(args: JsonObject): String {
        val from = args.int("from_chapter")
        val to = args.int("to_chapter")
        val range = when {
            from != null && to != null -> "第 $from-$to 章"
            to != null -> "截至第 $to 章"
            else -> ""
        }
        return listOf(range, args.text("title")).filter(String::isNotBlank).joinToString(" · ")
    }

    /** 「主角的身世 · 5 段」——数量只在模型显式给了时才写，避免编造默认值。 */
    private fun withCount(head: String, count: Int?, unit: String): String {
        if (head.isBlank()) return ""
        return if (count != null) "$head · $count$unit" else head
    }

    private fun parse(raw: String?): JsonObject? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "{}") return null
        return runCatching { AiJson.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    private fun JsonObject.text(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.trim()
            ?.replace(WHITESPACE, " ")
            .orEmpty()

    private fun JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

    /** 未知工具兜底只挑真正的字符串值：数字/布尔参数（top_k、enabled）拿出来没有意义。 */
    private fun JsonObject.firstText(): String = entries.asSequence()
        .filter { (_, value) ->
            runCatching { value.jsonPrimitive.isString }.getOrDefault(false)
        }
        .map { (key, _) -> text(key) }
        .firstOrNull(String::isNotBlank)
        .orEmpty()

    private fun String.clip(): String =
        if (length <= MAX_CHARS) this else take(MAX_CHARS).trimEnd() + "…"

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_CHARS = 48
}
