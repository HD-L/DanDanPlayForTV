package tv.dandan.app.remote

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class RemoteRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
object RemoteHttp {
    fun read(input: InputStream, timeoutMs: Long = 3000, nanoTime: () -> Long = System::nanoTime): RemoteRequest {
        require(timeoutMs in 1..30000) { "无效请求时限" }
        val started = nanoTime()
        fun deadline() { require(nanoTime() - started < timeoutMs * 1_000_000) { "请求读取超时" } }
        var total = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                deadline()
                val next = input.read()
                deadline()
                require(next >= 0) { "请求头不完整" }
                total++
                require(total <= 16384 && bytes.size() < 4096) { "请求头过大" }
                if (next == 10) {
                    val content = bytes.toByteArray()
                    require(content.isNotEmpty() && content.last() == 13.toByte()) { "请求头必须使用 CRLF" }
                    return String(content, 0, content.size - 1, Charsets.ISO_8859_1)
                }
                bytes.write(next)
            }
        }
        val start = line().split(' ')
        require(start.size == 3 && start[0] in listOf("GET", "POST") && start[1].startsWith('/') && start[2] in listOf("HTTP/1.0", "HTTP/1.1")) { "无效请求行" }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val index = header.indexOf(':')
            require(index > 0 && !header.first().isWhitespace()) { "无效请求头" }
            val key = header.take(index).lowercase()
            require(key !in headers) { "重复请求头" }
            headers[key] = header.substring(index + 1).trim()
        }
        require("transfer-encoding" !in headers) { "不支持分块请求" }
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: throw IllegalArgumentException("无效请求长度") } ?: 0
        require(length in 0..262144) { "请求正文过大" }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            deadline()
            val count = input.read(body, read, length - read)
            deadline()
            require(count > 0) { "请求正文不完整" }
            read += count
        }
        return RemoteRequest(start[0], start[1], headers, body.toString(Charsets.UTF_8))
    }
    fun authorized(header: String?, token: String): Boolean {
        if (token.isEmpty() || header == null || !header.startsWith("Bearer ")) return false
        return MessageDigest.isEqual(header.removePrefix("Bearer ").toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
    }
}
