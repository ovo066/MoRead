package com.mozhi.reader.ai.client

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resumeWithException

/** Bounded audio/JSON reads; cancellation also closes an in-flight synthesis request. */
internal class TtsHttpTransport(private val client: OkHttpClient, private val apiKey: String) {
    suspend fun read(request: Request, limit: Int = MAX_AUDIO_BYTES): SynthesizedSpeech =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            try {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(mapTransportError(e))
                        }

                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use {
                                    if (!it.isSuccessful) throw httpError(it.code,
                                        extractErrorMessage(it.peekBody(8192).string())?.replace(apiKey, "[redacted]"))
                                    require(it.body.contentLength() <= limit) { "语音响应过大，已取消缓存" }
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    it.body.byteStream().use { input ->
                                        while (true) {
                                            continuation.context.ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            require(output.size() + count <= limit) { "语音响应过大，已取消缓存" }
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                    if (output.size() == 0) throw AiClientException.Empty()
                                    SynthesizedSpeech(output.toByteArray(), it.header("Content-Type"),
                                        it.header("X-Request-Id") ?: it.header("X-Generation-Id") ?: it.header("request-id"))
                                }
                                continuation.resumeWith(Result.success(result))
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(
                                    if (error is IOException) mapTransportError(error) else error)
                            }
                        }
                    })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }

    companion object {
        const val MAX_AUDIO_BYTES = 30 * 1024 * 1024
        const val MAX_JSON_BYTES = MAX_AUDIO_BYTES * 4 / 3 + 64 * 1024
    }
}
