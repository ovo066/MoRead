package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteRepository
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal data class ProgressOverview(
    val book: BookEntity,
    val currentChapter: ChapterEntity?,
    val previousChapter: ChapterEntity?,
    val nextChapter: ChapterEntity?,
    val totalCharacters: Long,
    val charactersBeforeCurrent: Long = 0,
    val tags: List<String>,
    val readingDays: List<ReadingDailyEntity>,
    val noteCount: Int,
    val plotSummaries: List<NoteEntity>,
    val annotationCount: Int,
    val currentChapterAnnotationCount: Int,
    val bookmarkCount: Int
)

internal fun formatProgressOverview(
    overview: ProgressOverview,
    spoilerProtectionEnabled: Boolean,
    nowMillis: Long = System.currentTimeMillis()
): String = buildString {
    val book = overview.book
    append("《").append(book.title).append("》")
    if (book.author.isNotBlank()) append("｜作者 ").append(book.author)
    append("｜").append(if (book.sourceType == BookSourceType.EPUB) "EPUB" else "TXT")
    if (overview.tags.isNotEmpty()) append("｜标签：").append(overview.tags.joinToString("、"))
    append("｜状态：").append(book.readState().label())

    append("\n篇幅：").append(book.totalChapters.coerceAtLeast(0)).append(" 章")
    if (overview.totalCharacters > 0) append("，约 ").append(formatChineseCount(overview.totalCharacters)).append("字")
    append("。")

    val currentNumber = if (book.totalChapters > 0) {
        (book.lastReadChapterIndex + 1).coerceIn(1, book.totalChapters)
    } else 0
    append("\n进度：")
    if (currentNumber == 0) {
        append("尚无可阅读章节。")
    } else if (book.lastReadAt == 0L) {
        append("尚未开始阅读；起始章节为第 ").append(currentNumber).append(" 章")
        overview.currentChapter?.title?.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
        append("。")
    } else {
        append("第 ").append(currentNumber).append(" 章")
        overview.currentChapter?.title?.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
        val chapterChars = overview.currentChapter?.charCount?.coerceAtLeast(0) ?: 0
        if (chapterChars > 0) {
            val offset = book.lastReadCharOffset.coerceIn(0, chapterChars)
            append("，本章已读 ").append(percent(offset.toLong(), chapterChars.toLong()))
                .append("（").append(offset).append('/').append(chapterChars).append(" 字）")
        }
        if (overview.totalCharacters > 0) {
            val consumed = overview.charactersBeforeCurrent + book.lastReadCharOffset.coerceAtLeast(0)
            append("，全书约 ").append(percent(consumed, overview.totalCharacters))
        }
        append("。")
    }

    overview.previousChapter?.let { previous ->
        append("\n上一章：第 ").append(previous.chapterIndex + 1).append(" 章")
        previous.title.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
        append("。")
    }
    if (!spoilerProtectionEnabled) {
        overview.nextChapter?.let { next ->
            append("\n下一章：第 ").append(next.chapterIndex + 1).append(" 章")
            next.title.takeIf(String::isNotBlank)?.let { append("「").append(it).append("」") }
            append("。")
        }
    }

    val durationMs = overview.readingDays.sumOf { it.durationMs.coerceAtLeast(0) }
    val lastReadAt = listOf(book.lastReadAt, overview.readingDays.maxOfOrNull { it.lastReadAt } ?: 0L).max()
    append("\n阅读记录：累计 ").append(formatDuration(durationMs))
        .append("，共 ").append(overview.readingDays.size).append(" 天")
    if (lastReadAt > 0) append("，最近一次 ").append(formatRelativeTime(lastReadAt, nowMillis))
    append("。")

    val latestPlot = overview.plotSummaries.maxByOrNull { it.updatedAt }
    append("\n现有素材：剧情梗概 ").append(overview.plotSummaries.size).append(" 篇")
    latestPlot?.let {
        val coverage = it.relatedChapterIndex?.let { index -> "覆盖至第 ${index + 1} 章，" }.orEmpty()
        append("（").append(coverage).append(formatRelativeTime(it.updatedAt, nowMillis)).append("更新）")
    }
    append("｜笔记 ").append(overview.noteCount).append(" 条")
        .append("｜划线批注 ").append(overview.annotationCount).append(" 条")
    if (overview.currentChapterAnnotationCount > 0) {
        append("（本章 ").append(overview.currentChapterAnnotationCount).append(" 条）")
    }
    append("｜书签 ").append(overview.bookmarkCount).append(" 个。")

    if (spoilerProtectionEnabled && currentNumber > 0) {
        append("\n用户尚未读到第 ").append(currentNumber)
            .append(" 章之后的内容，回答不要涉及后续剧情。")
    }
}

