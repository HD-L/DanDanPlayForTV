package tv.dandan.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import tv.dandan.core.AnimeSummary
import tv.dandan.core.BackupSnapshot
import tv.dandan.core.MediaEntry
import tv.dandan.core.MediaSource
import tv.dandan.core.AnimeSeason

/** 页面路由。每个路由有稳定的 key，用于恢复焦点与取消过期的异步结果。 */
sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data object Library : Route { override val key = "library" }
    data object Anime : Route { override val key = "anime" }
    data object Settings : Route { override val key = "settings" }

    data class Directory(val source: MediaSource, val directory: String = source.location, val name: String = source.name) : Route { override val key = "directory:${source.id}:$directory" }
    data object Favorites : Route { override val key = "favorites" }
    /** 片库作品页：选择剧集播放；[groupKey] 是片库分组的稳定键。 */
    data class LibrarySeries(val groupKey: String, val title: String) : Route { override val key = "library-series:$groupKey" }
    /** 为片库作品手动指定弹弹play 作品。 */
    data class LibraryMatch(val groupKey: String, val title: String, val keyword: String) : Route { override val key = "library-match:$groupKey" }
    data object LibraryIndex : Route { override val key = "library-index" }
    data class SourceActions(val source: MediaSource) : Route { override val key = "source-actions:${source.id}" }
    data class SourceEditor(val source: MediaSource) : Route { override val key = "source-editor:${source.id}" }
    data class MediaActions(val entry: MediaEntry, val queue: List<MediaEntry>) : Route { override val key = "media-actions:${entry.id}" }
    data class MatchDanmaku(val entry: MediaEntry) : Route { override val key = "match:${entry.id}" }
    data class AttachmentUrl(val title: String, val entryId: String) : Route { override val key = "attachment-url:$entryId:$title" }
    data class SubtitleSearch(val entry: MediaEntry, val chosen: (String) -> Unit) : Route { override val key = "subtitle-search:${entry.id}" }
    data object VideoUrl : Route { override val key = "video-url" }

    data class AnimeSearch(val bindingMedia: MediaEntry? = null) : Route { override val key = "anime-search:${bindingMedia?.id.orEmpty()}" }
    data object AnimeSeasons : Route { override val key = "anime-seasons" }
    data class AnimeSeasonList(val season: AnimeSeason) : Route { override val key = "anime-season:${season.year}:${season.month}" }
    data class AnimeDetail(val anime: AnimeSummary, val bindingMedia: MediaEntry? = null) : Route { override val key = "anime-detail:${anime.id}:${bindingMedia?.id.orEmpty()}" }
    data class AnimeTagSearch(val tag: String) : Route { override val key = "anime-tag:$tag" }
    data object AnimeFollows : Route { override val key = "anime-follows" }
    /** 资源搜索；从剧集进入时携带番剧与剧集，以便播放后绑定官方弹幕。 */
    data class MagnetSearch(val keyword: String, val anime: AnimeSummary? = null, val episode: tv.dandan.core.AnimeEpisode? = null) : Route { override val key = "magnet-search:${anime?.id.orEmpty()}:${episode?.id ?: 0}:$keyword" }
    data class TorrentFiles(val magnet: String, val title: String, val anime: AnimeSummary? = null, val episode: tv.dandan.core.AnimeEpisode? = null) : Route { override val key = "torrent:${tv.dandan.core.MagnetRules.infoHash(magnet).orEmpty()}" }
    data object MagnetSettings : Route { override val key = "magnet-settings" }

    data class Preference(val section: PreferenceSection) : Route { override val key = "preference:${section.name}" }
    data object BlockSettings : Route { override val key = "block-settings" }
    data object Credentials : Route { override val key = "credentials" }
    data object CloudBlock : Route { override val key = "cloud-block" }
    data object Remote : Route { override val key = "remote" }
    data object SubtitleSettings : Route { override val key = "subtitle-settings" }
    data object DataManagement : Route { override val key = "data-management" }
    data class BackupPreview(val snapshot: BackupSnapshot, val encrypted: Boolean) : Route { override val key = "backup-preview" }
    data object BackupRestored : Route { override val key = "backup-restored" }
    data object Capabilities : Route { override val key = "capabilities" }
    data class Confirm(val title: String, val message: String, val confirmLabel: String, val destructive: Boolean = false, val operation: () -> Unit, val afterwards: () -> Unit = {}) : Route { override val key = "confirm:$title" }
}

enum class PreferenceSection(val chinese: String) { PLAYBACK("播放"), DANMAKU("弹幕"), SUBTITLE("字幕") }

/** 四个一级栏目各自维护返回栈；切换栏目清空栈。 */
class Navigator(initialTab: Int = 0) {
    val tabs = listOf(Route.Home, Route.Library, Route.Anime, Route.Settings)
    var tab by mutableIntStateOf(initialTab.coerceIn(0, 3))
        private set
    val stack = mutableStateListOf<Route>(tabs[tab])
    /** 单调递增，用于 AnimatedContent 判断前进/后退方向。 */
    var direction by mutableIntStateOf(1)
        private set
    val current: Route get() = stack.last()
    val canPop: Boolean get() = stack.size > 1

    /** [fromRail] 为 true 表示焦点在导航栏上切换；否则是内容区里的操作，新页面应接管焦点。 */
    fun switchTab(index: Int, fromRail: Boolean = false) {
        if (!fromRail) FocusGate.pendingContentFocus = true
        val next = index.coerceIn(0, 3)
        direction = if (next >= tab) 1 else -1
        tab = next
        stack.clear(); stack += tabs[next]
    }
    fun push(route: Route) { FocusGate.pendingContentFocus = true; direction = 1; stack += route }
    fun pop(): Boolean {
        if (!canPop) return false
        FocusGate.pendingContentFocus = true
        direction = -1
        stack.removeAt(stack.lastIndex)
        return true
    }
    /** 返回到栏目根页。 */
    fun popToRoot() { FocusGate.pendingContentFocus = true; direction = -1; while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun replace(route: Route) { FocusGate.pendingContentFocus = true; direction = 1; stack.removeAt(stack.lastIndex); stack += route }
}
