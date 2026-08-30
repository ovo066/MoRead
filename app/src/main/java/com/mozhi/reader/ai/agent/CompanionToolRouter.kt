package com.mozhi.reader.ai.agent

/** 按当前用户意图裁剪工具集合，避免每轮把整套 schema 都发送给模型。 */
internal object CompanionToolRouter {
    /**
     * 伴读主会话实际可用的完整工具集。
     *
     * 角色编辑器里的“工具权限”表达的是能力白名单，不能再按单轮关键词二次裁掉；
     * 否则模型在没命中硬编码词表时收到的就是空 tools，并会如实回答“没有工具”。
     */
    fun available(
        personaEnabledTools: Set<String>,
        requiredTools: Set<String> = emptySet(),
        webSearchEnabled: Boolean,
        longTermMemoryEnabled: Boolean
    ): Set<String> = buildSet {
        addAll(READ_ONLY_TOOLS)
        addAll(personaEnabledTools)
        addAll(requiredTools)
        if (webSearchEnabled) addAll(WEB_TOOLS)
        if (!longTermMemoryEnabled) remove("recall_memory")
    }

    fun select(
        userText: String,
        sceneAvailable: Boolean,
        personaEnabledTools: Set<String>,
        requiredTools: Set<String> = emptySet(),
        webSearchEnabled: Boolean,
        longTermMemoryEnabled: Boolean
    ): Set<String> {
        val text = userText.trim().lowercase()
        val candidates = requiredTools.toMutableSet()

        if (text.isNotEmpty()) {
            if ((!sceneAvailable && !text.containsAny(CASUAL_WORDS)) || text.containsAny(BOOK_SEARCH_WORDS)) {
                candidates += "search_book"
            }
            if (text.containsAny(SECTION_READ_WORDS)) candidates += "read_book_section"
            if (text.containsAny(CHAPTER_LIST_WORDS)) candidates += "list_chapters"
            if (text.containsAny(ANNOTATION_LIST_WORDS)) candidates += "list_annotations"
            if (text.containsAny(NOTE_LIST_WORDS)) candidates += "list_notes"
            if (longTermMemoryEnabled && text.containsAny(MEMORY_WORDS)) candidates += "recall_memory"
            if (text.containsAny(PROGRESS_WORDS)) candidates += "get_reading_progress"
            if (text.containsAny(ANNOTATION_WORDS)) candidates += "add_annotation"
            if (text.containsAny(NOTE_WORDS)) candidates += "write_note"
            if (text.containsAny(PLOT_SUMMARY_WORDS)) candidates += "save_plot_summary"
            if (text.containsAny(IMAGE_WORDS)) candidates += "generate_image"
            if (text.containsAny(SPEECH_WORDS)) candidates += "synthesize_speech"
            if (webSearchEnabled && text.containsAny(WEB_WORDS)) candidates += "web_search"
            if (webSearchEnabled && URL_REGEX.containsMatchIn(text)) candidates += "web_scrape"
        }

        val allowed = available(
            personaEnabledTools = personaEnabledTools,
            requiredTools = requiredTools,
            webSearchEnabled = webSearchEnabled,
            longTermMemoryEnabled = longTermMemoryEnabled
        )
        return candidates.filterTo(linkedSetOf()) { it in allowed }
    }

    private fun String.containsAny(words: Set<String>): Boolean = words.any(::contains)

    private val CASUAL_WORDS = setOf("你好", "嗨", "谢谢", "在吗", "早上好", "晚上好")
    private val BOOK_SEARCH_WORDS = setOf(
        "原文", "前文", "书里", "文中", "哪一章", "哪里提到", "谁是", "人物关系", "时间线", "为什么"
    )
    private val SECTION_READ_WORDS = setOf(
        "概括", "总结", "梗概", "整章", "全文", "第几章", "前几章", "章节范围", "从第"
    )
    private val CHAPTER_LIST_WORDS = setOf("目录", "章节列表", "哪一卷", "第几卷", "这一卷", "卷名", "篇章结构")
    private val ANNOTATION_LIST_WORDS = setOf("划线", "划过", "批注过", "标过", "我的批注", "你的批注")
    private val NOTE_LIST_WORDS = setOf("已有笔记", "之前的笔记", "读笔记", "查看笔记", "旧梗概", "原梗概")
    private val MEMORY_WORDS = setOf("记得", "还记得", "之前聊", "以前说", "约定", "我的偏好")
    private val PROGRESS_WORDS = setOf("读到", "阅读进度", "看到哪", "第几章")
    private val ANNOTATION_WORDS = setOf("批注", "点评这段", "标注这段")
    private val NOTE_WORDS = setOf("记笔记", "保存笔记", "写入笔记", "存成笔记")
    private val PLOT_SUMMARY_WORDS = setOf("保存梗概", "更新梗概", "剧情梗概")
    private val IMAGE_WORDS = setOf("画一张", "画一下", "生成图片", "生成插图", "配图", "插图")
    private val SPEECH_WORDS = setOf("朗读", "读出来", "配音", "生成语音")
    private val WEB_WORDS = setOf("联网", "网上", "网络搜索", "搜索网页", "最新", "新闻", "资料来源")
    private val READ_ONLY_TOOLS = setOf(
        "get_reading_progress",
        "search_book",
        "read_book_section",
        "list_chapters",
        "list_annotations",
        "list_notes",
        "recall_memory"
    )
    private val WEB_TOOLS = setOf("web_search", "web_scrape")
    private val URL_REGEX = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
}