package com.mozhi.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI 伴读角色卡（字段参考 SillyTavern Character Card）。
 *
 * [personaId][AnnotationEntity.personaId] 类外键一律不做 FK 约束：角色删除后其批注/笔记/会话
 * 是用户资产，必须留存，界面上渲染为「已删除角色」。
 */
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarPath: String? = null,
    /** 卡片副标题，如「共情写作者 · 情绪共读」。 */
    val subtitle: String = "",
    /** 人设描述：身份、世界观、性格。 */
    val personality: String,
    /** 语气与行为风格约束。 */
    val speakingStyle: String = "",
    /** 开场白。 */
    val greeting: String = "",
    /** JSON 数组 `[{"user":"…","assistant":"…"}]`，few-shot 用。 */
    val exampleDialogsJson: String = "[]",
    /** true = 角色扮演，false = 纯工具助手；决定系统提示词的语气约束。 */
    val isRoleplay: Boolean,
    /** JSON 字符串数组，工具名白名单；空数组 = 不开放工具。与已注册工具求交集后生效。 */
    val enabledToolsJson: String = "[]",
    /**
     * JSON 数组：世界书/设定集条目（见 [PersonaLoreEntry]），与人设分开存。
     * SQL 默认值让 v7 老行与种子 INSERT 不带此列也合法。
     */
    @ColumnInfo(defaultValue = "'[]'")
    val worldBookJson: String = "[]",
    /** 世界书总开关：关掉后所有条目都不注入（条目级开关见 [PersonaLoreEntry.enabled]）。 */
    @ColumnInfo(defaultValue = "1")
    val worldBookEnabled: Boolean = true,
    /** 覆盖全局 CHAT 分配的模型（ai_models.id）；null 用全局。悬空引用按未设置处理。 */
    val chatModelId: Long? = null,
    /** 内置模板角色标记：仅记录出身，可改可删，删了不自动复活。 */
    val isBuiltIn: Boolean = false,
    val createdAt: Long
)

/** 示例对话一问一答。 */
@Serializable
data class PersonaExampleDialog(
    val user: String,
    val assistant: String
)

/**
 * 世界书/设定集条目。
 * [enabled] 条目级开关；[constant] 常驻注入，false = 关键词触发（[keys] 命中当前上下文才注入）。
 */
@Serializable
data class PersonaLoreEntry(
    val name: String = "",
    val content: String,
    val enabled: Boolean = true,
    val constant: Boolean = true,
    val keys: List<String> = emptyList()
)

private val personaJson = Json { ignoreUnknownKeys = true }

/** 解析工具白名单；坏 JSON 降级为空列表（= 不开放工具），不让脏数据炸掉会话。 */
fun PersonaEntity.enabledTools(): List<String> = runCatching {
    personaJson.decodeFromString<List<String>>(enabledToolsJson)
}.getOrDefault(emptyList())

/** 解析示例对话；坏 JSON 降级为空列表。 */
fun PersonaEntity.exampleDialogs(): List<PersonaExampleDialog> = runCatching {
    personaJson.decodeFromString<List<PersonaExampleDialog>>(exampleDialogsJson)
}.getOrDefault(emptyList())

/** 解析世界书；坏 JSON 降级为空列表。 */
fun PersonaEntity.worldBook(): List<PersonaLoreEntry> = runCatching {
    personaJson.decodeFromString<List<PersonaLoreEntry>>(worldBookJson)
}.getOrDefault(emptyList())

fun encodeEnabledTools(tools: List<String>): String =
    personaJson.encodeToString(tools)

fun encodeExampleDialogs(dialogs: List<PersonaExampleDialog>): String =
    personaJson.encodeToString(dialogs)

fun encodeWorldBook(entries: List<PersonaLoreEntry>): String =
    personaJson.encodeToString(entries)

/**
 * 批注（用户与 AI 统一模型，personaId = null 为用户）。
 *
 * 选区坐标是章内 UTF-16 字符偏移（与阅读位置、选词引擎同一坐标系），
 * 与 text.mz 的字节坐标无关；区间左闭右开 `[startCharOffset, endCharOffset)`。
 */
@Entity(
    tableName = "annotations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "chapterIndex"])]
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** null = 用户手写；无 FK，见 [PersonaEntity] 说明。 */
    val personaId: Long? = null,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    /** 选中原文快照，定位失效时仍可展示。 */
    val selectedText: String,
    /** 批注想法，可为空串（纯高亮）。 */
    val note: String = "",
    /** 高亮色名，空串用默认色；用户与各 AI 角色的底色区分由 personaId 决定。 */
    val colorTag: String = "",
    val createdAt: Long
)

/** 读书笔记（personaId = null 为用户手写）。 */
@Entity(
    tableName = "notes",
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
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val personaId: Long? = null,
    val title: String,
    val contentMarkdown: String,
    /** NOTE | PLOT_SUMMARY；专类便于书籍详情集中回顾，不靠标题字符串猜。 */
    val kind: String = "NOTE",
    /** 由伴读会话保存时记录来源；会话删除后笔记仍保留，故不设 FK。 */
    val sourceConversationId: Long? = null,
    /** 可选原文锚点：笔记出自某处时记录章内位置（UTF-16 字符偏移）。 */
    val relatedChapterIndex: Int? = null,
    val relatedCharOffset: Int? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/** AI/选区生成的书籍插图；文件在应用私有目录，元数据用于书籍详情「插图廊」。 */
@Entity(
    tableName = "illustrations",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["bookId", "createdAt"])]
)
data class IllustrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** 章内 UTF-16 锚点；null 表示只关联书籍、不固定正文位置。 */
    val chapterIndex: Int? = null,
    val charOffset: Int? = null,
    val sourceText: String = "",
    val prompt: String,
    val imagePath: String,
    val mediaType: String? = null,
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
    /** 无 FK：角色删除后插图仍是用户资产。 */
    val createdByPersonaId: Long? = null,
    val createdAt: Long
)
