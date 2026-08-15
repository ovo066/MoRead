package com.mozhi.reader.core.importer.lan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已接收但还没导入书架的文件。 */
data class LanReceivedFile(
    val name: String,
    val sizeBytes: Long,
    val receivedAt: Long,
    val path: String
)

/** 正在接收中的文件；[totalBytes] 为 -1 表示对方没声明长度。 */
data class LanIncomingFile(
    val name: String,
    val receivedBytes: Long,
    val totalBytes: Long
)

data class LanServerState(
    val running: Boolean = false,
    /** 形如 `http://192.168.1.7:1122`；未连 Wi-Fi 时为 null。 */
    val address: String? = null,
    val port: Int = 0,
    val error: String? = null,
    val incoming: LanIncomingFile? = null,
    val received: List<LanReceivedFile> = emptyList()
)

/**
 * 局域网传书服务端：手机开一个只在局域网可达的最小 HTTP 服务，电脑浏览器打开即可拖拽上传。
 *
 * 明确的边界（安全上不打算做得更多，也不假装做到了）：
 * - 只接受 txt/epub，文件名经 [LanUploadNaming] 清洗后落进应用私有缓存目录；
 * - 单文件与单次会话都有硬上限，请求头长度也有上限；
 * - 没有鉴权：局域网内任何人都能上传。页面上如实提示「仅在可信网络开启」，
 *   并且服务只在用户停留在传书页/前台服务存活期间监听，用完即关。
 */
