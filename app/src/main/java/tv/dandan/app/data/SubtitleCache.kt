package tv.dandan.app.data

import tv.dandan.core.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 原始下载与可播放的 UTF-8 文件一并保留，只有通过字幕时序验证后才发布。 */
internal class SubtitleCache(
    directory: File,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val decode: (ByteArray, String) -> String = { bytes, language -> TextDecoding.decode(bytes, preferredLanguage = language) },
) {
    private val directory = directory.canonicalFile
    private val canonicalBudget: Int

    init {
        require(maxBytes in 1..Int.MAX_VALUE / 3) { "字幕缓存大小限制无效" }
        canonicalBudget = maxBytes * 3
    }

    fun getOrDownload(fileId: Long, language: String, download: () -> ByteArray): File {
        val key = key(fileId, language)
        // 固定数量的锁避免已下载文件持续占用内存；同一目录、同一键的不同实例使用同一把锁。
        val lock = locks[Math.floorMod("${directory.path}/$key".hashCode(), locks.size)]
        return synchronized(lock) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("字幕下载已取消")
            require(directory.mkdirs() || directory.isDirectory) { "无法创建字幕缓存目录" }
            val candidates = extensions.map { File(directory, "$key.$it") }
            for (cached in candidates) {
                if (!cached.exists()) continue
                val valid = runCatching {
                    val bytes = read(safeFile(cached.name), canonicalBudget)
                    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                    validate(text)
                }.isSuccess
                if (valid) return@synchronized cached
                remove(cached)
            }
            remove(File(directory, "$key.raw"))
            val bytes = download()
            require(bytes.size in 1..maxBytes) { "字幕文件为空或超过允许大小" }
            val text = decode(bytes, language)
            validate(text)
            val extension = when {
                text.trimStart().startsWith("WEBVTT") -> "vtt"
                text.contains("[Events]", ignoreCase = true) -> "ass"
                else -> "srt"
            }
            val canonicalBytes = text.toByteArray(Charsets.UTF_8)
            require(canonicalBytes.size in 1..canonicalBudget) { "字幕缓存超过允许大小" }
            val canonical = safeFile("$key.$extension")
            val raw = safeFile("$key.raw")
            val rawTemporary = Files.createTempFile(directory.toPath(), ".$key-", ".raw.tmp").toFile()
            var canonicalTemporary: File? = null
            try {
                canonicalTemporary = Files.createTempFile(directory.toPath(), ".$key-", ".subtitle.tmp").toFile()
                write(rawTemporary, bytes)
                write(canonicalTemporary, canonicalBytes)
                move(rawTemporary, raw)
                move(canonicalTemporary, canonical)
                canonical
            } catch (error: Exception) {
                if (!canonical.exists()) remove(raw)
                throw IOException("字幕缓存保存失败", error)
            } finally {
                rawTemporary.delete()
                canonicalTemporary?.delete()
            }
        }
    }

    fun rawForCanonical(fileName: String): File? = runCatching {
        val match = canonicalName.matchEntire(fileName) ?: return@runCatching null
        val canonical = safeFile(fileName)
        val raw = safeFile("${match.groupValues[1]}.raw")
        raw.takeIf { canonical.isFile && it.isFile && it.length() in 1..maxBytes }
    }.getOrNull()

    private fun validate(text: String) {
        require(text.isNotBlank() && '\u0000' !in text && SubtitleParser.parse(text).isNotEmpty()) { "服务返回的文件没有有效字幕文字与时序" }
    }

    private fun key(fileId: Long, language: String): String {
        require(fileId > 0 && language.matches(Regex("[a-z]{2}(?:-[a-z]{2})?"))) { "字幕缓存标识无效" }
        return "$fileId-$language"
    }

    private fun safeFile(name: String): File {
        require('/' !in name && '\\' !in name) { "字幕缓存路径无效" }
        return File(directory, name).canonicalFile.also { require(it.parentFile == directory) { "字幕缓存路径超出允许范围" } }
    }

    private fun read(file: File, budget: Int): ByteArray {
        require(file.isFile && file.length() in 1..budget) { "字幕缓存为空或超过允许大小" }
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0 && output.size() <= budget - count) { "字幕缓存超过允许大小或读取无进展" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun write(file: File, bytes: ByteArray) = FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    private fun move(from: File, to: File) {
        try { Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    private fun remove(file: File) { if (file.exists() && !file.delete()) throw IOException("无法清理无效字幕缓存") }

    companion object {
        private val extensions = listOf("srt", "vtt", "ass")
        private val canonicalName = Regex("([1-9][0-9]*-[a-z]{2}(?:-[a-z]{2})?)\\.(?:srt|vtt|ass)")
        private val locks = Array(64) { Any() }
    }
}
