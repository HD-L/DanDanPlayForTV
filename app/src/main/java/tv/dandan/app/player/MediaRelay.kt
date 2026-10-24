package tv.dandan.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import tv.dandan.app.data.AppRepository
import tv.dandan.app.data.MediaDataSourceFactory
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal interface RelayReader : Closeable {
    fun open(position: Long): Long
    val responseHeaders: Map<String, List<String>>
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}
internal fun interface RelayReaderFactory { fun create(): RelayReader }

/** 每个播放会话只转发一个媒体；切集或释放会话时必须关闭本对象。 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MediaRelay internal constructor(private val factory: RelayReaderFactory) : Closeable {
    constructor(context: Context, repo: AppRepository, uri: String) : this(mediaFactory(context, repo, uri))

    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val clients = mutableSetOf<Socket>()
    private val readers = mutableSetOf<RelayReader>()
    private val path = "/media/" + ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 6, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val workerId = AtomicInteger()
    private val workers = ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(3), { task ->
        Thread(task, "媒体转发-${workerId.incrementAndGet()}").apply { isDaemon = true }
    })
    val url: String = "http://127.0.0.1:${server.localPort}$path"
    /** 监听端口是否仍打开（测试用：直接连旧端口来判断不可靠，端口可能已被同一进程里的其它服务复用）。 */
    internal val listening: Boolean get() = !server.isClosed
    private val acceptThread = Thread(::acceptClients, "媒体转发监听").apply { isDaemon = true; start() }

    private fun acceptClients() {
        try {
            while (!closed.get()) {
                val client = server.accept()
                client.soTimeout = 3000
                val accepted = synchronized(lock) {
                    if (closed.get()) false else { clients += client; true }
                }
                if (!accepted) { client.close(); continue }
                try { workers.execute { serve(client) } }
                catch (_: RejectedExecutionException) { forgetClient(client) }
            }
        } catch (_: IOException) {
            // 关闭监听会打断 accept；意外监听错误也回收当前连接。
        } finally { close() }
    }

    private fun serve(client: Socket) {
        var reader: RelayReader? = null
        var headersSent = false
        try {
            val request = RelayRules.readRequest(client.getInputStream(), path)
            RelayRules.initialPosition(request.range)
            val opened = openReader(0)
            reader = opened.first
            val total = RelayRules.totalLength(opened.second, 0, reader.responseHeaders)
            var source = reader
            val window = RelayRules.resolve(request.range, total)
            if (request.method == "GET" && window.position != 0L) {
                forgetReader(source)
                val ranged = openReader(window.position)
                reader = ranged.first
                source = ranged.first
                val actualTotal = RelayRules.totalLength(ranged.second, window.position, source.responseHeaders)
                if (actualTotal != total) throw IOException("媒体长度在读取期间发生变化")
            }
            ensureOpen()
            client.soTimeout = 0
            headersSent = true
            writeHeaders(client, window.status, window.length, window.contentRange, total != null)
            if (request.method == "HEAD") return
            val output = client.getOutputStream()
            val buffer = ByteArray(64 * 1024)
            var remaining = window.length
            while (remaining == null || remaining > 0) {
                ensureOpen()
                val wanted = minOf(buffer.size.toLong(), remaining ?: buffer.size.toLong()).toInt()
                val count = source.read(buffer, 0, wanted)
                if (count < 0) {
                    if (remaining != null && remaining > 0) throw EOFException("媒体转发内容提前结束")
                    break
                }
                if (count == 0 || count > wanted) throw IOException("媒体读取返回无效长度")
                output.write(buffer, 0, count)
                if (remaining != null) remaining -= count
            }
            output.flush()
        } catch (error: RelayHttpException) {
            if (!headersSent && !closed.get()) runCatching { writeHeaders(client, error.status, 0, error.contentRange) }
        } catch (_: SocketTimeoutException) {
            if (!headersSent && !closed.get()) runCatching { writeHeaders(client, 408, 0) }
        } catch (_: Exception) {
            // 响应已开始后只关闭连接，不能在媒体正文中插入错误页面或来源地址。
            if (!headersSent && !closed.get()) runCatching { writeHeaders(client, 502, 0) }
        } finally {
            reader?.let(::forgetReader)
            forgetClient(client)
        }
    }

    private fun openReader(position: Long): Pair<RelayReader, Long> {
        ensureOpen()
        val reader = factory.create()
        synchronized(lock) {
            if (closed.get()) { runCatching { reader.close() }; throw IOException("媒体转发已关闭") }
            readers += reader
        }
        return try {
            val length = reader.open(position)
            ensureOpen()
            reader to length
        } catch (error: Exception) { forgetReader(reader); throw error }
    }

    private fun writeHeaders(client: Socket, status: Int, length: Long?, contentRange: String? = null, ranges: Boolean = false) {
        val reason = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            408 -> "Request Timeout"; 416 -> "Range Not Satisfiable"; 431 -> "Request Header Fields Too Large"; else -> "Bad Gateway"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\nConnection: close\r\nContent-Type: application/octet-stream\r\nCache-Control: no-store\r\n")
            if (length != null) append("Content-Length: $length\r\n")
            if (contentRange != null) append("Content-Range: $contentRange\r\n")
            if (ranges) append("Accept-Ranges: bytes\r\n")
            if (status == 405) append("Allow: GET, HEAD\r\n")
            append("\r\n")
        }
        client.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); flush() }
    }

    private fun ensureOpen() { if (closed.get()) throw IOException("媒体转发已关闭") }
    private fun forgetReader(reader: RelayReader) {
        // 先关闭再移除，保证并发 close 仍能看到正在关闭的来源。
        runCatching { reader.close() }
        synchronized(lock) { readers -= reader }
    }
    private fun forgetClient(client: Socket) {
        runCatching { client.close() }
        synchronized(lock) { clients -= client }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        workers.shutdownNow()
        val resources = synchronized(lock) { clients.toList() to readers.toList() }
        resources.first.forEach(::forgetClient)
        resources.second.forEach(::forgetReader)
    }

    private companion object {
        fun mediaFactory(context: Context, repo: AppRepository, uri: String): RelayReaderFactory {
            val sourceFactory = MediaDataSourceFactory(context.applicationContext, repo)
            val mediaUri = Uri.parse(uri)
            return RelayReaderFactory { DataSourceReader(sourceFactory.createDataSource(), mediaUri) }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private class DataSourceReader(private val source: DataSource, private val uri: Uri) : RelayReader {
    override fun open(position: Long): Long = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
    override val responseHeaders: Map<String, List<String>> get() = source.responseHeaders
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source.read(buffer, offset, length)
    override fun close() = source.close()
}
