package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.companion.LibraryBookScope
import com.mozhi.reader.ai.companion.LibraryScopeGuard
import com.mozhi.reader.ai.companion.LibraryConversationSources
import com.mozhi.reader.ai.companion.LibraryOrganizationCoordinator
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import com.mozhi.reader.core.retrieval.ReadingScope
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.BookContentMutation
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Local catalog discovery, bounded per-book reads, and user-confirmed metadata proposals. */
@Singleton
class LibraryCompanionToolset @Inject constructor(
    private val library: LibraryRepository,
    private val notes: NoteRepository,
    private val annotations: AnnotationRepository,
    private val guard: LibraryScopeGuard,
    private val readerTools: ReaderToolset,
    private val shelf: ShelfOrganizationRepository,
    private val organization: LibraryOrganizationCoordinator
) {
    suspend fun forConversation(sources: LibraryConversationSources, personaId: Long?): List<AgentTool> {
        val catalog = LibraryCatalogTool(library, shelf)
        val organizer = LibraryOrganizationTool(organization)
        // Schemas come from an actual catalog entry; constructing them does not read its text.
        val sample = library.getBooks().firstOrNull { it.removedAt == 0L } ?: return listOf(catalog, organizer)
        val names = setOf("list_chapters", "list_notes", "list_annotations", "read_book_section", "search_book", "grep_book")
        val templates = readerTools.forBook(sample.id, personaId, enabledTools = names,
            readingScope = ReadingScope.upto(0, 0), memoryScope = MemoryScope(longTermEnabled = false), buildMissingIndex = false)
            .filter { it.spec.name in names }
        val resolved = mutableMapOf<Pair<Long, String>, AgentTool>()
        return listOf(catalog, organizer) + templates.map { template ->
            ScopedLibraryTool(template, emptyList(), guard::validate, resolve = sources::authorize) { scope, arguments ->
                val args = boundedLibraryArguments(template.spec.name, arguments, scope)
                val tool = resolved.getOrPut(scope.bookId to template.spec.name) {
                    when (template.spec.name) {
                        "list_notes" -> ListNotesTool(notes, scope.bookId, personaId, scope.readingScope, strictSourceScope = true)
                        "list_annotations" -> ListAnnotationsTool(library, annotations, scope.bookId, personaId, scope.readingScope, strictSourceScope = true)
                        else -> readerTools.forBook(scope.bookId, personaId, enabledTools = names, readingScope = scope.readingScope,
                            memoryScope = MemoryScope(longTermEnabled = false), buildMissingIndex = false).first { it.spec.name == template.spec.name }
                    }
                }
                // Network embedding queries must not hold the book's filesystem mutation lock.
                if (template.spec.name == "search_book") tool.execute(args)
                else BookContentMutation.withBook(scope.bookId) { tool.execute(args) }
            }
        }
    }
}

internal class ScopedLibraryTool(
    template: AgentTool,
    private val scopes: List<LibraryBookScope>,
    private val validate: suspend (LibraryBookScope) -> Unit,
    private val resolve: (suspend (Long) -> LibraryBookScope)? = null,
    private val read: suspend (LibraryBookScope, JsonObject) -> String
) : AgentTool {
    override val displayName = template.displayName
    override val spec = template.spec.copy(
        description = "book_id 必填，先 find_books 查编号。每本书独立限制已读范围；一轮最多查阅 4 本。正文片段最多 6000 字、5 章，不自动新建向量索引。" + template.spec.description,
        parameters = JsonObject(template.spec.parameters.toMutableMap().apply {
            val properties = (this["properties"] as? JsonObject).orEmpty().toMutableMap()
            properties["book_id"] = buildJsonObject {
                put("type", "integer")
                put("description", "find_books 返回的书籍编号")
                if (resolve == null) put("enum", JsonArray(scopes.map { JsonPrimitive(it.bookId) }))
            }
            this["properties"] = JsonObject(properties)
            this["required"] = JsonArray(((this["required"] as? JsonArray).orEmpty() + JsonPrimitive("book_id")).distinct())
        })
    )

    override suspend fun execute(arguments: JsonObject): String {
        val bookId = (arguments["book_id"] as? JsonPrimitive)?.longOrNull
        val scope = bookId?.takeIf { it > 0 }?.let { resolve?.invoke(it) ?: scopes.firstOrNull { scope -> scope.bookId == it } }
            ?: return if (resolve == null) "工具执行失败：book_id 不在指定范围内" else "工具执行失败：请先用 find_books 查找书籍编号"
        validate(scope)
        val result = read(scope, JsonObject(arguments - "book_id"))
        validate(scope)
        val source = "书籍#${scope.bookId}《${scope.title}》｜本轮范围：${scope.label}"
        // Keep the established error prefix visible to AgentLoop's status classification.
        return if (result.isToolSuccess()) "$source\n$result" else "$result\n$source"
    }
}

internal fun boundedLibraryArguments(name: String, arguments: JsonObject, scope: LibraryBookScope): JsonObject {
    val result = arguments.toMutableMap()
    if (name == "read_book_section" || name == "list_notes") {
        val max = (arguments["max_chars"] as? JsonPrimitive)?.intOrNull ?: 6_000
        result["max_chars"] = JsonPrimitive(max.coerceIn(1_000, 6_000))
    }
    if (name == "list_annotations" || name == "list_chapters") {
        result.putIfAbsent("from_chapter", JsonPrimitive(if (name == "list_chapters") (scope.maxChapterIndex - 19).coerceAtLeast(1) else scope.maxChapterIndex + 1))
        result.putIfAbsent("to_chapter", JsonPrimitive(scope.maxChapterIndex + 1))
    }
    if (name == "read_book_section") {
        val from = (arguments["from_chapter"] as? JsonPrimitive)?.intOrNull ?: error("请提供 from_chapter")
        val to = (arguments["to_chapter"] as? JsonPrimitive)?.intOrNull ?: from
        require(from >= 1 && to >= from && to.toLong() - from <= 4L) { "单次最多读取 5 章，请缩小范围" }
    }
    return JsonObject(result)
}