internal class ListChaptersTool(
    private val libraryRepository: LibraryRepository,
    private val bookId: Long,
    private val spoilerProtectionEnabled: Boolean
) : AgentTool {
    override val displayName: String = "查看章节目录"
    override val spec = ToolSpec(
        name = "list_chapters",
        description = "列出书籍目录、卷部层级与章节号；不确定章节号或要按卷概括时先调用，防剧透开启时不会显示未读章节标题。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from_chapter") { put("type", "integer"); put("description", "起始章节号，从 1 开始") }
                putJsonObject("to_chapter") { put("type", "integer"); put("description", "结束章节号（包含）") }
                putJsonObject("level") { put("type", "string"); putJsonArray("enum") { add("volume"); add("chapter"); add("all") }; put("description", "volume 只列卷部，chapter 只列章节，all 全部；默认 all") }
            }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val book = libraryRepository.getBook(bookId) ?: return "未找到当前书籍"
        val chapters = libraryRepository.getChapters(bookId)
        val toc = libraryRepository.getTocEntries(bookId)
        val from = arguments.int("from_chapter")
        val to = arguments.int("to_chapter")
        if (from != null && from < 1) return "起始章节号必须从 1 开始"
        if (to != null && from != null && to < from) return "章节范围无效：$from-$to"
        val level = arguments.text("level").lowercase().ifBlank { "all" }
        if (level !in setOf("volume", "chapter", "all")) return "level 只能是 volume、chapter 或 all"
        return formatChapterOutline(book, chapters, toc, from, to, level, spoilerProtectionEnabled)
    }
}

internal fun formatChapterOutline(
    book: BookEntity,
    chapters: List<ChapterEntity>,
    toc: List<BookTocEntryEntity>,
    fromChapter: Int? = null,
    toChapter: Int? = null,
    level: String = "all",
    spoilerProtectionEnabled: Boolean = true
): String {
    if (chapters.isEmpty()) return "《${book.title}》还没有可用章节目录。"
    val readableLast = if (spoilerProtectionEnabled) {
        book.lastReadChapterIndex.coerceIn(0, chapters.last().chapterIndex)
    } else chapters.last().chapterIndex
    val explicit = fromChapter != null || toChapter != null
    val lastChapterNumber = chapters.last().chapterIndex + 1
    val requestedFrom = (fromChapter ?: if (explicit) 1 else (book.lastReadChapterIndex - 20 + 1)).coerceAtLeast(1)
    if (requestedFrom > lastChapterNumber) return "起始章节号 $requestedFrom 超出全书范围（共 $lastChapterNumber 章）。"
    val requestedTo = (toChapter ?: if (explicit) lastChapterNumber else (book.lastReadChapterIndex + 20 + 1))
        .coerceIn(requestedFrom, lastChapterNumber)
    val visibleTo = if (spoilerProtectionEnabled) minOf(requestedTo - 1, readableLast) else requestedTo - 1
    if (visibleTo < requestedFrom - 1) return "第 $requestedFrom 章超出当前已读范围。"
    val visibleFrom = requestedFrom - 1
    val lines = mutableListOf<String>()
    lines += buildString {
        append("《").append(book.title).append("》目录（共 ").append(chapters.size).append(" 章")
        if (book.lastReadAt > 0) append("，已读到第 ").append(book.lastReadChapterIndex + 1).append(" 章")
        if (spoilerProtectionEnabled) append("；防剧透：只列出已读部分")
        append("）")
    }

    if (level != "chapter" && toc.isNotEmpty()) {
        volumeRanges(toc).forEach { volume ->
            val include = if (explicit) {
                volume.lastIndex >= visibleFrom && volume.firstIndex <= visibleTo
            } else {
                volume.firstIndex <= readableLast
            }
            if (!include) return@forEach
            val suffix = if (book.lastReadChapterIndex in volume.firstIndex..volume.lastIndex) {
                "（已读到 #${book.lastReadChapterIndex + 1}）"
            } else ""
            lines += "【${volume.title}】#${volume.firstIndex + 1}-#${volume.lastIndex + 1}$suffix"
        }
    }
    if (level != "volume" && visibleTo >= visibleFrom) {
        chapters.asSequence()
            .filter { it.chapterIndex in visibleFrom..visibleTo }
            .forEach { chapter ->
                val current = if (chapter.chapterIndex == book.lastReadChapterIndex) "  ← 当前" else ""
                val size = if (chapter.charCount > 0) "    ${formatChineseCount(chapter.charCount.toLong())}字" else ""
                lines += "  #${chapter.chapterIndex + 1} ${chapter.title.ifBlank { "未命名章节" }}$size$current"
            }
    }
    val unread = (chapters.last().chapterIndex - readableLast).coerceAtLeast(0)
    if (spoilerProtectionEnabled && unread > 0) lines += "后面还有 $unread 章尚未读到，标题不予显示。"
    return capChapterOutline(lines, requestedTo)
}


