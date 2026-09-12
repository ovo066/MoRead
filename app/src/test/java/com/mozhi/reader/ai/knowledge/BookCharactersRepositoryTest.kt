package com.mozhi.reader.ai.knowledge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.library.LibraryRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class BookCharactersRepositoryTest {
    private val texts = listOf("林舟在灯塔等候。", "小满带来一封信。这是完全没有读到的最后一章。")
    private val chapters = texts.mapIndexed { index, text -> ChapterEntity(bookId = 1, chapterIndex = index, title = "第${index + 1}章", href = "", charCount = text.length) }
    private val book = BookEntity(id = 1, title = "灯塔来信", author = "示例", coverPath = null, epubPath = "", sourceType = BookSourceType.EPUB,
        importedAt = 1, totalChapters = 2, maxReachedChapterIndex = 0, maxReachedCharOffset = 0)
    private var revision = "a".repeat(64)
    private val library = mockk<LibraryRepository>()
    private val factory = mockk<AiClientFactory>()
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private lateinit var database: MoReadDatabase
    private lateinit var repository: BookCharactersRepository
    private val client = CharacterClient()

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).allowMainThreadQueries().build()
        database.bookDao().insertBook(book)
        coEvery { library.getBook(1) } returns book
        coEvery { library.getChapters(1) } returns chapters
        chapters.forEachIndexed { index, chapter ->
            coEvery { library.getChapter(1, index) } returns chapter
            coEvery { library.readChapterTextStrict(1, chapter) } returns texts[index]
        }
        coEvery { library.bookTextRevision(1) } answers { revision }
        every { library.observeBook(1) } returns MutableStateFlow(book)
        val provider = AiProviderEntity(id = 1, name = "测试模型", baseUrl = "https://example.test", apiKeyAlias = "alias", type = AiProviderType.CHAT, createdAt = 1)
        coEvery { factory.forRole(ModelRole.CHEAP) } returns ResolvedChatClient(client, ChatOptions.Default, provider, "test-extractor")
        val loop = AgentLoop(chatDao, dagger.Lazy { error("Use resolved model") }, dagger.Lazy { error("No attachments") }, dagger.Lazy { error("No summaries") })
        repository = BookCharactersRepository(library, database, factory, ChapterKnowledgeAgent(loop, KnowledgeRequestLimiter()))
    }

    @After fun close() { database.close() }

    @Test fun explicitWholeBookPreviewIsLocalAndGenerationIncludesTheUnreadLastChapter() = runBlocking {
        assertNull(repository.observe(1).first().saved)
        val plan = repository.preview(1)
        assertEquals(2, plan.chapterCount)
        assertEquals(texts.sumOf { it.length.toLong() }, plan.sourceCharacters)
        assertEquals(0, client.requests)
        val entry = repository.generate(plan)
        val saved = repository.observe(1).first().saved!!
        assertEquals(2, client.requests)
        assertEquals(2, saved.guide.scannedChapters)
        assertEquals(listOf("林舟", "小满"), saved.guide.characters.map { it.name })
        val unread = saved.guide.characters.last().evidence.single()
        assertEquals(1 to 0, repository.locate(entry, unread))
        assertTrue(client.received.any { "完全没有读到的最后一章" in it })
        assertTrue(database.bookCharacterDao().getParts(1).isEmpty())
        coVerify(exactly = 0) { chatDao.insertMessage(any()) }
        coVerify(exactly = 0) { chatDao.insertConversation(any()) }
    }

    @Test fun cancelledUpdateRetainsPublishedGuideAndResumesVerifiedPartsWithoutAnotherRequest() = runBlocking {
        val old = repository.generate(repository.preview(1))
        val paused = pauseAfterFirstPart()
        assertEquals(old, database.bookCharacterDao().getGuide(1))
        assertEquals(1, repository.observe(1).first().checkpointParts)
        paused.cancelAndJoin()
        client.onStream = {}
        val before = client.requests
        val resume = repository.preview(1)
        assertTrue(resume.resuming)
        assertEquals(1, resume.completedParts)
        val updated = repository.generate(resume)
        assertEquals(before + 1, client.requests)
        assertNotEquals(old.generationId, updated.generationId)
        assertFalse(repository.preview(1).resuming)
        assertNull(database.bookCharacterDao().getCheckpoint(1))
    }

    @Test fun sourceChangeInvalidatesCheckpointsAndCannotReplacePublishedData() = runBlocking {
        val old = repository.generate(repository.preview(1))
        val before = client.requests
        client.onStream = { if (client.requests == before + 2) revision = "b".repeat(64) }
        assertTrue(runCatching { repository.generate(repository.preview(1)) }.isFailure)
        assertEquals(old, database.bookCharacterDao().getGuide(1))
        val snapshot = repository.observe(1).first()
        assertTrue(snapshot.outdated)
        assertNull(snapshot.saved)
        assertEquals(0, snapshot.checkpointParts)
        assertFalse(repository.preview(1).resuming)
        client.onStream = {}
        repository.generate(repository.preview(1))
        assertEquals(before + 4, client.requests)
    }

    @Test fun damagedCheckpointIsReextractedAndEmptyCharacterResultsAreValid() = runBlocking {
        val paused = pauseAfterFirstPart()
        paused.cancelAndJoin()
        val row = database.bookCharacterDao().getParts(1).single()
        database.bookCharacterDao().savePart(row.copy(contentJson = row.contentJson.replace("林舟在灯塔等候。", "林舟在海上航行。")))
        client.onStream = {}
        val before = client.requests
        client.empty = true
        val result = repository.generate(repository.preview(1))
        assertEquals(before + 2, client.requests)
        assertTrue(BookCharactersCodec.visible(result, revision)!!.guide.characters.isEmpty())
    }

    @Test fun permanentDeletionCascadesGuidesAndPendingParts() = runBlocking {
        repository.generate(repository.preview(1))
        val paused = pauseAfterFirstPart()
        paused.cancelAndJoin()
        database.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 1")
        assertNull(database.bookCharacterDao().getGuide(1))
        assertTrue(database.bookCharacterDao().getParts(1).isEmpty())
    }

    private suspend fun CoroutineScope.pauseAfterFirstPart(): Job {
        val entered = CompletableDeferred<Unit>()
        val before = client.requests
        client.onStream = { if (client.requests == before + 2) { entered.complete(Unit); awaitCancellation() } }
        val plan = repository.preview(1)
        val job = launch { repository.generate(plan) }
        withTimeout(10_000) { entered.await() }
        assertEquals(1, database.bookCharacterDao().getParts(1).size)
        return job
    }

    private inner class CharacterClient : ChatApiClient {
        var requests = 0
        var empty = false
        var onStream: suspend () -> Unit = {}
        val received = mutableListOf<String>()
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>, options: ChatOptions): Flow<ChatDelta> = flow {
            requests++
            val source = messages.last { it.role == ChatRole.USER }.content
            received += source
            onStream()
            val name = if ("小满带来一封信。" in source) "小满" else "林舟"
            val quote = if (name == "小满") "小满带来一封信。" else "林舟在灯塔等候。"
            val json = if (empty) """{"characters":[]}""" else """{"characters":[{"name":"$name","facts":[{"text":"$quote","quote":"$quote"}]}]}"""
            emit(ChatDelta.ToolCalls(listOf(ToolCall("submit-$requests", tools.single().name, json))))
        }
        override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String = error("Use detached agent")
        override suspend fun embed(texts: List<String>): List<FloatArray> = error("No embeddings")
    }
}
