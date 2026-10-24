package tv.dandan.app.ui.screens

import tv.dandan.app.ui.requestContentFocus
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.ui.AnimeSearchRules
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.screens.HeroText
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens
import tv.dandan.core.AnimeSeason
import tv.dandan.core.AnimeSummary
import tv.dandan.core.EpisodeBinding
import tv.dandan.core.MediaEntry

/** 番剧栏目：顶部入口 + 本周更新海报墙。 */
@Composable
fun AnimeHubScreen(host: TvHost, owner: Any) {
    val loader = rememberLoader { host.repository.api.weekly() }
    var hero by remember { mutableStateOf(Hero("番剧", "每周更新与剧集资料来自弹弹play。此页不提供视频资源。")) }
    val wall = rememberWallFocus()
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        HeroBackdrop(hero)
        Column(Modifier.fillMaxSize()) {
        HeroText(hero)
        LazyColumn(state = listState, contentPadding = PaddingValues(start = TvDimens.screenPaddingStart, top = 8.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(TvDimens.rowSpacing), modifier = Modifier.fillMaxSize().topFade()) {
            item("entries") {
                ContentRow("浏览", listOf("search", "follows", "seasons", "magnet", "credentials"), firstItemFocus = wall.requester("entries"), focusIndex = wall.indexFor("entries"), onItemFocused = wall.onFocused("entries")) { _, item, modifier ->
                    when (item) {
                        "follows" -> TileCard("我的追番", { host.navigator.push(Route.AnimeFollows) }, modifier, "保存在本机，无需登录", Icons.Default.Favorite, accent = TvColors.amber, onFocus = { hero = Hero("我的追番", "在番剧详情页按“追番”加入；剧集可长按标记已看。") })
                        "magnet" -> TileCard("资源搜索节点", { host.navigator.push(Route.MagnetSettings) }, modifier, "配置后剧集可在线播放", Icons.Default.Share, onFocus = { hero = Hero("资源搜索节点", "填写第三方资源搜索服务后，详情页每一集都能“找资源”并边下边播。") })
                        "search" -> TileCard("搜索番剧", { host.navigator.push(Route.AnimeSearch()) }, modifier, "在手机输入名称", Icons.Default.Search, onFocus = { hero = Hero("搜索番剧", "在手机输入名称后，回到电视选择番剧与剧集。") })
                        "seasons" -> TileCard("按季度浏览", { host.navigator.push(Route.AnimeSeasons) }, modifier, "历年各季新番", Icons.Default.DateRange, onFocus = { hero = Hero("按季度浏览", "选择年份与季度，查看该季全部番剧。") })
                        else -> TileCard("开放平台凭证", { host.navigator.push(Route.Credentials) }, modifier, "AppId / AppSecret", Icons.Default.Settings, accent = TvColors.amber, onFocus = { hero = Hero("开放平台凭证", "番剧与弹幕服务需要你自己的弹弹play开发者凭证，不等于用户登录。") })
                    }
                }
            }
            when (val state = loader.value) {
                Load.Loading -> item { Box(Modifier.fillMaxWidth().height(200.dp)) { LoadingPane("正在获取每周番剧…") } }
                is Load.Failed -> item { InlineError(state.message, onRetry = loader::reload, onFix = { host.navigator.push(Route.Credentials) }) }
                is Load.Ready -> {
                    val groups = weeklyGroups(state.value)
                    if (groups.isEmpty()) item { EmptyPane("服务当前没有返回本周番剧，可搜索番剧或稍后重试。") }
                    groups.forEach { (label, list) ->
                        item("week-$label") {
                            ContentRow(label, list, key = { it.id }, trailing = "${list.size} 部", firstItemFocus = wall.requester("week-$label"), focusIndex = wall.indexFor("week-$label"), onItemFocused = wall.onFocused("week-$label")) { _, anime, modifier ->
                                PosterCard(anime.title, anime.imageUrl, { host.navigator.push(Route.AnimeDetail(anime)) }, modifier, badge = anime.rating.takeIf { it.isNotBlank() }.orEmpty(), onFocus = { hero = anime.hero(label) })
                            }
                        }
                    }
                }
            }
        }
        }
        // 等每周数据就绪（或失败）后再恢复焦点，避免加载期间先聚焦入口行而覆盖记住的位置。
        val rows = when (val state = loader.value) { is Load.Ready -> listOf("entries") + weeklyGroups(state.value).map { "week-${it.first}" }; is Load.Failed -> listOf("entries"); else -> null }
        LaunchedEffect(rows) { if (rows != null) wall.restore(listState, rows, "entries") }
    }
}

