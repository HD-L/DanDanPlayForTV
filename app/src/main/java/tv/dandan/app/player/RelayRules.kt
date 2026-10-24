package tv.dandan.app.player

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal class RelayHttpException(val status: Int, val contentRange: String? = null) : IOException("媒体转发请求无效（HTTP $status）")
internal data class RelayRequest(val method: String, val range: String?)
internal data class RelayWindow(val status: Int, val position: Long, val length: Long?, val contentRange: String?)

/** 仅接受当前媒体的单范围请求，不允许客户端指定另一媒体地址。 */
internal object RelayRules {
    private sealed interface Range {
        data class Span(val start: Long, val end: Long?) : Range
        data class Suffix(val count: Long) : Range
    }

    fun readRequest(input: InputStream, expectedPath: String, nanoTime: () -> Long = System::nanoTime): RelayRequest {
        val started = nanoTime()
        var total = 0
        fun line(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                if (nanoTime() - started >= 3_000_000_000L) throw RelayHttpException(408)
                val next = input.read()
                if (nanoTime() - started >= 3_000_000_000L) throw RelayHttpException(408)
                if (next < 0) throw RelayHttpException(400)
                if (++total > 16384 || bytes.size() >= 4096) throw RelayHttpException(431)
                if (next == 10) {
                    val raw = bytes.toByteArray()
                    if (raw.isEmpty() || raw.last() != 13.toByte()) throw RelayHttpException(400)
                    return String(raw, 0, raw.size - 1, Charsets.ISO_8859_1)
                }
                if (next == 0 || (next < 32 && next != 13 && next != 9) || next == 127) throw RelayHttpException(400)
                bytes.write(next)
            }
        }
        val start = line().split(' ')
        if (start.size != 3 || start[2] !in listOf("HTTP/1.0", "HTTP/1.1")) throw RelayHttpException(400)
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon <= 0 || !header.take(colon).matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) throw RelayHttpException(400)
            val name = header.take(colon).lowercase()
            val value = header.substring(colon + 1).trim()
            if ('\r' in value || name in headers) throw RelayHttpException(400)
            headers[name] = value
        }
        if ("transfer-encoding" in headers || "expect" in headers) throw RelayHttpException(400)
        headers["content-length"]?.let { if (!it.matches(Regex("0+"))) throw RelayHttpException(400) }
        if (start[0] !in listOf("GET", "HEAD")) throw RelayHttpException(405)
        if (start[1] != expectedPath) throw RelayHttpException(404)
        return RelayRequest(start[0], headers["range"])
    }

    fun initialPosition(header: String?): Long = (parseRange(header) as? Range.Span)?.start ?: 0

    fun resolve(header: String?, totalLength: Long?): RelayWindow {
        require(totalLength == null || totalLength >= 0) { "媒体长度无效" }
        if (header == null) return RelayWindow(200, 0, totalLength, null)
        val unsatisfied = totalLength?.let { "bytes */$it" }
        val range = try { parseRange(header) } catch (_: RelayHttpException) { throw RelayHttpException(416, unsatisfied) }
        if (totalLength == null || totalLength == 0L) throw RelayHttpException(416, unsatisfied)
        val (start, end) = when (range) {
            is Range.Span -> range.start to minOf(range.end ?: totalLength - 1, totalLength - 1)
            is Range.Suffix -> (totalLength - minOf(range.count, totalLength)) to (totalLength - 1)
            null -> throw RelayHttpException(416, unsatisfied)
        }
        if (start >= totalLength) throw RelayHttpException(416, unsatisfied)
        return RelayWindow(206, start, end - start + 1, "bytes $start-$end/$totalLength")
    }

    /** open 未限制读取长度时，返回值是当前位置之后的长度；HTTP 的总长优先取 Content-Range。 */
    fun totalLength(openLength: Long, position: Long, headers: Map<String, List<String>>): Long? {
        val values = headers.entries.filter { it.key.equals("Content-Range", true) }.flatMap { it.value }
        if (values.isNotEmpty()) {
            if (values.size != 1) return null
            val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)", RegexOption.IGNORE_CASE).matchEntire(values.single().trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val size = match.groupValues[3].toLongOrNull() ?: return null
            return size.takeIf { start == position && end >= start && end < size }
        }
        return if (openLength >= 0 && position >= 0 && openLength <= Long.MAX_VALUE - position) position + openLength else null
    }

    private fun parseRange(header: String?): Range? {
        if (header == null) return null
        val match = Regex("bytes=([0-9]*)-([0-9]*)", RegexOption.IGNORE_CASE).matchEntire(header.trim()) ?: throw RelayHttpException(416)
        val left = match.groupValues[1]
        val right = match.groupValues[2]
        if (left.isEmpty()) return Range.Suffix(right.toLongOrNull()?.takeIf { it > 0 } ?: throw RelayHttpException(416))
        val start = left.toLongOrNull() ?: throw RelayHttpException(416)
        val end = if (right.isEmpty()) null else right.toLongOrNull()?.takeIf { it >= start } ?: throw RelayHttpException(416)
        return Range.Span(start, end)
    }
}
