package tv.dandan.app.ui.screens

import tv.dandan.app.ui.requestContentFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.SourceFormRules
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.chinese
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens
import tv.dandan.core.*
import java.util.UUID

/** 发起手机表单添加/修改媒体源；本地目录提交后仍需回到电视授权。 */
fun requestSourceForm(host: TvHost, owner: Any, original: MediaSource? = null, done: () -> Unit = { host.navigator.switchTab(1) }) {
    host.phoneForm(owner, SourceFormRules.form(original), validator = { SourceFormRules.validate(it, original) }) { values ->
        val edited = SourceFormRules.candidate(values, original, UUID.randomUUID().toString())
        fun save(source: MediaSource) {
            host.notify("正在保存媒体源…")
            host.background({ host.repository.saveSource(source) }) { result ->
                result.fold({ host.notify("媒体源已保存"); done() }, { host.notify(it.message ?: "保存失败") })
            }
        }
        if (edited.kind == SourceKind.DOCUMENT_TREE && edited.location.isBlank()) {
            host.notify("请在电视系统选择媒体目录并授予读取权限")
            host.pickDirectory(cancelled = { host.notify("已取消目录授权，媒体源未保存") }) { uri ->
                runCatching { SourceFormRules.withDirectory(edited, uri.toString()) }.fold(::save) { host.notify(it.message ?: "目录授权无效，请重新选择") }
            }
        } else save(edited)
    }
}

private data class DirectoryData(val source: MediaSource, val entries: List<MediaEntry>, val history: Map<String, PlaybackProgress>)

/** 目录页：目录在前，视频显示大小、进度与关联标签；长按视频打开文件操作。 */
@Composable
fun DirectoryScreen(host: TvHost, owner: Any, route: Route.Directory) {
    var query by rememberSaveable { mutableStateOf("") }
    var excluded by rememberSaveable { mutableStateOf("") }
    val loader = rememberLoader(host.refreshEpoch) {
        val latest = host.repository.sources().firstOrNull { it.id == route.source.id } ?: throw java.io.IOException("此媒体源已删除，请返回媒体库")
        val entries = host.repository.listEntries(latest, if (route.directory == route.source.location) latest.location else route.directory)
        DirectoryData(latest, entries, host.repository.history().associateBy { it.media.id })
    }
    val isRoot = route.directory == route.source.location
    ScreenScaffold(route.name, subtitle = if (isRoot) route.source.kind.chinese() + " · 长按视频打开文件操作" else "长按视频打开文件操作", scrollable = false, headerTrailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(if (query.isBlank() && excluded.isBlank()) "搜索 / 过滤" else "过滤中", icon = Icons.Default.Search, filled = false, onClick = {
                host.phoneForm(owner, PhoneForm("搜索和文件名过滤", "词语用空格分隔。搜索词需全部命中；排除词只隐藏文件，目录仍可进入。", listOf(PhoneField("query", "搜索词", query, maxLength = 200), PhoneField("excluded", "排除词", excluded, maxLength = 1000)))) { values ->
                    query = values.getValue("query").trim(); excluded = values.getValue("excluded").trim()
                }
            })
            if (query.isNotBlank() || excluded.isNotBlank()) TvButton("清除", filled = false, onClick = { query = ""; excluded = "" })
            if (isRoot) TvButton("管理", icon = Icons.Default.Settings, filled = false, onClick = { host.navigator.push(Route.SourceActions(route.source)) })
        }
    }) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在列出目录…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() }) {
                Spacer(Modifier.height(12.dp)); TvButton("修改此媒体源", filled = false, onClick = { host.navigator.push(Route.SourceEditor(route.source)) })
            }
            is Load.Ready -> {
                val data = state.value
                val visible = remember(data, query, excluded) { runCatching { LibraryRules.visible(data.entries, query, excluded) }.getOrDefault(data.entries) }
                val videos = remember(visible) { visible.filterNot { it.directory } }
                if (visible.isEmpty()) EmptyPane(if (data.entries.isEmpty()) "此目录没有可播放视频或子目录。" else "没有符合搜索和过滤条件的项目。")
                else MediaList(visible, videos, data.history, host, trailingInfo = "${visible.size} 项", onDirectory = { host.navigator.push(Route.Directory(data.source, it.uri, it.title)) })
            }
        }
    }
}

