package com.mozhi.reader.ai.companion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.ai.chat.CompanionGenerationTracker
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LibraryOrganizationCoordinatorTest {
    private lateinit var db: MoReadDatabase
    private lateinit var library: LibraryRepository
    private lateinit var shelf: ShelfOrganizationRepository
    private lateinit var coordinator: LibraryOrganizationCoordinator
    private val tracker = CompanionGenerationTracker()
    private var conversation = 0L
    private var oldTag = 0L
    private var oldGroup = 0L
    private val dao get() = db.shelfOrganizationDao()
    private fun request(id: Long = 1, add: List<String> = listOf("小说"), remove: List<String> = emptyList(), group: String? = null) =
        LibraryOrganizationRequest(id, add, remove, group)

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).build()
        library = mockk()
        coEvery { library.getBook(any()) } coAnswers { db.bookDao().getBook(firstArg()) }
        shelf = ShelfOrganizationRepository(dao)
        coordinator = LibraryOrganizationCoordinator(db, db.chatDao(), library, shelf, dao, tracker)
        oldTag = shelf.createOrGetTag("待读")
        oldGroup = shelf.createOrGetGroup("书架", null)
        for (id in 1L..2L) {
            db.bookDao().insertBook(BookEntity(id = id, title = "书$id", author = "", coverPath = null,
                epubPath = "", sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 1, groupId = oldGroup))
        }
        shelf.addTagToBooks(oldTag, listOf(1, 2))
        conversation = db.chatDao().insertConversation(ConversationEntity(bookId = null, title = "整理", type = LibraryBookScopes.CONVERSATION_TYPE, createdAt = 1))
    }

    @After fun close() { db.close() }

    private suspend fun save(plan: LibraryOrganizationPlan, role: String = "tool"): Long = db.chatDao().insertMessage(
        MessageEntity(conversationId = conversation, role = role, content = LibraryOrganizationPlans.encode(plan), createdAt = 2)
    )
    private suspend fun status(id: Long) = LibraryOrganizationPlans.decode(db.chatDao().getMessage(id)!!.content)!!.status

    @Test fun previewNeverMutatesAndOnlyExplicitConfirmationAppliesAllChanges() = runBlocking {
        val plan = coordinator.preview(listOf(request(remove = listOf("待读"), group = "文学")))
        assertEquals(listOf("待读"), dao.getTagsForBook(1).map { it.name })
        assertNull(dao.findTagByName("小说")); assertNull(dao.findGroup("文学", null))
        val message = save(plan)
        assertEquals(1, coordinator.confirm(message, true))
        assertEquals(listOf("小说"), dao.getTagsForBook(1).map { it.name })
        assertEquals("文学", dao.getGroup(db.bookDao().getBook(1)!!.groupId!!)!!.name)
        assertEquals(listOf("待读"), dao.getTagsForBook(2).map { it.name })
        assertEquals("APPLIED", status(message))
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
        assertNotNull(dao.findTagByName("待读")) // Only a reference is removed, never its definition.
    }

    @Test fun cancelChangesNothingAndCannotBeAppliedLater() = runBlocking {
        val message = save(coordinator.preview(listOf(request())))
        assertEquals(0, coordinator.confirm(message, false))
        assertEquals("CANCELLED", status(message))
        assertEquals(listOf("待读"), dao.getTagsForBook(1).map { it.name })
        assertNull(dao.findTagByName("小说"))
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
    }

    @Test fun renamedSourceTagRejectsStalePlanWithoutOverwritingNewMetadata() = runBlocking {
        val message = save(coordinator.preview(listOf(request(remove = listOf("待读")))))
        dao.updateTag(dao.findTagByName("待读")!!.copy(name = "正在读"))
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
        assertEquals("PENDING", status(message))
        assertEquals(listOf("正在读"), dao.getTagsForBook(1).map { it.name })
        assertNull(dao.findTagByName("小说"))
    }

    @Test fun renamedOrReparentedGroupRejectsStalePlan() = runBlocking {
        val message = save(coordinator.preview(listOf(request(group = "文学"))))
        dao.updateGroup(dao.getGroup(oldGroup)!!.copy(name = "我的书"))
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
        assertNull(dao.findGroup("文学", null))
        dao.updateGroup(dao.getGroup(oldGroup)!!.copy(name = "书架", parentId = 99))
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
    }

    @Test fun renamedDestinationTagOrGroupCannotSilentlyCreateReplacementDefinitions() = runBlocking {
        val tag = shelf.createOrGetTag("小说")
        val group = shelf.createOrGetGroup("文学", null)
        val message = save(coordinator.preview(listOf(request(group = "文学"))))
        dao.updateTag(dao.findTagByName("小说")!!.copy(name = "虚构"))
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
        dao.updateTag(dao.findTagByName("虚构")!!.copy(name = "小说"))
        dao.updateGroup(dao.getGroup(group)!!.copy(name = "小说架"))
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
        assertEquals(tag, dao.findTagByName("小说")!!.id)
        assertNull(dao.findGroup("文学", null))
    }

    @Test fun removedBookAndInFlightGenerationBothPreventApplying() = runBlocking {
        val message = save(coordinator.preview(listOf(request())))
        tracker.begin(conversation, Job())
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
        tracker.end(conversation)
        db.bookDao().markRemoved(1, 10)
        assertTrue(runCatching { coordinator.confirm(message, true) }.isFailure)
        assertEquals("PENDING", status(message))
        assertEquals(0, coordinator.confirm(message, false)) // A stale plan may still be dismissed.
    }

    @Test fun writeFailureRollsBackTagsGroupsAndPlanTogether() = runBlocking {
        val message = save(coordinator.preview(listOf(request(1, group = "文学"), request(2, group = "文学"))))
        val chats = mockk<ChatDao>()
        coEvery { chats.getMessage(message) } coAnswers { db.chatDao().getMessage(message) }
        coEvery { chats.getConversation(conversation) } coAnswers { db.chatDao().getConversation(conversation) }
        coEvery { chats.updateMessageContent(any(), any(), any()) } throws IllegalStateException("disk full")
        val failing = LibraryOrganizationCoordinator(db, chats, library, shelf, dao, tracker)
        assertTrue(runCatching { failing.confirm(message, true) }.isFailure)
        assertNull(dao.findTagByName("小说")); assertNull(dao.findGroup("文学", null))
        assertEquals(oldGroup, db.bookDao().getBook(1)!!.groupId)
        assertEquals(oldGroup, db.bookDao().getBook(2)!!.groupId)
        assertEquals("PENDING", status(message))
    }

    @Test fun noOpRowsAreNotPresentedAsChanges() = runBlocking {
        assertTrue(runCatching { coordinator.preview(listOf(request(add = listOf("待读"), group = "书架"))) }.isFailure)
        val plan = coordinator.preview(listOf(request(1, add = listOf("待读")), request(2)))
        assertEquals(listOf(2L), plan.changes.map { it.bookId })
    }

    @Test fun restoredOrAssistantAuthoredInvalidPlansFailClosed() = runBlocking {
        val plan = coordinator.preview(listOf(request()))
        val assistant = save(plan, "assistant")
        assertTrue(runCatching { coordinator.confirm(assistant, true) }.isFailure)
        val valid = LibraryOrganizationPlans.encode(plan)
        assertNull(LibraryOrganizationPlans.decode(valid.replace("\"addTags\":[\"小说\"]", "\"addTags\":[\"\"]")))
        assertTrue(runCatching { LibraryOrganizationPlans.encode(plan.copy(changes = plan.changes + plan.changes)) }.isFailure)
        assertTrue(runCatching { LibraryOrganizationPlans.requests(Json.parseToJsonElement("""{"changes":[{"book_id":1,"add_tags":["小说"],"remove_tags":["小说"]}]}""").jsonObject) }.isFailure)
        assertNull(LibraryOrganizationPlans.fromMessage(db.chatDao().getMessage(assistant)!!))
    }
}
