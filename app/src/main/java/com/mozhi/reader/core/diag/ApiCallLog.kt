package com.mozhi.reader.core.diag

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 单次 API 调用的诊断记录。落盘/展示前已完成脱敏与截断：
 * - 不记录任何请求头（Authorization / x-api-key 等自然不会出现）；
 * - 查询串里的密钥参数（Gemini 的 ?key= 等）一律抹成 ***；
 * - 请求/响应体只留有限预览，绝不含书籍全文。
 */
@Serializable
data class ApiCallLogEntry(
    val timestamp: Long,
    val method: String,
    val url: String,
    /** HTTP 状态码；0 表示网络层失败（未拿到响应）。 */
    val status: Int = 0,
    val durationMs: Long = 0,
    /** 响应为 SSE 流：耗时口径是「到响应头」，正文预览不读流。 */
    val streaming: Boolean = false,
    val requestBytes: Long = -1,
    val requestPreview: String? = null,
    val responseType: String? = null,
    val responsePreview: String? = null,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null && status in 200..299
}

/** JSONL 编解码：一行一条；坏行静默跳过，日志文件损坏不影响应用。 */
object ApiCallLogCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encodeLine(entry: ApiCallLogEntry): String =
        json.encodeToString(ApiCallLogEntry.serializer(), entry)

    fun decodeLines(raw: String?): List<ApiCallLogEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                runCatching { json.decodeFromString(ApiCallLogEntry.serializer(), line) }.getOrNull()
            }
            .toList()
    }
}

/** 常见的密钥类查询参数名（大小写不敏感）；Gemini 把 Key 放在 ?key= 里。 */
private val SENSITIVE_QUERY_KEYS =
    setOf("key", "api_key", "apikey", "api-key", "token", "access_token", "secret")

/** 抹掉 URL 查询串中的密钥参数；解析失败按原样返回（此时不含合法查询串）。 */
fun redactSensitiveQuery(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url.substringBefore('?')
    val sensitive = parsed.queryParameterNames.filter { it.lowercase() in SENSITIVE_QUERY_KEYS }
    if (sensitive.isEmpty()) return url
    val builder = parsed.newBuilder()
    sensitive.forEach { builder.setQueryParameter(it, "***") }
    return builder.build().toString()
}

/** 预览截断：超长时保留前 [maxChars] 字符并标注原始长度。 */
fun clipPreview(text: String, maxChars: Int): String =
    if (text.length <= maxChars) {
        text
    } else {
        text.take(maxChars) + "\n…（已截断，原文 ${text.length} 字符）"
    }
