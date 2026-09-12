package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Searches local metadata only. No chapter text, vector construction or model call. */
internal class LibraryCatalogTool(private val library: LibraryRepository, private val shelf: ShelfOrganizationRepository) : AgentTool {
    override val displayName = "查找书籍"
    override val spec = ToolSpec("find_books", "按书名、作者、标签或分组查找书库中的书。query 留空可分页浏览；先查真实 book_id 再读正文，不要猜编号。不会上传正文。", buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") { put("type", "string"); put("description", "书名、作者或标签关键词，可空") }
            putJsonObject("tag") { put("type", "string"); put("description", "按已有标签筛选，可空") }
            putJsonObject("group") { put("type", "string"); put("description", "按已有分组筛选，可空") }
            putJsonObject("offset") { put("type", "integer"); put("description", "分页偏移，默认 0") }
        }
    })

    override suspend fun execute(arguments: JsonObject): String {
        fun text(name: String) = (arguments[name] as? JsonPrimitive)?.contentOrNull.orEmpty().trim().take(100)
        val query = text("query")
        val tag = text("tag")
        val group = text("group")
        val offset = ((arguments["offset"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
        val organization = shelf.snapshot.first()
        val tags = organization.tags.associateBy { it.id }
        val groups = organization.groups.associateBy { it.id }
        val refs = organization.tagRefs.groupBy { it.bookId }
        val matches = library.getBooks().filter { book ->
            val names = refs[book.id].orEmpty().mapNotNull { tags[it.tagId]?.name }
            val groupName = groups[book.groupId]?.name.orEmpty()
            book.removedAt == 0L && (query.isBlank() || (listOf(book.title, book.author, groupName) + names).any { it.contains(query, true) }) &&
                (tag.isBlank() || names.any { it.equals(tag, true) }) && (group.isBlank() || groupName.contains(group, true))
        }
        return buildJsonObject {
            put("total", matches.size)
            if (offset.toLong() + 20 < matches.size) put("next_offset", offset + 20)
            putJsonArray("books") {
                matches.drop(offset).take(20).forEach { book -> add(buildJsonObject {
                    put("book_id", book.id); put("title", book.title.take(120)); put("author", book.author.take(80))
                    put("group", groups[book.groupId]?.name.orEmpty().take(80))
                    put("tags", JsonArray(refs[book.id].orEmpty().take(12).mapNotNull { tags[it.tagId]?.name?.take(80)?.let(::JsonPrimitive) }))
                    put("read_chapter", book.maxReachedChapterIndex + 1); put("read_offset", book.maxReachedCharOffset)
                }) }
            }
        }.toString()
    }
}
