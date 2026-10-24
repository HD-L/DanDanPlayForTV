package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ParallelRangeReaderTest {
    private val data = ByteArray(10 * 1024 * 1024 + 123) { (it * 31 % 251).toByte() }
    private val chunk = 1024 * 1024

    /** 模拟分段请求：返回 [position, position + length) 的内容，可注入延迟与失败。 */
    private fun server(delayMs: Long = 0, fail: (Long, Int) -> Boolean = { _, _ -> false }): Pair<(Long, Long) -> RangeStream, MutableList<Long>> {
        val requests = Collections.synchronizedList(mutableListOf<Long>())
        val attempts = java.util.concurrent.ConcurrentHashMap<Long, Int>()
        val fetch: (Long, Long) -> RangeStream = { position, length ->
            requests += position
            val attempt = attempts.merge(position, 1, Int::plus)!!
            if (fail(position, attempt)) throw IOException("模拟网络中断")
            if (delayMs > 0) Thread.sleep(delayMs)
            RangeStream(ByteArrayInputStream(data, position.toInt(), length.toInt())) {}
        }
        return fetch to requests
    }

    private fun readAll(reader: ParallelRangeReader, size: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024 + 7)
        while (true) {
            val count = reader.read(buffer, 0, buffer.size)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > size) fail("读取超过预期长度")
        }
        return out.toByteArray()
    }

    @Test fun readsEveryByteInOrderWithSeveralConnections() {
        val (fetch, requests) = server(delayMs = 5)
        ParallelRangeReader(0, data.size.toLong(), chunk, 3, 4, fetch).use { reader ->
            assertArrayEquals(data, readAll(reader, data.size))
        }
        assertEquals(11, requests.toSet().size)
        // 从中间位置打开（播放器定位）
        val start = 3_500_000L
        ParallelRangeReader(start, data.size.toLong(), chunk, 3, 4, server().first).use { reader ->
            assertArrayEquals(data.copyOfRange(start.toInt(), data.size), readAll(reader, data.size))
        }
    }

    @Test fun firstChunkStreamFromTheOpeningResponseAndRampUp() {
        val (fetch, requests) = server()
        val first = RangeStream(ByteArrayInputStream(data, 0, chunk)) {}
        ParallelRangeReader(0, data.size.toLong(), chunk, 3, 6, fetch, first).use { reader ->
            Thread.sleep(300)
            // 刚打开只多预取一段：第一段来自打开时的响应，额外只请求第二段
            assertEquals(listOf(chunk.toLong()), requests.toList())
            assertEquals(2, reader.requestedChunks)
            assertArrayEquals(data, readAll(reader, data.size))
        }
    }

    @Test fun failedChunksRetryFromTheBreakPoint() {
        val (fetch, _) = server(fail = { position, attempt -> position == 3L * chunk && attempt == 1 })
        ParallelRangeReader(0, data.size.toLong(), chunk, 3, 4, fetch).use { reader ->
            assertArrayEquals(data, readAll(reader, data.size))
        }
        val (broken, _) = server(fail = { position, _ -> position == 2L * chunk })
        ParallelRangeReader(0, data.size.toLong(), chunk, 2, 4, broken).use { reader ->
            assertThrows(IOException::class.java) { readAll(reader, data.size) }
        }
    }

    @Test fun closeWakesABlockedReader() {
        val gate = CountDownLatch(1)
        val blocking: (Long, Long) -> RangeStream = { _, _ ->
            RangeStream(object : InputStream() {
                override fun read(): Int { gate.await(); throw IOException("已关闭") }
                override fun read(b: ByteArray, off: Int, len: Int): Int { gate.await(5, TimeUnit.SECONDS); throw IOException("已关闭") }
            }) { gate.countDown() }
        }
        val reader = ParallelRangeReader(0, data.size.toLong(), chunk, 2, 4, blocking)
        val failure = arrayOfNulls<Throwable>(1)
        val thread = Thread { failure[0] = runCatching { reader.read(ByteArray(10), 0, 10) }.exceptionOrNull() }.apply { start() }
        Thread.sleep(200)
        reader.close()
        thread.join(3000)
        assertFalse(thread.isAlive)
        assertTrue(failure[0] is IOException)
    }

    @Test fun contentRangeTotals() {
        assertEquals(1000L, ParallelReading.total("bytes 0-99/1000", 0))
        assertEquals(1000L, ParallelReading.total("bytes 500-999/1000", 500))
        assertNull(ParallelReading.total("bytes 0-99/*", 0))
        assertNull(ParallelReading.total("bytes 10-99/1000", 0))
        assertNull(ParallelReading.total(null, 0))
    }
}
