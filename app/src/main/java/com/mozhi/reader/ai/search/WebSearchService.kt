package com.mozhi.reader.ai.search

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.core.security.ApiKeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

data class WebScrapeResult(
    val title: String,
    val url: String,
    val content: String
)

data class WebImageSearchResult(
    val title: String,
    val imageUrl: String,
    val pageUrl: String,
    val source: String,
    val width: Int? = null,
    val height: Int? = null
)

@Singleton
class WebSearchService @Inject constructor(
    private val settingsStore: WebSearchSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val httpClient: OkHttpClient
) {
    suspend fun search(query: String, limit: Int): List<WebSearchResult> {
        val settings = settingsStore.current()
        check(settings.enabled) { "网络搜索尚未启用，请先在设置中开启" }
        val provider = settings.provider
        val key = apiKeyStore.get(WebSearchCredentialAliases.forProvider(provider))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("${provider.label} API Key 尚未配置")
        val endpoint = settings.searchEndpoint(provider)
        check(endpoint.toHttpUrlOrNull() != null) { "${provider.label} 接口地址无效" }
        val safeLimit = limit.coerceIn(1, MAX_RESULTS)
        val request = buildRequest(
            provider = provider,
            endpoint = endpoint,
            key = key,
            payload = buildWebSearchPayload(provider, query.trim(), safeLimit, settings)
        )
        val raw = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error("${provider.label} 搜索失败（HTTP ${response.code}）：${body.take(ERROR_PREVIEW_CHARS)}")
                }
                body
            }
        }
        return try {
            parseWebSearchResponse(provider, raw).take(safeLimit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("${provider.label} 返回内容无法解析：${error.message ?: "未知格式"}", error)
        }
    }

    suspend fun searchImages(query: String, limit: Int): List<WebImageSearchResult> {
        val settings = settingsStore.current()
        check(settings.enabled) { "网络搜索尚未启用，请先在设置中开启" }
        val provider = settings.provider
        val key = apiKeyStore.get(WebSearchCredentialAliases.forProvider(provider))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("${provider.label} API Key 尚未配置")
        val endpoint = settings.searchEndpoint(provider)
        check(endpoint.toHttpUrlOrNull() != null) { "${provider.label} 接口地址无效" }
        val safeLimit = limit.coerceIn(1, MAX_IMAGE_RESULTS)
        val request = buildRequest(
            provider = provider,
            endpoint = endpoint,
            key = key,
            payload = buildImageSearchPayload(provider, query.trim(), safeLimit, settings)
        )
        val raw = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error("${provider.label} 图片搜索失败（HTTP ${response.code}）：${body.take(ERROR_PREVIEW_CHARS)}")
                }
                body
            }
        }
        return try {
            parseImageSearchResponse(provider, raw).take(safeLimit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("${provider.label} 图片结果无法解析：${error.message ?: "未知格式"}", error)
        }
    }

    suspend fun scrape(url: String): WebScrapeResult {
        val settings = settingsStore.current()
        check(settings.enabled) { "网络搜索尚未启用，请先在设置中开启" }
        val provider = settings.provider
        val key = apiKeyStore.get(WebSearchCredentialAliases.forProvider(provider))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: error("${provider.label} API Key 尚未配置")
        val cleanUrl = url.trim()
        check(cleanUrl.toHttpUrlOrNull() != null) { "网页地址无效" }
        val endpoint = settings.scrapeEndpoint(provider)
        check(endpoint.toHttpUrlOrNull() != null) { "${provider.label} 抓取接口地址无效" }
        val request = buildRequest(
            provider = provider,
            endpoint = endpoint,
            key = key,
            payload = buildWebScrapePayload(provider, cleanUrl, settings)
        )
        val raw = withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error("${provider.label} 抓取失败（HTTP ${response.code}）：${body.take(ERROR_PREVIEW_CHARS)}")
                }
                body
            }
        }
        return try {
            parseWebScrapeResponse(provider, raw, cleanUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("${provider.label} 抓取内容无法解析：${error.message ?: "未知格式"}", error)
        }
    }

    private fun buildRequest(
        provider: WebSearchProvider,
        endpoint: String,
        key: String,
        payload: JsonObject
    ): Request {
        return Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .apply {
                when (provider) {
                    WebSearchProvider.FIRECRAWL,
                    WebSearchProvider.TAVILY -> header("Authorization", "Bearer $key")
                    WebSearchProvider.EXA -> header("x-api-key", key)
                }
            }
            .build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_RESULTS = 8
        const val MAX_IMAGE_RESULTS = 30
        const val ERROR_PREVIEW_CHARS = 500
    }
}

