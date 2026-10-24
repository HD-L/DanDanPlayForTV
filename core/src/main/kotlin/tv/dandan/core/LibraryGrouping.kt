package tv.dandan.core

import java.util.Locale

enum class LibraryKind { SERIES, MOVIE, OTHER }

/** 片库中的一个视频及其解析结果；[number] 为集号（特别篇为 SP 序号），附加内容可能为空。 */
data class LibraryItem(val entry: MediaEntry, val parsed: ParsedMediaName, val number: Double?) {
    val label: String get() = number?.let { n -> if (n % 1.0 == 0.0) String.format(Locale.ROOT, "%02d", n.toInt()) else n.toString() }.orEmpty()
}

/**
 * 片库中的一部作品：剧集按集号排列，特别篇与附加内容（无字幕 OP/ED、菜单、PV 等）分开存放。
 * [key] 只由作品名、季与年份推出，重新扫描后保持不变，用于保存识别结果与隐藏状态。
 */
data class LibraryGroup(
    val key: String,
    val kind: LibraryKind,
    val title: String,
    val altTitles: List<String>,
    val season: Int,
    val year: Int?,
    val episodes: List<LibraryItem>,
    val specials: List<LibraryItem>,
    val extras: List<LibraryItem>,
    val folder: String,
) {
    /** 连播队列：正片在前，特别篇在后。 */
    val playable: List<MediaEntry> get() = (episodes + specials).map { it.entry }
    val count: Int get() = episodes.size + specials.size
    val displayTitle: String get() = if (kind == LibraryKind.SERIES && season > 1 && !title.contains("第") && !title.contains("Season", ignoreCase = true)) "$title 第${season}季" else title
    val searchKeywords: List<String> get() = (listOf(title) + altTitles).filter { it.length >= 2 }.distinct()
    fun items(): List<LibraryItem> = episodes + specials + extras
}

/** 按作品归并扫描得到的视频，规则见各步骤注释。纯函数，便于单元测试。 */
object LibraryGrouping {
    private val extrasFolder = Regex("(?i)^(?:extras?|bonus|menus?|pv|cm|nc|ncop|nced|ncop\\s*[&+]\\s*nced|nced\\s*[&+]\\s*ncop|nc\\s*op\\s*[&+]\\s*nc\\s*ed|特典|映像特典|special features|featurettes|interviews?|trailers?|scans|cds?|fonts?|samples?)$")
    private val specialsFolder = Regex("(?i)^(?:sps?|specials?|ova|oad|特别篇|番外)$")
    private val seasonFolder = Regex("(?i)^(?:season\\s*(\\d{1,2})|s(\\d{1,2})|第\\s*(\\d{1,2})\\s*季)$")
    /** 通用的存放目录（下载、视频、动画……），目录名不能代表作品。 */
    private val genericFolder = Regex("(?i)^(?:downloads?|dowloads?|download_?\\w*|video|videos|movies?|films?|tv|tv\\s*shows?|series|anime|animes?|media|share|shared|public|nas|home|mnt|volume\\d*|disk\\d*|hdd\\d*|usb\\d*|下载|视频|影视|电影|电视剧|剧集|动画|动漫|番剧|新番|媒体|共享|迅雷下载|百度网盘)$")

    private class Info(val entry: MediaEntry, val parsed: ParsedMediaName, val folders: List<String>) {
        val folderPath = folders.joinToString("/")
    }

    private class Builder(val key: String, val kind: LibraryKind, var title: String, val season: Int, val year: Int?) {
        val alt = linkedSetOf<String>()
        val episodes = mutableListOf<Pair<Info, Double?>>()
        val specials = mutableListOf<Pair<Info, Double?>>()
        val extras = mutableListOf<Info>()
        fun infos() = episodes.map { it.first } + specials.map { it.first } + extras
        fun maxSize() = (episodes + specials).maxOfOrNull { it.first.entry.sizeBytes } ?: 0L
    }

    fun group(entries: List<MediaEntry>): List<LibraryGroup> {
        val infos = entries.asSequence().filter { !it.directory }.distinctBy { it.id }
            .map { Info(it, MediaNameParser.parse(it.title), MediaNameParser.folderSegments(it.uri)) }.toList()
        val folderParse = HashMap<String, ParsedMediaName>()
        // 目录名可能含点（Show.S01），加一个假扩展名防止被当成文件扩展名截掉
        fun parsedFolder(name: String) = folderParse.getOrPut(name) { MediaNameParser.parse("$name.dir") }
        fun inExtrasFolder(info: Info) = info.folders.takeLast(2).any { extrasFolder.matches(it.trim()) }
        fun inSpecialsFolder(info: Info) = info.folders.lastOrNull()?.trim()?.let { specialsFolder.matches(it) } == true
        /** 自下而上跳过“Season 1 / SPs / Extras”之类的目录，找到能代表作品名的目录。 */
        fun folderContext(info: Info): Pair<ParsedMediaName?, Int?> {
            var season: Int? = null
            for (name in info.folders.asReversed()) {
                val trimmed = name.trim()
                seasonFolder.matchEntire(trimmed)?.let { m -> if (season == null) season = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).toIntOrNull(); return@let }
                if (seasonFolder.matches(trimmed) || extrasFolder.matches(trimmed) || specialsFolder.matches(trimmed)) continue
                val parsed = parsedFolder(trimmed)
                if (parsed.title.isNotBlank()) return parsed to (season ?: parsed.season)
            }
            return null to season
        }
        fun titleOf(info: Info): Triple<String, Int?, Boolean> {
            if (info.parsed.title.isNotBlank()) return Triple(info.parsed.title, info.parsed.season ?: folderContext(info).second, false)
            val (folder, season) = folderContext(info)
            return Triple(folder?.title ?: info.folders.lastOrNull().orEmpty().ifBlank { PlayerUiRules.displayTitle(info.entry.title) }, info.parsed.season ?: season, true)
        }

