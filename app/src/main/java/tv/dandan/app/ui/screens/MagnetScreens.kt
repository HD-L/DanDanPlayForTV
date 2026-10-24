package tv.dandan.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import tv.dandan.app.data.MagnetEngine
import tv.dandan.app.data.MagnetPlayback
import tv.dandan.app.data.MagnetSearchApi
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.requestContentFocus
import tv.dandan.app.ui.theme.TvDimens
import tv.dandan.core.EpisodeBinding
import tv.dandan.core.MagnetResource
import tv.dandan.core.MagnetRules
import tv.dandan.core.TorrentFile

/**
 * 资源搜索结果：字幕组与分类以胶囊在顶部筛选（只在已加载的结果里过滤，不额外请求），
 * 确定进入种子文件列表；关键字不准时用手机修改。
 */
@Composable
fun MagnetSearchScreen(host: TvHost, owner: Any, route: Route.MagnetSearch) {
    var keyword by rememberSaveable { mutableStateOf(route.keyword) }
    var page by rememberSaveable { mutableIntStateOf(1) }
    var results by remember { mutableStateOf<List<MagnetResource>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var subgroup by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    val api = remember { MagnetSearchApi { host.repository.settings().magnetNode } }
    val loader = rememberLoader(keyword, page) { api.search(keyword, page = page) }
    // 读取器在后台刷新时保留旧结果，这里记录结果对应的关键字与页码，用来判断是否仍在搜索。
    var loadedFor by rememberSaveable { mutableStateOf("") }
    val busy = loadedFor != "$keyword#$page" && loader.value !is Load.Failed
    LaunchedEffect(loader.value) {
        (loader.value as? Load.Ready)?.value?.let { result ->
            loadedFor = "$keyword#$page"
            // 部分节点忽略 page 参数并始终返回 HasMore=true：本页没有新资源时停止翻页，避免"加载更多"无限重复。
            val merged = (if (page == 1) result.items else results + result.items).distinctBy { MagnetRules.infoHash(it.magnet) }
            hasMore = result.hasMore && (page == 1 || merged.size > results.size)
            results = merged
        }
    }
    val editFocus = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    fun editKeyword() {
        host.phoneForm(owner, PhoneForm("修改资源搜索关键字", "番剧名与集数用空格分隔，例如：葬送的芙莉莲 03。", listOf(PhoneField("keyword", "关键字", keyword, required = true, maxLength = 200)))) { values ->
            keyword = values.getValue("keyword").trim(); page = 1; subgroup = ""; type = ""; results = emptyList()
        }
    }
    val subtitle = buildString {
        append("关键字：$keyword")
        if (route.episode != null) append(" · 播放后自动关联本集弹幕")
    }
    ScreenScaffold("找资源", subtitle = subtitle, scrollable = false, headerTrailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("修改关键字", icon = Icons.Default.Edit, filled = false, onClick = ::editKeyword, modifier = Modifier.focusRequester(editFocus))
            TvButton("节点设置", icon = Icons.Default.Settings, filled = false, onClick = { host.navigator.push(Route.MagnetSettings) })
        }
    }) {
        val state = loader.value
        when {
            state is Load.Failed && results.isEmpty() -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() }) {
                Spacer(Modifier.height(12.dp)); TvButton("修改关键字", filled = false, onClick = ::editKeyword)
            }
            busy && results.isEmpty() -> LoadingPane("正在通过节点搜索资源…")
            else -> {
                val subgroups = remember(results) { results.map { it.subgroupName }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(30) }
                val types = remember(results) { results.map { it.typeName }.filter { it.isNotBlank() }.distinct().take(12) }
                val visible = remember(results, subgroup, type) { results.filter { (subgroup.isBlank() || it.subgroupName == subgroup) && (type.isBlank() || it.typeName == type) } }
                // 筛选标签与计数固定在列表上方：TV 上列表按焦点"居中偏上"滚动，放在列表里会被滚出视野。
                LaunchedEffect(results.isNotEmpty()) { if (results.isNotEmpty() && page == 1) requestContentFocus(firstResult) }
                if (results.isEmpty()) { EmptyPane("没有找到资源。可以缩短关键字，或只保留番剧名。", actionLabel = "修改关键字") { editKeyword() }; return@ScreenScaffold }
                if (subgroups.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.padding(end = TvDimens.screenPaddingEnd).focusRestorer()) {
                    item { TvChip("全部字幕组", onClick = { subgroup = "" }, selected = subgroup.isBlank()) }
                    items(subgroups) { name -> TvChip(name, onClick = { subgroup = if (subgroup == name) "" else name }, selected = subgroup == name) }
                }
                if (types.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp), modifier = Modifier.padding(top = 6.dp, end = TvDimens.screenPaddingEnd).focusRestorer()) {
                    item { TvChip("全部分类", onClick = { type = "" }, selected = type.isBlank()) }
                    items(types) { name -> TvChip(name, onClick = { type = if (type == name) "" else name }, selected = type == name) }
                }
                Note("${visible.size} 条资源" + if (visible.size != results.size) "（共 ${results.size} 条）" else "", modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(state = rememberLazyListState(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, bottom = 40.dp), modifier = Modifier.fillMaxSize().topFade().focusRestorer()) {
                    items(visible, key = { it.magnet }) { item ->
                        ActionRow(
                            item.title,
                            modifier = if (item == visible.first()) Modifier.focusRequester(firstResult) else Modifier,
                            subtitle = listOf(item.subgroupName, item.typeName, item.size, item.publishDate).filter { it.isNotBlank() }.joinToString(" · "),
                            icon = Icons.Default.Share,
                            chevron = true,
                            onClick = { host.navigator.push(Route.TorrentFiles(item.magnet, item.title, route.anime, route.episode)) },
                        )
                    }
                    if (hasMore) item("more") {
                        ActionRow(if (busy) "正在加载…" else "加载更多", icon = Icons.Default.Refresh, enabled = !busy, onClick = { page++ })
                    }
                }
            }
        }
    }
}

