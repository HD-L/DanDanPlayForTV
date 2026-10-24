package tv.dandan.core

import java.io.Serializable

enum class SourceKind { DOCUMENT_TREE, HTTP, WEBDAV, SMB, FTP }
enum class PlaybackEngine { MEDIA3, VLC }
enum class DanmakuLanguage { ORIGINAL, SIMPLIFIED, TRADITIONAL }

data class MediaSource(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val location: String,
    val username: String = "",
    val password: String = "",
) : Serializable

data class MediaEntry(
    val id: String,
    val title: String,
    val uri: String,
    val directory: Boolean = false,
    val sourceId: String = "",
    val sizeBytes: Long = 0,
    val subtitleUri: String = "",
    val danmakuUri: String = "",
) : Serializable

data class PlaybackProgress(
    val media: MediaEntry,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

data class AppSettings(
    val danmakuEnabled: Boolean = true,
    val danmakuSizeSp: Float = 24f,
    val danmakuAlpha: Float = 0.85f,
    val danmakuDurationMs: Long = 8000,
    val danmakuStroke: Float = 2f,
    val danmakuMaxLines: Int = 8,
    val danmakuMaxCount: Int = 60,
    val showScrolling: Boolean = true,
    val showTop: Boolean = true,
    val showBottom: Boolean = true,
    val blockedKeywords: List<String> = emptyList(),
    val blockedPatterns: List<String> = emptyList(),
    val subtitleSizeSp: Float = 24f,
    val subtitleColor: Int = -1,
    val subtitleDelayMs: Long = 0,
    val playbackSpeed: Float = 1f,
    val playbackEngine: PlaybackEngine = PlaybackEngine.MEDIA3,
    val subtitleStroke: Float = 2f,
    val cloudDanmakuBlock: Boolean = true,
    val danmakuLanguage: DanmakuLanguage = DanmakuLanguage.ORIGINAL,
    /** 第三方资源搜索节点地址（用户自填，http(s)://host[:port]），空表示未配置。 */
    val magnetNode: String = "",
    /** HTTP / WebDAV 来源用几个连接分段并行下载；个别服务器不支持时可关闭。 */
    val multiConnection: Boolean = true,
) : Serializable

enum class DanmakuMode { SCROLL, TOP, BOTTOM }
data class DanmakuComment(val timeMs: Long, val text: String, val color: Int = -1, val mode: DanmakuMode = DanmakuMode.SCROLL)
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)
data class ApiCredentials(val appId: String = "", val appSecret: String = "") {
    val configured: Boolean get() = appId.isNotBlank() && appSecret.isNotBlank()
}
data class AnimeSummary(val id: String, val title: String, val imageUrl: String = "", val description: String = "", val airDay: Int = -1, val rating: String = "")
data class AnimeSeason(val year: Int, val month: Int, val name: String)
data class AnimeEpisode(val id: Long, val title: String, val number: String = "")
data class AnimeTag(val id: Int, val name: String)
data class AnimeDetail(val anime: AnimeSummary, val episodes: List<AnimeEpisode>, val relateds: List<AnimeSummary> = emptyList(), val similars: List<AnimeSummary> = emptyList(), val tags: List<AnimeTag> = emptyList(), val typeDescription: String = "", val titles: List<String> = emptyList())
data class EpisodeMatch(val episodeId: Long, val animeTitle: String, val episodeTitle: String, val shiftMs: Long = 0, val exact: Boolean = false, val animeId: Long = 0, val imageUrl: String = "")
data class EpisodeBinding(val episodeId: Long, val shiftMs: Long = 0)

/** 资源搜索节点返回的一条磁链资源。 */
data class MagnetResource(val title: String, val magnet: String, val typeName: String = "", val subgroupName: String = "", val size: String = "", val publishDate: String = "", val pageUrl: String = "") : Serializable
data class MagnetOption(val id: Int, val name: String)
data class MagnetSearchPage(val items: List<MagnetResource>, val hasMore: Boolean)
/** 种子内的一个文件。 */
data class TorrentFile(val index: Int, val name: String, val sizeBytes: Long, val subPath: String = "") : Serializable
