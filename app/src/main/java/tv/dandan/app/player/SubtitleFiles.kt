package tv.dandan.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import tv.dandan.app.data.AppRepository
import tv.dandan.app.data.MediaDataSourceFactory
import tv.dandan.app.data.TextDecoding
import tv.dandan.core.SubtitleParser
import tv.dandan.core.SubtitleTimeline
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class PreparedSubtitle(val timeline: SubtitleTimeline, val external: ExternalSubtitle)

@UnstableApi
class SubtitleFiles(private val context: Context, private val repository: AppRepository) : Closeable {
    private val files = ConcurrentHashMap.newKeySet<File>()
    private val sources = ConcurrentHashMap.newKeySet<androidx.media3.datasource.DataSource>()
    @Volatile private var closed = false
    fun load(uri: String, name: String, encoding: String = "自动"): PreparedSubtitle {
        val bytes = read(uri, 8 * 1024 * 1024)
        val text = if (encoding == "自动") TextDecoding.decode(bytes) else SubtitleEncoding.decode(bytes, encoding)
        require(!Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "字幕不允许外部实体" }
        val ext = name.substringAfterLast('.', "").lowercase()
        val ttml = ext == "ttml" || Regex("<tt(?:\\s|>)").containsMatchIn(text)
        val ass = ext in setOf("ass", "ssa") || text.contains("[Script Info]", true)
        val mime = when { ttml -> MimeTypes.APPLICATION_TTML; ass -> MimeTypes.TEXT_SSA; text.trimStart().startsWith("WEBVTT") -> MimeTypes.TEXT_VTT; else -> MimeTypes.APPLICATION_SUBRIP }
        val timeline = if (ttml) SubtitleTimeline(emptyList()) else SubtitleTimeline(SubtitleParser.parse(text).also { require(it.isNotEmpty()) { "没有识别到字幕文字与时序" } })
        val file = File.createTempFile("字幕-", ".${if (ttml) "ttml" else if (ass) "ass" else if (mime == MimeTypes.TEXT_VTT) "vtt" else "srt"}", context.cacheDir)
        synchronized(this) {
            if (closed) { file.delete(); throw IllegalStateException("播放会话已关闭") }
            file.writeText(text, Charsets.UTF_8)
            files += file
        }
        return PreparedSubtitle(timeline, ExternalSubtitle(Uri.fromFile(file).toString(), mime, "外挂字幕", ass))
    }
    fun font(uri: String): File {
        val bytes = read(uri, 16 * 1024 * 1024)
        require(bytes.size >= 12 && (bytes.take(4) == listOf<Byte>(0, 1, 0, 0) || bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) in setOf("OTTO", "ttcf"))) { "请选择有效的 TTF / OTF 字体" }
        val directory = File(context.cacheDir, "字幕字体").apply { require(mkdirs() || isDirectory) { "无法创建字幕字体目录" } }
        val file = File.createTempFile("字幕字体-", ".ttf", directory)
        synchronized(this) {
            if (closed) { file.delete(); throw IllegalStateException("播放会话已关闭") }
            file.writeBytes(bytes)
            files += file
        }
        return file
    }
    private fun read(uri: String, budget: Int): ByteArray {
        check(!closed) { "播放会话已关闭" }
        val data = MediaDataSourceFactory(context, repository).createDataSource()
        synchronized(this) { check(!closed) { "播放会话已关闭" }; sources += data }
        return try {
            val length = data.open(DataSpec(Uri.parse(uri)))
            require(length == C.LENGTH_UNSET.toLong() || length <= budget) { "文件超过允许大小" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                check(!closed) { "播放会话已关闭" }
                val read = data.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                require(read > 0 && output.size() <= budget - read) { "文件超过允许大小或读取无进展" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally { runCatching { data.close() }; sources -= data }
    }
    override fun close() {
        synchronized(this) { closed = true; files.forEach(File::delete); files.clear() }
        sources.toList().forEach { runCatching { it.close() } }
        sources.clear()
    }
}

