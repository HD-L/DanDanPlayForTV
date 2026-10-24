package tv.dandan.app.player

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MediaRelayTest {
    private val bytes = "0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()

    @Test fun constructorDoesNotOpenMediaAndUrlHasOnlyLocalToken() {
        val factory = MemoryFactory(bytes)
        MediaRelay(factory).use { relay ->
            assertEquals(0, factory.opens.get())
            val uri = URI(relay.url)
            assertEquals("127.0.0.1", uri.host)
            assertNull(uri.userInfo)
            assertNull(uri.query)
            assertTrue(uri.path.matches(Regex("/media/[0-9a-f]{64}")))
        }
    }

    @Test fun getHeadAndRangesCloseEveryOpenedReader() {
        val factory = MemoryFactory(bytes)
        MediaRelay(factory).use { relay ->
            val whole = request(relay, "GET")
            assertEquals(200, whole.status)
            assertArrayEquals(bytes, whole.body)
            val readsAfterGet = factory.reads.get()
            val head = request(relay, "HEAD")
            assertEquals(200, head.status)
            assertEquals(bytes.size.toString(), head.headers["content-length"])
            assertEquals(0, head.body.size)
            assertEquals(readsAfterGet, factory.reads.get())
            val opensBeforeRangedHead = factory.opens.get()
            val rangedHead = request(relay, "HEAD", "Range: bytes=30-\r\n")
            assertEquals(206, rangedHead.status)
            assertEquals("bytes 30-35/36", rangedHead.headers["content-range"])
            assertEquals(0, rangedHead.body.size)
            assertEquals(opensBeforeRangedHead + 1, factory.opens.get())
            assertEquals(readsAfterGet, factory.reads.get())
            val range = request(relay, "GET", "Range: bytes=10-19\r\n")
            assertEquals(206, range.status)
            assertEquals("bytes 10-19/36", range.headers["content-range"])
            assertArrayEquals(bytes.copyOfRange(10, 20), range.body)
            val tail = request(relay, "GET", "Range: bytes=30-1000\r\n")
            assertEquals("bytes 30-35/36", tail.headers["content-range"])
            assertArrayEquals(bytes.copyOfRange(30, 36), tail.body)
            val suffix = request(relay, "GET", "Range: bytes=-3\r\n")
            assertEquals(206, suffix.status)
            assertArrayEquals(bytes.takeLast(3).toByteArray(), suffix.body)
            val invalid = request(relay, "GET", "Range: bytes=36-\r\n")
            assertEquals(416, invalid.status)
            assertEquals("bytes */36", invalid.headers["content-range"])
        }
        assertEquals(factory.opens.get(), factory.closes.get())
    }

    @Test fun pathAndBodyRejectionsNeverOpenMedia() {
        val factory = MemoryFactory(bytes)
        MediaRelay(factory).use { relay ->
            assertEquals(404, request(relay, "GET", path = "/other").status)
            assertEquals(404, request(relay, "GET", path = URI(relay.url).rawPath + "?uri=http://other").status)
            assertEquals(400, request(relay, "GET", "Content-Length: 4\r\n").status)
            assertEquals(400, request(relay, "GET", "Transfer-Encoding: chunked\r\n").status)
            assertEquals(416, request(relay, "GET", "Range: bytes=0-1,4-5\r\n").status)
            assertEquals(0, factory.opens.get())
        }
    }

    @Test fun unknownLengthHasNoFalseContentLengthAndRangeIsRejected() {
        val factory = MemoryFactory(bytes, unknownLength = true)
        MediaRelay(factory).use { relay ->
            val whole = request(relay, "GET")
            assertEquals(200, whole.status)
            assertNull(whole.headers["content-length"])
            assertArrayEquals(bytes, whole.body)
            val reads = factory.reads.get()
            val range = request(relay, "GET", "Range: bytes=0-10\r\n")
            assertEquals(416, range.status)
            assertNull(range.headers["content-range"])
            assertEquals(reads, factory.reads.get())
        }
        assertEquals(factory.opens.get(), factory.closes.get())
    }

    @Test fun closeStopsListenerClientAndBlockedReader() {
        val reading = CountDownLatch(1)
        val released = CountDownLatch(1)
        val sourceClosed = AtomicBoolean()
        val relay = MediaRelay(RelayReaderFactory {
            object : RelayReader {
                override fun open(position: Long) = 100L
                override val responseHeaders = emptyMap<String, List<String>>()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reading.countDown()
                    released.await(5, TimeUnit.SECONDS)
                    return -1
                }
                override fun close() { sourceClosed.set(true); released.countDown() }
            }
        })
        val uri = URI(relay.url)
        Socket(uri.host, uri.port).use { client ->
            client.soTimeout = 3000
            client.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(reading.await(3, TimeUnit.SECONDS))
            relay.close()
            relay.close()
            assertTrue(sourceClosed.get())
            client.getInputStream().readBytes()
            assertFalse(relay.listening)
        }
    }

    @Test fun onlyThreeWorkersCanOpenMediaAndCloseReclaimsQueuedClients() {
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val opening = AtomicInteger()
        val created = AtomicInteger()
        val closed = AtomicInteger()
        val relay = MediaRelay(RelayReaderFactory {
            created.incrementAndGet()
            object : RelayReader {
                private val disposed = AtomicBoolean()
                override fun open(position: Long): Long {
                    opening.incrementAndGet()
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    return 0
                }
                override val responseHeaders = emptyMap<String, List<String>>()
                override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
                override fun close() { if (disposed.compareAndSet(false, true)) closed.incrementAndGet(); release.countDown() }
            }
        })
        val uri = URI(relay.url)
        val clients = (1..9).map {
            Socket(uri.host, uri.port).apply {
                soTimeout = 3000
                runCatching { getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\n\r\n".toByteArray()) }
            }
        }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(3, opening.get())
            relay.close()
            clients.forEach { runCatching { it.getInputStream().readBytes() } }
            assertEquals(created.get(), closed.get())
        } finally { relay.close(); clients.forEach { it.close() } }
    }

    private class MemoryFactory(private val bytes: ByteArray, private val unknownLength: Boolean = false) : RelayReaderFactory {
        val opens = AtomicInteger()
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        override fun create(): RelayReader = object : RelayReader {
            private var position = 0
            private val closed = AtomicBoolean()
            override fun open(position: Long): Long { opens.incrementAndGet(); this.position = position.toInt(); return if (unknownLength) -1 else bytes.size - position }
            override val responseHeaders = emptyMap<String, List<String>>()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads.incrementAndGet()
                if (position == bytes.size) return -1
                val count = minOf(length, bytes.size - position)
                bytes.copyInto(buffer, offset, position, position + count)
                position += count
                return count
            }
            override fun close() { if (closed.compareAndSet(false, true)) closes.incrementAndGet() }
        }
    }

    private data class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)
    private fun request(relay: MediaRelay, method: String, headers: String = "", path: String = URI(relay.url).rawPath): Response {
        val uri = URI(relay.url)
        val raw = Socket(uri.host, uri.port).use { client ->
            client.soTimeout = 3000
            client.getOutputStream().write("$method $path HTTP/1.1\r\nHost: 127.0.0.1\r\n$headers\r\n".toByteArray())
            client.getInputStream().readBytes()
        }
        val separator = "\r\n\r\n".toByteArray()
        val boundary = (0..raw.size - separator.size).first { offset -> separator.indices.all { raw[offset + it] == separator[it] } }
        val lines = raw.copyOfRange(0, boundary).toString(Charsets.US_ASCII).split("\r\n")
        return Response(lines.first().split(' ')[1].toInt(), lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }, raw.copyOfRange(boundary + 4, raw.size))
    }
}
