package tv.dandan.app.data

import tv.dandan.core.MediaEntry
import tv.dandan.core.TorrentFile
import java.io.File

/** 磁链媒体条目：稳定编号由 info hash + 文件索引生成，续播时据此重新启动任务。 */
object MagnetPlayback {
    const val SCHEME = "magnet-play"
    fun entryId(hash: String, index: Int): String = "magnet:$hash:$index"
    /** 构造可写入观看历史的条目；uri 为占位地址，播放前由 [resolve] 换成引擎本机地址。 */
    fun entry(hash: String, file: TorrentFile, title: String = file.name): MediaEntry =
        MediaEntry(entryId(hash, file.index), title.take(300), "$SCHEME://$hash/${file.index}", sizeBytes = file.sizeBytes)
    fun isMagnet(entry: MediaEntry): Boolean = entry.uri.startsWith("$SCHEME://")
    private fun parse(uri: String): Pair<String, Int>? = Regex("^$SCHEME://([0-9a-f]{40})/(\\d+)$").find(uri)?.let { it.groupValues[1] to it.groupValues[2].toInt() }

    /** 在后台线程调用：确保种子存在、启动下载并返回替换为本机播放地址的条目。 */
    fun resolve(engine: MagnetEngine, entry: MediaEntry): MediaEntry {
        val (hash, index) = parse(entry.uri) ?: throw IllegalArgumentException("磁链播放记录无效")
        val torrent: File = engine.fetchTorrent("magnet:?xt=urn:btih:$hash")
        val files = engine.torrentFiles(torrent)
        val url = engine.startPlay(torrent, files, index)
        return entry.copy(uri = url)
    }
}