private fun capChapterOutline(lines: List<String>, requestedTo: Int): String {
    val accepted = mutableListOf<String>()
    var firstOmitted: String? = null
    for (line in lines) {
        val candidate = (accepted + line).joinToString("\n")
        if (accepted.size >= MAX_OUTLINE_LINES || candidate.length > MAX_OUTLINE_CHARS) {
            firstOmitted = line
            break
        }
        accepted += line
    }
    if (firstOmitted == null) return accepted.joinToString("\n")

    fun continuationFor(line: String?): String {
        val nextChapter = line?.let { Regex("^\\s*#(\\d+)").find(it) }
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (nextChapter != null) {
            "目录内容未完：请继续调用 list_chapters(from_chapter=$nextChapter, to_chapter=$requestedTo)。"
        } else {
            "目录内容未完：请缩小章节范围后继续调用 list_chapters。"
        }
    }

    var continuation = continuationFor(firstOmitted)
    while (accepted.isNotEmpty() &&
        (accepted.size + 1 > MAX_OUTLINE_LINES || (accepted + continuation).joinToString("\n").length > MAX_OUTLINE_CHARS)
    ) {
        firstOmitted = accepted.removeAt(accepted.lastIndex)
        continuation = continuationFor(firstOmitted)
    }
    return (accepted + continuation).joinToString("\n").take(MAX_OUTLINE_CHARS)
}

private data class VolumeRange(val title: String, val firstIndex: Int, val lastIndex: Int)

private fun volumeRanges(toc: List<BookTocEntryEntity>): List<VolumeRange> = toc.mapIndexedNotNull { position, entry ->
    if (!entry.hasChildren && entry.chapterIndex != null) return@mapIndexedNotNull null
    val descendants = toc.drop(position + 1).takeWhile { it.depth > entry.depth }
        .mapNotNull { it.chapterIndex }
    val own = entry.chapterIndex?.let(::listOf).orEmpty()
    val indices = own + descendants
    if (indices.isEmpty()) null else VolumeRange(entry.title.ifBlank { "未命名分卷" }, indices.min(), indices.max())
}