private data class TorrentData(val hash: String, val playable: List<TorrentFile>, val total: Int)

/** 种子文件列表：获取种子后列出视频；选中即开始边下边播，同一种子的视频组成连播队列。 */
@Composable
fun TorrentFilesScreen(host: TvHost, route: Route.TorrentFiles) {
    val actions = rememberActions()
    val loader = rememberLoader(route.magnet) {
        val engine = MagnetEngine.get(host.appContext)
        val torrent = engine.fetchTorrent(route.magnet)
        val files = engine.torrentFiles(torrent)
        TorrentData(MagnetRules.infoHash(route.magnet)!!, MagnetRules.playable(files), files.size)
    }
    val first = remember { FocusRequester() }
    ScreenScaffold("选择要播放的文件", subtitle = route.title, scrollable = false) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在获取种子信息，冷门资源可能需要更久…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val data = state.value
                LaunchedEffect(Unit) { requestContentFocus(first) }
                if (data.playable.isEmpty()) { EmptyPane("种子里的 ${data.total} 个文件都不是视频。"); return@ScreenScaffold }
                val queue = data.playable.map { MagnetPlayback.entry(data.hash, it) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, bottom = 40.dp), modifier = Modifier.fillMaxSize().focusRestorer()) {
                    item { Note("${data.playable.size} 个视频" + (if (data.total > data.playable.size) "（另有 ${data.total - data.playable.size} 个非视频文件）" else "") + " · 确定开始边下边播，开头需要缓冲") }
                    items(data.playable.indices.toList(), key = { data.playable[it].index }) { position ->
                        val file = data.playable[position]
                        ActionRow(file.name, modifier = if (position == 0) Modifier.focusRequester(first) else Modifier, icon = Icons.Default.PlayArrow, subtitle = listOf(formatSize(file.sizeBytes), file.subPath).filter { it.isNotBlank() }.joinToString(" · "), onClick = {
                            val entry = queue[position]
                            val episode = route.episode
                            // 从剧集进入时，用户选中的文件就是这一集：绑定官方剧集，播放时直接加载该集弹幕。
                            if (episode != null) actions.run({ host.repository.saveEpisode(entry, EpisodeBinding(episode.id)) }, host::notify) { host.play(entry, queue) }
                            else host.play(entry, queue)
                        })
                    }
                }
            }
        }
    }
}

