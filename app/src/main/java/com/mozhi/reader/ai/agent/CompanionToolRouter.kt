package com.mozhi.reader.ai.agent

/** 按当前用户意图裁剪工具集合，避免每轮把整套 schema 都发送给模型。 */
internal object CompanionToolRouter {
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

        val globallyAvailable = if (webSearchEnabled) WEB_TOOLS else emptySet()
        val allowed = personaEnabledTools + requiredTools + globallyAvailable
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
    private val MEMORY_WORDS = setOf("记得", "还记得", "之前聊", "以前说", "约定", "我的偏好")
    private val PROGRESS_WORDS = setOf("读到", "阅读进度", "看到哪", "第几章")
    private val ANNOTATION_WORDS = setOf("批注", "点评这段", "标注这段")
    private val NOTE_WORDS = setOf("记笔记", "保存笔记", "写入笔记", "存成笔记")
    private val PLOT_SUMMARY_WORDS = setOf("保存梗概", "更新梗概", "剧情梗概")
    private val IMAGE_WORDS = setOf("画一张", "画一下", "生成图片", "生成插图", "配图", "插图")
    private val SPEECH_WORDS = setOf("朗读", "读出来", "配音", "生成语音")
    private val WEB_WORDS = setOf("联网", "网上", "网络搜索", "搜索网页", "最新", "新闻", "资料来源")
    private val WEB_TOOLS = setOf("web_search", "web_scrape")
    private val URL_REGEX = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)
}