internal class ListAnnotationsTool(
    private val libraryRepository: LibraryRepository,
    private val annotations: AnnotationRepository,
    private val bookId: Long,
    private val currentPersonaId: Long?,
    private val spoilerProtectionEnabled: Boolean
) : AgentTool {
    override val displayName: String = "查看划线批注"
    override val spec = ToolSpec(
        name = "list_annotations",
        description = "读取用户和伴读角色已有的划线批注；讨论划线或新增批注前先调用，避免重复标注。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("from_chapter") { put("type", "integer"); put("description", "起始章节号，默认当前章") }
                putJsonObject("to_chapter") { put("type", "integer"); put("description", "结束章节号，默认等于起始章") }
                putJsonObject("author") { put("type", "string"); putJsonArray("enum") { add("user"); add("companion"); add("all") }; put("description", "作者筛选，默认 all") }
                putJsonObject("query") { put("type", "string"); put("description", "可选关键词，匹配引文或想法") }
            }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val book = libraryRepository.getBook(bookId) ?: return "未找到当前书籍"
        val current = book.lastReadChapterIndex + 1
        val from = arguments.int("from_chapter") ?: current
        var to = arguments.int("to_chapter") ?: from
        if (from < 1 || to < from) return "章节范围无效：$from-$to"
        if (spoilerProtectionEnabled) to = minOf(to, current)
        if (from > to) return "第 $from 章超出当前已读范围。"
        val author = arguments.text("author").lowercase().ifBlank { "all" }
        if (author !in setOf("user", "companion", "all")) return "author 只能是 user、companion 或 all"
        val query = arguments.text("query")
        val rows = annotations.getForChapterRange(bookId, from - 1, to - 1)
        val counts = annotations.getReplyCounts(rows.map { it.id })
        return formatAnnotationList(book.title, from, to, rows, counts, author, query, currentPersonaId)
    }
}

internal fun formatAnnotationList(
    bookTitle: String,
    fromChapter: Int,
    toChapter: Int,
    annotations: List<AnnotationEntity>,
    replyCounts: Map<Long, Int> = emptyMap(),
    author: String = "all",
    query: String = "",
    currentPersonaId: Long? = null
): String {
    val filtered = annotations.filter { row ->
        val authorMatch = when (author) {
            "user" -> row.personaId == null
            "companion" -> row.personaId != null
            else -> true
        }
        val queryMatch = query.isBlank() || row.selectedText.contains(query, true) || row.note.contains(query, true)
        authorMatch && queryMatch
    }
    val range = if (fromChapter == toChapter) "第 $fromChapter 章" else "第 $fromChapter-$toChapter 章"
    if (filtered.isEmpty()) return "《$bookTitle》$range 还没有符合条件的划线批注。"
    val lines = mutableListOf("《$bookTitle》$range 的划线批注（${filtered.size} 条）：")
    filtered.forEach { row ->
        val who = when {
            row.personaId == null -> "用户"
            row.personaId == currentPersonaId -> "当前伴读"
            else -> "伴读角色 #${row.personaId}"
        }
        val replies = replyCounts[row.id].orZero().takeIf { it > 0 }?.let { " · 讨论 $it 条" }.orEmpty()
        lines += "#${row.id} 第 ${row.chapterIndex + 1} 章 · $who$replies"
        lines += "  引文：${row.selectedText.singleLine().clip(80)}"
        if (row.note.isNotBlank()) lines += "  想法：${row.note.singleLine().clip(120)}"
    }
    return capLines(lines, maxLines = 100, maxChars = MAX_ANNOTATION_CHARS, continuation = "批注列表已截断，请缩小章节范围或增加关键词后重试。")
}

