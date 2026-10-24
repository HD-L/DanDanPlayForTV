package tv.dandan.app.player

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class RelayRulesTest {
    @Test fun explicitOpenEndedAndSuffixRangesStayInsideMedia() {
        assertEquals(RelayWindow(206, 10, 10, "bytes 10-19/100"), RelayRules.resolve("bytes=10-19", 100))
        assertEquals(RelayWindow(206, 90, 10, "bytes 90-99/100"), RelayRules.resolve("bytes=90-", 100))
        assertEquals(RelayWindow(206, 80, 20, "bytes 80-99/100"), RelayRules.resolve("bytes=-20", 100))
        assertEquals(RelayWindow(206, 0, 100, "bytes 0-99/100"), RelayRules.resolve("bytes=-999", 100))
        assertEquals(RelayWindow(206, 90, 10, "bytes 90-99/100"), RelayRules.resolve("bytes=90-999", 100))
    }

    @Test fun invalidMultipleAndUnsatisfiableRangesReturn416() {
        listOf("bytes=100-", "bytes=20-10", "bytes=-0", "bytes=1-2,4-5", "items=0-1", "bytes=+1-2", "bytes=0-9223372036854775808").forEach { range ->
            val error = assertThrows(RelayHttpException::class.java) { RelayRules.resolve(range, 100) }
            assertEquals(416, error.status)
            assertEquals("bytes */100", error.contentRange)
        }
        assertEquals("bytes */0", assertThrows(RelayHttpException::class.java) { RelayRules.resolve("bytes=0-", 0) }.contentRange)
    }

    @Test fun unknownLengthStreamsOnlyTheWholeMediaWithoutFalseRangeMetadata() {
        assertEquals(RelayWindow(200, 0, null, null), RelayRules.resolve(null, null))
        listOf("bytes=0-9", "bytes=5-", "bytes=-10").forEach { range ->
            val error = assertThrows(RelayHttpException::class.java) { RelayRules.resolve(range, null) }
            assertEquals(416, error.status)
            assertNull(error.contentRange)
        }
        assertEquals(RelayWindow(200, 0, 0, null), RelayRules.resolve(null, 0))
        assertEquals(RelayWindow(206, Long.MAX_VALUE - 1, 1, "bytes ${Long.MAX_VALUE - 1}-${Long.MAX_VALUE - 1}/${Long.MAX_VALUE}"), RelayRules.resolve("bytes=${Long.MAX_VALUE - 1}-", Long.MAX_VALUE))
    }

    @Test fun responseContentRangeDeterminesTotalInsteadOfRemainingBytes() {
        assertEquals(100L, RelayRules.totalLength(10, 90, mapOf("content-range" to listOf("bytes 90-99/100"))))
        assertEquals(100L, RelayRules.totalLength(10, 90, emptyMap()))
        assertEquals(100L, RelayRules.totalLength(-1, 0, mapOf("Content-Range" to listOf("bytes 0-9/100"))))
        assertNull(RelayRules.totalLength(-1, 0, emptyMap()))
        assertNull(RelayRules.totalLength(10, Long.MAX_VALUE - 1, emptyMap()))
        assertNull(RelayRules.totalLength(-1, 0, mapOf("Content-Range" to listOf("bytes 0-9/*"))))
    }

    @Test fun onlyTheExactTokenPathCanReadMedia() {
        assertEquals("GET", read("GET /secret HTTP/1.1\r\nHost: localhost\r\nRange: bytes=0-9\r\n\r\n").method)
        assertEquals("HEAD", read("HEAD /secret HTTP/1.0\r\n\r\n").method)
        listOf("/secret?uri=file", "/secret/../x", "http://localhost/secret", "/%73ecret", "/other").forEach { path ->
            assertEquals(404, assertThrows(RelayHttpException::class.java) { read("GET $path HTTP/1.1\r\n\r\n") }.status)
        }
    }

    @Test fun requestBodiesDuplicatesUnsupportedMethodsAndOversizedHeadersAreRejected() {
        listOf(
            "GET /secret HTTP/1.1\r\nContent-Length: 1\r\n\r\nx",
            "GET /secret HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n",
            "GET /secret HTTP/1.1\r\nRange: bytes=0-1\r\nrange: bytes=2-3\r\n\r\n",
            "GET /secret HTTP/1.1\r\n folded: bad\r\n\r\n",
            "GET /secret HTTP/1.1\n\n",
        ).forEach { wire -> assertEquals(400, assertThrows(RelayHttpException::class.java) { read(wire) }.status) }
        assertEquals(405, assertThrows(RelayHttpException::class.java) { read("POST /secret HTTP/1.1\r\n\r\n") }.status)
        assertEquals(431, assertThrows(RelayHttpException::class.java) { read("GET /secret HTTP/1.1\r\nX: ${"a".repeat(4096)}\r\n\r\n") }.status)
        val many = (1..80).joinToString("") { "X-$it: ${"a".repeat(300)}\r\n" }
        assertEquals(431, assertThrows(RelayHttpException::class.java) { read("GET /secret HTTP/1.1\r\n$many\r\n") }.status)
    }

    private fun read(wire: String): RelayRequest = RelayRules.readRequest(ByteArrayInputStream(wire.toByteArray(Charsets.ISO_8859_1)), "/secret")
}