/** 海报网格：搜索结果与季度列表共用。 */
@Composable
fun PosterGrid(items: List<AnimeSummary>, onOpen: (AnimeSummary) -> Unit, onFocus: (AnimeSummary) -> Unit = {}, modifier: Modifier = Modifier, firstFocus: FocusRequester? = null) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val targetFocus = firstFocus ?: remember { FocusRequester() }
    val targetId = remember(items) { items.firstOrNull { it.id == focusedId }?.id ?: items.firstOrNull()?.id }
    LaunchedEffect(targetId) {
        val index = items.indexOfFirst { it.id == targetId }
        if (index > 0) gridState.scrollToItem(index)
        androidx.compose.runtime.withFrameNanos { }
        requestContentFocus(targetFocus)
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(TvDimens.posterWidth),
        horizontalArrangement = Arrangement.spacedBy(TvDimens.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(TvDimens.cardSpacing),
        contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, top = 12.dp, bottom = 48.dp),
        modifier = modifier.fillMaxSize().focusRestorer(),
    ) {
        items.forEach { anime ->
            item(key = anime.id) {
                PosterCard(anime.title, anime.imageUrl, { onOpen(anime) }, (if (anime.id == targetId) Modifier.focusRequester(targetFocus) else Modifier).onFocusChanged { if (it.isFocused) focusedId = anime.id }, subtitle = anime.rating.takeIf { it.isNotBlank() }?.let { "评分 $it" }.orEmpty(), onFocus = { onFocus(anime) })
            }
        }
    }
}

@Composable
fun AnimeSearchScreen(host: TvHost, owner: Any, bindingMedia: MediaEntry?) {
    var keyword by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<List<AnimeSummary>>?>(null) }
    val actions = rememberActions()
    val searchFocus = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    fun search() {
        host.phoneForm(owner, PhoneForm("搜索番剧", "提交名称后电视自动搜索，回到电视选择番剧与剧集。", listOf(PhoneField("keyword", "番剧名称", keyword, required = true, maxLength = 200))), validator = { AnimeSearchRules.validate(it.getValue("keyword")) }) { values ->
            keyword = values.getValue("keyword").trim()
            results = Load.Loading
            actions.run({ host.repository.api.search(keyword) }, { results = Load.Failed(it) }) { results = Load.Ready(it) }
        }
    }
    LaunchedEffect(Unit) { if (results == null) search(); requestContentFocus(searchFocus) }
    ScreenScaffold(if (bindingMedia == null) "搜索番剧" else "搜索并绑定弹幕", subtitle = bindingMedia?.title ?: "输入名称后选择番剧与剧集。", scrollable = false, headerTrailing = {
        TvButton(if (keyword.isBlank()) "在手机输入名称" else "重新搜索：$keyword", icon = Icons.Default.Search, onClick = ::search, modifier = Modifier.focusRequester(searchFocus))
    }) {
        when (val state = results) {
            null -> EmptyPane("扫描电视上的二维码，在手机输入番剧名称。")
            Load.Loading -> LoadingPane("正在搜索…")
            is Load.Failed -> ErrorPane(state.message, onRetry = ::search) { Spacer(Modifier.height(10.dp)); TvButton("配置开放平台凭证", filled = false, onClick = { host.navigator.push(Route.Credentials) }) }
            is Load.Ready -> {
                if (state.value.isEmpty()) EmptyPane("没有找到番剧，请尝试更短的名称。", actionLabel = "重新搜索") { search() }
                else PosterGrid(state.value, onOpen = { host.navigator.push(Route.AnimeDetail(it, bindingMedia)) }, firstFocus = firstResult)
            }
        }
    }
}