        val series = linkedMapOf<String, Builder>()
        val leftovers = mutableListOf<Info>()
        val pendingSpecials = mutableListOf<Info>()
        val pendingExtras = mutableListOf<Info>()

        // 1. 正片：有集号且不是附加内容
        for (info in infos) {
            val extra = info.parsed.extra || inExtrasFolder(info)
            val special = !extra && (info.parsed.special || inSpecialsFolder(info))
            when {
                extra -> pendingExtras += info
                special -> pendingSpecials += info
                info.parsed.episode != null -> {
                    val (title, season, _) = titleOf(info)
                    val number = season ?: 1
                    val key = "s:${MediaNameParser.normalize(title)}:$number"
                    val builder = series.getOrPut(key) { Builder(key, LibraryKind.SERIES, title, number, info.parsed.year) }
                    info.parsed.altTitles.forEach(builder.alt::add)
                    builder.episodes += info to info.parsed.episode
                }
                else -> leftovers += info
            }
        }
        fun seriesFor(normalized: String, season: Int?): Builder? {
            val candidates = series.values.filter { it.kind == LibraryKind.SERIES && MediaNameParser.normalize(it.title) == normalized }
            return candidates.firstOrNull { it.season == season } ?: candidates.minByOrNull { it.season }
        }
        // 2. 特别篇：并入同名作品（取最小季）；没有同名正片时单独成作品
        for (info in pendingSpecials) {
            val (title, _, _) = titleOf(info)
            val normalized = MediaNameParser.normalize(title)
            val target = seriesFor(normalized, null) ?: series.getOrPut("s:$normalized:0") { Builder("s:$normalized:0", LibraryKind.SERIES, title, 0, info.parsed.year) }
            target.specials += info to info.parsed.episode
        }
        // 3. 附加内容：并入同名作品；否则并入所在目录（或其上一级）里的作品；都没有时按普通视频处理
        fun folderOwner(path: String): Builder? = series.values.firstOrNull { b -> (b.episodes + b.specials).any { it.first.folderPath == path } }
        for (info in pendingExtras) {
            val (title, season, _) = titleOf(info)
            val target = seriesFor(MediaNameParser.normalize(title), season)
                ?: folderOwner(info.folderPath)
                ?: folderOwner(info.folders.dropLast(1).joinToString("/"))
            if (target != null) target.extras += info else leftovers += info
        }

        // 4. 其余视频按目录处理：带年份或发布标签的是影片；同目录的小文件归为该目录作品的附加内容；其余合成“其他”
        val movies = linkedMapOf<String, Builder>()
        val others = mutableListOf<Builder>()
        for ((folder, list) in leftovers.groupBy { it.folderPath }) {
            // 带发布标签的是影片；只有年份时，同目录里同名同年份的视频不超过两个（上下两部分）才算影片，
            // 避免把一批同名的录像当成一部电影
            fun nameKey(info: Info) = MediaNameParser.normalize(titleOf(info).first) + ":" + info.parsed.year
            val sameName = list.groupingBy(::nameKey).eachCount()
            val movieLike = list.filter { it.parsed.tagged || (it.parsed.year != null && (sameName[nameKey(it)] ?: 0) <= 2) }
            for (info in movieLike) {
                val (title, _, _) = titleOf(info)
                val key = "m:${MediaNameParser.normalize(title)}:${info.parsed.year ?: ""}"
                movies.getOrPut(key) { Builder(key, LibraryKind.MOVIE, title, 1, info.parsed.year) }.episodes += info to null
            }
            val hosts = (series.values + movies.values).filter { b -> (b.episodes + b.specials).any { it.first.folderPath == folder } }
            val host = hosts.maxByOrNull { it.maxSize() }
            val rest = mutableListOf<Info>()
            for (info in list - movieLike.toSet()) {
                val size = info.entry.sizeBytes
                if (host != null && size > 0 && size * 100 < host.maxSize() * 15) host.extras += info else rest += info
            }
            if (rest.size >= 2) {
                val name = folder.substringAfterLast('/')
                val title = parsedFolder(name).title.ifBlank { name }.ifBlank { "未分类视频" }
                others += Builder("c:${folder.lowercase(Locale.ROOT)}", LibraryKind.OTHER, title, 1, null).apply { rest.forEach { episodes += it to null } }
            } else rest.forEach { info ->
                val (title, _, _) = titleOf(info)
                others += Builder("o:${MediaNameParser.normalize(title)}:${folder.lowercase(Locale.ROOT)}", LibraryKind.OTHER, title, 1, null).apply { episodes += info to null }
            }
        }