/** 磁链设置：资源搜索节点（手机填写）、缓存占用与清理、手动打开磁链。 */
@Composable
fun MagnetSettingsScreen(host: TvHost, owner: Any) {
    val actions = rememberActions()
    val loader = rememberLoader(host.refreshEpoch) {
        val engine = if (MagnetEngine.supported) MagnetEngine.get(host.appContext) else null
        Triple(host.repository.settings(), engine?.cacheBytes() ?: 0L, engine?.torrentCount() ?: 0)
    }
    val first = remember { FocusRequester() }
    ScreenScaffold("磁链与资源搜索", subtitle = "资源来自你自己填写的第三方节点；弹弹 TV 不提供、不托管任何资源。") {
        if (!MagnetEngine.supported) Note("此设备 CPU 架构不受迅雷下载引擎支持（仅 arm64-v8a / armeabi-v7a），磁链播放不可用。", emphasis = true)
        when (val state = loader.value) {
            Load.Loading -> LoadingPane()
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val (settings, cache, torrents) = state.value
                LaunchedEffect(Unit) { requestContentFocus(first) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("资源搜索节点")
                    ActionRow("节点地址", modifier = Modifier.focusRequester(first), icon = Icons.Default.Edit, value = settings.magnetNode.ifBlank { "未配置" }, subtitle = "格式 http(s)://域名或IP[:端口]，在手机填写", onClick = {
                        host.phoneForm(owner, PhoneForm("资源搜索节点", "填写第三方资源搜索服务的地址，例如 http://192.168.1.10:8080。留空表示停用。", listOf(PhoneField("node", "节点地址", settings.magnetNode, maxLength = 200))),
                            validator = { values -> values.getValue("node").trim().takeIf { it.isNotBlank() }?.let { runCatching { MagnetRules.normalizeNode(it); null }.getOrElse { e -> e.message } } }) { values ->
                            val node = values.getValue("node").trim().let { if (it.isBlank()) "" else MagnetRules.normalizeNode(it) }
                            actions.run({ host.repository.saveSettings(host.repository.settings().copy(magnetNode = node)) }, host::notify) { host.notify(if (node.isBlank()) "已停用资源搜索" else "节点已保存"); loader.reload() }
                        }
                    })
                    if (settings.magnetNode.isNotBlank()) ActionRow("测试节点", icon = Icons.Default.Search, subtitle = "读取节点的分类列表", onClick = {
                        actions.run({ MagnetSearchApi { settings.magnetNode }.types() }, { host.notify("节点不可用：$it") }) { host.notify("节点可用，返回 ${it.size} 个分类") }
                    })
                    if (MagnetEngine.supported) {
                        SectionTitle("播放")
                        if (settings.magnetNode.isNotBlank()) ActionRow("搜索资源", icon = Icons.Default.Search, chevron = true, subtitle = "在手机输入关键字，电视列出节点结果", onClick = {
                            host.phoneForm(owner, PhoneForm("搜索资源", "番剧名与集数用空格分隔。", listOf(PhoneField("keyword", "关键字", required = true, maxLength = 200)))) { values -> host.navigator.push(Route.MagnetSearch(values.getValue("keyword").trim())) }
                        })
                        ActionRow("打开磁链", icon = Icons.Default.Share, chevron = true, subtitle = "在手机粘贴磁链，电视列出种子里的视频", onClick = { openMagnetForm(host, owner) })
                        SectionTitle("缓存")
                        InfoLine("边下边播缓存", formatSize(cache).takeIf { cache > 0 } ?: "空")
                        InfoLine("已保存种子", "$torrents 个")
                        ActionRow("清理缓存", icon = Icons.Default.Clear, danger = true, subtitle = "删除已下载的视频片段并停止当前任务；种子文件保留以便续播", onClick = {
                            actions.run({ MagnetEngine.get(host.appContext).clearCache() }, host::notify) { host.notify("缓存已清理"); loader.reload() }
                        })
                    }
                }
            }
        }
    }
}

/** 手机粘贴磁链后进入种子文件列表。 */
fun openMagnetForm(host: TvHost, owner: Any) {
    host.phoneForm(owner, PhoneForm("打开磁链", "粘贴以 magnet:? 开头、含 40 位 info hash 的磁链，或直接粘贴 40 位哈希。", listOf(PhoneField("magnet", "磁链", required = true, multiline = true, maxLength = 4000))),
        validator = { values -> if (MagnetRules.infoHash(values.getValue("magnet")) == null) "没有识别到 info hash（40 位十六进制或 32 位 base32）" else null }) { values ->
        val text = values.getValue("magnet").trim()
        val hash = MagnetRules.infoHash(text)!!
        val magnet = if (text.startsWith("magnet:", ignoreCase = true)) text else "magnet:?xt=urn:btih:$hash"
        val title = Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: hash
        host.navigator.push(Route.TorrentFiles(magnet, title))
    }
}
