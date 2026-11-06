package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 伴读能交给用户的文件种类；只影响卡片图标与默认标题，正文一律 Markdown。 */
enum class DocumentKind(val wire: String) {
    READING_REPORT("reading_report"),
    CHAPTER_SUMMARY("chapter_summary"),
    CHARACTER_SHEET("character_sheet"),
    QUOTE_COLLECTION("quote_collection"),
    FREE("free");

    companion object {
        fun fromWire(value: String?): DocumentKind =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() } ?: FREE
    }
}

/** 一份已校验的文档；落库形态就是 create_document 的调用参数，与图表一样不另建表。 */
@Serializable
data class DocumentSpec(
    val title: String = "",
    val kind: String = DocumentKind.FREE.wire,
    val content_md: String = ""
) {
    val documentKind: DocumentKind get() = DocumentKind.fromWire(kind)
    val content: String get() = content_md
}

object DocumentSpecCodec {
    const val MAX_TITLE = 60
    const val MAX_CHARS = 60_000
    const val MIN_CHARS = 20
    /** 成功结果的开头；历史消息据此判断这份文件当时是否真的生成了。 */
    const val SUCCESS_PREFIX = "文件「"

    sealed interface Outcome {
        data class Valid(val spec: DocumentSpec) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    fun fromToolCall(arguments: String, result: String?): DocumentSpec? {
        if (result == null || !result.startsWith(SUCCESS_PREFIX)) return null
        return (decode(arguments) as? Outcome.Valid)?.spec
    }

    fun decode(arguments: String): Outcome = try {
        validate(AiJson.decodeFromString(DocumentSpec.serializer(), arguments))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Outcome.Invalid("参数不是合法的文件规格：${error.message?.take(160) ?: "无法解析"}")
    }

    fun validate(raw: DocumentSpec): Outcome {
        val content = raw.content_md.trim()
        val kind = DocumentKind.fromWire(raw.kind)
        val title = raw.title.trim().lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE)
        return when {
            content.length < MIN_CHARS -> Outcome.Invalid("content_md 太短；短内容直接在对话里回答，不必做成文件")
            content.length > MAX_CHARS -> Outcome.Invalid("content_md 最多 $MAX_CHARS 字，请精简或拆成多份文件")
            title.isBlank() -> Outcome.Invalid("需要 title（文件标题）")
            else -> Outcome.Valid(DocumentSpec(title = title, kind = kind.wire, content_md = content))
        }
    }

    /** 导出文件名：去掉文件系统不认的字符，空了回落到 [fallback]。 */
    fun fileName(spec: DocumentSpec, fallback: String): String {
        val base = spec.title.replace(ILLEGAL_FILE_CHARS, " ").replace(Regex("\\s+"), " ").trim().take(MAX_TITLE)
        return base.ifBlank { fallback } + ".md"
    }

    /** 卡片预览：去掉 Markdown 标记后的前几行正文。 */
    fun preview(spec: DocumentSpec, maxChars: Int = 90): String =
        spec.content.lineSequence()
            .map { it.trim().trimStart('#', '>', '-', '*', '+', ' ').replace("**", "").replace("`", "").trim() }
            .filter { it.isNotBlank() && !it.startsWith("|") && !it.all { ch -> ch == '-' || ch == '=' } }
            .filterNot { it == spec.title }
            .joinToString(" ")
            .take(maxChars)

    private val ILLEGAL_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
}

/**
 * 把整理好的长内容交给用户成一份文件（聊天里显示为文件卡，可预览、分享、保存）。
 * 只做本地校验，不读书、不写库；素材须先经其他工具取得，因而天然受阅读进度约束。
 */
internal class CreateDocumentTool : AgentTool {
    override val displayName = "生成文件"
    override val spec = ToolSpec(
        name = "create_document",
        description = "把读书报告、章节总结、人物档案、金句摘录等成篇内容做成一份 Markdown 文件交给用户，" +
            "聊天里显示为可预览、分享、保存的文件卡。用户要求「整理成文档/报告/导出/发我一份」时使用；" +
            "篇幅较长、结构化、值得带走的内容也可主动用它，而不是把长文贴进对话。" +
            "写之前先用 get_reading_progress、list_annotations、list_notes、search_book 等取素材，" +
            "只写用户已读范围内的内容，引用原文要来自工具结果，不要编造。" +
            "调用成功后用一两句话说明文件里有什么，不要在回复里重复正文。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string"); put("description", "文件标题，${DocumentSpecCodec.MAX_TITLE} 字以内") }
                putJsonObject("kind") {
                    put("type", "string")
                    putJsonArray("enum") { DocumentKind.entries.forEach { add(it.wire) } }
                    put("description", "reading_report 读书报告 / chapter_summary 章节总结 / character_sheet 人物档案 / quote_collection 金句摘录 / free 其他")
                }
                putJsonObject("content_md") { put("type", "string"); put("description", "完整 Markdown 正文，以一级标题开头") }
            }
            putJsonArray("required") { add("title"); add("content_md") }
        }
    )

    override suspend fun execute(arguments: JsonObject): ToolResult =
        when (val outcome = DocumentSpecCodec.decode(arguments.toString())) {
            is DocumentSpecCodec.Outcome.Invalid -> ToolResult.Failure("INVALID_ARGUMENT", outcome.reason)
            is DocumentSpecCodec.Outcome.Valid -> ToolResult.Success(
                "${DocumentSpecCodec.SUCCESS_PREFIX}${outcome.spec.title}」已生成（${outcome.spec.content.length} 字），" +
                    "已作为文件卡显示给用户。请用一两句话说明内容要点，不要重复正文。"
            )
        }
}
