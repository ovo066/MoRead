package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.chat.AiChatRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** 同一轮的只读工具会并发执行；预算检查与登记必须是一个原子步骤。 */
    private val mutex = Mutex()
    val all: List<LibraryBookScope> get() = synchronized(scopes) { scopes.values.toList() }

    init {
        LibraryBookScopes.encode(initial)
        require(focusedBookIds.size <= LibraryBookScopes.MAX_FOCUS_BOOKS && associated.size == focusedBookIds.size)
        require(associated.all { it in scopes })
    }

    suspend fun authorize(bookId: Long): LibraryBookScope = mutex.withLock {
        require(bookId > 0) { "请先查找有效书籍编号" }
        require(bookId in accessed || accessed.size < LibraryBookScopes.MAX_READ_BOOKS) { "本轮最多查阅 4 本书" }
        val scope = scopes[bookId] ?: run {
            require(scopes.size < LibraryBookScopes.MAX_BOOKS) { "本话题涉及书籍较多，请新建话题" }
            guard.capture(listOf(bookId)).single().also { synchronized(scopes) { scopes[bookId] = it } }
        }
        accessed += bookId
        associated += bookId
        persistLocked()
        scope
    }

    suspend fun persist() = mutex.withLock { persistLocked() }

    private suspend fun persistLocked() {
        val payload = LibraryBookScopes.encode(all) to LibraryBookScopes.encodeTurnBooks(associated.toList())
        if (lastPersisted == payload) return
        chats.updateLibraryContext(conversationId, userRoundId, payload.first, payload.second)
        lastPersisted = payload
    }

    suspend fun validate() = guard.validate(all)
}