@Composable
fun AnimeSeasonsScreen(host: TvHost) {
    val loader = rememberLoader { host.repository.api.seasons() }
    ScreenScaffold("按季度浏览", subtitle = "选择服务提供的季度，再打开番剧。", scrollable = false) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在获取季度列表…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val seasons = state.value
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { requestContentFocus(first) }
                if (seasons.isEmpty()) EmptyPane("服务暂未返回可浏览的季度，请稍后重试。")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(TvDimens.rowSpacing), contentPadding = PaddingValues(bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
                    seasons.groupBy { it.year }.toSortedMap(compareByDescending { it }).forEach { (year, list) ->
                        item(key = year) {
                            ContentRow("$year 年", list.sortedByDescending { it.month }, key = { "${it.year}-${it.month}" }, firstItemFocus = if (year == seasons.first().year) first else null) { _, season, modifier ->
                                TileCard(season.name.ifBlank { "${season.month} 月" }, { host.navigator.push(Route.AnimeSeasonList(season)) }, modifier, subtitle = "${season.year} 年 ${season.month} 月新番", icon = Icons.Default.DateRange)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimeSeasonListScreen(host: TvHost, season: AnimeSeason) {
    val loader = rememberLoader { host.repository.api.season(season) }
    val first = remember { FocusRequester() }
    ScreenScaffold(season.name.ifBlank { "${season.year} 年 ${season.month} 月" }, subtitle = "本季番剧 · 确定查看简介与剧集", scrollable = false) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在获取季度番剧…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                if (state.value.isEmpty()) EmptyPane("这个季度暂无番剧资料。")
                else PosterGrid(state.value, onOpen = { host.navigator.push(Route.AnimeDetail(it)) }, firstFocus = first)
            }
        }
    }
}

private data class DetailData(val detail: tv.dandan.core.AnimeDetail, val following: Boolean, val watched: Set<Long>, val magnetReady: Boolean)

/**
 * 番剧详情：左侧海报、资料与追番按钮；右侧依次是标签、剧集、相关作品与相似作品。
 * 剧集：确定 = 找资源（磁链），长按 = 切换已看；绑定模式下确定 = 绑定此剧集。
 */
@Composable
fun AnimeDetailScreen(host: TvHost, anime: AnimeSummary, bindingMedia: MediaEntry?) {
    val loader = rememberLoader(anime.id, host.refreshEpoch) {
        val detail = host.repository.api.detail(anime.id)
        DetailData(detail, host.repository.isFollowing(anime.id), host.repository.watchedEpisodes(anime.id), tv.dandan.app.data.MagnetEngine.supported && host.repository.settings().magnetNode.isNotBlank())
    }
    val actions = rememberActions()
    val first = remember { FocusRequester() }
    var following by remember { mutableStateOf<Boolean?>(null) }
    var watched by remember { mutableStateOf<Set<Long>?>(null) }
    val ready = (loader.value as? Load.Ready)?.value
    LaunchedEffect(ready) { if (ready != null) { following = ready.following; watched = ready.watched } }
    val full = ready?.detail?.anime?.takeIf { it.title.isNotBlank() } ?: anime
    fun toggleWatched(ids: Collection<Long>, mark: Boolean) {
        val previous = watched
        watched = if (mark) previous.orEmpty() + ids else previous.orEmpty() - ids.toSet()
        actions.run({ host.repository.setWatched(anime.id, ids, mark) }, { watched = previous; host.notify(it) }) {}
    }
    Box(Modifier.fillMaxSize()) {
        if (full.imageUrl.isNotBlank()) {
            AsyncImage(model = full.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, modifier = Modifier.fillMaxHeight().fillMaxWidth(.7f).align(Alignment.TopEnd).blur(30.dp).alpha(.5f))
            BackdropScrim()
        }
        Row(Modifier.fillMaxSize().padding(start = TvDimens.screenPaddingStart, top = TvDimens.screenPaddingTop)) {
            Column(Modifier.width(290.dp).fillMaxHeight().padding(bottom = 24.dp)) {
                Box(Modifier.size(150.dp, 216.dp).clip(MaterialTheme.shapes.medium)) { Poster(full.imageUrl, full.title, Modifier.fillMaxSize()) }
                Spacer(Modifier.height(16.dp))
                Text(full.title, style = MaterialTheme.typography.headlineSmall, color = TvColors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (full.rating.isNotBlank()) Badge("评分 ${full.rating}")
                    if (full.airDay in 0..7) Badge(airDayLabel(full.airDay), color = TvColors.accent)
                }
                Spacer(Modifier.height(10.dp))
                Text(full.description.ifBlank { "服务未提供简介。" }, style = MaterialTheme.typography.bodySmall, color = TvColors.muted, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(32.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                when (val state = loader.value) {
                    Load.Loading -> LoadingPane("正在获取简介与剧集…")
                    is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
                    is Load.Ready -> {
                        val data = state.value
                        val episodes = data.detail.episodes
                        val marks = watched ?: data.watched
                        LaunchedEffect(Unit) { requestContentFocus(first) }
                        LazyColumn(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, bottom = 48.dp), modifier = Modifier.fillMaxSize().focusRestorer()) {
                            if (data.detail.tags.isNotEmpty()) item("tags") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp), modifier = Modifier.focusRestorer()) {
                                    items(data.detail.tags.take(24), key = { it.id.toString() + it.name }) { tag -> TvChip(tag.name, onClick = { host.navigator.push(Route.AnimeTagSearch(tag.name)) }) }
                                }
                            }
                            item("episodes-title") {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                    Text(if (bindingMedia == null) "剧集 · 已看 ${marks.count { id -> episodes.any { it.id == id } }}/${episodes.size}" else "为“${bindingMedia.title}”选择剧集", style = MaterialTheme.typography.headlineSmall, color = TvColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (bindingMedia == null && following != null) {
                                        val isFollowing = following == true
                                        TvButton(if (isFollowing) "已追番" else "追番", icon = if (isFollowing) Icons.Default.Favorite else Icons.Default.FavoriteBorder, filled = !isFollowing, onClick = {
                                            following = !isFollowing
                                            actions.run({ host.repository.toggleFollow(full) }, { following = isFollowing; host.notify(it) }) { now -> host.notify(if (now) "已加入我的追番" else "已取消追番") }
                                        })
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    if (bindingMedia == null && episodes.isNotEmpty()) {
                                        val allWatched = episodes.all { it.id in marks }
                                        TvButton(if (allWatched) "全部标为未看" else "全部标为已看", filled = false, onClick = { toggleWatched(episodes.map { it.id }, !allWatched) })
                                    }
                                }
                            }
                            if (bindingMedia == null) item("episodes-hint") { Note(if (data.magnetReady) "确定：搜索该集资源并在线播放 · 长按：标记已看 / 未看" else if (!tv.dandan.app.data.MagnetEngine.supported) "此设备架构不支持磁链播放 · 长按剧集标记已看 / 未看" else "确定：配置资源搜索节点后可在线播放 · 长按：标记已看 / 未看") }
                            if (episodes.isEmpty()) item { EmptyPane("服务没有提供剧集列表，请稍后重试。", actionLabel = "刷新") { loader.reload() } }
                            items(episodes, key = { it.id }) { episode ->
                                val label = (episode.number.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()) + episode.title
                                val seen = episode.id in marks
                                ActionRow(
                                    label,
                                    modifier = if (episode == episodes.first()) Modifier.focusRequester(first) else Modifier,
                                    icon = if (seen) Icons.Default.Check else Icons.Default.PlayArrow,
                                    subtitle = if (bindingMedia != null) "确定绑定此剧集" else if (seen) "已看" else "",
                                    value = if (bindingMedia == null && data.magnetReady) "找资源" else "",
                                    onLongClick = if (bindingMedia == null) ({ toggleWatched(listOf(episode.id), !seen) }) else null,
                                    onClick = {
                                        when {
                                            bindingMedia != null -> actions.run({ host.repository.saveEpisode(bindingMedia, EpisodeBinding(episode.id)) }, host::notify) { host.notify("剧集已绑定，返回视频即可播放"); host.navigator.pop(); host.navigator.pop() }
                                            !tv.dandan.app.data.MagnetEngine.supported -> host.notify("此设备架构不支持磁链播放，可长按标记已看")
                                            !data.magnetReady -> host.navigator.push(Route.MagnetSettings)
                                            else -> host.navigator.push(Route.MagnetSearch(tv.dandan.core.MagnetRules.keyword(full.title, episode.number), full, episode))
                                        }
                                    },
                                )
                            }
                            if (bindingMedia == null && data.detail.relateds.isNotEmpty()) item("relateds") {
                                Spacer(Modifier.height(18.dp))
                                ContentRow("相关作品", data.detail.relateds.take(20), key = { "r-" + it.id }) { _, item, modifier -> PosterCard(item.title, item.imageUrl, { host.navigator.push(Route.AnimeDetail(item)) }, modifier, badge = item.rating) }
                            }
                            if (bindingMedia == null && data.detail.similars.isNotEmpty()) item("similars") {
                                Spacer(Modifier.height(10.dp))
                                ContentRow("相似作品", data.detail.similars.take(20), key = { "s-" + it.id }) { _, item, modifier -> PosterCard(item.title, item.imageUrl, { host.navigator.push(Route.AnimeDetail(item)) }, modifier, badge = item.rating) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 按标签浏览：海报网格。 */
@Composable
fun AnimeTagSearchScreen(host: TvHost, tag: String) {
    val loader = rememberLoader(tag) { host.repository.api.searchByTag(listOf(tag)) }
    ScreenScaffold("标签：$tag", subtitle = "带有此标签的番剧 · 确定查看简介与剧集", scrollable = false) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在按标签搜索…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> if (state.value.isEmpty()) EmptyPane("没有找到带此标签的番剧。") else PosterGrid(state.value, onOpen = { host.navigator.push(Route.AnimeDetail(it)) })
        }
    }
}

/** 我的追番：本地保存的关注列表。 */
@Composable
fun AnimeFollowsScreen(host: TvHost) {
    val loader = rememberLoader(host.refreshEpoch) { host.repository.follows() }
    ScreenScaffold("我的追番", subtitle = "保存在这台电视上，不需要登录。在番剧详情页按“追番”加入或移除。", scrollable = false) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取追番…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> if (state.value.isEmpty()) EmptyPane("还没有追番。打开任意番剧详情，选择“追番”。") else PosterGrid(state.value, onOpen = { host.navigator.push(Route.AnimeDetail(it)) })
        }
    }
}

internal fun airDayLabel(day: Int) = when (day) { 1 -> "周一更新"; 2 -> "周二更新"; 3 -> "周三更新"; 4 -> "周四更新"; 5 -> "周五更新"; 6 -> "周六更新"; 0, 7 -> "周日更新"; else -> "" }
