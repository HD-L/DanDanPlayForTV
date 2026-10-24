package tv.dandan.app.data

import java.io.IOException

internal data class ReadWindow(val remaining: Long, val openLength: Long)
internal class PositionOutsideMediaException : IOException("读取位置超过文件长度")
internal object ReadWindows {
    fun resolve(position: Long, requested: Long, total: Long): ReadWindow {
        require(position >= 0 && requested >= -1 && total >= -1) { "读取范围无效" }
        if (total >= 0 && position > total) throw PositionOutsideMediaException()
        val available = if (total >= 0) total - position else -1
        val remaining = if (requested < 0) available else if (available < 0) requested else minOf(requested, available)
        return ReadWindow(remaining, if (requested >= 0) requested else remaining)
    }
    fun http(status: Int, contentRange: String?, contentLength: Long, position: Long, requested: Long): ReadWindow {
        if (status == 416) {
            val total = contentRange?.let { Regex("bytes \\*/(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() } ?: throw IOException("媒体读取范围无效")
            if (position > total) throw PositionOutsideMediaException()
            if (position != total) throw IOException("媒体服务拒绝了有效的读取范围")
            return resolve(position, requested, total)
        }
        if (status !in 200..299) throw IOException("媒体读取失败（HTTP $status）")
        if (status == 206) {
            val range = contentRange?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(it) } ?: throw IOException("媒体服务返回了无效的读取范围")
            val start = range.groupValues[1].toLongOrNull() ?: throw IOException("读取范围无效")
            val end = range.groupValues[2].toLongOrNull() ?: throw IOException("读取范围无效")
            val total = range.groupValues[3].toLongOrNull()
            if (total != null && position > total) throw PositionOutsideMediaException()
            if (start != position || end < start || end == Long.MAX_VALUE || (total != null && end >= total)) throw IOException("媒体服务返回了错误的读取位置")
            val advertised = end - start + 1
            if (contentLength >= 0 && contentLength != advertised) throw IOException("媒体服务内容长度与读取范围不一致")
            return ReadWindow(if (requested < 0) advertised else minOf(requested, advertised), if (requested < 0) advertised else requested)
        }
        if (position > 0) {
            if (contentLength >= 0 && position >= contentLength) return resolve(position, requested, contentLength)
            throw IOException("媒体服务不支持 HTTP 随机定位")
        }
        return resolve(0, requested, contentLength)
    }
}