internal class ListNotesTool(
    private val notes: NoteRepository,
    private val bookId: Long,
    private val currentPersonaId: Long?
) : AgentTool {
    override val displayName: String = "查看笔记与梗概"
    override val spec = ToolSpec(
        name = "list_notes",
        description = "列出本书已有笔记与剧情梗概，或按 note_id 分段读取全文；更新内容前先读取原文，用户手写内容只读。",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { add("note"); add("plot_summary"); add("all") }; put("description", "内容类型，默认 all") }
                putJsonObject("note_id") { put("type", "integer"); put("description", "笔记编号；提供时读取该条全文") }
                putJsonObject("start_char") { put("type", "integer"); put("description", "全文读取起点，默认 0") }
                putJsonObject("max_chars") { put("type", "integer"); put("description", "本次最多返回字符数，1000-8000，默认 6000") }
            }
        }
    )

    override suspend fun execute(arguments: JsonObject): String {
        val noteId = arguments["note_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (noteId != null) {
            val note = notes.getNote(noteId) ?: return "未找到第 $noteId 条笔记"
            if (note.bookId != bookId) return "第 $noteId 条笔记不属于当前书籍"
            val start = (arguments.int("start_char") ?: 0).coerceAtLeast(0)
            val maxChars = (arguments.int("max_chars") ?: DEFAULT_NOTE_READ_CHARS).coerceIn(1_000, MAX_NOTE_READ_CHARS)
            return formatNoteContent(note, start, maxChars, currentPersonaId)
        }
        val kind = arguments.text("kind").lowercase().ifBlank { "all" }
        if (kind !in setOf("note", "plot_summary", "all")) return "kind 只能是 note、plot_summary 或 all"
        return formatNoteIndex(notes.getForBook(bookId), kind, currentPersonaId)
    }
}

internal fun formatNoteIndex(
    notes: List<NoteEntity>,
    kind: String = "all",
    currentPersonaId: Long? = null,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val filtered = notes.filter {
        when (kind) {
            "note" -> it.kind == NoteRepository.KIND_NOTE
            "plot_summary" -> it.kind == NoteRepository.KIND_PLOT_SUMMARY
            else -> true
        }
    }.sortedWith(compareByDescending<NoteEntity> { it.updatedAt }.thenByDescending { it.id })
    if (filtered.isEmpty()) return "当前书籍还没有符合条件的笔记或剧情梗概。"
    val lines = mutableListOf("本书的笔记与梗概（共 ${filtered.size} 条）：")
    filtered.forEach { note ->
        val kindLabel = if (note.kind == NoteRepository.KIND_PLOT_SUMMARY) "梗概" else "笔记"
        val author = when {
            note.personaId == null -> "用户手写（只读，不可改写）"
            note.personaId == currentPersonaId -> "当前伴读"
            else -> "伴读角色 #${note.personaId}"
        }
        lines += "#${note.id} [$kindLabel] ${note.title.ifBlank { "未命名" }} · ${note.contentMarkdown.length} 字 · ${formatRelativeTime(note.updatedAt, nowMillis)}更新 · 作者：$author"
        note.contentMarkdown.firstMeaningfulLine()?.let { lines += "  开头：${it.clip(100)}" }
    }
    lines += "读全文：list_notes(note_id=${filtered.first().id})"
    return capLines(lines, 120, MAX_NOTE_INDEX_CHARS, "笔记目录已截断；请用 kind 筛选后重试。")
}

internal fun formatNoteContent(note: NoteEntity, startChar: Int, maxChars: Int, currentPersonaId: Long? = null): String {
    val safeStart = startChar.coerceIn(0, note.contentMarkdown.length)
    val end = (safeStart + maxChars).coerceAtMost(note.contentMarkdown.length)
    val author = when {
        note.personaId == null -> "用户手写（只读，不可改写）"
        note.personaId == currentPersonaId -> "当前伴读"
        else -> "伴读角色 #${note.personaId}"
    }
    return buildString {
        append("#").append(note.id).append(" ").append(note.title.ifBlank { "未命名" })
            .append("｜作者：").append(author).append("\n")
        append(note.contentMarkdown.substring(safeStart, end))
        if (end < note.contentMarkdown.length) {
            append("\n\n内容未完：请继续调用 list_notes(note_id=").append(note.id)
                .append(", start_char=").append(end).append(", max_chars=").append(maxChars).append(")。")
        }
    }
}

internal sealed interface NoteWriteTarget {
    data object Create : NoteWriteTarget
    data class Update(val note: NoteEntity) : NoteWriteTarget
    data class Reject(val reason: String) : NoteWriteTarget
}

