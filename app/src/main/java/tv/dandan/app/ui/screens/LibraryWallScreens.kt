package tv.dandan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.dandan.app.data.LibraryRecognizer
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.chinese
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.requestContentFocus
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens
import tv.dandan.core.*

private data class WallData(
    val sources: List<MediaSource>,
    val groups: List<LibraryGroup>,
    val infos: Map<String, SeriesInfo>,
    val hidden: Set<String>,
    val history: Map<String, PlaybackProgress>,
    val bindings: Map<String, Long>,
    val watched: Map<String, Set<Long>>,
    val scanning: Boolean,
) {
    fun watchedIn(group: LibraryGroup): Set<Long> = infos[group.key]?.takeIf { it.recognized }?.let { watched[it.animeId] }.orEmpty()
}

private val wallHero = Hero("媒体库", "扫描到的视频按作品整理成海报墙。确定打开作品，选择任意一集播放。")
/** 海报墙网格中位于海报之前的整行项目（来源与工具、筛选）。 */
private const val WALL_HEADER_ITEMS = 2

/**
 * 媒体库：海报墙。顶部一行是添加来源、索引与扫描、收藏、磁链和各媒体源的目录浏览；
 * 其下是筛选（全部 / 剧集 / 电影 / 其他 / 未识别 / 已隐藏）与识别进度，再往下是按作品排列的海报。
 * 初始焦点在第一个工具卡；从作品页返回时回到原来的海报。
 */
@Composable
fun LibraryScreen(host: TvHost, owner: Any) {
    val repository = host.repository
    val loader = rememberLoader(host.refreshEpoch) {
        WallData(
            repository.sources(), repository.libraryGroups(), repository.seriesInfos(), repository.hiddenLibrary(),
            repository.history().associateBy { it.media.id }, repository.libraryBindings(), repository.allWatchedEpisodes(),
            repository.library.states().any { it.phase == ScanPhase.RUNNING },
        )
    }
    var filterName by rememberSaveable { mutableStateOf(LibraryFilter.ALL.name) }
    val filter = LibraryFilter.values().firstOrNull { it.name == filterName } ?: LibraryFilter.ALL
    var status by remember { mutableStateOf(repository.recognizer.state) }
    var hero by remember { mutableStateOf(wallHero) }
    val scanning by rememberUpdatedState((loader.value as? Load.Ready)?.value?.scanning == true)
    LaunchedEffect(Unit) {
        val recognizer = repository.recognizer
        recognizer.start()
        var seen = recognizer.version
        var ticks = 0
        while (true) {
            delay(1500); ticks++
            status = recognizer.state
            val changed = recognizer.version != seen
            seen = recognizer.version
            // 识别出新作品时刷新海报；扫描进行中每 9 秒刷新一次，新扫到的作品陆续出现
            if (changed || (scanning && ticks % 6 == 0)) loader.reload()
        }
    }
    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
            HeroText(hero)
            when (val state = loader.value) {
                Load.Loading -> LoadingPane("正在整理片库…")
                is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload)
                is Load.Ready -> LibraryWall(host, owner, state.value, filter, { filterName = it.name }, status, onHero = { hero = it }, onRecognize = {
                    repository.recognizer.start(force = true); status = repository.recognizer.state
                })
            }
        }
    }
}

