package com.mozhi.reader.feature.reader

internal enum class CompanionToolIcon {
    SEARCH,
    BOOK,
    MEMORY,
    ANNOTATION,
    NOTE,
    IMAGE,
    AUDIO,
    WEB,
    PROGRESS,
    PLAN,
    GENERIC
}

internal data class CompanionToolPresentation(
    val title: String,
    val action: String,
    val description: String,
    val icon: CompanionToolIcon
)

internal fun companionToolPresentation(
    toolName: String,
    fallbackTitle: String = ""
): CompanionToolPresentation = when (toolName) {
    "search_book" -> CompanionToolPresentation(
        title = "检索书中原文",
        action = "检索已读正文",
        description = "在已读范围内查找相关片段",
        icon = CompanionToolIcon.SEARCH
    )
    "read_book_section" -> CompanionToolPresentation(
        title = "读取章节正文",
        action = "读取命中段落",
        description = "读取指定章节或连续段落",
        icon = CompanionToolIcon.BOOK
    )
    "get_reading_progress" -> CompanionToolPresentation(
        title = "查看阅读进度",
        action = "确认阅读进度",
        description = "确认当前章节与允许读取的范围",
        icon = CompanionToolIcon.PROGRESS
    )
    "recall_memory" -> CompanionToolPresentation(
        title = "回忆过往交流",
        action = "回忆长期记忆",
        description = "检索你与当前角色的历史记忆",
        icon = CompanionToolIcon.MEMORY
    )
    "add_annotation" -> CompanionToolPresentation(
        title = "添加段落批注",
        action = "添加原文批注",
        description = "把角色的想法写回对应原文",
        icon = CompanionToolIcon.ANNOTATION
    )
    "write_note" -> CompanionToolPresentation(
        title = "保存阅读笔记",
        action = "整理阅读笔记",
        description = "把本轮内容保存到书籍笔记",
        icon = CompanionToolIcon.NOTE
    )
    "save_plot_summary" -> CompanionToolPresentation(
        title = "保存剧情梗概",
        action = "更新剧情梗概",
        description = "整理并保存已读范围内的剧情",
        icon = CompanionToolIcon.NOTE
    )
    "generate_image" -> CompanionToolPresentation(
        title = "生成选段插图",
        action = "生成选段插图",
        description = "根据文字生成并保存图片",
        icon = CompanionToolIcon.IMAGE
    )
    "synthesize_speech" -> CompanionToolPresentation(
        title = "生成角色语音",
        action = "生成角色语音",
        description = "把角色回复合成为可播放语音",
        icon = CompanionToolIcon.AUDIO
    )
    "web_search" -> CompanionToolPresentation(
        title = "搜索互联网",
        action = "搜索互联网",
        description = "查找书外信息与近期资料",
        icon = CompanionToolIcon.WEB
    )
    "web_scrape" -> CompanionToolPresentation(
        title = "读取网页",
        action = "读取网页正文",
        description = "提取指定网页中的主要内容",
        icon = CompanionToolIcon.WEB
    )
    "create_reading_plan" -> CompanionToolPresentation(
        title = "创建阅读计划",
        action = "创建阅读计划",
        description = "把阅读目标拆成可执行安排",
        icon = CompanionToolIcon.PLAN
    )
    else -> CompanionToolPresentation(
        title = fallbackTitle.ifBlank { "调用工具" },
        action = fallbackTitle.ifBlank { "调用工具" },
        description = "执行 ${toolName.ifBlank { "未知工具" }}",
        icon = CompanionToolIcon.GENERIC
    )
}
