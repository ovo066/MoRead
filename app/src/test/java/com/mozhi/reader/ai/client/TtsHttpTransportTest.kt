package com.mozhi.reader.ai.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test

class TtsHttpTransportTest {
    @Test fun rejectsOversizedDeclaredAndChunkedBodies() = runBlocking {
        for (declared in listOf(32L, -1L)) {
            val body = object : ResponseBody() {
                override fun contentType(): MediaType? = null
                override fun contentLength() = declared
                override fun source(): BufferedSource = Buffer().write(ByteArray(32))
            }
            val http = OkHttpClient.Builder().addInterceptor { response(it.request(), body) }.build()
            val error = runCatching { TtsHttpTransport(http, "test-key").read(request(), limit = 16) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test fun cancellationCancelsTheHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val http = OkHttpClient.Builder().addInterceptor {
            call.set(it.call())
            started.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!it.call().isCanceled() && System.nanoTime() < deadline) Thread.sleep(5)
            response(it.request(), byteArrayOf(1).toResponseBody())
        }.build()
        val task = async { TtsHttpTransport(http, "test-key").read(request()) }
        assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
        task.cancelAndJoin()
        assertTrue(call.get().isCanceled())
    }

    private fun request() = Request.Builder().url("https://tts.example.test/v1/tts").build()
    private fun response(request: Request, body: ResponseBody) = Response.Builder().request(request)
        .code(200).message("OK").protocol(Protocol.HTTP_1_1).body(body).build()
}
