package tv.dandan.app.ui.screens

import tv.dandan.app.ui.requestContentFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import tv.dandan.app.MainActivity
import tv.dandan.app.data.SourceRules
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.*
import tv.dandan.core.EpisodeBinding
import tv.dandan.core.MediaEntry
import tv.dandan.core.MediaSource
import tv.dandan.core.SourceKind

/** 文件操作：收藏、绑定剧集、导入字幕弹幕。导入只作用于本次播放并随观看进度保留。 */
@Composable
fun MediaActionsScreen(host: TvHost, owner: Any, route: Route.MediaActions) {
    var entry by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(route.entry) }
    val loader = rememberLoader(entry.id, host.refreshEpoch) { host.repository.episodeFor(entry.id) to host.repository.isFavorite(entry.id) }
    val actions = rememberActions()
    val playFocus = remember { FocusRequester() }
    ScreenScaffold(entry.title, subtitle = "选择关联的剧集后，播放时可请求官方弹幕。导入文件用于此次播放并随观看进度保留。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取文件关联…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val (binding, favorite) = state.value
                LaunchedEffect(Unit) { requestContentFocus(playFocus) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow("开始播放", modifier = Modifier.focusRequester(playFocus), icon = Icons.Default.PlayArrow, subtitle = if (binding == null) "尚未绑定官方剧集" else "已绑定剧集 ID：${binding.episodeId}", onClick = {
                        host.play(entry, route.queue.map { if (it.id == entry.id) entry else it })
                    })
                    ActionRow(if (favorite) "取消收藏" else "收藏此视频", icon = if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, onClick = { actions.run({ host.repository.toggleFavorite(entry) }, host::notify) { loader.reload() } })
                    SectionTitle("弹幕")
                    ActionRow("按文件名匹配弹幕", subtitle = "服务依赖开发者凭证；无可靠文件指纹时只按名称搜索", icon = Icons.Default.Search, chevron = true, onClick = { host.navigator.push(Route.MatchDanmaku(entry)) })
                    ActionRow("手动搜索并绑定剧集", icon = Icons.Default.Star, chevron = true, onClick = { host.navigator.push(Route.AnimeSearch(entry)) })
                    ActionRow("导入弹幕 XML", subtitle = if (entry.danmakuUri.isBlank()) "通过系统文件选择器授权" else "已选择本地弹幕", icon = Icons.Default.List, onClick = { host.pickFile { entry = entry.copy(danmakuUri = it.toString()) } })
                    ActionRow("导入弹幕 XML 地址", icon = Icons.Default.Share, chevron = true, onClick = { attachmentUrl(host, owner, "弹幕 XML 地址") { entry = entry.copy(danmakuUri = it) } })
                    if (entry.danmakuUri.isNotBlank()) ActionRow("移除本次本地弹幕", icon = Icons.Default.Clear, danger = true, onClick = { entry = entry.copy(danmakuUri = "") })
                    SectionTitle("字幕")
                    ActionRow("搜索在线字幕", subtitle = "需要配置字幕服务；下载后保存到本机", icon = Icons.Default.Search, chevron = true, onClick = { host.navigator.push(Route.SubtitleSearch(entry) { uri -> entry = entry.copy(subtitleUri = uri) }) })
                    ActionRow("导入字幕文件", subtitle = if (entry.subtitleUri.isBlank()) "SRT · WebVTT · ASS / SSA · TTML" else "已选择外挂字幕", icon = Icons.Default.List, onClick = { host.pickFile { entry = entry.copy(subtitleUri = it.toString()) } })
                    ActionRow("导入字幕地址", icon = Icons.Default.Share, chevron = true, onClick = { attachmentUrl(host, owner, "字幕地址") { entry = entry.copy(subtitleUri = it) } })
                    if (entry.subtitleUri.isNotBlank()) ActionRow("移除本次外挂字幕", icon = Icons.Default.Clear, danger = true, onClick = { entry = entry.copy(subtitleUri = "") })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** 字幕 / 弹幕地址直接由手机表单收集，不再单独开页。 */
private fun attachmentUrl(host: TvHost, owner: Any, title: String, chosen: (String) -> Unit) {
    host.phoneForm(owner, PhoneForm(title, "使用不含内嵌账号密码或片段标记的 HTTP / HTTPS 地址。", listOf(PhoneField("location", "HTTP / HTTPS 地址", required = true))),
        validator = { values -> runCatching { SourceRules.validate(MediaSource("phone-attachment", title, SourceKind.HTTP, values.getValue("location").trim())); null }.getOrElse { MainActivity.INVALID_VIDEO_URL } }) { values ->
        chosen(SourceRules.validate(MediaSource("phone-attachment", title, SourceKind.HTTP, values.getValue("location").trim())).location)
        host.notify("$title 已关联到当前视频")
    }
}

@Composable
fun AttachmentUrlScreen(host: TvHost, owner: Any, route: Route.AttachmentUrl) {
    // 保留路由以兼容外部跳转；实际输入已并入文件操作页。
    LaunchedEffect(Unit) { host.navigator.pop() }
}

/** 按文件名匹配官方剧集；结果以列表呈现，选中即绑定。 */
@Composable
fun MatchDanmakuScreen(host: TvHost, entry: MediaEntry) {
    val loader = rememberLoader {
        val hash = runCatching { host.repository.fileHash(entry) }.getOrNull()
        host.repository.api.matchByName(entry.title, entry.sizeBytes, hash)
    }
    val actions = rememberActions()
    ScreenScaffold("匹配弹幕", subtitle = entry.title) {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取文件特征并匹配剧集…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() }) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton("手动搜索番剧", filled = false, onClick = { host.navigator.push(Route.AnimeSearch(entry)) })
                    TvButton("配置开放平台凭证", filled = false, onClick = { host.navigator.push(Route.Credentials) })
                }
            }
            is Load.Ready -> {
                val matches = state.value
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { requestContentFocus(first) }
                if (matches.isEmpty()) EmptyPane("没有找到匹配剧集，可手动搜索番剧。", actionLabel = "手动搜索番剧") { host.navigator.push(Route.AnimeSearch(entry)) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEachIndexed { index, candidate ->
                        ActionRow(candidate.animeTitle, subtitle = candidate.episodeTitle + if (candidate.exact) " · 精确匹配" else "", icon = Icons.Default.Star, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier, onClick = {
                            actions.run({ host.repository.saveEpisode(entry, EpisodeBinding(candidate.episodeId, candidate.shiftMs)) }, host::notify) { host.notify("剧集已绑定，播放时加载官方弹幕"); host.navigator.pop() }
                        })
                    }
                    if (matches.isNotEmpty()) ActionRow("手动搜索番剧", icon = Icons.Default.Search, chevron = true, onClick = { host.navigator.push(Route.AnimeSearch(entry)) })
                }
            }
        }
    }
}
