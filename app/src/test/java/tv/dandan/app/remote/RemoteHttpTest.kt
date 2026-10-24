package tv.dandan.app.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteHttpTest {
    @Test fun acceptsLengthDelimitedUtf8Json() {
        val body = "{\"title\":\"第一集\"}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = RemoteHttp.read(("POST /api/open HTTP/1.1\r\nAuthorization: Bearer abc\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes).inputStream())
        assertEquals("POST",request.method)
        assertEquals(body,request.body)
        assertEquals("Bearer abc",request.headers["authorization"])
    }
    @Test fun rejectsLargeBodyBeforeReading() {
        try {
            RemoteHttp.read("POST /api/input HTTP/1.1\r\nContent-Length: 262145\r\n\r\n".byteInputStream())
            fail("应在读取正文前拒绝超限")
        } catch (e: IllegalArgumentException) { assertEquals("请求正文过大", e.message) }
    }
    @Test fun completeAllowedChineseRulesFitInFormTransport() {
        val keywords = (1..200).joinToString("\n") { "词".repeat(197) + it.toString().padStart(3, '0') }
        val patterns = (1..100).joinToString("\n") { "字".repeat(253) + it.toString().padStart(3, '0') }
        val body = "{\"keywords\":\"$keywords\",\"patterns\":\"$patterns\"}".replace("\n", "\\n")
        val bytes = body.toByteArray(Charsets.UTF_8)
        val parsed = RemoteHttp.read(("POST /api/input HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray() + bytes).inputStream())
        assertEquals(body, parsed.body)
    }
    @Test fun rejectsIncompleteBody() {
        assertRejected("POST /api/control HTTP/1.1\r\nContent-Length: 20\r\n\r\n{}")
    }
    @Test fun rejectsAmbiguousDuplicateLengthAndChunkedEncoding() {
        assertRejected("POST /api/control HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 5\r\n\r\n{}")
        assertRejected("POST /api/control HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")
    }
    @Test fun tokenMustMatchExactly() {
        assertFalse(RemoteHttp.authorized(null,"abc"))
        assertFalse(RemoteHttp.authorized("Bearer abcd","abc"))
        assertFalse(RemoteHttp.authorized("Bearer abc",""))
        assertTrue(RemoteHttp.authorized("Bearer abc","abc"))
    }
    @Test fun trickleBytesCannotKeepRequestAlivePastTotalDeadline() {
        var nanos = 0L
        try {
            RemoteHttp.read("GET / HTTP/1.1\r\n\r\n".byteInputStream(), timeoutMs = 2) { nanos += 1_000_000; nanos }
            fail("逐字节延迟必须受总请求时限约束")
        } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("超时")) }
    }
    private fun assertRejected(value: String) {
        try { RemoteHttp.read(value.byteInputStream()); fail("必须拒绝无效请求") }
        catch (e: IllegalArgumentException) { assertFalse(e.message.isNullOrBlank()) }
    }
}
