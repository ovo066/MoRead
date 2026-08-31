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
    /**
     * 常驻用户画像（Memory 2.0 批次 B）：称呼、偏好与雷点、阅读口味、关系进展、共读书目。
     * 整段覆盖式改写，与人设同级注入且永不裁；只记本人，面具设定不进这里。
     */
    @ColumnInfo(defaultValue = "''")
    val userProfile: String = "",
    /** 关掉后该角色不固化、不召回长期记忆，也不注入画像（会话内滚动摘要不受此控）。 */
    @ColumnInfo(defaultValue = "1")
    val memoryEnabled: Boolean = true,
    /** 聊天外观 JSON（见 [PersonaChatAppearance]）；空对象 = 跟随阅读主题。 */
    @ColumnInfo(defaultValue = "'{}'")
    val chatAppearanceJson: String = "{}",
    /**
     * 角色声音：音色库（tts_voices.voiceId）里的一个 id；空 = 该角色不发语音，
     * 提示词里也不会告诉模型可以发语音（不给它兑现不了的能力）。
     */
    @ColumnInfo(defaultValue = "''")
    val voiceId: String = "",
    /** 语音情绪/风格指令；MiniMax 映射 voice_setting.emotion，OpenAI 映射 instructions。 */
    @ColumnInfo(defaultValue = "''")
    val voiceEmotion: String = "",
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
    /** 批注想法，可为空串（纯高亮）；作为讨论串的楼主层展示。 */
    val note: String = "",
    /** 划线色名（见 [AnnotationColors]），空串用默认强调色。 */
    val colorTag: String = "",
    /** 划线样式（见 [AnnotationStyle]），存 wire 值。 */
    @ColumnInfo(defaultValue = "'HIGHLIGHT'")
    val style: String = AnnotationStyle.HIGHLIGHT.wire,
    /** 随批注附带的语音/插图清单 JSON（见 AnnotationMedia）；'{}' = 纯文字批注。 */
    @ColumnInfo(defaultValue = "'{}'")
    val mediaJson: String = "{}",
    /** Visibility boundary used by the AI that created this annotation; null for user/legacy rows. */
    val sourceScopeChapterIndex: Int? = null,
    val sourceScopeCharOffset: Int? = null,
    val createdAt: Long
)

/**
 * 批注样式。语义约定（写进 add_annotation 工具描述，让 AI 批注自带信息层次）：
 * 荧光 = 金句/精彩段落，波浪 = 伏笔/暗线，直线 = 知识点/典故。
 */
enum class AnnotationStyle(val wire: String) {
    HIGHLIGHT("HIGHLIGHT"),
    UNDERLINE("UNDERLINE"),
    WAVY("WAVY");

    companion object {
        /** 未知/历史值一律回落荧光，坏数据不炸渲染。 */
        fun fromWire(value: String?): AnnotationStyle =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: HIGHLIGHT
    }
}

/** 划线色名（四预设 + "#RRGGBB" 自定义）；ARGB 由渲染层派生，这里只管稳定的标签值。 */
object AnnotationColors {
    const val AMBER = "amber"
    const val BAMBOO = "bamboo"
    const val INDIGO = "indigo"
    const val ROSE = "rose"
    val ALL = listOf(AMBER, BAMBOO, INDIGO, ROSE)

    /** 预设名或合法十六进制原样保留（统一成 "#RRGGBB" 大写）；其余回落琥珀。 */
    fun normalize(tag: String?): String {
        val trimmed = tag?.trim().orEmpty()
        ALL.firstOrNull { it.equals(trimmed, ignoreCase = true) }?.let { return it }
        val hex = trimmed.removePrefix("#")
        if (hex.length == 6 && hex.all(::isHexDigit)) return "#" + hex.uppercase()
        return AMBER
    }

    /** 角色批注不占用户色板：按 personaId 稳定散列，同角色永远同色。 */
    fun forPersona(personaId: Long): String = ALL[(personaId % ALL.size).toInt().coerceAtLeast(0)]

    private fun isHexDigit(c: Char): Boolean = c.isDigit() || c.lowercaseChar() in 'a'..'f'
}

/** 段评讨论串的回复层；楼主层是 [AnnotationEntity.note] 本身。 */
@Entity(
    tableName = "annotation_replies",
    foreignKeys = [
        ForeignKey(
            entity = AnnotationEntity::class,
            parentColumns = ["id"],
            childColumns = ["annotationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("annotationId")]
)
data class AnnotationReplyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val annotationId: Long,
    /** null = 用户；无 FK，角色删除后发言留存。 */
    val personaId: Long? = null,
    /** 可选：针对楼内某条回复；null = 直接回复楼主层。 */
    val replyToId: Long? = null,
    val contentMarkdown: String,
    /** 随回复附带的语音/插图清单 JSON（见 AnnotationMedia）；'{}' = 纯文字回复。 */
    @ColumnInfo(defaultValue = "'{}'")
    val mediaJson: String = "{}",
    val createdAt: Long
)

/**
 * AI 创作（续写/改写）。内容永不写入正文——正文只在锚点行末画一枚创作星标，
 * 点开卡片弹层查看（伴读二期批次三接 UI，本表随 v15 先行建好）。
 */
@Entity(
    tableName = "ai_creations",
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
data class AiCreationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    /** CONTINUE（续写，点锚 start == end）| REWRITE（改写，范围锚）。 */
    val type: String,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    /** 最近一次生效的用户方向指令。 */
    val directive: String = "",
    /** 当前展示版本；null = 最新版本。 */
    val activeVersionId: Long? = null,
    /** v1 恒 null（执笔模式不带人设）；无 FK。 */
    val personaId: Long? = null,
    val createdAt: Long
)

/** 创作的一个版本；「换个方向再写」新增版本，「继续写」在版本内追加。 */
@Entity(
    tableName = "ai_creation_versions",
    foreignKeys = [
        ForeignKey(
            entity = AiCreationEntity::class,
            parentColumns = ["id"],
            childColumns = ["creationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("creationId")]
)
data class AiCreationVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val creationId: Long,
    val ord: Int,
    /** 本版本对应的方向指令快照。 */
    val directive: String = "",
    val content: String = "",
    /** STREAMING | DONE | ERROR；进程被杀留下的 STREAMING 视为截断的 DONE。 */
    val status: String,
    val modelName: String = "",
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
    /** Visibility boundary used by the AI that created this note; null for user/legacy rows. */
    val sourceScopeChapterIndex: Int? = null,
    val sourceScopeCharOffset: Int? = null,
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
