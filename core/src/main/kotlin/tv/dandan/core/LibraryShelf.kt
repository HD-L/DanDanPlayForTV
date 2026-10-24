package tv.dandan.core

/** 海报墙的筛选项。 */
enum class LibraryFilter(val label: String) { ALL("全部"), SERIES("剧集"), MOVIE("电影"), OTHER("其他"), UNRECOGNIZED("未识别"), HIDDEN("已隐藏") }

/** 海报墙与作品页的展示规则：筛选、排序、显示名、观看状态。 */
object LibraryShelf {
    fun matches(group: LibraryGroup, info: SeriesInfo?, hidden: Boolean, filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.ALL -> !hidden
        LibraryFilter.SERIES -> !hidden && group.kind == LibraryKind.SERIES
        LibraryFilter.MOVIE -> !hidden && group.kind == LibraryKind.MOVIE
        LibraryFilter.OTHER -> !hidden && group.kind == LibraryKind.OTHER
        LibraryFilter.UNRECOGNIZED -> !hidden && group.kind != LibraryKind.OTHER && info?.recognized != true
        LibraryFilter.HIDDEN -> hidden
    }

    fun counts(groups: List<LibraryGroup>, infos: Map<String, SeriesInfo>, hidden: Set<String>): Map<LibraryFilter, Int> =
        LibraryFilter.values().associateWith { filter -> groups.count { matches(it, infos[it.key], it.key in hidden, filter) } }

    /** 显示名：识别成功用官方作品名，否则用本地解析出的作品名（续季带“第 N 季”）。 */
    fun title(group: LibraryGroup, info: SeriesInfo?): String = info?.takeIf { it.recognized && it.title.isNotBlank() }?.title ?: group.displayTitle

    /** 海报墙按显示名的自然顺序排列。 */
    fun sorted(groups: List<LibraryGroup>, infos: Map<String, SeriesInfo>): List<LibraryGroup> =
        groups.sortedWith { a, b -> LibraryRules.naturalCompare(title(a, infos[a.key]), title(b, infos[b.key])) }

    /** 首页“我的片库”：最近看过的作品在前，其余按名称；不含个人视频与隐藏的作品。 */
    fun recent(groups: List<LibraryGroup>, infos: Map<String, SeriesInfo>, hidden: Set<String>, history: Map<String, PlaybackProgress>, limit: Int = 30): List<LibraryGroup> {
        val visible = groups.filter { it.kind != LibraryKind.OTHER && it.key !in hidden }
        val lastPlayed = visible.associate { group -> group.key to (group.items().maxOfOrNull { history[it.entry.id]?.updatedAtMs ?: 0L } ?: 0L) }
        val (played, rest) = visible.partition { (lastPlayed[it.key] ?: 0L) > 0L }
        return (played.sortedByDescending { lastPlayed[it.key] } + sorted(rest, infos)).take(limit)
    }

    /** 某个视频是否看完：本机播放进度达到 95%，或对应的官方剧集被标记为已看。 */
    fun watched(entry: MediaEntry, history: Map<String, PlaybackProgress>, bindings: Map<String, Long>, watchedEpisodes: Set<Long>): Boolean =
        LibraryGrouping.watched(history[entry.id]) || bindings[entry.id]?.let { it in watchedEpisodes } == true

    /** 剧集行的标签：“01”“12.5”，特别篇“SP01”。 */
    fun label(item: LibraryItem, special: Boolean): String = if (special) "SP" + item.label.ifEmpty { "" } else item.label
}
