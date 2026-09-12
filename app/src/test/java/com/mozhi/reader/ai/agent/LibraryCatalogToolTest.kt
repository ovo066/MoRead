package com.mozhi.reader.ai.agent

import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import com.mozhi.reader.core.library.ShelfOrganizationSnapshot
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LibraryCatalogToolTest {
    private val library = mockk<LibraryRepository>()
    private val shelf = mockk<ShelfOrganizationRepository>()
    private val tool = LibraryCatalogTool(library, shelf)
    private fun book(id: Long) = BookEntity(id = id, title = "远行$id", author = "写书的人", coverPath = null,
        epubPath = "", sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 5)

    @Test fun emptyQueryPagesMetadataWithoutReadingBookBodies() = runTest {
        coEvery { library.getBooks() } returns (1L..26L).map(::book)
        every { shelf.snapshot } returns flowOf(ShelfOrganizationSnapshot())
        val first = Json.parseToJsonElement(tool.execute(buildJsonObject {})).jsonObject
        assertEquals(26, first["total"]!!.jsonPrimitive.int)
        assertEquals(20, first["books"]!!.jsonArray.size)
        val second = Json.parseToJsonElement(tool.execute(buildJsonObject { put("offset", first["next_offset"]!!) })).jsonObject
        assertEquals(6, second["books"]!!.jsonArray.size)
        assertNull(second["next_offset"])
        coVerify(exactly = 2) { library.getBooks() }
        confirmVerified(library)
    }

    @Test fun titleAuthorTagAndGroupFiltersExcludeRemovedBooks() = runTest {
        coEvery { library.getBooks() } returns listOf(book(1).copy(groupId = 10), book(2), book(3).copy(removedAt = 1))
        every { shelf.snapshot } returns flowOf(ShelfOrganizationSnapshot(
            groups = listOf(ShelfGroupEntity(10, "旅行", createdAt = 1)),
            tags = listOf(BookTagEntity(20, "随笔", "琥珀", createdAt = 1)),
            tagRefs = listOf(BookTagRefEntity(1, 20))
        ))
        suspend fun ids(vararg terms: Pair<String, String>): List<Long> {
            val result = Json.parseToJsonElement(tool.execute(buildJsonObject { terms.forEach { put(it.first, it.second) } })).jsonObject
            return result["books"]!!.jsonArray.map { it.jsonObject["book_id"]!!.jsonPrimitive.long }
        }
        assertEquals(listOf(1L), ids("query" to "随笔"))
        assertEquals(listOf(1L), ids("group" to "旅行", "tag" to "随笔"))
        assertEquals(listOf(2L), ids("query" to "远行2"))
        assertEquals(listOf(1L, 2L), ids("query" to "写书的人"))
        assertTrue(ids("query" to "不存在").isEmpty())
    }
}
