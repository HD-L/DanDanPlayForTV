package tv.dandan.app.data

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** 多连接读取的开关与参数。开关跟随设置“网络来源多连接读取”，由播放页在载入设置时更新。 */
internal object ParallelReading {
    @Volatile var enabled = true
    /** 每段 2MB：足够让单个请求跑满速度，又不会让刚打开就跳走时浪费太多。 */
    const val CHUNK = 2 * 1024 * 1024
    const val CONNECTIONS = 3
    /** 最多预取 12 段（约 24MB）。 */
    const val AHEAD = 12
    /**
     * 单连接连续读满 16MB 才切换到多连接：说明在正常播放，而不是打开文件时读取文件头、索引和附件（MKV 常带几 MB 的字体附件），
     * 或拖动进度后只读一小段。实测在电视上提前切换时，每次跳读会浪费约 6MB 已预取的数据。
     */
    const val SWITCH_AFTER = 16L * 1024 * 1024

    /** 解析 `Content-Range: bytes S-E/T`，要求起点等于请求位置，返回总长度 T；格式不对或总长度未知时返回 null。 */
    fun total(contentRange: String?, position: Long): Long? {
        val match = contentRange?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it.trim()) } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return total.takeIf { start == position && end >= start && end < total }
    }
}

/** 一个已打开的分段读取：输入流与关闭方法（关闭网络响应）。 */
internal class RangeStream(val input: InputStream, private val onClose: () -> Unit) : Closeable {
    override fun close() = onClose()
}

/**
 * 多连接顺序读取：把 [start, end) 切成 [chunkSize] 大小的分段，用最多 [connections] 个连接同时下载，
 * 再按顺序交给播放器。Wi-Fi 下单个 TCP 连接常被延迟和丢包限制在每秒一两兆，几个连接一起下载总速度明显更高。
 *
 * - 每个分段边下载边可读，播放器不必等整段下完；
 * - 预取最多 [aheadChunks] 段，内存有上限；刚打开时只预取一段，播放器连续读完第一段后才放开，
 *   打开后马上跳走（读取文件尾部的索引、拖动进度）时不会白白下载；
 * - 单个分段失败会从断点重试两次，仍失败时把错误交给播放器（播放器会重新打开数据源）。
 */
internal class ParallelRangeReader(
    private val start: Long,
    private val end: Long,
    private val chunkSize: Int,
    private val connections: Int,
    private val aheadChunks: Int,
    private val fetch: (position: Long, length: Long) -> RangeStream,
    first: RangeStream? = null,
) : Closeable {
    private class Chunk(val position: Long, val length: Int) {
        val data = ByteArray(length)
        @Volatile var filled = 0
        @Volatile var error: IOException? = null
    }

    private val chunkCount = ((end - start + chunkSize - 1) / chunkSize)
    private val chunks = ConcurrentHashMap<Long, Chunk>()
    private val lock = Object()
    private val open = Collections.synchronizedSet(mutableSetOf<RangeStream>())
    @Volatile private var closed = false
    /** 下一个待分配给连接的分段（受 [lock] 保护）。 */
    private var nextIndex = 0L
    /** 播放器正在读取的分段与段内位置（只由读取线程修改，[readIndex] 变化时在 [lock] 内通知）。 */
    @Volatile private var readIndex = 0L
    @Volatile private var readOffset = 0
    private val workersStarted = AtomicInteger()

    init {
        require(start in 0 until end && chunkSize > 0 && connections > 0 && aheadChunks > 0) { "分段读取参数无效" }
        val initial = first
        if (initial != null) {
            val chunk = synchronized(lock) { assign()!! }
            pool.execute { download(chunk, initial); work() }
            workersStarted.incrementAndGet()
        }
        while (workersStarted.get() < connections) { pool.execute(::work); workersStarted.incrementAndGet() }
    }

    /** 已发出过的分段请求数，供测试检查预取节奏。 */
    val requestedChunks: Long get() = synchronized(lock) { nextIndex }

    /** 在 [lock] 内分配下一个分段；超出预取窗口或已读完时返回 null。 */
    private fun assign(): Chunk? {
        if (nextIndex >= chunkCount) return null
        val window = if (readIndex == 0L && readOffset < chunkSize) 1L else aheadChunks.toLong()
        if (nextIndex > readIndex + window) return null
        val index = nextIndex++
        val position = start + index * chunkSize
        return Chunk(position, minOf(chunkSize.toLong(), end - position).toInt()).also { chunks[index] = it }
    }

    private fun work() {
        while (!closed) {
            val chunk = synchronized(lock) {
                var next = assign()
                while (next == null && !closed && nextIndex < chunkCount) { lock.wait(500); next = assign() }
                next
            } ?: return
            download(chunk, null)
        }
    }

    private fun download(chunk: Chunk, initial: RangeStream?) {
        var attempt = 0
        var pending = initial
        while (!closed && chunk.filled < chunk.length) {
            try {
                val stream = pending ?: fetch(chunk.position + chunk.filled, (chunk.length - chunk.filled).toLong())
                pending = null
                open += stream
                try {
                    while (!closed && chunk.filled < chunk.length) {
                        val count = stream.input.read(chunk.data, chunk.filled, chunk.length - chunk.filled)
                        if (count < 0) throw EOFException("分段内容提前结束")
                        chunk.filled += count
                        TransferMeter.add(count)
                        synchronized(lock) { lock.notifyAll() }
                    }
                } finally { open -= stream; runCatching { stream.close() } }
            } catch (failure: Exception) {
                val error = failure as? IOException ?: IOException("分段下载失败", failure)
                pending?.let { runCatching { it.close() } }
                pending = null
                if (closed) return
                if (++attempt >= 3) { chunk.error = error; synchronized(lock) { lock.notifyAll() }; return }
                try { Thread.sleep(300L * attempt) } catch (_: InterruptedException) { return }
            }
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (readIndex >= chunkCount) return -1
            val chunk = synchronized(lock) {
                var current = chunks[readIndex]
                while (!closed && (current == null || (current.filled <= readOffset && current.error == null))) {
                    lock.wait(1000)
                    current = chunks[readIndex]
                }
                if (closed) throw IOException("媒体读取已关闭")
                current!!
            }
            val available = chunk.filled - readOffset
            if (available > 0) {
                val count = minOf(length, available)
                System.arraycopy(chunk.data, readOffset, buffer, offset, count)
                readOffset += count
                if (readOffset >= chunk.length) {
                    chunks.remove(readIndex)
                    synchronized(lock) { readIndex++; readOffset = 0; lock.notifyAll() }
                }
                return count
            }
            chunk.error?.let { throw IOException("媒体分段下载失败：${it.message}", it) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { lock.notifyAll() }
        open.toList().forEach { runCatching { it.close() } }
        TransferMeter.wasted(chunks.entries.sumOf { (index, chunk) -> (chunk.filled - if (index == readIndex) readOffset else 0).coerceAtLeast(0).toLong() })
        chunks.clear()
    }

    companion object {
        /** 分段下载线程在多次打开之间复用（播放器每次定位都会重新打开数据源）。 */
        private val pool = Executors.newCachedThreadPool { runnable -> Thread(runnable, "媒体分段下载").apply { isDaemon = true } }
    }
}