        // 5. 专属目录（目录里直接存放的只有这部作品的视频）的目录名往往是更好的中文名，用它作显示标题；
        //    “Season 1 / SPs / Extras”之类的目录看它的上一级，这时要求上一级的全部子目录都只有这部作品
        val all = series.values + movies.values + others
        val owner = HashMap<String, Builder>()
        all.forEach { b -> b.infos().forEach { owner[it.entry.id] = b } }
        val subtree = HashMap<String, MutableSet<Builder>>()
        for (info in infos) {
            val b = owner[info.entry.id] ?: continue
            info.folders.indices.forEach { depth -> subtree.getOrPut(info.folders.take(depth + 1).joinToString("/")) { mutableSetOf() } += b }
        }
        fun structural(name: String) = name.trim().let { seasonFolder.matches(it) || specialsFolder.matches(it) || extrasFolder.matches(it) }
        val byFolder = infos.groupBy { it.folderPath }
        for (b in all) {
            if (b.kind == LibraryKind.OTHER) continue
            val folders = (b.episodes + b.specials).map { it.first.folderPath }.toSet()
            var folder = folders.singleOrNull() ?: continue
            val direct = folder
            while (folder.isNotEmpty() && structural(folder.substringAfterLast('/'))) folder = folder.substringBeforeLast('/', "")
            if (folder.isEmpty()) continue
            val dedicated = if (folder == direct) byFolder[folder].orEmpty().all { owner[it.entry.id] === b } else subtree[folder].orEmpty().all { it === b }
            if (!dedicated) continue
            val name = folder.substringAfterLast('/')
            if (genericFolder.matches(name.trim())) continue
            val parsed = parsedFolder(name)
            val folderTitle = parsed.title
            val a = MediaNameParser.normalize(folderTitle); val c = MediaNameParser.normalize(b.title)
            // 影片常直接放在杂项目录里：目录名要像发布名（带标签/年份）或与片名相关才采用
            val related = parsed.tagged || parsed.year != null || parsed.season != null || (a.isNotEmpty() && (a.contains(c) || c.contains(a)))
            if (b.kind == LibraryKind.MOVIE && !related) continue
            if (folderTitle.isNotBlank() && a != c) {
                b.alt += b.title; parsed.altTitles.forEach(b.alt::add); b.title = folderTitle
            }
        }

        val natural = Comparator<Info> { a, c -> LibraryRules.naturalCompare(a.entry.title, c.entry.title) }
        fun items(list: List<Pair<Info, Double?>>) = list.sortedWith(compareBy<Pair<Info, Double?>> { it.second ?: Double.MAX_VALUE }.thenComparing({ it.first }, natural)).map { LibraryItem(it.first.entry, it.first.parsed, it.second) }
        return all.map { b ->
            val folder = (b.episodes + b.specials).map { it.first.folderPath }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
            LibraryGroup(
                key = b.key, kind = b.kind, title = b.title, altTitles = (b.alt - b.title).toList(), season = b.season,
                year = b.year ?: (b.episodes.firstNotNullOfOrNull { it.first.parsed.year }),
                episodes = items(b.episodes), specials = items(b.specials),
                extras = b.extras.distinctBy { it.entry.id }.sortedWith(natural).map { LibraryItem(it.entry, it.parsed, it.parsed.episode) },
                folder = folder,
            )
        }.filter { it.count > 0 || it.extras.isNotEmpty() }
            .sortedWith { a, c -> LibraryRules.naturalCompare(a.displayTitle, c.displayTitle) }
    }

    /** 观看进度达到 95% 视为看完。 */
    fun watched(progress: PlaybackProgress?): Boolean = progress != null && PlayerUiRules.watchedFraction(progress.positionMs, progress.durationMs) >= 1f

    /**
     * “继续播放”的目标：最近播放的一集没看完就续播它，看完了就播下一集；从没播放过则从第一集开始。
     * 返回目标视频与是否为续播。
     */
    fun nextUp(group: LibraryGroup, history: Map<String, PlaybackProgress>, isWatched: (MediaEntry) -> Boolean = { watched(history[it.id]) }): Pair<MediaEntry, Boolean>? {
        val queue = group.playable
        if (queue.isEmpty()) return null
        val latest = queue.mapNotNull { entry -> history[entry.id]?.let { entry to it } }.maxByOrNull { it.second.updatedAtMs }
            ?: return (queue.firstOrNull { !isWatched(it) } ?: queue.first()) to false
        if (!isWatched(latest.first)) return latest.first to true
        val index = queue.indexOfFirst { it.id == latest.first.id }
        return queue.drop(index + 1).firstOrNull { !isWatched(it) }?.let { it to false }
            ?: queue.firstOrNull { !isWatched(it) }?.let { it to false }
            ?: (queue.first() to false)
    }
}