internal fun resolveNoteWriteTarget(
    requested: NoteEntity?,
    latest: NoteEntity?,
    bookId: Long,
    personaId: Long,
    kind: String,
    asNew: Boolean
): NoteWriteTarget {
    if (asNew) return NoteWriteTarget.Create
    val target = requested ?: latest ?: return NoteWriteTarget.Create
    if (target.bookId != bookId) return NoteWriteTarget.Reject("第 ${target.id} 条内容不属于当前书籍。")
    if (target.personaId == null) return NoteWriteTarget.Reject("第 ${target.id} 条是用户手写的笔记，只能读取不能改写。如需补充，请新建一条。")
    if (target.personaId != personaId) return NoteWriteTarget.Reject("第 ${target.id} 条由其他伴读角色创建，当前角色不能改写。")
    if (target.kind != kind) return NoteWriteTarget.Reject("第 ${target.id} 条内容类型不匹配，不能覆盖。")
    return NoteWriteTarget.Update(target)
}

data class PromptAnnotation(val quote: String, val note: String)

internal fun formatPromptAnnotations(annotations: List<AnnotationEntity>): List<PromptAnnotation> = annotations
    .asSequence()
    .filter { it.personaId == null }
    .sortedWith(compareByDescending<AnnotationEntity> { it.note.isNotBlank() }.thenByDescending { it.createdAt })
    .take(3)
    .map { PromptAnnotation(it.selectedText.singleLine().clip(60), it.note.singleLine().clip(60)) }
    .toList()

private fun JsonObject.text(key: String): String =
    runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim().orEmpty()

private fun JsonObject.int(key: String): Int? =
    runCatching { this[key]?.jsonPrimitive?.intOrNull }.getOrNull()

private fun Int?.orZero(): Int = this ?: 0

private fun String.singleLine(): String = replace(Regex("\\s+"), " ").trim()
private fun String.clip(max: Int): String = if (length <= max) this else take(max).trimEnd() + "…"
private fun String.firstMeaningfulLine(): String? = lineSequence()
    .map { it.replace(Regex("^[#>*_`~\\-\\s]+"), "").trim() }
    .firstOrNull(String::isNotBlank)

private fun capLines(lines: List<String>, maxLines: Int, maxChars: Int, continuation: String): String {
    val out = StringBuilder()
    var bodyLines = 0
    var truncated = false
    for (line in lines) {
        if (bodyLines >= maxLines || out.length + line.length + 1 > maxChars - continuation.length - 2) {
            truncated = true
            break
        }
        if (out.isNotEmpty()) out.append('\n')
        out.append(line)
        bodyLines++
    }
    if (truncated) {
        if (out.isNotEmpty()) out.append('\n')
        out.append(continuation.take((maxChars - out.length - 1).coerceAtLeast(0)))
    }
    return out.toString().take(maxChars)
}

private fun formatChineseCount(value: Long): String = when {
    value >= 10_000 -> "%.1f 万".format(Locale.US, value / 10_000.0).replace(".0 ", " ")
    value >= 1_000 -> "%.1f 千".format(Locale.US, value / 1_000.0).replace(".0 ", " ")
    else -> value.toString()
}

private fun percent(part: Long, total: Long): String {
    if (total <= 0) return "0%"
    return "%.1f%%".format(Locale.US, (part.coerceIn(0, total) * 100.0) / total).replace(".0%", "%")
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0 分钟"
    val minutes = durationMs / 60_000
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "$hours 小时 ${rest} 分" else "$minutes 分钟"
}

private fun formatRelativeTime(timeMillis: Long, nowMillis: Long): String {
    if (timeMillis <= 0L) return "未知时间"
    val nowDate = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val thenDate = Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = ChronoUnit.DAYS.between(thenDate, nowDate)
    return when {
        days <= 0 -> "今天"
        days == 1L -> "昨天"
        days < 30 -> "$days 天前"
        days < 365 -> "${days / 30} 个月前"
        else -> "${days / 365} 年前"
    }
}

private const val MAX_OUTLINE_LINES = 200
private const val MAX_OUTLINE_CHARS = 6_000
private const val MAX_ANNOTATION_CHARS = 2_000
private const val MAX_NOTE_INDEX_CHARS = 8_000
private const val DEFAULT_NOTE_READ_CHARS = 6_000
private const val MAX_NOTE_READ_CHARS = 8_000