@Singleton
class LanBookServer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(LanServerState())
    val state: StateFlow<LanServerState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null

    fun inboxDirectory(): File = File(context.cacheDir, INBOX_DIRECTORY).apply { mkdirs() }

    @Synchronized
    fun start() {
        if (running.get()) return
        val socket = try {
            bindFirstAvailablePort()
        } catch (error: IOException) {
            _state.value = _state.value.copy(
                running = false,
                error = "无法启动传书服务：${error.message ?: "端口被占用"}"
            )
            return
        }
        serverSocket = socket
        running.set(true)
        workers = Executors.newFixedThreadPool(CONNECTION_THREADS)
        val host = NetworkAddresses.siteLocalIpv4()
        _state.value = LanServerState(
            running = true,
            address = host?.let { "http://$it:${socket.localPort}" },
            port = socket.localPort,
            error = if (host == null) "未检测到局域网地址，请先连接 Wi-Fi" else null,
            received = _state.value.received
        )
        acceptThread = Thread({ acceptLoop(socket) }, "lan-book-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
        acceptThread = null
        _state.value = _state.value.copy(
            running = false,
            address = null,
            port = 0,
            incoming = null
            // error 故意保留：绑定失败时前台服务会立刻走到这里，清空它等于把
            // 「端口被占用 / 未连 Wi-Fi」的唯一提示抹掉。下次 start() 会整体覆盖。
        )
    }

    /** 已导入书架后调用：从列表和磁盘上一起清掉。 */
    fun clearReceived() {
        _state.value.received.forEach { file -> runCatching { File(file.path).delete() } }
        _state.value = _state.value.copy(received = emptyList())
    }

    /** 只从待处理列表移除，文件留在收件箱：交给导入任务处置成败后再删。 */
    fun forgetReceived() {
        _state.value = _state.value.copy(received = emptyList())
    }

    fun removeReceived(path: String) {
        runCatching { File(path).delete() }
        _state.value = _state.value.copy(
            received = _state.value.received.filterNot { it.path == path }
        )
    }

    private fun bindFirstAvailablePort(): ServerSocket {
        var lastError: IOException? = null
        for (port in DEFAULT_PORT until DEFAULT_PORT + PORT_ATTEMPTS) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port), CONNECTION_BACKLOG)
                }
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("没有可用端口")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                // stop() 关闭 socket 会从这里出来，属正常退出路径。
                break
            }
            val pool = workers ?: run { runCatching { client.close() }; break }
            runCatching {
                pool.execute {
                    client.use(::handleConnection)
                }
            }.onFailure { runCatching { client.close() } }
        }
    }

    private fun handleConnection(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        client.tcpNoDelay = true
        val input = client.getInputStream().buffered()
        val output = BufferedOutputStream(client.getOutputStream())
        try {
            val head = HttpRequestParser.readHead(input) ?: return
            when {
                head.method == "GET" && head.path == "/" ->
                    respondAsset(output, "lan/index.html", "text/html; charset=utf-8")
                head.method == "GET" && head.path == "/app.js" ->
                    respondAsset(output, "lan/app.js", "application/javascript; charset=utf-8")
                head.method == "GET" && head.path == "/style.css" ->
                    respondAsset(output, "lan/style.css", "text/css; charset=utf-8")
                head.method == "GET" && head.path == "/status" ->
                    respondJson(output, 200, statusJson())
                head.method == "POST" && head.path == "/upload" -> handleUpload(head, input, output)
                head.method == "OPTIONS" -> respondJson(output, 200, "{\"ok\":true}")
                else -> respondJson(output, 404, "{\"error\":\"没有这个地址\"}")
            }
        } catch (error: Throwable) {
            _state.value = _state.value.copy(incoming = null)
            runCatching { respondJson(output, 400, errorJson(error.message ?: "请求处理失败")) }
        } finally {
            runCatching { output.flush() }
        }
    }

    private fun handleUpload(head: HttpRequestHead, input: InputStream, output: OutputStream) {
        val declared = head.contentLength
        if (declared > MAX_SESSION_BYTES) {
            respondJson(output, 413, errorJson("单次上传超过 2 GB"))
            return
        }
        val boundary = head.multipartBoundary
        val saved = if (boundary != null) {
            receiveMultipart(input, boundary, declared)
        } else {
            // 自家页面走的快路径：文件名在头里，消息体就是文件本身。
            listOfNotNull(
                receiveRawBody(
                    input = input,
                    rawName = head.header("x-file-name")?.let(HttpRequestParser::decodeComponent),
                    declaredLength = declared
                )
            )
        }
        _state.value = _state.value.copy(incoming = null)
        if (saved.isEmpty()) {
            respondJson(output, 400, errorJson("没有收到可导入的 txt / epub 文件"))
        } else {
            respondJson(
                output,
                200,
                "{\"ok\":true,\"files\":[" +
                    saved.joinToString(",") { "{\"name\":${jsonString(it.name)},\"size\":${it.sizeBytes}}" } +
                    "]}"
            )
        }
    }

    private fun receiveMultipart(
        input: InputStream,
        boundary: String,
        declaredLength: Long
    ): List<LanReceivedFile> {
        val reader = MultipartReader(input, boundary)
        val saved = mutableListOf<LanReceivedFile>()
        while (true) {
            val part = reader.nextPart() ?: break
            val name = LanUploadNaming.sanitize(part.fileName)
            if (name == null) {
                reader.skipBody()
                continue
            }
            val target = allocate(name)
            _state.value = _state.value.copy(
                incoming = LanIncomingFile(name, 0L, declaredLength)
            )
            val written = try {
                target.outputStream().buffered().use { sink ->
                    reader.writeBodyTo(ProgressOutputStream(sink, name, declaredLength))
                }
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            if (written > MAX_FILE_BYTES) {
                target.delete()
                throw IOException("「$name」超过 200 MB，已拒绝")
            }
            saved += register(target, written)
        }
        return saved
    }

    private fun receiveRawBody(
        input: InputStream,
        rawName: String?,
        declaredLength: Long
    ): LanReceivedFile? {
        val name = LanUploadNaming.sanitize(rawName) ?: return null
        if (declaredLength < 0) throw IOException("上传缺少 Content-Length")
        if (declaredLength > MAX_FILE_BYTES) throw IOException("「$name」超过 200 MB，已拒绝")
        val target = allocate(name)
        _state.value = _state.value.copy(
            incoming = LanIncomingFile(name, 0L, declaredLength)
        )
        var written = 0L
        try {
            target.outputStream().buffered().use { sink ->
                val buffer = ByteArray(COPY_BUFFER)
                while (written < declaredLength) {
                    val wanted = minOf(buffer.size.toLong(), declaredLength - written).toInt()
                    val read = input.read(buffer, 0, wanted)
                    if (read < 0) throw IOException("上传在完成之前中断")
                    sink.write(buffer, 0, read)
                    written += read
                    publishProgress(name, written, declaredLength)
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return register(target, written)
    }

    private fun allocate(name: String): File {
        val directory = inboxDirectory()
        val taken = (directory.list()?.toSet().orEmpty()) +
            _state.value.received.map(LanReceivedFile::name).toSet()
        return File(directory, LanUploadNaming.uniqueName(name, taken))
    }

    private fun register(file: File, size: Long): LanReceivedFile {
        val record = LanReceivedFile(
            name = file.name,
            sizeBytes = size,
            receivedAt = System.currentTimeMillis(),
            path = file.absolutePath
        )
        _state.value = _state.value.copy(
            incoming = null,
            received = _state.value.received + record
        )
        return record
    }

    private fun publishProgress(name: String, written: Long, total: Long) {
        val current = _state.value.incoming
        // 每 256KB 才推一次，避免大文件把 StateFlow 刷成每毫秒一帧。
        if (current != null && written - current.receivedBytes < PROGRESS_STEP && written < total) {
            return
        }
        _state.value = _state.value.copy(incoming = LanIncomingFile(name, written, total))
    }

    /** 边写盘边把进度推给界面。 */
    private inner class ProgressOutputStream(
        private val delegate: OutputStream,
        private val name: String,
        private val total: Long
    ) : OutputStream() {
        private var written = 0L

        override fun write(b: Int) {
            delegate.write(b)
            written++
            publishProgress(name, written, total)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            written += len
            if (written > MAX_FILE_BYTES) throw IOException("「$name」超过 200 MB，已拒绝")
            publishProgress(name, written, total)
        }

        override fun flush() = delegate.flush()
    }

    private fun statusJson(): String = buildString {
        append("{\"running\":true,\"received\":[")
        append(
            _state.value.received.joinToString(",") { file ->
                "{\"name\":${jsonString(file.name)},\"size\":${file.sizeBytes}}"
            }
        )
        append("]}")
    }

    private fun errorJson(message: String): String = "{\"error\":${jsonString(message)}}"

    private fun respondAsset(output: OutputStream, assetPath: String, contentType: String) {
        val bytes = runCatching {
            context.assets.open(assetPath).use(InputStream::readBytes)
        }.getOrNull()
        if (bytes == null) {
            respondJson(output, 404, errorJson("页面资源缺失"))
            return
        }
        writeResponse(output, 200, contentType, bytes)
    }

    private fun respondJson(output: OutputStream, status: Int, body: String) {
        writeResponse(output, status, "application/json; charset=utf-8", body.toByteArray())
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            // 每请求一关：不做 keep-alive，省掉一整套连接复用状态机。
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "Error"
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u").append(String.format("%04x", character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    companion object {
        const val DEFAULT_PORT = 1122
        const val PORT_ATTEMPTS = 6
        const val MAX_FILE_BYTES = 200L * 1024 * 1024
        const val MAX_SESSION_BYTES = 2L * 1024 * 1024 * 1024
        const val INBOX_DIRECTORY = "lan-inbox"
        private const val CONNECTION_THREADS = 4
        private const val CONNECTION_BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val COPY_BUFFER = 64 * 1024
        private const val PROGRESS_STEP = 256L * 1024
    }
}
