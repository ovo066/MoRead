package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.chat.AiChatRepository

/** One user turn's source registry. Discovering a title is not permission to upload its body. */
class LibraryConversationSources(
    private val conversationId: Long,
    private val userRoundId: String,
    initial: List<LibraryBookScope>,
    private val guard: LibraryScopeGuard,
    private val chats: AiChatRepository,
    focusedBookIds: List<Long> = emptyList()
) {
    private val scopes = initial.associateByTo(linkedMapOf()) { it.bookId }
    private val accessed = linkedSetOf<Long>()
    private val associated = focusedBookIds.toCollection(linkedSetOf())
    private var lastPersisted: Pair<String, String>? = null
    val all: List<LibraryBookScope> get() = scopes.values.toList()

    init {
        LibraryBookScopes.encode(initial)
        require(focusedBookIds.size <= LibraryBookScopes.MAX_FOCUS_BOOKS && associated.size == focusedBookIds.size)
        require(associated.all { it in scopes })
    }

    suspend fun authorize(bookId: Long): LibraryBookScope {
        require(bookId > 0) { "请先查找有效书籍编号" }
        require(bookId in accessed || accessed.size < LibraryBookScopes.MAX_READ_BOOKS) { "本轮最多查阅 4 本书" }
        val scope = scopes[bookId] ?: run {
            require(scopes.size < LibraryBookScopes.MAX_BOOKS) { "本话题涉及书籍较多，请新建话题" }
            guard.capture(listOf(bookId)).single().also { scopes[bookId] = it }
        }
        accessed += bookId
        associated += bookId
        persist()
        return scope
    }

    suspend fun persist() {
        val payload = LibraryBookScopes.encode(all) to LibraryBookScopes.encodeTurnBooks(associated.toList())
        if (lastPersisted == payload) return
        chats.updateLibraryContext(conversationId, userRoundId, payload.first, payload.second)
        lastPersisted = payload
    }

    suspend fun validate() = guard.validate(all)
}