/** 视频/目录列表：初始焦点在第一项，离开再回来恢复到原项。 */
@Composable
fun MediaList(visible: List<MediaEntry>, queue: List<MediaEntry>, history: Map<String, PlaybackProgress>, host: TvHost, trailingInfo: String = "", onDirectory: (MediaEntry) -> Unit = {}) {
    val targetFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var focusedId by rememberSaveable { mutableStateOf<String?>(null) }
    val targetId = remember(visible) { visible.firstOrNull { it.id == focusedId }?.id ?: visible.firstOrNull()?.id }
    LaunchedEffect(targetId) {
        val index = visible.indexOfFirst { it.id == targetId }
        if (index > 0) listState.scrollToItem(index)
        androidx.compose.runtime.withFrameNanos { }
        requestContentFocus(targetFocus)
    }
    Column(Modifier.fillMaxSize()) {
        if (trailingInfo.isNotBlank()) Text(trailingInfo, style = MaterialTheme.typography.labelMedium, color = TvColors.muted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, bottom = 40.dp), modifier = Modifier.fillMaxSize().focusRestorer()) {
            items(visible, key = { it.id }) { entry ->
                val modifier = (if (entry.id == targetId) Modifier.focusRequester(targetFocus) else Modifier).onFocusChanged { if (it.isFocused) focusedId = entry.id }
                if (entry.directory) ActionRow(entry.title, onClick = { onDirectory(entry) }, modifier = modifier, subtitle = "目录", icon = Icons.Default.List, chevron = true)
                else MediaRow(entry, history[entry.id], modifier, onPlay = { host.play(entry, queue) }, onActions = { host.navigator.push(Route.MediaActions(entry, queue)) })
            }
        }
    }
}

@Composable
fun MediaRow(entry: MediaEntry, progress: PlaybackProgress?, modifier: Modifier = Modifier, onPlay: () -> Unit, onActions: () -> Unit) {
    val info = buildList {
        if (progress != null) add(if (ratio(progress) >= .97f) "已看完" else "看到 ${progressLabel(progress)}")
        if (entry.sizeBytes > 0) add(formatSize(entry.sizeBytes))
        if (entry.subtitleUri.isNotBlank()) add("字幕")
        if (entry.danmakuUri.isNotBlank()) add("弹幕")
    }.joinToString(" · ")
    Column(modifier.fillMaxWidth()) {
        androidx.tv.material3.ListItem(
            selected = false,
            onClick = onPlay,
            onLongClick = onActions,
            shape = androidx.tv.material3.ListItemDefaults.shape(MaterialTheme.shapes.small),
            colors = androidx.tv.material3.ListItemDefaults.colors(containerColor = TvColors.surfaceVariant.copy(alpha = .55f), contentColor = TvColors.onSurface, focusedContainerColor = TvColors.surfaceBright, focusedContentColor = TvColors.onSurface),
            scale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f),
            border = androidx.tv.material3.ListItemDefaults.border(focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, TvColors.accent), shape = MaterialTheme.shapes.small)),
            leadingContent = { androidx.tv.material3.Icon(Icons.Default.PlayArrow, contentDescription = null, tint = if (progress != null) TvColors.amber else TvColors.accent) },
            headlineContent = { Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            supportingContent = if (info.isBlank()) null else { { Text(info, style = MaterialTheme.typography.bodySmall, color = TvColors.muted, maxLines = 1) } },
            trailingContent = { Text("长按：操作", style = MaterialTheme.typography.labelSmall, color = TvColors.muted) },
        )
        if (progress != null && ratio(progress) >= 0f) Box(Modifier.padding(horizontal = 14.dp)) { ProgressStripe(ratio(progress), Modifier.height(3.dp)) }
    }
}

@Composable
fun FavoritesScreen(host: TvHost, owner: Any) {
    val loader = rememberLoader(host.refreshEpoch) { host.repository.favorites() to host.repository.history().associateBy { it.media.id } }
    ScreenScaffold("我的收藏", subtitle = "收藏按媒体编号保存；文件移动后请从新位置重新收藏。", scrollable = false) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取收藏…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val (entries, history) = state.value
                if (entries.isEmpty()) EmptyPane("还没有收藏。在目录或索引中长按视频，选择“收藏此视频”。")
                else MediaList(entries, entries, history, host, trailingInfo = "${entries.size} 个收藏")
            }
        }
    }
}

private data class IndexData(val sources: List<MediaSource>, val entries: List<MediaEntry>, val states: List<ScanSnapshot>, val history: Map<String, PlaybackProgress>)

