package com.mozhi.reader.ai.agent

/** 只按场景和用户授权提供工具；模型自行决定是否调用，不按发言关键词筛选。 */
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

    /** 段评讨论只开放查询能力，不在讨论过程中写批注、笔记或生成媒体。 */
    fun forDiscussion(longTermMemoryEnabled: Boolean): Set<String> =
        READ_ONLY_TOOLS - if (longTermMemoryEnabled) emptySet() else setOf("recall_memory")

    private val READ_ONLY_TOOLS = setOf(
        "get_reading_progress", "search_book", "grep_book", "read_book_section",
        "list_chapters", "list_annotations", "list_notes", "recall_memory"
    )
    private val WEB_TOOLS = setOf("web_search", "web_scrape")
}