internal fun buildWebSearchPayload(
    provider: WebSearchProvider,
    query: String,
    limit: Int,
    settings: WebSearchSettings = WebSearchSettings(provider = provider)
): JsonObject = when (provider) {
    WebSearchProvider.FIRECRAWL -> buildJsonObject {
        put("query", query)
        put("limit", limit)
    }
    WebSearchProvider.EXA -> buildJsonObject {
        put("query", query)
        put("numResults", limit)
        put("type", "auto")
        putJsonObject("contents") {
            putJsonObject("text") { put("maxCharacters", 1_200) }
        }
    }
    WebSearchProvider.TAVILY -> buildJsonObject {
        put("query", query)
        put("max_results", limit)
        put("search_depth", settings.tavilySearchDepth.wireValue)
        put("include_answer", false)
        put("include_raw_content", false)
    }
}

internal fun buildImageSearchPayload(
    provider: WebSearchProvider,
    query: String,
    limit: Int,
    settings: WebSearchSettings = WebSearchSettings(provider = provider)
): JsonObject = when (provider) {
    WebSearchProvider.FIRECRAWL -> buildJsonObject {
        put("query", query)
        put("limit", limit)
        put(
            "sources",
            JsonArray(listOf(buildJsonObject { put("type", "images") }))
        )
    }
    WebSearchProvider.EXA -> buildJsonObject {
        put("query", query)
        put("numResults", limit)
        put("type", "auto")
        putJsonObject("contents") {
            put("text", false)
            putJsonObject("extras") { put("imageLinks", 5) }
        }
    }
    WebSearchProvider.TAVILY -> buildJsonObject {
        put("query", query)
        put("max_results", limit)
        put("search_depth", settings.tavilySearchDepth.wireValue)
        put("include_answer", false)
        put("include_raw_content", false)
        put("include_images", true)
        put("include_image_descriptions", true)
    }
}

internal fun buildWebScrapePayload(
    provider: WebSearchProvider,
    url: String,
    settings: WebSearchSettings = WebSearchSettings(provider = provider)
): JsonObject = when (provider) {
    WebSearchProvider.FIRECRAWL -> buildJsonObject {
        put("url", url)
        put("formats", JsonArray(listOf(JsonPrimitive("markdown"))))
        put("onlyMainContent", true)
    }
    WebSearchProvider.EXA -> buildJsonObject {
        put("urls", JsonArray(listOf(JsonPrimitive(url))))
        put("text", true)
    }
    WebSearchProvider.TAVILY -> buildJsonObject {
        put("urls", url)
        put("extract_depth", settings.tavilyExtractDepth.wireValue)
        put("format", "markdown")
        put("include_images", false)
    }
}

/** 解析器独立成纯函数，兼容 Firecrawl v1 数组与 v2 data.web 两种响应。 */
internal fun parseWebSearchResponse(
    provider: WebSearchProvider,
    raw: String
): List<WebSearchResult> {
    val root = AiJson.parseToJsonElement(raw).jsonObject
    val entries = when (provider) {
        WebSearchProvider.FIRECRAWL -> {
            when (val data = root["data"]) {
                is JsonArray -> data
                is JsonObject -> data["web"] as? JsonArray
                    ?: data["results"] as? JsonArray
                    ?: JsonArray(emptyList())
                else -> root["results"] as? JsonArray ?: JsonArray(emptyList())
            }
        }
        WebSearchProvider.EXA,
        WebSearchProvider.TAVILY -> root["results"] as? JsonArray ?: JsonArray(emptyList())
    }
    return entries.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val url = item.string("url")?.takeIf { it.toHttpUrlOrNull() != null }
            ?: return@mapNotNull null
        val title = item.string("title").orEmpty().ifBlank { url }
        val snippet = when (provider) {
            WebSearchProvider.FIRECRAWL -> item.firstString("description", "snippet", "markdown")
            WebSearchProvider.EXA -> item.firstString("text", "summary", "snippet")
            WebSearchProvider.TAVILY -> item.firstString("content", "snippet", "raw_content")
        }.orEmpty().normalizeSnippet()
        WebSearchResult(title = title.take(240), url = url, snippet = snippet)
    }.distinctBy(WebSearchResult::url)
}