@Composable
private fun LibraryWall(host: TvHost, owner: Any, data: WallData, filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit, status: LibraryRecognizer.State, onHero: (Hero) -> Unit, onRecognize: () -> Unit) {
    val counts = remember(data) { LibraryShelf.counts(data.groups, data.infos, data.hidden) }
    val visible = remember(data, filter) { LibraryShelf.sorted(data.groups.filter { LibraryShelf.matches(it, data.infos[it.key], it.key in data.hidden, filter) }, data.infos) }
    val gridState = rememberLazyGridState()
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedIndex by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(-1) }
    val toolsFocus = remember { FocusRequester() }
    val posterFocus = remember { FocusRequester() }
    // 返回时回到原来的海报；那部作品已隐藏或移除时，落到同一位置的海报
    val target = remember(visible) {
        visible.firstOrNull { it.key == focusedKey }?.key ?: if (focusedIndex >= 0) visible.getOrNull(focusedIndex.coerceAtMost(visible.lastIndex))?.key else null
    }
    LaunchedEffect(Unit) {
        val index = visible.indexOfFirst { it.key == target }
        if (index >= 0) gridState.scrollToItem(index + WALL_HEADER_ITEMS) else gridState.scrollToItem(0)
        withFrameNanos { }
        requestContentFocus(if (index >= 0) posterFocus else toolsFocus)
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(TvDimens.posterWidth),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(TvDimens.cardSpacing),
        contentPadding = PaddingValues(start = TvDimens.screenPaddingStart, end = TvDimens.screenPaddingEnd, top = 4.dp, bottom = 48.dp),
        modifier = Modifier.fillMaxSize().topFade().focusRestorer(),
    ) {
        item("tools", span = { GridItemSpan(maxLineSpan) }) { WallTools(host, owner, data.sources, toolsFocus, onHero) }
        item("filters", span = { GridItemSpan(maxLineSpan) }) { WallFilters(host, counts, filter, onFilter, status, onRecognize) }
        if (visible.isEmpty()) item("empty", span = { GridItemSpan(maxLineSpan) }) {
            when {
                data.sources.isEmpty() -> EmptyPane("尚未添加媒体源。选择“添加媒体源”，用手机扫码填写连接信息。")
                data.groups.isEmpty() && data.scanning -> EmptyPane("正在扫描媒体源，扫描到的视频会陆续按作品出现在这里。")
                data.groups.isEmpty() -> EmptyPane("还没有扫描出视频。扫描媒体源后，视频会按作品整理成海报墙。", actionLabel = "扫描全部媒体源") {
                    data.sources.forEach { source -> runCatching { host.repository.library.start(source.id) } }
                    host.navigator.push(Route.LibraryIndex)
                }
                else -> EmptyPane(if (filter == LibraryFilter.HIDDEN) "没有隐藏的作品。" else "没有符合“${filter.label}”的作品。")
            }
        }
        items(visible, key = { it.key }) { group ->
            val info = data.infos[group.key]
            val title = LibraryShelf.title(group, info)
            val watchedIds = data.watchedIn(group)
            val watched = group.playable.count { LibraryShelf.watched(it, data.history, data.bindings, watchedIds) }
            val single = group.playable.singleOrNull()?.let { data.history[it.id] }
            val progress = when {
                group.kind == LibraryKind.MOVIE && single != null -> ratio(single)
                watched > 0 -> watched.toFloat() / group.count.coerceAtLeast(1)
                else -> -1f
            }
            val modifier = (if (group.key == target) Modifier.focusRequester(posterFocus) else Modifier).onFocusChanged {
                if (it.isFocused) { focusedKey = group.key; focusedIndex = visible.indexOfFirst { g -> g.key == group.key } }
            }
            PosterCard(
                title, info?.imageUrl.orEmpty(), { host.navigator.push(Route.LibrarySeries(group.key, title)) }, modifier,
                subtitle = posterSubtitle(group, info, watched),
                badge = info?.rating?.takeIf { it.isNotBlank() && info.recognized }.orEmpty(),
                progress = progress,
                fullTitlePlaceholder = true,
                onFocus = { onHero(groupHero(group, info, watched)) },
            )
        }
    }
}