@Composable
fun LibraryIndexScreen(host: TvHost, owner: Any) {
    var query by rememberSaveable { mutableStateOf("") }
    val loader = rememberLoader(host.refreshEpoch) { IndexData(host.repository.sources(), host.repository.indexedEntries(), host.repository.library.states(), host.repository.history().associateBy { it.media.id }) }
    val actions = rememberActions()
    val refreshFocus = remember { FocusRequester() }
    // 有扫描在进行时每 3 秒刷新一次状态
    val running = (loader.value as? Load.Ready)?.value?.states?.any { it.phase == ScanPhase.RUNNING } == true
    LaunchedEffect(running) { while (running) { kotlinx.coroutines.delay(3000); loader.reload() } }
    ScreenScaffold("索引与扫描", subtitle = "后台扫描会继续运行。单次最多 10 分钟、10000 个目录、50000 个视频，NAS 系统目录自动跳过。", scrollable = false, headerTrailing = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(if (query.isBlank()) "搜索名称" else "搜索：$query", icon = Icons.Default.Search, filled = false, onClick = {
                host.phoneForm(owner, PhoneForm("搜索索引", "词语用空格分隔，需全部命中。", listOf(PhoneField("query", "搜索词", query, maxLength = 200)))) { query = it.getValue("query").trim() }
            })
            TvButton("刷新状态", icon = Icons.Default.Refresh, filled = false, onClick = loader::reload, modifier = Modifier.focusRequester(refreshFocus))
        }
    }) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取索引与扫描状态…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val data = state.value
                val visible = remember(data, query) { runCatching { LibraryRules.visible(data.entries, query) }.getOrDefault(data.entries) }
                val firstSource = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (data.sources.isNotEmpty()) requestContentFocus(firstSource) else requestContentFocus(refreshFocus) }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, bottom = 40.dp), modifier = Modifier.fillMaxSize().focusRestorer()) {
                    if (data.sources.isEmpty()) item { EmptyPane("请先添加媒体源，再扫描目录建立索引。") }
                    items(data.sources, key = { "src-" + it.id }) { source ->
                        val scan = data.states.firstOrNull { it.sourceId == source.id }
                        val count = data.entries.count { it.sourceId == source.id }
                        val running = scan?.phase == ScanPhase.RUNNING
                        ActionRow(
                            source.name,
                            modifier = if (source == data.sources.first()) Modifier.focusRequester(firstSource) else Modifier,
                            onClick = { if (running) { host.repository.library.cancel(source.id); loader.reload() } else actions.run({ host.repository.library.start(source.id) }, host::notify) { loader.reload() } },
                            subtitle = listOfNotNull(scan?.phase?.chinese() ?: "尚未扫描", "$count 个视频", scan?.let { "${it.directories} 个目录" }, scan?.message?.takeIf { it.isNotBlank() }).joinToString(" · "),
                            icon = Icons.Default.Refresh,
                            value = if (running) "取消扫描" else if (scan == null) "扫描" else "重新扫描",
                        )
                    }
                    item { SectionTitle(if (visible.isEmpty()) "索引中的视频" else "索引中的视频 · ${visible.size} 项") }
                    if (visible.isEmpty()) item { EmptyPane(if (data.entries.isEmpty()) "索引中还没有视频。扫描后使用“刷新状态”查看结果。" else "没有符合搜索条件的视频。") }
                    items(visible, key = { it.id }) { entry -> MediaRow(entry, data.history[entry.id], onPlay = { host.play(entry, visible) }, onActions = { host.navigator.push(Route.MediaActions(entry, visible)) }) }
                }
            }
        }
    }
}

internal fun ScanPhase.chinese() = when (this) {
    ScanPhase.IDLE -> "尚未扫描"; ScanPhase.RUNNING -> "正在扫描"; ScanPhase.COMPLETE -> "扫描完成"
    ScanPhase.CANCELLED -> "已取消，索引可能不完整"; ScanPhase.LIMIT_REACHED -> "达到预算，索引可能不完整"; ScanPhase.FAILED -> "扫描失败，索引可能不完整"
}

@Composable
fun SourceActionsScreen(host: TvHost, owner: Any, original: MediaSource) {
    val loader = rememberLoader { host.repository.sources().firstOrNull { it.id == original.id } }
    val actions = rememberActions()
    ScreenScaffold("管理媒体源", subtitle = "${original.name} · ${original.kind.chinese()}") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane()
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val source = state.value
                if (source == null) { EmptyPane("此来源已删除", actionLabel = "返回媒体库") { host.navigator.popToRoot() }; return@ScreenScaffold }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow("修改来源", onClick = { host.navigator.push(Route.SourceEditor(source)) }, subtitle = "在手机修改连接信息，或重新授权本地目录", icon = Icons.Default.Settings, chevron = true)
                    ActionRow("扫描此媒体源", onClick = { actions.run({ host.repository.library.start(source.id) }, host::notify) { host.navigator.push(Route.LibraryIndex) } }, subtitle = "后台建立索引；单次最多 10 分钟、10000 个目录、50000 个视频，NAS 系统目录自动跳过", icon = Icons.Default.Refresh)
                    ActionRow("删除来源", onClick = {
                        host.navigator.push(Route.Confirm("删除来源", "将删除“${source.name}”的连接配置及关联索引、收藏、观看历史与剧集绑定。视频文件会保留。", "确认删除", destructive = true, operation = { host.repository.deleteSource(source.id) }, afterwards = { host.navigator.switchTab(1) }))
                    }, subtitle = "同时清理该来源的索引、收藏、观看历史和剧集绑定；视频文件保留", danger = true)
                }
            }
        }
    }
}

