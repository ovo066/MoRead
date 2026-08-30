package com.mozhi.reader.core.diag

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/**
 * 挂在共享 OkHttpClient 上的应用级拦截器，覆盖全部 AI 请求（四方言聊天、embedding、
 * TTS/生图、模型目录、测连）。开关关闭时零开销直通。
 *
 * 流式（SSE）响应只记状态与到响应头的耗时，绝不读流；非流式文本响应用 peekBody
 * 取有限预览，不消耗原响应。请求体快照跳过一次性/双工体。
 */
@Singleton
class ApiCallLogInterceptor @Inject constructor(
    private val store: ApiCallLogStore
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!store.recordingEnabled || request.tag(SkipApiCallLogging::class.java) != null) {
            return chain.proceed(request)
        }

        val startedAt = System.currentTimeMillis()
        val startNanos = System.nanoTime()
        val url = redactSensitiveQuery(request.url.toString())
        val (requestPreview, requestBytes) = requestSnapshot(request)

        val response = try {
            chain.proceed(request)
        } catch (error: IOException) {
            store.record(
                ApiCallLogEntry(
                    timestamp = startedAt,
                    method = request.method,
                    url = url,
                    status = 0,
                    durationMs = elapsedMs(startNanos),
                    requestBytes = requestBytes,
                    requestPreview = requestPreview,
                    error = describe(error)
                )
            )
            throw error
        }

        val contentType = response.body?.contentType()?.toString()
            ?: response.header("Content-Type")
        val streaming = contentType.orEmpty().contains("event-stream", ignoreCase = true)
        val responsePreview = when {
            streaming -> null
            isTextLike(contentType) -> runCatching {
                clipPreview(response.peekBody(RESPONSE_PEEK_BYTES).string(), PREVIEW_CHARS)
            }.getOrNull()
            else -> null
        }
        store.record(
            ApiCallLogEntry(
                timestamp = startedAt,
                method = request.method,
                url = url,
                status = response.code,
                durationMs = elapsedMs(startNanos),
                streaming = streaming,
                requestBytes = requestBytes,
                requestPreview = requestPreview,
                responseType = contentType,
                responsePreview = responsePreview
            )
        )
        return response
    }

    private fun requestSnapshot(request: Request): Pair<String?, Long> {
        val body = request.body ?: return null to 0L
        val declared = try {
            body.contentLength()
        } catch (_: Exception) {
            -1L
        }
        if (body.isDuplex() || body.isOneShot()) return "（一次性请求体，未记录）" to declared
        if (!isTextLike(body.contentType()?.toString())) return "（二进制请求体，未记录）" to declared
        if (declared < 0L || declared > MAX_SNAPSHOT_BODY_BYTES) {
            return "（请求体过大，未记录）" to declared
        }
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val size = buffer.size
            val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            val text = buffer.readString(minOf(size, REQUEST_PEEK_BYTES), charset)
            val preview = if (size > REQUEST_PEEK_BYTES) {
                text + "\n…（已截断，请求体共 $size 字节）"
            } else {
                text
            }
            preview to size
        } catch (_: Exception) {
            null to declared
        }
    }

    private fun isTextLike(contentType: String?): Boolean {
        val type = contentType?.lowercase() ?: return false
        return "json" in type || type.startsWith("text") || "xml" in type || "urlencoded" in type
    }

    private fun describe(error: IOException): String {
        val name = error.javaClass.simpleName
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name：$message"
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private companion object {
        const val REQUEST_PEEK_BYTES = 2048L
        const val MAX_SNAPSHOT_BODY_BYTES = 1024L * 1024L
        const val RESPONSE_PEEK_BYTES = 8192L
        const val PREVIEW_CHARS = 4000
    }
}


/** 标记不应进入诊断日志的请求（备份、媒体等大体积流式传输）。 */
object SkipApiCallLogging
