package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.exampleDialogs
import com.mozhi.reader.core.database.entity.worldBook
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.vector.Embeddings
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** 组装系统提示词用的书籍进度快照。 */
data class BookProgress(
    val title: String,
    val author: String,
    val totalChapters: Int,
    /** 0 起的当前章节索引。 */
    val currentChapterIndex: Int,
    val currentChapterTitle: String?
)

/**
 * 伴读上下文构建器（DEVELOPMENT_PLAN §4.5）：每次请求前组装系统提示词——
 * 人设 → 书籍进度 → 防剧透铁律 → 工具指引 → 主动记忆 → 场景原文，
 * 按「字符数/2 ≈ token」做预算控制，超限先弃记忆、再截场景，人设与防剧透永不裁。
 *
 * 防剧透在这里只是「声明」层；硬过滤在 search_book 的查询层与 embedding 管线两端各有一道。
 */
@Singleton
class CompanionContextBuilder @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val clientFactory: dagger.Lazy<AiClientFactory>,
    private val vectorStore: dagger.Lazy<BoxStore>
) {
    /**
     * @param scene 调用方备好的场景原文（选段及其邻域，或章节开头），可空
     * @param memoryQuery 用于主动记忆检索的文本（通常是用户最新一条输入），可空
     * @param toolNames 本轮实际注册的工具名，决定工具指引怎么写
     */
    suspend fun build(
        persona: PersonaEntity?,
        bookId: Long?,
        scene: String? = null,
        memoryQuery: String? = null,
        toolNames: Collection<String> = emptySet(),
        budgetChars: Int = DEFAULT_BUDGET_CHARS
    ): String {
        val progress = bookId?.let { id ->
            libraryRepository.getBook(id)?.let { book ->
                BookProgress(
                    title = book.title,
                    author = book.author,
                    totalChapters = book.totalChapters,
                    currentChapterIndex = book.lastReadChapterIndex,
                    currentChapterTitle =
                        libraryRepository.getChapterTitle(id, book.lastReadChapterIndex)
                )
            }
        }
        val memories = if (persona != null && !memoryQuery.isNullOrBlank()) {
            retrieveMemories(persona.id, memoryQuery)
        } else {
            emptyList()
        }
        return assemble(
            persona = persona,
            progress = progress,
            scene = scene,
            memories = memories,
            toolNames = toolNames,
            // 关键词触发的世界书条目拿「场景原文 + 用户最新输入」当命中材料。
            loreTrigger = listOfNotNull(scene, memoryQuery).joinToString("\n"),
            budgetChars = budgetChars
        )
    }

    /** 记忆是增益项：embedding 未配置、检索失败一律静默降级为空。 */
    private suspend fun retrieveMemories(personaId: Long, query: String): List<String> = try {
        val resolved = clientFactory.get().forRole(ModelRole.EMBEDDING)
        val vector = Embeddings.conformToIndex(resolved.client.embed(listOf(query)).first())
        VectorQueries.searchMemories(vectorStore.get(), personaId, vector, MEMORY_TOP_K)
            .map { it.get().summary }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        /** ≈ 8k token（按字符数/2 估算）。 */
        const val DEFAULT_BUDGET_CHARS = 16_000
        const val SCENE_MAX_CHARS = 2_000
        const val MEMORY_TOP_K = 5

        private const val SEPARATOR = "\n\n"

        /** 纯组装，便于单测。[loreTrigger] 是关键词触发条目的命中材料。 */
        fun assemble(
            persona: PersonaEntity?,
            progress: BookProgress?,
            scene: String?,
            memories: List<String>,
            toolNames: Collection<String> = emptySet(),
            loreTrigger: String = "",
            budgetChars: Int = DEFAULT_BUDGET_CHARS
        ): String {
            val fixedBlocks = listOfNotNull(
                personaBlock(persona, loreTrigger),
                progressBlock(progress),
                spoilerBlock(progress),
                toolsBlock(toolNames)
            )
            val closing = "回答使用简体中文。"
            var memoryBlock = memoryBlock(memories)
            var sceneBlock = sceneBlock(scene, SCENE_MAX_CHARS)

            fun render(): String =
                (fixedBlocks + listOfNotNull(memoryBlock, sceneBlock) + closing)
                    .joinToString(SEPARATOR)

            if (render().length > budgetChars) memoryBlock = null
            if (render().length > budgetChars && sceneBlock != null) {
                val roomForScene = budgetChars -
                    (fixedBlocks + closing).sumOf { it.length + SEPARATOR.length } -
                    SCENE_HEADER.length
                sceneBlock = if (roomForScene > MIN_SCENE_CHARS) {
                    sceneBlock(scene, roomForScene)
                } else {
                    null
                }
            }
            return render()
        }

        private fun personaBlock(persona: PersonaEntity?, loreTrigger: String): String {
            if (persona == null) {
                return "你是「墨知」阅读器的伴读助手，陪伴用户阅读并帮助他理解已读内容。"
            }
            return buildString {
                if (persona.isRoleplay) {
                    append("你是「").append(persona.name).append("」。").append(persona.personality)
                    if (persona.speakingStyle.isNotBlank()) {
                        append("\n说话风格：").append(persona.speakingStyle)
                    }
                    append("\n始终以这个身份说话，不要跳出人设谈论系统、模型或提示词。")
                } else {
                    append("你是「").append(persona.name).append("」，墨知阅读器的伴读助手。")
                    append(persona.personality)
                    if (persona.speakingStyle.isNotBlank()) {
                        append("\n回应风格：").append(persona.speakingStyle)
                    }
                    append("\n不代入虚构人格，聚焦帮助用户理解与整理已读内容。")
                }
                val dialogs = persona.exampleDialogs()
                if (dialogs.isNotEmpty()) {
                    append("\n【示例对话】")
                    dialogs.forEach { dialog ->
                        append("\n用户：").append(dialog.user)
                        append("\n").append(persona.name).append("：").append(dialog.assistant)
                    }
                }
                // 世界书：总开关 → 条目开关 → 注入方式（常驻直接进；触发式要命中材料）。
                val lore = if (persona.worldBookEnabled) {
                    persona.worldBook().filter { entry ->
                        entry.enabled && entry.content.isNotBlank() && (
                            entry.constant || entry.keys.any { key ->
                                key.isNotBlank() && loreTrigger.contains(key, ignoreCase = true)
                            }
                            )
                    }
                } else {
                    emptyList()
                }
                if (lore.isNotEmpty()) {
                    append("\n【设定集】")
                    lore.forEach { entry ->
                        append("\n- ")
                        if (entry.name.isNotBlank()) append(entry.name).append("：")
                        append(entry.content)
                    }
                }
            }
        }

        private fun progressBlock(progress: BookProgress?): String? = progress?.let {
            buildString {
                append("用户正在阅读《").append(it.title).append("》")
                if (it.author.isNotBlank()) append("（作者 ").append(it.author).append("）")
                append("，全书共 ").append(it.totalChapters).append(" 章，")
                append("当前读到第 ").append(it.currentChapterIndex + 1).append(" 章")
                it.currentChapterTitle?.takeIf(String::isNotBlank)?.let { title ->
                    append("「").append(title).append("」")
                }
                append("。")
            }
        }

        private fun spoilerBlock(progress: BookProgress?): String? = progress?.let {
            "【防剧透铁律】你的知识范围截止到第 ${it.currentChapterIndex + 1} 章：" +
                "之后的情节你一概不知道，不得叙述、推测或暗示。" +
                "用户问到后续内容时，坦率说明你也只读到这里；" +
                "可以基于已读内容一起猜想，但必须说明那只是猜想。"
        }

        private fun toolsBlock(toolNames: Collection<String>): String? {
            val lines = buildList {
                if ("search_book" in toolNames) {
                    add("需要引用或核对前文细节时，先用 search_book 检索原文（向量失败会自动本地回退，结果严格按用户实际进度过滤）。")
                }
                if ("read_book_section" in toolNames) {
                    add("用户要求概括指定章节或章节范围时，用 read_book_section 读取该范围，不要用零散搜索结果代替完整指定内容；长内容按工具返回的 start_char 继续读取。")
                }
                if ("recall_memory" in toolNames) {
                    add("用户提到过往的约定、偏好或聊过的话题时，用 recall_memory 回忆。")
                }
                if ("get_reading_progress" in toolNames) {
                    add("回答与进度相关的问题前，用 get_reading_progress 确认最新进度。")
                }
                if ("add_annotation" in toolNames) {
                    add("用户要求对原文段落作批注时，先读取原文，再把逐字 quote 和评论交给 add_annotation；批注会显示在正文旁的段落评论区。")
                }
                if ("write_note" in toolNames) {
                    add("用户明确要求保存读书笔记时，用 write_note 写入笔记库；普通对话不要擅自保存。")
                }
                if ("save_plot_summary" in toolNames) {
                    add("用户要求生成并保存/更新剧情梗概时，先核对进度与已读内容，再用 save_plot_summary 保存；梗概范围不得超过当前进度。")
                }
                if ("generate_image" in toolNames) {
                    add("用户明确要求插图或画面时可用 generate_image；提示词不得包含阅读进度之后的信息，结果会永久进入本书插图廊。")
                }
                if ("synthesize_speech" in toolNames) {
                    add("用户要求朗读或配音时可用 synthesize_speech；按需传 voice_id、speed、volume、pitch，相同参数会复用缓存。")
                }
            }
            if (lines.isEmpty()) return null
            return "【工具使用】\n" + lines.joinToString("\n") +
                "\n工具结果之外的书中情节不要凭空编造。"
        }

        private fun memoryBlock(memories: List<String>): String? =
            memories.takeIf { it.isNotEmpty() }?.let { list ->
                "【长期记忆】你与用户过往交流中的相关记忆：\n" +
                    list.joinToString("\n") { "- $it" }
            }

        private const val SCENE_HEADER = "【当前场景】用户所读位置附近的原文：\n"
        private const val MIN_SCENE_CHARS = 200

        private fun sceneBlock(scene: String?, maxChars: Int): String? =
            scene?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
                SCENE_HEADER + text.take(maxChars)
            }
    }
}