@Composable
fun SourceEditorScreen(host: TvHost, owner: Any, original: MediaSource) {
    val loader = rememberLoader { host.repository.sources().firstOrNull { it.id == original.id } }
    val actions = rememberActions()
    ScreenScaffold("修改媒体源", subtitle = "扫码在手机修改信息并保存，同一张表单可更换来源类型。返回可取消。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取来源配置…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val source = state.value
                if (source == null) { EmptyPane("此媒体源已删除", actionLabel = "返回媒体库") { host.navigator.popToRoot() }; return@ScreenScaffold }
                InfoLine("显示名称", source.name.ifBlank { "待填写" })
                InfoLine("来源类型", source.kind.chinese())
                InfoLine(if (source.kind == SourceKind.DOCUMENT_TREE) "本地目录" else "连接地址", if (source.location.isBlank()) "待填写" else if (source.kind == SourceKind.DOCUMENT_TREE) "已通过系统授权" else source.location)
                if (source.kind in listOf(SourceKind.WEBDAV, SourceKind.SMB, SourceKind.FTP)) InfoLine("账号 / 密码", "${if (source.username.isBlank()) "未填写" else "已保存"} / ${if (source.password.isBlank()) "未填写" else "已保存"}")
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow("在手机修改并保存", onClick = { requestSourceForm(host, owner, source) { host.navigator.pop(); loader.reload() } }, icon = Icons.Default.Settings, chevron = true)
                    if (source.kind == SourceKind.DOCUMENT_TREE) ActionRow("重新授权本地目录", onClick = {
                        host.pickDirectory { uri ->
                            runCatching { SourceFormRules.withDirectory(source, uri.toString()) }
                                .fold({ edited -> actions.run({ host.repository.saveSource(edited) }, host::notify) { host.notify("本地目录已更新"); host.navigator.pop() } }, { host.notify(it.message ?: "目录授权无效，请重试") })
                        }
                    }, subtitle = "手机不能代替电视授予文件读取权限", icon = Icons.Default.List)
                }
            }
        }
    }
}

/** 通用确认页：破坏性操作用红色主按钮，初始焦点在取消。 */
@Composable
fun ConfirmScreen(host: TvHost, route: Route.Confirm) {
    val actions = rememberActions()
    var busy by remember { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestContentFocus(cancelFocus) }
    ScreenScaffold(route.title) {
        Note(route.message, emphasis = true)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("取消", onClick = { host.navigator.pop() }, filled = false, modifier = Modifier.focusRequester(cancelFocus), enabled = !busy)
            TvButton(if (busy) "处理中…" else route.confirmLabel, danger = route.destructive, enabled = !busy, onClick = {
                busy = true
                actions.run(route.operation, { busy = false; host.notify(it) }) { busy = false; host.navigator.pop(); route.afterwards() }
            })
        }
    }
}

@Composable
fun VideoUrlScreen(host: TvHost, owner: Any) {
    var location by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestContentFocus(focus) }
    ScreenScaffold("打开视频链接", subtitle = "在手机浏览器填写可直接播放的视频地址，提交后电视自动开始播放。") {
        Note("支持 HTTP / HTTPS、HLS 和 DASH。地址不能包含内嵌账号密码。番剧资料页不提供视频。")
        if (location.isNotBlank()) InfoLine("上次地址", location)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionRow("在手机填写视频地址并播放", modifier = Modifier.focusRequester(focus), icon = Icons.Default.PlayArrow, onClick = {
                host.phoneForm(owner, PhoneForm("打开视频链接", "支持 HTTP / HTTPS、HLS 和 DASH。地址不能包含内嵌账号密码。", listOf(PhoneField("location", "HTTP / HTTPS 视频地址", location, required = true))),
                    validator = { values -> runCatching { tv.dandan.app.data.SourceRules.validate(MediaSource("phone-video", "网络视频", SourceKind.HTTP, values.getValue("location").trim())); null }.getOrElse { tv.dandan.app.MainActivity.INVALID_VIDEO_URL } }) { values ->
                    location = values.getValue("location").trim()
                    if (!host.openHttpVideo(location)) host.notify(tv.dandan.app.MainActivity.INVALID_VIDEO_URL)
                }
            })
            if (location.isNotBlank()) ActionRow("再次播放上次地址", onClick = { if (!host.openHttpVideo(location)) host.notify(tv.dandan.app.MainActivity.INVALID_VIDEO_URL) }, icon = Icons.Default.Refresh)
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024))
    else -> "${(bytes + 1023) / 1024} KB"
}
