package tv.dandan.app.ui.screens

import tv.dandan.app.ui.requestContentFocus
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens
import tv.dandan.core.AnimeSummary
import tv.dandan.core.MediaEntry
import tv.dandan.core.PlaybackProgress
import java.util.Calendar

/** 海报墙顶部展示区的内容：随焦点项变化。 */
data class Hero(val title: String, val description: String = "", val imageUrl: String = "", val meta: String = "")

private data class HomeData(
    val history: List<PlaybackProgress>, val weekly: List<AnimeSummary>?, val weeklyError: String, val season: List<AnimeSummary>, val follows: List<AnimeSummary> = emptyList(),
    val shelf: List<ShelfCard> = emptyList(), val continuing: Map<String, ContinueInfo> = emptyMap(),
)
/** 首页“我的片库”的一张海报。 */
private data class ShelfCard(val key: String, val title: String, val imageUrl: String, val subtitle: String, val hero: Hero, val progress: Float)
/** 继续观看里属于片库作品的视频：显示作品名、集数与海报，而不是文件名。 */
private data class ContinueInfo(val title: String, val episode: String, val imageUrl: String)

private fun libraryHome(host: TvHost, history: List<PlaybackProgress>): Pair<List<ShelfCard>, Map<String, ContinueInfo>> {
    val repository = host.repository
    val groups = repository.libraryGroups()
    if (groups.isEmpty()) return emptyList<ShelfCard>() to emptyMap()
    val infos = repository.seriesInfos(); val hidden = repository.hiddenLibrary(); val bindings = repository.libraryBindings(); val marks = repository.allWatchedEpisodes()
    val byId = history.associateBy { it.media.id }
    val shelf = tv.dandan.core.LibraryShelf.recent(groups, infos, hidden, byId).map { group ->
        val info = infos[group.key]
        val title = tv.dandan.core.LibraryShelf.title(group, info)
        val watchedIds = info?.takeIf { it.recognized }?.let { marks[it.animeId] }.orEmpty()
        val watched = group.playable.count { tv.dandan.core.LibraryShelf.watched(it, byId, bindings, watchedIds) }
        val subtitle = when (group.kind) { tv.dandan.core.LibraryKind.MOVIE -> "电影"; else -> if (watched > 0) "${group.count} 集 · 已看 $watched" else "${group.count} 集" }
        val description = info?.takeIf { it.recognized }?.description?.takeIf { it.isNotBlank() } ?: "确定打开作品，选择任意一集播放。"
        ShelfCard(group.key, title, info?.imageUrl.orEmpty(), subtitle, Hero(title, description, info?.imageUrl.orEmpty(), "我的片库 · $subtitle"), if (watched > 0) watched.toFloat() / group.count.coerceAtLeast(1) else -1f)
    }
    val owners = HashMap<String, tv.dandan.core.LibraryGroup>()
    groups.forEach { group -> group.items().forEach { owners[it.entry.id] = group } }
    val continuing = history.mapNotNull { progress ->
        val group = owners[progress.media.id]?.takeIf { it.kind != tv.dandan.core.LibraryKind.OTHER } ?: return@mapNotNull null
        val info = infos[group.key]
        val special = group.specials.firstOrNull { it.entry.id == progress.media.id }
        val episode = group.episodes.firstOrNull { it.entry.id == progress.media.id }
        val label = when { special != null -> "SP" + special.label; group.kind == tv.dandan.core.LibraryKind.SERIES && episode != null && episode.label.isNotEmpty() -> "第 ${episode.label} 集"; else -> "" }
        progress.media.id to ContinueInfo(tv.dandan.core.LibraryShelf.title(group, info), label, info?.imageUrl.orEmpty())
    }.toMap()
    return shelf to continuing
}