@Composable
private fun WallTools(host: TvHost, owner: Any, sources: List<MediaSource>, firstFocus: FocusRequester, onHero: (Hero) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(start = ROW_BLEED, top = 8.dp, bottom = 10.dp), modifier = Modifier.fillMaxWidth().bleedStart(ROW_BLEED).focusRestorer()) {
        item("add") {
            CompactTile("添加媒体源", { requestSourceForm(host, owner) }, Modifier.focusRequester(firstFocus), "手机扫码填写", Icons.Default.Add,
                onFocus = { onHero(Hero("添加媒体源", "支持本地目录、WebDAV、SMB、FTP 与 HTTP 视频地址。添加并扫描后，视频按作品整理到下方海报墙。", meta = "媒体库")) })
        }
        item("index") {
            CompactTile("索引与扫描", { host.navigator.push(Route.LibraryIndex) }, subtitle = "后台扫描全部来源", icon = Icons.Default.Search,
                onFocus = { onHero(Hero("索引与扫描", "扫描媒体源建立索引；扫描完成后自动向弹弹play 识别海报、简介与剧集。", meta = "媒体库")) })
        }
        item("favorites") {
            CompactTile("我的收藏", { host.navigator.push(Route.Favorites) }, subtitle = "收藏的视频", icon = Icons.Default.Favorite, accent = TvColors.amber,
                onFocus = { onHero(Hero("我的收藏", "在作品页或目录中长按视频，选择“收藏此视频”。", meta = "媒体库")) })
        }
        if (tv.dandan.app.data.MagnetEngine.supported) item("magnet") {
            CompactTile("打开磁链", { openMagnetForm(host, owner) }, subtitle = "手机粘贴，边下边播", icon = Icons.Default.Share,
                onFocus = { onHero(Hero("打开磁链", "在手机粘贴磁力链接，选择文件后边下边播。", meta = "媒体库")) })
        }
        items(sources, key = { "src-" + it.id }) { source ->
            CompactTile(source.name, { host.navigator.push(Route.Directory(source)) }, subtitle = source.kind.chinese() + " · 浏览目录", icon = Icons.Default.List,
                onFocus = { onHero(Hero(source.name, "按目录逐级浏览此媒体源；长按视频打开文件操作。", meta = source.kind.chinese())) })
        }
    }
}

