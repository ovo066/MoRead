package com.mozhi.reader.ai.prompt

/** The three selection actions of M1; ASK opens the panel with an empty input instead of firing. */
enum class SelectionAiAction(val label: String) {
    TRANSLATE("翻译"),
    ANALYZE("解析"),
    ASK("提问")
}

/**
 * Prompt templates for selection actions. Kept together so tone and format rules stay consistent;
 * the context window is assembled by the caller (selection + surrounding paragraph slice).
 */
object SelectionPrompts {

    fun system(bookTitle: String, chapterTitle: String): String = buildString {
        append("你是「墨知」阅读器的伴读助手，正在陪用户阅读")
        if (bookTitle.isNotBlank()) append("《").append(bookTitle).append("》")
        if (chapterTitle.isNotBlank()) append("的「").append(chapterTitle).append("」一节")
        append("。根据选段直接回答，使用简体中文，不复述原文，控制在三百字以内。")
    }

    fun firstMessage(
        action: SelectionAiAction,
        selection: String,
        context: String
    ): String {
        val contextBlock = if (context.isNotBlank() && context != selection) {
            "\n\n【上下文】\n$context"
        } else {
            ""
        }
        return when (action) {
            SelectionAiAction.TRANSLATE ->
                "请把下面的选段翻译成现代白话中文（若已是白话文则翻译成英文），保持语气与文体：\n\n【选段】\n$selection$contextBlock"

            SelectionAiAction.ANALYZE ->
                "请解析下面的选段：讲了什么、有什么值得注意的写法或伏笔、涉及哪些人物或典故：\n\n【选段】\n$selection$contextBlock"

            SelectionAiAction.ASK ->
                "我选中了这段文字，接下来会就它提问：\n\n【选段】\n$selection$contextBlock"
        }
    }
}
