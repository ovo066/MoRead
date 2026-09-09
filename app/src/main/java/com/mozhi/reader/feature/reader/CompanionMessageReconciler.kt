package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.MessageEntity

/** Main-owned bridge for event-first commits. Explicit mutations must invalidate pending rows. */
internal class CompanionMessageReconciler(private var conversationId: Long? = null) {
    private val awaitingRoom = linkedMapOf<Long, MessageEntity>()
    private var lastObservedIds: Set<Long> = emptySet()
    private val forgotten = hashSetOf<Long>()

    fun committed(message: MessageEntity): Boolean {
        if (message.conversationId != conversationId || message.id in forgotten) return false
        if (message.id !in lastObservedIds) awaitingRoom[message.id] = message
        return true
    }

    fun observe(rows: List<MessageEntity>): List<MessageEntity> {
        val eligible = rows.filter { it.conversationId == conversationId && it.id !in forgotten }
        lastObservedIds = eligible.mapTo(hashSetOf()) { it.id }
        awaitingRoom.keys.removeAll(lastObservedIds)
        return (eligible + awaitingRoom.values).distinctBy { it.id }.sortedBy { it.id }
    }

    /** Tombstones survive absent/stale snapshots and late commit events, including newest IDs. */
    fun forget(ids: Set<Long>) {
        forgotten += ids
        lastObservedIds = lastObservedIds - ids
        awaitingRoom.keys.removeAll(ids)
    }

    /** A failed database mutation restores its rows rather than hiding unsaved changes. */
    fun restore(rows: List<MessageEntity>) {
        forgotten.removeAll(rows.map { it.id }.toSet())
        rows.forEach { committed(it) }
    }

    fun clear(conversationId: Long? = null) {
        this.conversationId = conversationId
        awaitingRoom.clear()
        lastObservedIds = emptySet()
        forgotten.clear()
    }
}