@Composable
private fun WallFilters(host: TvHost, counts: Map<LibraryFilter, Int>, filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit, status: LibraryRecognizer.State, onRecognize: () -> Unit) {
    // 筛选在上，识别进度或问题在下一行，长提示不会挤掉筛选项
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        val shown = LibraryFilter.values().filter { it == LibraryFilter.ALL || it == filter || (counts[it] ?: 0) > 0 }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(start = ROW_BLEED, top = 6.dp, bottom = 6.dp), modifier = Modifier.fillMaxWidth().bleedStart(ROW_BLEED).focusRestorer()) {
            items(shown, key = { it.name }) { item -> TvChip("${item.label} ${counts[item] ?: 0}", onClick = { onFilter(item) }, selected = item == filter) }
        }
        when {
            status.running -> Text("正在识别海报与剧集 ${status.done + 1}/${status.total} · ${status.current}", style = MaterialTheme.typography.labelMedium, color = TvColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            status.message.startsWith("识别中断") || status.message.startsWith("识别暂停") -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                TvChip("重新识别", onClick = onRecognize)
                Spacer(Modifier.width(12.dp))
                Text(status.message, style = MaterialTheme.typography.labelMedium, color = TvColors.amber, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            status.message.isNotBlank() -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                TvChip("配置开放平台凭证", onClick = { host.navigator.push(Route.Credentials) })
                Spacer(Modifier.width(12.dp))
                Text(status.message, style = MaterialTheme.typography.labelMedium, color = TvColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun posterSubtitle(group: LibraryGroup, info: SeriesInfo?, watched: Int): String = buildList {
    when (group.kind) {
        LibraryKind.SERIES -> add(if (watched > 0) "${group.count} 集 · 已看 $watched" else "${group.count} 集")
        LibraryKind.MOVIE -> add(if (group.count > 1) "电影 · ${group.count} 个文件" else "电影")
        LibraryKind.OTHER -> add("${group.count} 个视频")
    }
    if (group.kind != LibraryKind.OTHER && info?.recognized != true) add(if (info == null) "待识别" else "未识别")
}.joinToString(" · ")

private fun groupHero(group: LibraryGroup, info: SeriesInfo?, watched: Int): Hero {
    val title = LibraryShelf.title(group, info)
    val meta = buildList {
        add(when (group.kind) { LibraryKind.SERIES -> "剧集"; LibraryKind.MOVIE -> "电影"; LibraryKind.OTHER -> "个人视频" })
        info?.typeDescription?.takeIf { it.isNotBlank() && info.recognized }?.let(::add)
        if (group.kind == LibraryKind.SERIES) add(if (watched > 0) "已看 $watched/${group.count}" else "${group.count} 集")
        info?.rating?.takeIf { it.isNotBlank() && info.recognized }?.let { add("评分 $it") }
    }.joinToString(" · ")
    val description = when {
        info?.recognized == true && info.description.isNotBlank() -> info.description
        group.kind == LibraryKind.OTHER -> "${group.count} 个视频。个人视频不会发送到弹幕服务，确定打开后选择播放。"
        info == null -> "正在等待识别海报与剧集；确定打开作品选择剧集。"
        info.recognized -> "确定打开作品选择剧集。"
        else -> "没能自动识别这部作品。打开作品页可“重新识别”，手动指定对应的番剧。"
    }
    return Hero(title, description, info?.imageUrl.orEmpty(), meta)
}

private data class SeriesData(
    val group: LibraryGroup?,
    val info: SeriesInfo?,
    val hidden: Boolean,
    val history: Map<String, PlaybackProgress>,
    val bindings: Map<String, Long>,
    val watched: Set<Long>,
)

/**
 * 片库作品页：左侧海报、名称与简介；右侧是操作按钮与剧集列表（正片、特别篇、特典）。
 * 确定 = 从这一集开始连播本作品；长按 = 文件操作（收藏、弹幕匹配、字幕）。初始焦点在“继续播放”。
 */
@Composable
fun LibrarySeriesScreen(host: TvHost, route: Route.LibrarySeries) {
    val repository = host.repository
    val loader = rememberLoader(route.groupKey, host.refreshEpoch) {
        val group = repository.libraryGroups().firstOrNull { it.key == route.groupKey }
        val info = repository.seriesInfos()[route.groupKey]
        val ids = group?.items()?.map { it.entry.id }?.toSet().orEmpty()
        SeriesData(
            group, info, route.groupKey in repository.hiddenLibrary(),
            repository.history().filter { it.media.id in ids }.associateBy { it.media.id },
            repository.libraryBindings().filterKeys { it in ids } + ids.mapNotNull { id -> repository.episodeFor(id)?.let { id to it.episodeId } },
            info?.takeIf { it.recognized }?.let { repository.watchedEpisodes(it.animeId) }.orEmpty(),
        )
    }
    val ready = (loader.value as? Load.Ready)?.value
    // 作品还没有识别结果时，后台识别完成后自动刷新海报与剧集名
    LaunchedEffect(ready?.info == null) {
        if (ready != null && ready.info != null) return@LaunchedEffect
        val recognizer = repository.recognizer
        var seen = recognizer.version
        while (true) { delay(2000); if (recognizer.version != seen) { seen = recognizer.version; loader.reload() } }
    }
    val info = ready?.info?.takeIf { it.recognized }
    val title = ready?.group?.let { LibraryShelf.title(it, ready.info) } ?: route.title
    Box(Modifier.fillMaxSize()) {
        if (info != null && info.imageUrl.isNotBlank()) {
            AsyncImage(model = info.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, modifier = Modifier.fillMaxHeight().fillMaxWidth(.7f).align(Alignment.TopEnd).blur(30.dp).alpha(.5f))
            BackdropScrim()
        }
        Row(Modifier.fillMaxSize().padding(start = TvDimens.screenPaddingStart, top = TvDimens.screenPaddingTop)) {
            Column(Modifier.width(290.dp).fillMaxHeight().padding(bottom = 24.dp)) {
                Box(Modifier.size(150.dp, 216.dp).clip(MaterialTheme.shapes.medium)) { Poster(info?.imageUrl.orEmpty(), title, Modifier.fillMaxSize(), fullTitle = true) }
                Spacer(Modifier.height(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = TvColors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val group = ready?.group
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (info != null && info.rating.isNotBlank()) Badge("评分 ${info.rating}")
                    if (info != null && info.typeDescription.isNotBlank()) Badge(info.typeDescription, color = TvColors.accent)
                    if (group != null && group.kind == LibraryKind.SERIES && group.season > 1 && info == null) Badge("第 ${group.season} 季", color = TvColors.accent)
                    if (group != null && group.kind != LibraryKind.OTHER && ready.info?.recognized != true) Badge(if (ready.info == null) "待识别" else "未识别", color = TvColors.muted)
                }
                Spacer(Modifier.height(10.dp))
                val description = when {
                    info != null && info.description.isNotBlank() -> info.description
                    group == null -> ""
                    group.kind == LibraryKind.OTHER -> "个人视频不会发送到弹幕服务。"
                    ready.info == null -> "正在后台向弹弹play 识别海报、简介与官方剧集名。"
                    info == null -> "没能自动识别。选择“重新识别”，搜索并指定对应的番剧后，剧集会按集号绑定官方弹幕。"
                    else -> "服务未提供简介。"
                }
                Text(description, style = MaterialTheme.typography.bodySmall, color = TvColors.muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
                if (group != null) {
                    Spacer(Modifier.height(12.dp))
                    val size = group.items().sumOf { it.entry.sizeBytes }
                    Text(listOfNotNull("${group.items().size} 个文件", if (size > 0) formatSize(size) else null, group.folder.substringAfterLast('/').takeIf { it.isNotBlank() }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = TvColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when (val state = loader.value) {
                    Load.Loading -> LoadingPane("正在读取作品…")
                    is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
                    is Load.Ready -> {
                        val data = state.value
                        if (data.group == null) {
                            val back = remember { FocusRequester() }
                            LaunchedEffect(Unit) { requestContentFocus(back) }
                            EmptyPane("这部作品已不在片库中，可能文件已移动或索引已清除。")
                            TvButton("返回媒体库", onClick = { host.navigator.pop() }, modifier = Modifier.focusRequester(back))
                        } else SeriesContent(host, data.group, data, onChanged = loader::reload)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(host: TvHost, group: LibraryGroup, data: SeriesData, onChanged: () -> Unit) {
    val actions = rememberActions()
    val info = data.info?.takeIf { it.recognized }
    val official = remember(info) { info?.episodes?.associateBy { it.id }.orEmpty() }
    fun watched(entry: MediaEntry) = LibraryShelf.watched(entry, data.history, data.bindings, data.watched)
    val next = remember(data) { LibraryGrouping.nextUp(group, data.history) { watched(it) } }
    val nextItem = next?.let { (entry, _) -> (group.episodes + group.specials).firstOrNull { it.entry.id == entry.id } }
    val listState = rememberLazyListState()
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val playFocus = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    // 列表项顺序与下方 LazyColumn 一致，用于返回本页时滚动到原来的剧集
    val rowKeys = remember(group) {
        buildList {
            add("actions"); add("episodes-title"); group.episodes.forEach { add("ep:" + it.entry.id) }
            if (group.specials.isNotEmpty()) { add("specials-title"); group.specials.forEach { add("ep:" + it.entry.id) } }
            if (group.extras.isNotEmpty()) { add("extras-title"); group.extras.forEach { add("ep:" + it.entry.id) } }
        }
    }
    val target = remember(rowKeys) { focusedKey?.takeIf { it in rowKeys } }
    LaunchedEffect(Unit) {
        val index = rowKeys.indexOf(target)
        if (index > 0) {
            listState.scrollToItem(index)
            withFrameNanos { }
            requestContentFocus(rowFocus)
        } else requestContentFocus(playFocus)
    }
    fun play(entry: MediaEntry, queue: List<MediaEntry>) = host.play(entry, queue)
    val queue = group.playable
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, bottom = 48.dp), modifier = Modifier.fillMaxSize().focusRestorer()) {
        item("actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                val label = when {
                    next == null -> "播放"
                    group.kind == LibraryKind.MOVIE -> if (next.second) "继续播放" else "播放"
                    group.kind == LibraryKind.OTHER -> if (next.second) "继续播放" else "播放全部"
                    nextItem != null -> (if (next.second) "继续播放 " else "播放 ") + episodeName(nextItem, nextItem in group.specials)
                    else -> "播放"
                }
                TvButton(label, onClick = { next?.let { play(it.first, queue) } }, icon = Icons.Default.PlayArrow, modifier = Modifier.focusRequester(playFocus).onFocusChanged { if (it.isFocused) focusedKey = "actions" })
                if (info != null) TvButton("番剧资料", filled = false, icon = Icons.Default.Info, onClick = { host.navigator.push(Route.AnimeDetail(info.summary())) })
                if (group.kind != LibraryKind.OTHER) TvButton("重新识别", filled = false, icon = Icons.Default.Refresh, onClick = {
                    host.navigator.push(Route.LibraryMatch(group.key, LibraryShelf.title(group, data.info), group.title))
                })
                TvButton(if (data.hidden) "取消隐藏" else "隐藏", filled = false, onClick = {
                    val hide = !data.hidden
                    actions.run({ host.repository.setLibraryHidden(group.key, hide) }, host::notify) {
                        if (hide) { host.notify("已隐藏，可在海报墙的“已隐藏”中恢复"); host.navigator.pop() } else { host.notify("已恢复到海报墙"); onChanged() }
                    }
                })
            }
        }
        item("episodes-title") {
            val watchedCount = group.episodes.count { watched(it.entry) }
            val heading = when (group.kind) { LibraryKind.SERIES -> "剧集 · 已看 $watchedCount/${group.episodes.size}"; LibraryKind.MOVIE -> "影片"; LibraryKind.OTHER -> "视频 · ${group.episodes.size} 个" }
            Column(Modifier.padding(top = 6.dp)) {
                Text(heading, style = MaterialTheme.typography.headlineSmall, color = TvColors.onSurface)
                Note("确定：从这一集开始连续播放 · 长按：收藏、匹配弹幕、字幕等文件操作")
            }
        }
        items(group.episodes, key = { "ep:" + it.entry.id }) { item ->
            EpisodeRow(host, item, special = false, group, data, official, item.entry.id == next?.first?.id, rowFocus, focusedKey, { focusedKey = it }, queue)
        }
        if (group.specials.isNotEmpty()) {
            item("specials-title") { SectionTitle("特别篇 · ${group.specials.size} 个") }
            items(group.specials, key = { "ep:" + it.entry.id }) { item ->
                EpisodeRow(host, item, special = true, group, data, official, item.entry.id == next?.first?.id, rowFocus, focusedKey, { focusedKey = it }, queue)
            }
        }
        if (group.extras.isNotEmpty()) {
            item("extras-title") { SectionTitle("特典 · 无字幕片头片尾、菜单、预告等 ${group.extras.size} 个") }
            items(group.extras, key = { "ep:" + it.entry.id }) { item ->
                EpisodeRow(host, item, special = false, group, data, official, false, rowFocus, focusedKey, { focusedKey = it }, group.extras.map { it.entry }, extra = true)
            }
        }
    }
}

/** “第 01 集”“SP01”“第 12.5 集”。 */
private fun episodeName(item: LibraryItem, special: Boolean): String = when {
    special -> "SP" + item.label.ifEmpty { "" }
    item.label.isEmpty() -> ""
    else -> "第 ${item.label} 集"
}

@Composable
private fun EpisodeRow(
    host: TvHost,
    item: LibraryItem,
    special: Boolean,
    group: LibraryGroup,
    data: SeriesData,
    official: Map<Long, AnimeEpisode>,
    isNext: Boolean,
    rowFocus: FocusRequester,
    focusedKey: String?,
    onFocused: (String) -> Unit,
    queue: List<MediaEntry>,
    extra: Boolean = false,
) {
    val entry = item.entry
    val key = "ep:" + entry.id
    val progress = data.history[entry.id]
    val seen = LibraryShelf.watched(entry, data.history, data.bindings, data.watched)
    val officialEpisode = data.bindings[entry.id]?.let(official::get)
    val fileName = PlayerUiRules.displayTitle(entry.title)
    val headline = when {
        extra || group.kind == LibraryKind.OTHER -> fileName
        group.kind == LibraryKind.MOVIE -> officialEpisode?.title?.takeIf { it.isNotBlank() && data.info?.title != it } ?: LibraryShelf.title(group, data.info)
        else -> listOfNotNull(episodeName(item, special).ifBlank { null }, officialEpisode?.title?.takeIf { it.isNotBlank() } ?: item.parsed.episodeTitle.takeIf { it.isNotBlank() }).joinToString("  ")
    }
    val details = buildList {
        if (progress != null && !seen) add("看到 ${progressLabel(progress)}")
        if (seen) add("已看完")
        if (!extra && group.kind != LibraryKind.OTHER) add(fileName)
        if (entry.sizeBytes > 0) add(formatSize(entry.sizeBytes))
        if (entry.subtitleUri.isNotBlank()) add("字幕")
    }.joinToString(" · ")
    val modifier = (if (key == focusedKey) Modifier.focusRequester(rowFocus) else Modifier).onFocusChanged { if (it.isFocused) onFocused(key) }
    Column(modifier.fillMaxWidth()) {
        ListItem(
            selected = false,
            onClick = { host.play(entry, queue) },
            onLongClick = { host.navigator.push(Route.MediaActions(entry, queue)) },
            shape = ListItemDefaults.shape(MaterialTheme.shapes.small),
            colors = ListItemDefaults.colors(containerColor = TvColors.surfaceVariant.copy(alpha = .55f), contentColor = TvColors.onSurface, focusedContainerColor = TvColors.surfaceBright, focusedContentColor = TvColors.onSurface),
            scale = ListItemDefaults.scale(focusedScale = 1f),
            border = ListItemDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, TvColors.accent), shape = MaterialTheme.shapes.small)),
            leadingContent = {
                Icon(if (seen) Icons.Default.Check else Icons.Default.PlayArrow, contentDescription = null, tint = when { seen -> TvColors.muted; isNext || progress != null -> TvColors.amber; else -> TvColors.accent })
            },
            headlineContent = { Text(headline, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (seen) TvColors.muted else TvColors.onSurface) },
            supportingContent = if (details.isBlank()) null else { { Text(details, style = MaterialTheme.typography.bodySmall, color = TvColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            trailingContent = if (isNext) { { Text(if (progress != null && !seen) "继续" else "下一集", style = MaterialTheme.typography.labelLarge, color = TvColors.amber) } } else null,
        )
        if (progress != null && !seen && ratio(progress) > 0f) Box(Modifier.padding(horizontal = 14.dp)) { ProgressStripe(ratio(progress), Modifier.height(3.dp)) }
    }
}

/**
 * 重新识别：列出服务端对文件名的匹配结果与按名称搜索的结果，选择后剧集按集号重新绑定。
 * “自动识别”忽略已有结果重新走一遍自动流程。
 */
@Composable
fun LibraryMatchScreen(host: TvHost, owner: Any, route: Route.LibraryMatch) {
    var keyword by rememberSaveable { mutableStateOf(route.keyword) }
    val loader = rememberLoader(keyword) {
        val group = host.repository.libraryGroups().firstOrNull { it.key == route.groupKey } ?: throw java.io.IOException("这部作品已不在片库中")
        group to host.repository.recognizer.candidates(group, keyword)
    }
    val actions = rememberActions()
    val searchFocus = remember { FocusRequester() }
    val group = (loader.value as? Load.Ready)?.value?.first
    fun editKeyword() {
        host.phoneForm(owner, PhoneForm("重新识别", "输入作品名称（中文、日文或罗马音均可），回到电视选择对应的番剧。", listOf(PhoneField("keyword", "作品名称", keyword, required = true, maxLength = 200))),
            validator = { if (it.getValue("keyword").trim().length < 2) "请输入至少 2 个字符" else null }) { keyword = it.getValue("keyword").trim() }
    }
    fun done(info: SeriesInfo) {
        host.notify(if (info.recognized) "已识别为“${info.title}”，剧集已重新绑定弹幕" else "没有找到可信的结果，可修改名称后手动选择")
        if (info.recognized) host.navigator.pop()
    }
    ScreenScaffold("重新识别", subtitle = "为“${route.title}”选择对应的番剧；选择后剧集按集号重新绑定官方弹幕。", scrollable = false, headerTrailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("名称：$keyword", icon = Icons.Default.Search, onClick = ::editKeyword, modifier = Modifier.focusRequester(searchFocus))
            if (group != null) TvButton(if (actions.busy) "识别中…" else "自动识别", filled = false, icon = Icons.Default.Refresh, onClick = {
                actions.run({ host.repository.recognizer.redo(group) }, host::notify, ::done)
            })
        }
    }) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在查找候选番剧…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() }) {
                Spacer(Modifier.height(10.dp)); TvButton("配置开放平台凭证", filled = false, onClick = { host.navigator.push(Route.Credentials) })
            }
            is Load.Ready -> {
                val (current, candidates) = state.value
                if (candidates.isEmpty()) {
                    LaunchedEffect(Unit) { requestContentFocus(searchFocus) }
                    EmptyPane("没有找到候选番剧。请修改名称后重试，较短的名称更容易命中。")
                } else PosterGrid(candidates, onOpen = { anime -> actions.run({ host.repository.recognizer.assign(current, anime) }, host::notify, ::done) })
            }
        }
    }
}