/** 首页：继续观看、快捷入口、本周更新（今天优先）与本季新番。 */
@Composable
fun HomeScreen(host: TvHost, owner: Any) {
    val loader = rememberLoader(host.refreshEpoch) {
        val history = host.repository.history()
        var error = ""
        val weekly = runCatching { host.repository.api.weekly() }.getOrElse { error = it.message ?: "番剧服务暂不可用"; null }
        val season = if (weekly == null) emptyList() else runCatching {
            host.repository.api.seasons().firstOrNull()?.let { host.repository.api.season(it) }.orEmpty()
        }.getOrDefault(emptyList())
        val (shelf, continuing) = runCatching { libraryHome(host, history) }.getOrDefault(emptyList<ShelfCard>() to emptyMap())
        HomeData(history, weekly, error, season, host.repository.follows(), shelf, continuing)
    }
    var hero by remember { mutableStateOf(Hero("弹弹 TV", "从上次停下的地方继续，或打开你的媒体。")) }
    val wall = rememberWallFocus()

    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
        HeroText(hero)
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取观看记录…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload)
            is Load.Ready -> {
                val data = state.value
                val listState = rememberLazyListState()
                val rowOrder = buildList {
                    add("continue")
                    if (data.shelf.isNotEmpty()) add("library")
                    if (data.follows.isNotEmpty()) add("follows")
                    if (data.weekly != null) { weeklyGroups(data.weekly).forEach { add("week-${it.first}") }; if (data.season.isNotEmpty()) add("season") } else add("weekly-unavailable")
                    if (data.history.isNotEmpty()) add("shortcuts")
                }
                LaunchedEffect(data) { wall.restore(listState, rowOrder, "continue") }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = TvDimens.screenPaddingStart, top = 8.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(TvDimens.rowSpacing),
                    modifier = Modifier.fillMaxSize().topFade(),
                ) {
                    item("continue") {
                        if (data.history.isEmpty()) {
                            ContentRow("开始观看", listOf("add", "library", "url", "file"), firstItemFocus = wall.requester("continue"), focusIndex = wall.indexFor("continue"), onItemFocused = wall.onFocused("continue")) { _, item, modifier ->
                                when (item) {
                                    "add" -> TileCard("添加媒体源", { requestSourceForm(host, owner) }, modifier, "扫码在手机填写连接信息", Icons.Default.Add)
                                    "library" -> TileCard("浏览媒体库", { host.navigator.switchTab(1) }, modifier, "本地 · 网络 · 收藏 · 索引", Icons.Default.List)
                                    "url" -> TileCard("打开视频链接", { host.navigator.push(Route.VideoUrl) }, modifier, "HTTP / HTTPS · HLS · DASH", Icons.Default.Share)
                                    else -> TileCard("打开单个文件", { host.pickFile("video/*") { uri -> host.play(MediaEntry("document:$uri", uri.lastPathSegment ?: "本地视频", uri.toString())) } }, modifier, "通过系统文件选择器授权", Icons.Default.PlayArrow)
                                }
                            }
                        } else {
                            ContentRow("继续观看", data.history.take(20), key = { it.media.id }, firstItemFocus = wall.requester("continue"), focusIndex = wall.indexFor("continue"), onItemFocused = wall.onFocused("continue")) { _, progress, modifier ->
                                val known = data.continuing[progress.media.id]
                                val title = known?.title ?: progress.media.title
                                val subtitle = listOfNotNull(known?.episode?.takeIf { it.isNotBlank() }, progressLabel(progress)).joinToString(" · ")
                                WideCard(title, onClick = { host.play(progress.media) }, modifier = modifier, imageUrl = known?.imageUrl.orEmpty(), subtitle = subtitle, progress = ratio(progress), icon = Icons.Default.PlayArrow,
                                    onFocus = { hero = Hero(title, "确定继续播放 · $subtitle", known?.imageUrl.orEmpty(), meta = "继续观看") })
                            }
                        }
                    }
                    if (data.shelf.isNotEmpty()) item("library") {
                        ContentRow("我的片库", data.shelf, key = { "l-" + it.key }, trailing = "媒体库查看全部", firstItemFocus = wall.requester("library"), focusIndex = wall.indexFor("library"), onItemFocused = wall.onFocused("library")) { _, card, modifier ->
                            PosterCard(card.title, card.imageUrl, { host.navigator.push(Route.LibrarySeries(card.key, card.title)) }, modifier, subtitle = card.subtitle, progress = card.progress, fullTitlePlaceholder = true, onFocus = { hero = card.hero })
                        }
                    }
                    if (data.follows.isNotEmpty()) item("follows") {
                        ContentRow("我的追番", data.follows.take(30), key = { "f-" + it.id }, trailing = "${data.follows.size} 部", firstItemFocus = wall.requester("follows"), focusIndex = wall.indexFor("follows"), onItemFocused = wall.onFocused("follows")) { _, anime, modifier ->
                            PosterCard(anime.title, anime.imageUrl, { host.navigator.push(Route.AnimeDetail(anime)) }, modifier, badge = anime.rating.takeIf { it.isNotBlank() }.orEmpty(), onFocus = { hero = anime.hero("我的追番") })
                        }
                    }
                    if (data.weekly != null) {
                        val groups = weeklyGroups(data.weekly)
                        groups.forEach { (label, list) ->
                            item("week-$label") {
                                ContentRow(label, list, key = { it.id }, trailing = "${list.size} 部", firstItemFocus = wall.requester("week-$label"), focusIndex = wall.indexFor("week-$label"), onItemFocused = wall.onFocused("week-$label")) { _, anime, modifier ->
                                    PosterCard(anime.title, anime.imageUrl, { host.navigator.push(Route.AnimeDetail(anime)) }, modifier, badge = anime.rating.takeIf { it.isNotBlank() }.orEmpty(), onFocus = { hero = anime.hero(label) })
                                }
                            }
                        }
                        if (data.season.isNotEmpty()) item("season") {
                            ContentRow("本季新番", data.season.take(40), key = { "s-" + it.id }, trailing = "按季度浏览全部", firstItemFocus = wall.requester("season"), focusIndex = wall.indexFor("season"), onItemFocused = wall.onFocused("season")) { _, anime, modifier ->
                                PosterCard(anime.title, anime.imageUrl, { host.navigator.push(Route.AnimeDetail(anime)) }, modifier, badge = anime.rating.takeIf { it.isNotBlank() }.orEmpty(), onFocus = { hero = anime.hero("本季新番") })
                            }
                        }
                    } else item("weekly-unavailable") {
                        ContentRow("番剧资讯", listOf("credentials", "search"), firstItemFocus = wall.requester("weekly-unavailable"), focusIndex = wall.indexFor("weekly-unavailable"), onItemFocused = wall.onFocused("weekly-unavailable")) { _, item, modifier ->
                            if (item == "credentials") TileCard("配置开放平台凭证", { host.navigator.push(Route.Credentials) }, modifier, data.weeklyError, Icons.Default.Settings, accent = TvColors.amber)
                            else TileCard("搜索番剧", { host.navigator.push(Route.AnimeSearch()) }, modifier, "在手机输入名称", Icons.Default.List)
                        }
                    }
                    if (data.history.isNotEmpty()) item("shortcuts") {
                        ContentRow("快捷入口", listOf("library", "url", "file"), firstItemFocus = wall.requester("shortcuts"), focusIndex = wall.indexFor("shortcuts"), onItemFocused = wall.onFocused("shortcuts")) { _, item, modifier ->
                            when (item) {
                                "library" -> TileCard("浏览媒体库", { host.navigator.switchTab(1) }, modifier, "本地 · 网络 · 收藏 · 索引", Icons.Default.List)
                                "url" -> TileCard("打开视频链接", { host.navigator.push(Route.VideoUrl) }, modifier, "HTTP / HTTPS · HLS · DASH", Icons.Default.Share)
                                else -> TileCard("打开单个文件", { host.pickFile("video/*") { uri -> host.play(MediaEntry("document:$uri", uri.lastPathSegment ?: "本地视频", uri.toString())) } }, modifier, "通过系统文件选择器授权", Icons.Default.PlayArrow)
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

/** 顶部大图区：右侧模糊海报铺在整页背后，标题与说明占固定高度，列表从其下方开始，不会重叠。 */
@Composable
fun HeroBackdrop(hero: Hero, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        AnimatedContent(hero.imageUrl, transitionSpec = { fadeIn(tween(420)) togetherWith fadeOut(tween(420)) }, label = "backdrop") { url ->
            if (url.isNotBlank()) Box(Modifier.fillMaxSize()) {
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, modifier = Modifier.fillMaxHeight(.75f).fillMaxWidth(.62f).align(Alignment.TopEnd).blur(22.dp).alpha(.7f))
                BackdropScrim()
            } else Box(Modifier.fillMaxSize())
        }
    }
}

/** 顶部标题区，固定高度 [HERO_HEIGHT]。 */
@Composable
fun HeroText(hero: Hero, modifier: Modifier = Modifier) {
    AnimatedContent(hero, transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) }, label = "hero", modifier = modifier.fillMaxWidth().height(HERO_HEIGHT)) { value ->
        Column(Modifier.fillMaxSize().padding(start = TvDimens.screenPaddingStart, top = 30.dp, end = 400.dp)) {
            if (value.meta.isNotBlank()) Text(value.meta, style = MaterialTheme.typography.labelLarge, color = TvColors.accent, maxLines = 1)
            Text(value.title, style = MaterialTheme.typography.displaySmall, color = TvColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(value.description, style = MaterialTheme.typography.bodyMedium, color = TvColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

val HERO_HEIGHT = 150.dp

internal fun AnimeSummary.hero(meta: String) = Hero(title, description.ifBlank { "确定查看简介与剧集" }, imageUrl, buildString {
    append(meta)
    if (rating.isNotBlank()) append(" · 评分 $rating")
})

internal fun progressLabel(progress: PlaybackProgress): String {
    fun time(ms: Long): String = "${ms / 60_000}:${(ms / 1000 % 60).toString().padStart(2, '0')}"
    return if (progress.durationMs > 0) "${time(progress.positionMs)} / ${time(progress.durationMs)}" else "已观看 ${time(progress.positionMs)}"
}

internal fun ratio(progress: PlaybackProgress): Float = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else -1f

/** 把每周番剧按星期分组，今天排第一，随后按未来几天的顺序排列。 */
internal fun weeklyGroups(weekly: List<AnimeSummary>, today: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1): List<Pair<String, List<AnimeSummary>>> {
    val names = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    val order = (0 until 7).map { (today + it) % 7 }
    val grouped = weekly.groupBy { if (it.airDay in 0..6) it.airDay else if (it.airDay == 7) 0 else -1 }
    return buildList {
        order.forEachIndexed { index, day ->
            grouped[day]?.takeIf { it.isNotEmpty() }?.let { add((if (index == 0) "今天更新 · ${names[day]}" else if (index == 1) "明天 · ${names[day]}" else names[day]) to it) }
        }
        grouped[-1]?.takeIf { it.isNotEmpty() }?.let { add("更新时间未提供" to it) }
    }
}
