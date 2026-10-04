package com.mozhi.reader.core.database.dao

/** Metadata projections: a statistics screen never materializes chat content or book text. */
data class CompletedCompanionRound(
    val id: Long,
    val conversationId: Long,
    val createdAt: Long,
    val bookId: Long?,
    val type: String,
    val bookScopesJson: String,
    val replyRoundId: String?,
    val sourceBookIdsJson: String? = null,
    /** Companion role of the conversation; null for legacy chats without a role. */
    val personaId: Long? = null
)

data class CompanionWordsRow(val id: Long, val roundId: String?, val createdAt: Long, val type: String, val characters: Long, val role: String = "assistant")

data class CompanionUsageRow(
    val id: Long,
    val roundId: String?,
    val createdAt: Long,
    val type: String,
    val tokens: Int?
)
