package com.mozhi.reader.ai.media

import android.content.Context
import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.search.WebSearchService
import com.mozhi.reader.ai.search.WebSearchSettingsStore
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class OnlineBookCover(
    val title: String,
    val author: String,
    val imageUrl: String,
    val source: String
)

data class OnlineBookCoverSearchResult(
    val covers: List<OnlineBookCover>,
    val queries: List<String>,
    val agentEnhanced: Boolean
)

data class BookCoverGenerationProgress(
    val fraction: Float,
    val message: String
)

@Singleton
class BookCoverService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val clientFactory: AiClientFactory,
    private val imagePromptComposer: ImagePromptComposer,
    private val libraryRepository: LibraryRepository,
    private val agentLoop: AgentLoop,
    private val webSearchService: WebSearchService,
    private val webSearchSettingsStore: WebSearchSettingsStore
) {
    private data class CachedCoverSearch(val savedAt: Long, val result: OnlineBookCoverSearchResult)
    private val searchCache = ConcurrentHashMap<String, CachedCoverSearch>()

    suspend fun search(bookId: Long): OnlineBookCoverSearchResult = withContext(Dispatchers.IO) {
        val book = libraryRepository.getBook(bookId) ?: error("书籍不存在")
        val cleanTitle = book.title.trim()
        val cleanAuthor = book.author.trim()
        require(cleanTitle.isNotBlank()) { "请先填写书名" }
        val webSettings = webSearchSettingsStore.current()
        val configuredSearch = if (webSettings.enabled) {
            "${webSettings.provider.name}::${webSettings.searchEndpoint()}"
        } else {
            "fallback"
        }
        val cacheKey = "${cleanTitle.lowercase()}::${cleanAuthor.lowercase()}::$configuredSearch"
        searchCache[cacheKey]?.takeIf { System.currentTimeMillis() - it.savedAt < SEARCH_CACHE_MS }
            ?.result
            ?.let { return@withContext it }
        val agentQueries = try {
            composeSearchQueriesWithAgent(bookId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        val fallbackQuery = listOf(
            "\"$cleanTitle\"",
            cleanAuthor.takeIf(String::isNotBlank)?.let { "\"$it\"" },
            "书籍封面 book cover"
        ).filterNotNull().joinToString(" ")
        val guardedAgentQueries = agentQueries.map { query ->
            if (query.contains(cleanTitle, ignoreCase = true)) query else "\"$cleanTitle\" $query"
        }
        val queries = (guardedAgentQueries.take(MAX_SEARCH_QUERIES - 1) + fallbackQuery)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase() }
            .take(MAX_SEARCH_QUERIES)
        val configuredResults = if (webSettings.enabled) {
            searchConfiguredProvider(cleanTitle, cleanAuthor, queries)
        } else {
            emptyList()
        }
        val openLibrary = if (configuredResults.isEmpty()) {
            runCatching { searchOpenLibrary(cleanTitle, cleanAuthor) }.getOrDefault(emptyList()).ifEmpty {
                if (cleanAuthor.isNotBlank()) {
                    runCatching { searchOpenLibrary(cleanTitle, "") }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
        } else {
            emptyList()
        }
        val covers = configuredResults.ifEmpty { openLibrary }.ifEmpty {
            runCatching { searchGoogleBooks(cleanTitle, cleanAuthor) }.getOrDefault(emptyList())
        }.distinctBy(OnlineBookCover::imageUrl)
        val result = OnlineBookCoverSearchResult(
            covers = covers,
            queries = queries,
            agentEnhanced = agentQueries.isNotEmpty()
        )
        searchCache[cacheKey] = CachedCoverSearch(System.currentTimeMillis(), result)
        result
    }

    private suspend fun searchConfiguredProvider(
        title: String,
        author: String,
        queries: List<String>
    ): List<OnlineBookCover> {
        val covers = mutableListOf<OnlineBookCover>()
        for (query in queries) {
            val batch = try {
                webSearchService.searchImages(query, RESULTS_PER_QUERY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            covers += batch.map { result ->
                OnlineBookCover(
                    title = result.title.ifBlank { title },
                    author = author,
                    imageUrl = result.imageUrl,
                    source = result.source
                )
            }
            if (covers.distinctBy(OnlineBookCover::imageUrl).size >= MAX_CONFIGURED_RESULTS) break
        }
        return covers.distinctBy(OnlineBookCover::imageUrl).take(MAX_CONFIGURED_RESULTS)
    }

    private suspend fun composeSearchQueriesWithAgent(bookId: Long): List<String> {
        val book = libraryRepository.getBook(bookId) ?: error("书籍不存在")
        val output = StringBuilder()
        agentLoop.runDetached(
            history = listOf(
                ChatMessage(
                    ChatRole.SYSTEM,
                    """
                    你是书籍版本与封面检索专家。根据给定书名和作者生成精确检索词。
                    最终只输出 2 到 3 行图片搜索词，每行一个，不要解释、编号、Markdown 或 JSON。
                    每行必须指向这部具体作品，必须包含作品名或可靠别名，并包含作者（已知时）以及“书籍封面”或“book cover”。
                    禁止只搜索作者、人物、影视演员或泛题材；优先查询正式出版封面、原版封面和常见译本封面。
                    """.trimIndent()
                ),
                ChatMessage(
                    ChatRole.USER,
                    "为《${book.title}》（${book.author.ifBlank { "未知作者" }}）生成封面图片搜索词。"
                )
            ),
            tools = emptyList(),
            modelRole = ModelRole.CHEAP
        ).collect { event -> if (event is AgentEvent.Text) output.append(event.text) }
        return output.toString()
            .replace("```", "")
            .lineSequence()
            .map { line -> line.replace(Regex("^\\s*(?:[-*•]|\\d+[.)、])\\s*"), "").trim(' ', '"', '\'') }
            .filter { query ->
                query.length in 6..MAX_SEARCH_QUERY_CHARS &&
                    listOf("封面", "cover").any { query.contains(it, ignoreCase = true) }
            }
            .distinctBy { it.lowercase() }
            .take(MAX_SEARCH_QUERIES)
            .toList()
    }

    private fun searchOpenLibrary(title: String, author: String): List<OnlineBookCover> {
        val url = "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .apply { author.takeIf(String::isNotBlank)?.let { addQueryParameter("author", it) } }
            .addQueryParameter("limit", "30")
            .addQueryParameter("fields", "key,title,author_name,cover_i")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val raw = httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { "Open Library 封面搜索失败（HTTP ${response.code}）" }
            body
        }
        val root = Json.parseToJsonElement(raw).jsonObject
        return root["docs"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val coverId = item["cover_i"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            OnlineBookCover(
                title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { title },
                author = item["author_name"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.joinToString(" / ")
                    .orEmpty(),
                imageUrl = "https://covers.openlibrary.org/b/id/$coverId-L.jpg?default=false",
                source = "Open Library"
            )
        }
    }

    private fun searchGoogleBooks(title: String, author: String): List<OnlineBookCover> {
        val terms = buildList {
            title.trim().takeIf(String::isNotEmpty)?.let { add("intitle:$it") }
            author.trim().takeIf(String::isNotEmpty)?.let { add("inauthor:$it") }
        }.joinToString(" ").ifBlank { title.trim() }
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", terms)
            .addQueryParameter("maxResults", "20")
            .addQueryParameter("printType", "books")
            .build()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        val raw = httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { "网络封面搜索失败（HTTP ${response.code}）" }
            body
        }
        val root = Json.parseToJsonElement(raw).jsonObject
        return root["items"]?.jsonArray.orEmpty().mapNotNull { item ->
            val info = item.jsonObject["volumeInfo"]?.jsonObject ?: return@mapNotNull null
            val links = info["imageLinks"]?.jsonObject ?: return@mapNotNull null
            val image = listOf("extraLarge", "large", "medium", "small", "thumbnail", "smallThumbnail")
                .firstNotNullOfOrNull { key -> links[key]?.jsonPrimitive?.contentOrNull }
                ?.replaceFirst("http://", "https://")
                ?: return@mapNotNull null
            OnlineBookCover(
                title = info["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { title },
                author = info["authors"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.joinToString(" / ")
                    .orEmpty(),
                imageUrl = image,
                source = "Google Books"
            )
        }
    }

    suspend fun download(candidate: OnlineBookCover): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(candidate.imageUrl).header("User-Agent", USER_AGENT).get().build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "封面下载失败（HTTP ${response.code}）" }
            val bytes = response.body.bytes()
            require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "封面图片为空或超过 25 MB" }
            draftFile("network", response.header("Content-Type")).apply { writeBytes(bytes) }
        }
    }

    suspend fun generate(
        bookId: Long,
        customPrompt: String,
        onProgress: (BookCoverGenerationProgress) -> Unit = {}
    ): File {
        onProgress(BookCoverGenerationProgress(0.06f, "正在读取书籍信息"))
        val book = libraryRepository.getBook(bookId) ?: error("书籍不存在")
        val prompt = customPrompt.trim().takeIf(String::isNotEmpty) ?: run {
            onProgress(BookCoverGenerationProgress(0.16f, "Agent 正在分析内容与视觉意象"))
            runCatching { composePromptWithAgent(bookId) }.getOrNull()
                ?: fallbackPrompt(bookId)
        }
        onProgress(BookCoverGenerationProgress(0.34f, "正在整理封面构图提示词"))
        val generatedPrompt = imagePromptComposer.compose(
            """
            为小说《${book.title}》创作竖版 2:3 书籍封面主视觉。
            ${prompt.trim()}
            构图需适合缩略图，主体清晰，留出安全边距；不要生成文字、水印、边框、出版社标识。
            """.trimIndent()
        ).take(MAX_PROMPT_CHARS)
        val resolved = clientFactory.imageGeneration()
        onProgress(BookCoverGenerationProgress(0.48f, "正在调用 ${resolved.label} 生成图片"))
        val generated = resolved.client.generateImages(generatedPrompt).firstOrNull()
            ?: error("生图 API 已响应，但没有返回可用图片")
        onProgress(
            BookCoverGenerationProgress(
                0.76f,
                if (generated.url.isNullOrBlank()) "正在接收生成图片" else "正在下载生成结果"
            )
        )
        val bytes = resolved.client.materializeImage(generated)
        require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) { "生成封面为空或超过 25 MB" }
        onProgress(BookCoverGenerationProgress(0.9f, "正在校验图片并准备裁剪"))
        return withContext(Dispatchers.IO) {
            draftFile("ai", generated.mediaType, bytes).apply { writeBytes(bytes) }
                .also { onProgress(BookCoverGenerationProgress(1f, "封面生成完成")) }
        }
    }

    private suspend fun composePromptWithAgent(bookId: Long): String {
        val book = libraryRepository.getBook(bookId) ?: error("书籍不存在")
        val firstChapter = libraryRepository.getChapters(bookId).firstOrNull()
        val excerpt = firstChapter?.let { libraryRepository.readChapterText(bookId, it) }
            .orEmpty().replace(Regex("\\s+"), " ").take(1_200)
        val output = StringBuilder()
        agentLoop.runDetached(
            history = listOf(
                ChatMessage(
                    ChatRole.SYSTEM,
                    """
                    你是书籍封面艺术总监。根据书籍信息与开篇摘要提炼题材、时代、地点和视觉意象；
                    最终只输出一段中文生图提示词，不要解释，不要 Markdown，
                    包含主体、环境、构图、色彩、光线、媒介风格与应避开的剧透。封面不需要任何文字。
                    """.trimIndent()
                ),
                ChatMessage(ChatRole.USER, "请为《${book.title}》（${book.author.ifBlank { "未知作者" }}）设计封面。开篇摘要：${excerpt.ifBlank { "暂无" }}")
            ),
            tools = emptyList(),
            modelRole = ModelRole.CHEAP
        ).collect { event -> if (event is AgentEvent.Text) output.append(event.text) }
        return output.toString().trim().removePrefix("```").removeSuffix("```").trim()
            .take(MAX_PROMPT_CHARS)
            .ifBlank { error("AI 未生成封面提示词") }
    }

    private suspend fun fallbackPrompt(bookId: Long): String {
        val book = libraryRepository.getBook(bookId) ?: error("书籍不存在")
        val firstChapter = libraryRepository.getChapters(bookId).firstOrNull()
        val excerpt = firstChapter?.let { libraryRepository.readChapterText(bookId, it) }
            .orEmpty().replace(Regex("\\s+"), " ").take(1200)
        return "题材与氛围来自以下开篇内容：$excerpt。视觉风格精致、克制、有文学感，突出一个最具辨识度的核心意象。"
            .take(MAX_PROMPT_CHARS)
    }

    private fun draftFile(prefix: String, mediaType: String?, bytes: ByteArray? = null): File {
        val extension = when {
            mediaType?.contains("png", true) == true -> "png"
            mediaType?.contains("webp", true) == true -> "webp"
            bytes?.let(::isPng) == true -> "png"
            bytes?.let(::isWebp) == true -> "webp"
            else -> "jpg"
        }
        val directory = File(context.cacheDir, "book-cover-drafts").apply { mkdirs() }
        return File(directory, "$prefix-${System.currentTimeMillis()}-${System.nanoTime()}.$extension")
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= 4 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    private fun isWebp(bytes: ByteArray): Boolean = bytes.size >= 12 &&
        bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
        bytes.copyOfRange(8, 12).decodeToString() == "WEBP"

    private companion object {
        const val MAX_IMAGE_BYTES = 25 * 1024 * 1024
        const val MAX_PROMPT_CHARS = 8_000
        const val MAX_SEARCH_QUERY_CHARS = 240
        const val MAX_SEARCH_QUERIES = 3
        const val RESULTS_PER_QUERY = 12
        const val MAX_CONFIGURED_RESULTS = 24
        const val SEARCH_CACHE_MS = 10 * 60 * 1000L
        const val USER_AGENT = "MoRead/0.11.1 (Android book cover search)"
    }
}
