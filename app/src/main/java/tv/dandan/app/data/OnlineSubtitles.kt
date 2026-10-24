package tv.dandan.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.datasource.DataSpec
import tv.dandan.core.MediaEntry
import tv.dandan.core.SubtitleHash
import java.io.File
import java.io.IOException

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class OnlineSubtitles(context: Context, private val repository: AppRepository) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "subtitles")
    private val store = SubtitleStore(this.context)
    private val cache = SubtitleCache(directory, OpenSubtitlesApi.MAX_BYTES)

    fun search(name: String, entry: MediaEntry): List<OnlineSubtitle> {
        val preferences = store.read()
        return OpenSubtitlesApi(preferences.account).search(name, preferences.language, runCatching { hash(entry) }.getOrNull())
    }
    fun download(candidate: OnlineSubtitle): String {
        return uri(cache.getOrDownload(candidate.fileId, candidate.language) {
            OpenSubtitlesApi(store.read().account).download(candidate.fileId)
        })
    }
    fun rawUri(canonicalUri: String): String? {
        val parsed = runCatching { Uri.parse(canonicalUri) }.getOrNull() ?: return null
        if (parsed.scheme != "content" || parsed.authority != context.packageName + ".subtitles" || parsed.query != null || parsed.fragment != null) return null
        val segments = parsed.pathSegments
        if (segments.size != 2 || segments[0] != "subtitles") return null
        return cache.rawForCanonical(segments[1])?.let { runCatching { uri(it) }.getOrNull() }
    }
    fun autoMatch(entry: MediaEntry): String? {
        val prefs = store.read()
        if (!prefs.autoMatch || !prefs.account.configured) return null
        val fingerprint = hash(entry) ?: return null
        val name = entry.title.substringBeforeLast('.', entry.title).take(200)
        if (name.length < 2) return null
        val matches = OpenSubtitlesApi(prefs.account).search(name, prefs.language, fingerprint).filter { it.hashMatched && it.language == prefs.language }.distinctBy { it.fileId }
        return matches.singleOrNull()?.let(::download)
    }
    private fun uri(file: File) = FileProvider.getUriForFile(context, context.packageName + ".subtitles", file).toString()
    private fun hash(entry: MediaEntry): String? {
        if (entry.directory) return null
        var size = entry.sizeBytes
        val firstSource = MediaDataSourceFactory(context, repository).createDataSource()
        val first = ByteArray(SubtitleHash.CHUNK_SIZE)
        try {
            val available = firstSource.open(DataSpec.Builder().setUri(Uri.parse(entry.uri)).build())
            if (size <= 0) size = available
            if (size < SubtitleHash.CHUNK_SIZE * 2L) return null
            readChunk(firstSource, first)
        } finally { firstSource.close() }
        val lastSource = MediaDataSourceFactory(context, repository).createDataSource()
        val last = ByteArray(SubtitleHash.CHUNK_SIZE)
        try {
            lastSource.open(DataSpec.Builder().setUri(Uri.parse(entry.uri)).setPosition(size - last.size).setLength(last.size.toLong()).build())
            readChunk(lastSource, last)
        } finally { lastSource.close() }
        return SubtitleHash.calculate(size, first, last)
    }
    private fun readChunk(source: androidx.media3.datasource.DataSource, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = source.read(buffer, offset, buffer.size - offset)
            if (count <= 0) throw IOException("视频指纹读取不完整")
            offset += count
        }
    }
}
