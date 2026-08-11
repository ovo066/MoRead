package com.mozhi.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookSourceType {
    TXT,
    EPUB
}

enum class AiProviderType {
    CHAT,
    EMBEDDING,
    TTS,
    IMAGE
}

/** 内置供应商适配标识；只有 OPENROUTER 启用其专属多端点目录与 `/images` 语义。 */
enum class AiProviderAdapter {
    CUSTOM,
    OPENROUTER,
    OPENAI,
    ANTHROPIC,
    GEMINI,
    DEEPSEEK,
    MINIMAX
}

/**
 * 能力属于具体模型而非 Provider：同一个 OpenRouter / OpenAI-compatible Provider
 * 可以同时挂对话、向量、TTS 与生图模型。
 */
enum class AiModelType {
    CHAT,
    EMBEDDING,
    TTS,
    IMAGE
}

enum class ModelRole {
    CHAT,
    CHEAP,
    /** 伴读输入区的 AI 建议回复；未分配时回落 CHEAP → CHAT。 */
    SUGGESTION,
    EMBEDDING,
    TTS,
    IMAGE
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val coverPath: String?,
    val epubPath: String,
    val sourceType: BookSourceType,
    val importedAt: Long,
    val totalChapters: Int,
    val lastReadLocator: String? = null,
    val lastReadChapterIndex: Int = 0,
    val lastReadCharOffset: Int = 0,
    val lastReadAt: Long = 0,
    /** 0 = no `text.mz` on disk yet, 1 = current plain-text format. */
    val textVersion: Int = 0,
    /** 用户自定义标签，逗号分隔。单书标签量极小，不值得独立表。 */
    val tags: String = "",
    /** 用户手改过书名/作者/封面后置位，此后任何自动回填都不再覆盖。 */
    val metadataEdited: Boolean = false,
    /**
     * 手动标记的阅读状态（[BookReadState] 名）；null = 按进度自动推导。
     * 读完再回顾旧章不该掉回「在读」，所以手动一旦标记就固定下来。
     */
    @ColumnInfo(defaultValue = "NULL")
    val manualReadState: String? = null,
    /** 置顶时间戳；0 = 未置顶。置顶书在书架里按此倒序排最前。 */
    @ColumnInfo(defaultValue = "0")
    val pinnedAt: Long = 0
)

/** 书架阅读状态。搁置只能手动设，自动推导永远不会得到它。 */
enum class BookReadState {
    UNREAD,
    READING,
    FINISHED,
    SHELVED
}

/** 界面统一叫法（书架筛选、封面角标、详情页胶囊、长按菜单共用）。 */
fun BookReadState.label(): String = when (this) {
    BookReadState.UNREAD -> "未读"
    BookReadState.READING -> "在读"
    BookReadState.FINISHED -> "已读完"
    BookReadState.SHELVED -> "搁置"
}

/** 手动标记优先；没标记过就按进度推导。非法枚举名（降级安装等）按未标记处理。 */
fun BookEntity.readState(): BookReadState =
    manualReadState?.let { name -> runCatching { BookReadState.valueOf(name) }.getOrNull() }
        ?: when {
            lastReadAt == 0L -> BookReadState.UNREAD
            totalChapters > 0 && lastReadChapterIndex >= totalChapters - 1 -> BookReadState.FINISHED
            else -> BookReadState.READING
        }

val BookEntity.isPinned: Boolean get() = pinnedAt > 0

/** 把逗号分隔的 [BookEntity.tags] 拆成列表，顺手去空去重。 */
fun BookEntity.tagList(): List<String> = tags
    .split(',')
    .map(String::trim)
    .filter(String::isNotEmpty)
    .distinct()

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "chapterIndex"], unique = true)
    ]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val title: String,
    /** EPUB spine resource, and the extraction key for plain text. Empty for TXT books. */
    val href: String,
    /** UTF-16 code units in the chapter body, not bytes. */
    val charCount: Int,
    /** Byte offset into the book's `text.mz`; -1 until the book is materialized. */
    val textByteOffset: Long = -1,
    val textByteLength: Int = 0
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val locatorJson: String,
    val chapterIndex: Int = 0,
    val charOffset: Int = 0,
    val excerpt: String = "",
    val label: String,
    val createdAt: Long
)

@Entity(
    tableName = "reading_daily",
    primaryKeys = ["bookId", "epochDay"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ReadingDailyEntity(
    val bookId: Long,
    val epochDay: Long,
    val durationMs: Long,
    val lastReadAt: Long
)

@Entity(tableName = "ai_providers")
data class AiProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiKeyAlias: String,
    /** 仅用于 v8 及更早配置迁移；运行时能力判断一律读取 [AiModelEntity.type]。 */
    val type: AiProviderType,
    val extraJson: String = "{}",
    /** [com.mozhi.reader.ai.client.ApiDialect] name: OPENAI / OPENAI_RESPONSES / CLAUDE / GEMINI. */
    val apiFormat: String = "OPENAI",
    @ColumnInfo(defaultValue = "'CUSTOM'")
    val adapter: AiProviderAdapter = AiProviderAdapter.CUSTOM,
    val createdAt: Long
)

/** One model under a provider; a provider (endpoint + key) hosts many models. */
@Entity(
    tableName = "ai_models",
    foreignKeys = [
        ForeignKey(
            entity = AiProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("providerId")]
)
data class AiModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val modelName: String,
    @ColumnInfo(defaultValue = "'CHAT'")
    val type: AiModelType = AiModelType.CHAT,
    /** 仅覆盖该聊天模型的请求协议；空串继承 Provider 的默认聊天协议。 */
    @ColumnInfo(defaultValue = "''")
    val chatApiFormat: String = "",
    /**
     * 相对 Base URL 的专用端点；空串由客户端按类型推断。
     * 例如 OpenRouter 生图 `/images`、TTS `/audio/speech`、向量 `/embeddings`。
     */
    @ColumnInfo(defaultValue = "''")
    val endpointPath: String = "",
    /** 模型级 headers/body 与厂商参数；覆盖 Provider 同名配置。 */
    @ColumnInfo(defaultValue = "'{}'")
    val extraJson: String = "{}",
    val createdAt: Long
)

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long?,
    val personaId: Long? = null,
    val title: String,
    /** SELECTION | CHAT | COMPANION. */
    val type: String,
    /** 分支来源；不设 FK，删除母会话不应连带删除用户保留的分支。 */
    val parentConversationId: Long? = null,
    val branchedFromMessageId: Long? = null,
    /** 已固化为长期记忆的最大 messages.id；0 表示尚未固化。 */
    @ColumnInfo(defaultValue = "0")
    val memoryConsolidatedThroughMessageId: Long = 0,
    val createdAt: Long,
    /** 会话列表按最近实际交互排序；老库迁移时回填 createdAt。 */
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = createdAt
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** system | user | assistant | tool. */
    val role: String,
    val content: String,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val tokenUsage: Int? = null,
    val createdAt: Long,
    /** null = 从未编辑；保留原 createdAt 以维持管道顺序。 */
    val editedAt: Long? = null,
    /** 附件清单 JSON（[MessageAttachment] 数组）；null = 无附件。文件在 filesDir/attachments。 */
    val attachmentsJson: String? = null
)

/** RikkaHub-style assignment: a role points at one concrete model, not a whole provider. */
@Entity(tableName = "model_assignments")
data class ModelAssignmentEntity(
    @PrimaryKey val role: ModelRole,
    val modelId: Long?
)
