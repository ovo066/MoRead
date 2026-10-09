package com.mozhi.reader.ai.client

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ChatHttpCancellationTest {
    @Test fun cancellationAfterHeadersCancelsBlockedBodyAndReleasesCaller() = runBlocking {
        val reading = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            private val blocked = object : Source {
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
                override fun read(sink: Buffer, byteCount: Long): Long {
                    reading.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (!activeCall.get().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
                    throw IOException("body stopped")
                }
            }.buffer()
            override fun source(): BufferedSource = blocked
        }
        val http = OkHttpClient.Builder().addInterceptor {
            activeCall.set(it.call())
            response(it.request(), body)
        }.build()
        val task = async { execute(http, request()) }
        assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
        withTimeout(1_000) { task.cancelAndJoin() }
        assertTrue(activeCall.get().isCanceled())
    }

    @Test fun successfulBodyAndHttpErrorsStillReachCaller() = runBlocking {
        val success = OkHttpClient.Builder().addInterceptor {
            response(it.request(), "完成".toResponseBody())
        }.build()
        assertEquals("完成", execute(success, request()))
        val failure = OkHttpClient.Builder().addInterceptor {
            response(it.request(), """{"error":{"message":"service unavailable"}}""".toResponseBody(), 503)
        }.build()
        val error = runCatching { execute(failure, request()) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is AiClientException)
        assertTrue(error!!.message.orEmpty().contains("service unavailable"))
    }

    private fun request() = Request.Builder().url("https://chat.example.test/v1/chat/completions").build()
    private fun response(request: Request, body: ResponseBody, code: Int = 200) = Response.Builder()
        .request(request).code(code).message("test").protocol(Protocol.HTTP_1_1).body(body).build()
}