internal fun parseImageSearchResponse(
    provider: WebSearchProvider,
    raw: String
): List<WebImageSearchResult> {
    val root = AiJson.parseToJsonElement(raw).jsonObject
    val results = buildList<WebImageSearchResult> {
        when (provider) {
            WebSearchProvider.FIRECRAWL -> {
                val images = (root["data"] as? JsonObject)?.get("images") as? JsonArray
                    ?: root["images"] as? JsonArray
                    ?: JsonArray(emptyList())
                images.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    addImageResult(
                        item = item,
                        provider = provider,
                        imageKeys = arrayOf("imageUrl", "url"),
                        pageUrl = item.string("url"),
                        width = item["imageWidth"]?.jsonPrimitive?.intOrNull,
                        height = item["imageHeight"]?.jsonPrimitive?.intOrNull
                    )
                }
            }
            WebSearchProvider.EXA -> {
                val entries = root["results"] as? JsonArray ?: JsonArray(emptyList())
                entries.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    val pageUrl = item.string("url") ?: item.string("id")
                    val fallbackTitle = item.string("title")
                    addImageResult(
                        item = item,
                        provider = provider,
                        imageKeys = arrayOf("image"),
                        pageUrl = pageUrl,
                        fallbackTitle = fallbackTitle
                    )
                    val extras = item["extras"] as? JsonObject
                    listOfNotNull(
                        extras?.get("imageLinks") as? JsonArray,
                        item["imageLinks"] as? JsonArray
                    ).forEach { images ->
                        images.forEach { image ->
                            addImageElement(image, provider, pageUrl, fallbackTitle)
                        }
                    }
                }
            }
            WebSearchProvider.TAVILY -> {
                (root["images"] as? JsonArray).orEmpty().forEach { image ->
                    addImageElement(image, provider, null, null)
                }
                val entries = root["results"] as? JsonArray ?: JsonArray(emptyList())
                entries.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    val pageUrl = item.string("url")
                    val fallbackTitle = item.string("title")
                    (item["images"] as? JsonArray).orEmpty().forEach { image ->
                        addImageElement(image, provider, pageUrl, fallbackTitle)
                    }
                }
            }
        }
    }
    return results.distinctBy(WebImageSearchResult::imageUrl)
}

private fun MutableList<WebImageSearchResult>.addImageElement(
    element: JsonElement,
    provider: WebSearchProvider,
    pageUrl: String?,
    fallbackTitle: String?
) {
    when (element) {
        is JsonPrimitive -> {
            val imageUrl = element.contentOrNull?.trim().orEmpty()
            if (imageUrl.toHttpUrlOrNull() != null) {
                add(
                    WebImageSearchResult(
                        title = fallbackTitle.orEmpty().ifBlank { pageUrl ?: imageUrl }.take(240),
                        imageUrl = imageUrl,
                        pageUrl = pageUrl?.takeIf { it.toHttpUrlOrNull() != null } ?: imageUrl,
                        source = provider.label
                    )
                )
            }
        }
        is JsonObject -> addImageResult(
            item = element,
            provider = provider,
            imageKeys = arrayOf("url", "imageUrl", "image"),
            pageUrl = pageUrl,
            fallbackTitle = fallbackTitle
        )
        else -> Unit
    }
}

private fun MutableList<WebImageSearchResult>.addImageResult(
    item: JsonObject,
    provider: WebSearchProvider,
    imageKeys: Array<String>,
    pageUrl: String?,
    fallbackTitle: String? = null,
    width: Int? = null,
    height: Int? = null
) {
    val imageUrl = item.firstString(*imageKeys)?.takeIf { it.toHttpUrlOrNull() != null } ?: return
    val validPageUrl = pageUrl?.takeIf { it != imageUrl && it.toHttpUrlOrNull() != null } ?: imageUrl
    val title = item.firstString("title", "description")
        ?: fallbackTitle
        ?: validPageUrl
    add(
        WebImageSearchResult(
            title = title.take(240),
            imageUrl = imageUrl,
            pageUrl = validPageUrl,
            source = provider.label,
            width = width,
            height = height
        )
    )
}

internal fun parseWebScrapeResponse(
    provider: WebSearchProvider,
    raw: String,
    requestedUrl: String
): WebScrapeResult {
    val root = AiJson.parseToJsonElement(raw).jsonObject
    val item = when (provider) {
        WebSearchProvider.FIRECRAWL -> root["data"] as? JsonObject ?: JsonObject(emptyMap())
        WebSearchProvider.EXA,
        WebSearchProvider.TAVILY -> (root["results"] as? JsonArray)
            ?.firstOrNull() as? JsonObject ?: JsonObject(emptyMap())
    }
    val metadata = item["metadata"] as? JsonObject
    val url = item.string("url")
        ?: metadata?.firstString("sourceURL", "url")
        ?: requestedUrl
    val title = item.string("title")
        ?: metadata?.string("title")
        ?: url
    val content = when (provider) {
        WebSearchProvider.FIRECRAWL -> item.firstString("markdown", "content", "html")
        WebSearchProvider.EXA -> item.firstString("text", "summary", "content")
        WebSearchProvider.TAVILY -> item.firstString("raw_content", "content")
    }.orEmpty().trim()
    check(content.isNotBlank()) { "返回结果中没有可用的网页正文" }
    return WebScrapeResult(
        title = title.take(240),
        url = url,
        content = content.take(MAX_SCRAPED_CONTENT_CHARS)
    )
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)
    ?.contentOrNull
    ?.trim()
    ?.takeIf(String::isNotEmpty)

private fun JsonObject.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull(::string)

private fun String.normalizeSnippet(): String =
    replace(Regex("\\s+"), " ").trim().take(1_200)

private const val MAX_SCRAPED_CONTENT_CHARS = 20_000
