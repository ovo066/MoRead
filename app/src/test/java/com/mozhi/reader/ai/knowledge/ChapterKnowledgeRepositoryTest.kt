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
class ChapterKnowledgeRepositoryTest {
    private val readText = "林舟在灯塔等候。小满带来一封信。"
    private val sourceText = readText + "凶手身份属于未读部分。"
    private val raw = """{"outline":"林舟在灯塔等候，小满随后送来了一封信。","summary":[{"text":"小满送来一封信。","quote":"小满带来一封信。"}],"characters":[{"name":"林舟","facts":[{"text":"在灯塔等候。","quote":"林舟在灯塔等候。"}]}]}"""
    private var book = BookEntity(id = 1, title = "灯塔来信", author = "示例", coverPath = null, epubPath = "", sourceType = BookSourceType.EPUB,
        importedAt = 1, totalChapters = 1, maxReachedChapterIndex = 0, maxReachedCharOffset = readText.length)
    private var revision = "a".repeat(64)
    private val chapter = ChapterEntity(id = 1, bookId = 1, chapterIndex = 0, title = "第一章 来信", href = "", charCount = sourceText.length)
    private val library = mockk<LibraryRepository>()
    private val factory = mockk<AiClientFactory>()
    private val chatDao = mockk<ChatDao>(relaxed = true)
    private lateinit var database: MoReadDatabase
    private lateinit var repository: ChapterKnowledgeRepository
    private val client = ScriptedClient()

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).allowMainThreadQueries().build()
        database.bookDao().insertBook(book)
        coEvery { library.getBook(1) } answers { book }
        coEvery { library.getChapter(1, 0) } returns chapter
        coEvery { library.readChapterTextStrict(1, chapter) } returns sourceText
        coEvery { library.bookTextRevision(1) } answers { revision }
        every { library.observeBook(1) } returns MutableStateFlow(book)
        val provider = AiProviderEntity(id = 1, name = "测试模型", baseUrl = "https://example.test", apiKeyAlias = "alias", type = AiProviderType.CHAT, createdAt = 1)
        coEvery { factory.forRole(ModelRole.CHEAP) } returns ResolvedChatClient(client, ChatOptions.Default, provider, "test-extractor")
        val loop = AgentLoop(chatDao, dagger.Lazy { error("Use resolved model") }, dagger.Lazy { error("No attachments") }, dagger.Lazy { error("No summaries") })
        repository = ChapterKnowledgeRepository(library, database, factory, ChapterKnowledgeAgent(loop, KnowledgeRequestLimiter()))
    }

    @After fun close() { database.close() }

    @Test fun previewAndViewingAreLocalAndAgentSubmitsOnlyVerifiedReadContent() = runBlocking {
        assertTrue(repository.observe(1).first().chapters.isEmpty())
        val plan = repository.preview(1, 0)
        assertTrue(plan.source.partial)
        assertEquals(readText, plan.source.text)
        assertEquals(0, client.requests)
        val entry = repository.generate(plan)
        assertEquals(readText.length, entry.sourceEnd)
        assertEquals(1, client.requests) // No pointless second model call after a successful submit.
        assertFalse(client.received.flatten().any { "凶手身份" in it.content })
        assertEquals(entry, database.chapterKnowledgeDao().get(1, 0))
        val fact = ChapterKnowledgeCodec.visible(entry, com.mozhi.reader.core.retrieval.ReadingScope.uptoProgress(book), revision)!!.content.summary.single()
        assertEquals(0 to readText.indexOf(fact.quote), repository.locate(entry, fact))
        coVerify(exactly = 0) { chatDao.insertMessage(any()) }
        coVerify(exactly = 0) { chatDao.insertConversation(any()) }
    }

    @Test fun agentCanCorrectAnUnverifiableQuoteWithinTheTwoRoundBudget() = runBlocking {
        client.responses += raw.replace("小满带来一封信。\"", "不存在的原文。\"")
        client.responses += raw
        val entry = repository.generate(repository.preview(1, 0))
        assertEquals(2, client.requests)
        assertTrue(client.received.last().any { it.role == ChatRole.TOOL && it.content.startsWith("工具执行失败") })
        assertNotNull(database.chapterKnowledgeDao().get(entry.bookId, entry.chapterIndex))
    }

    @Test fun unreadOrRewoundSourcesFailBeforeCallingTheModel() = runBlocking {
        val plan = repository.preview(1, 0)
        book = book.copy(maxReachedCharOffset = 0)
        assertTrue(runCatching { repository.generate(plan) }.isFailure)
        assertEquals(0, client.requests)
        assertNull(database.chapterKnowledgeDao().get(1, 0))
        assertTrue(runCatching { repository.preview(1, 0) }.isFailure)
    }

    @Test fun sourceChangesDuringGenerationDoNotReplaceAnExistingOutline() = runBlocking {
        val old = repository.generate(repository.preview(1, 0))
        client.onStream = { revision = "b".repeat(64) }
        val plan = repository.preview(1, 0)
        assertTrue(runCatching { repository.generate(plan) }.isFailure)
        assertEquals(old, database.chapterKnowledgeDao().get(1, 0))
        assertTrue(repository.observe(1).first().chapters.isEmpty())
    }

    @Test fun cancellationKeepsSavedResultsAndReleasesGenerationOwnership() = runBlocking {
        val old = repository.generate(repository.preview(1, 0))
        val entered = CompletableDeferred<Unit>()
        client.onStream = { entered.complete(Unit); awaitCancellation() }
        val plan = repository.preview(1, 0)
        val job = launch { repository.generate(plan) }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(old, database.chapterKnowledgeDao().get(1, 0))
        client.onStream = {}
        assertNotNull(repository.generate(plan))
    }

    @Test fun textFallbackStillRequiresExactEvidenceAndPermanentBookDeletionCascades() = runBlocking {
        client.textOnly = true
        val entry = repository.generate(repository.preview(1, 0))
        val fact = ChapterKnowledgeCodec.visible(entry, com.mozhi.reader.core.retrieval.ReadingScope.uptoProgress(book), revision)!!.content.summary.single()
        assertTrue(runCatching { repository.locate(entry, fact.copy(quote = "不存在的引文")) }.isFailure)
        database.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id = 1")
        assertNull(database.chapterKnowledgeDao().get(1, 0))
    }

    @Test fun longChapterGetsOneConnectedSynthesisAfterItsOrderedParts() = runBlocking {
        val longText = ("段".repeat(9700) + readText + "\n").repeat(2)
        val longChapter = chapter.copy(charCount = longText.length)
        book = book.copy(maxReachedCharOffset = longText.length)
        coEvery { library.getChapter(1, 0) } returns longChapter
        coEvery { library.readChapterTextStrict(1, longChapter) } returns longText
        val synthesis = "林舟在灯塔等来了小满和她带来的信。全章围绕这次送信展开，信件把二人的行动联系在一起。"
        client.responses += listOf(raw, raw, """{"outline":"$synthesis"}""")
        val plan = repository.preview(1, 0)
        assertEquals(3, plan.requestCount)
        assertEquals(6, plan.maximumRequests)
        val entry = repository.generate(plan)
        val content = AiJson.decodeFromString<ChapterKnowledge>(entry.contentJson)
        assertEquals(synthesis, content.outline)
        assertEquals(3, client.requests)
        assertTrue(client.received.last().first().content.contains("连贯的整章梗概"))
        assertTrue(client.received.last().last().content.indexOf("第1段") < client.received.last().last().content.indexOf("第2段"))
    }

    @Test fun repositoryDoesNotSerializeDifferentChaptersBehindOneBookMutex() = runBlocking {
        val next = chapter.copy(id = 2, chapterIndex = 1)
        book = book.copy(totalChapters = 2, maxReachedChapterIndex = 1, maxReachedCharOffset = readText.length)
        coEvery { library.getChapter(1, 1) } returns next
        coEvery { library.readChapterTextStrict(1, next) } returns sourceText
        val both = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var entered = 0
        client.onStream = { entered++; if (entered == 2) both.complete(Unit); finish.await() }
        val first = repository.preview(1, 0)
        val second = repository.preview(1, 1)
        val firstJob = launch { repository.generate(first) }
        val secondJob = launch { repository.generate(second) }
        withTimeout(10_000) { both.await() }
        firstJob.cancelAndJoin()
        finish.complete(Unit)
        secondJob.join()
        assertNull(database.chapterKnowledgeDao().get(1, 0))
        assertNotNull(database.chapterKnowledgeDao().get(1, 1))
    }

    private inner class ScriptedClient : ChatApiClient {
        var requests = 0
        val received = mutableListOf<List<ChatMessage>>()
        val responses = mutableListOf<String>()
        var onStream: suspend () -> Unit = {}
        var textOnly = false
        override fun chatStream(messages: List<ChatMessage>, tools: List<ToolSpec>, options: ChatOptions): Flow<ChatDelta> = flow {
            requests++
            received += messages.toList()
            onStream()
            val response = if (responses.isNotEmpty()) responses.removeAt(0) else raw
            if (textOnly) emit(ChatDelta.Text(response))
            else emit(ChatDelta.ToolCalls(listOf(ToolCall("submit-$requests", tools.single().name, response))))
        }
        override suspend fun chat(messages: List<ChatMessage>, options: ChatOptions): String = error("Use the detached agent")
        override suspend fun embed(texts: List<String>): List<FloatArray> = error("No embeddings")
    }
}
