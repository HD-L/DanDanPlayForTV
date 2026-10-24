package tv.dandan.app.ui.screens

import tv.dandan.app.ui.requestContentFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import tv.dandan.app.data.OnlineSubtitle
import tv.dandan.app.data.OnlineSubtitles
import tv.dandan.app.data.SubtitleAccount
import tv.dandan.app.data.SubtitlePreferences
import tv.dandan.app.data.SubtitleStore
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.SubtitleForms
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens
import tv.dandan.core.MediaEntry

@Composable
fun SubtitleSettingsScreen(host: TvHost, owner: Any) {
    val store = remember { SubtitleStore(host.appContext) }
    val loader = rememberLoader { store.read() }
    val actions = rememberActions()
    val first = remember { FocusRequester() }
    fun save(value: SubtitlePreferences) = actions.run({ store.save(value) }, host::notify) { loader.reload() }
    ScreenScaffold("在线字幕服务", subtitle = "使用 OpenSubtitles 正式 API。账户下载配额由提供方决定，搜索候选后由你确认下载。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取字幕服务配置…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val prefs = state.value
                LaunchedEffect(Unit) { requestContentFocus(first) }
                Note(if (prefs.account.configured) "字幕服务已配置。凭证加密保存在本机。" else "尚未配置。需要你自己的 OpenSubtitles API Key、账号与密码。", emphasis = true)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow("在手机配置字幕服务", icon = Icons.Default.Edit, modifier = Modifier.focusRequester(first), onClick = {
                        host.phoneForm(owner, SubtitleForms.configuration(prefs.account), SubtitleForms::validate) { values -> save(prefs.copy(account = SubtitleForms.account(values))) }
                    })
                    ToggleRow("自动匹配字幕", prefs.autoMatch, { save(prefs.copy(autoMatch = it)) }, subtitle = "仅无外挂字幕时按文件指纹匹配，唯一可靠结果才自动下载")
                    ChoiceRow("优先语言", prefs.language, SubtitleStore.LANGUAGES.map { it.first }, { save(prefs.copy(language = it)) }, format = { code -> SubtitleStore.LANGUAGES.firstOrNull { it.first == code }?.second ?: code })
                    if (prefs.account.configured) ActionRow("清除本机字幕服务凭证", icon = Icons.Default.Clear, danger = true, onClick = { save(prefs.copy(account = SubtitleAccount())) })
                }
                Note("自动匹配会使用账户下载配额；关闭后仍可手动搜索。已下载字幕缓存到本机，续播无需重复下载。字幕服务凭证不包含在媒体库备份中，换设备后需重新配置。")
            }
        }
    }
}

/** 在线字幕搜索：手机输入名称，电视列出候选，确认后下载并关联到当前视频。 */
@Composable
fun SubtitleSearchScreen(host: TvHost, owner: Any, route: Route.SubtitleSearch) {
    val entry = route.entry
    val gateway = remember { OnlineSubtitles(host.appContext, host.repository) }
    var name by rememberSaveable { mutableStateOf(entry.title.substringBeforeLast('.', entry.title).take(200)) }
    var results by remember { mutableStateOf<Load<List<OnlineSubtitle>>?>(null) }
    var confirm by remember { mutableStateOf<OnlineSubtitle?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val actions = rememberActions()
    val searchFocus = remember { FocusRequester() }
    fun search() {
        host.phoneForm(owner, PhoneForm("搜索在线字幕", "选择名称后电视显示候选字幕，下载会使用字幕服务账户配额。", listOf(PhoneField("name", "视频或作品名称", name, required = true, maxLength = 200))),
            validator = { values -> if (values.getValue("name").trim().length < 2) "名称至少需要 2 个字符" else null }) { values ->
            name = values.getValue("name").trim(); results = Load.Loading
            actions.run({ gateway.search(name, entry) }, { results = Load.Failed(it) }) { results = Load.Ready(it) }
        }
    }
    LaunchedEffect(Unit) { requestContentFocus(searchFocus) }
    ScreenScaffold("搜索在线字幕", subtitle = "${entry.title} · 在手机输入名称，回到电视选择结果并确认下载。", scrollable = false, headerTrailing = {
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("在手机填写名称", icon = Icons.Default.Search, onClick = ::search, modifier = Modifier.focusRequester(searchFocus))
            TvButton("服务与语言", icon = Icons.Default.Settings, filled = false, onClick = { host.navigator.push(Route.SubtitleSettings) })
        }
    }) {
        when (val state = results) {
            null -> EmptyPane("当前名称：$name\n按“在手机填写名称”开始搜索。")
            Load.Loading -> LoadingPane("正在搜索字幕…")
            is Load.Failed -> ErrorPane(state.message, onRetry = ::search, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val candidates = state.value
                if (candidates.isEmpty()) EmptyPane("没有找到字幕，请尝试作品名或切换语言。", actionLabel = "重新搜索") { search() }
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = TvDimens.screenPaddingEnd, bottom = 40.dp), modifier = Modifier.fillMaxSize().focusRestorer()) {
                    item { Note("找到 ${candidates.size} 个候选字幕；文件指纹匹配的结果最可靠。") }
                    items(candidates, key = { it.fileId }) { candidate ->
                        ActionRow(candidate.title.ifBlank { candidate.release.ifBlank { "字幕 ${candidate.fileId}" } }, icon = Icons.Default.Star, subtitle = "${candidate.language} · ${if (candidate.hashMatched) "文件指纹匹配" else "名称候选，请核对发行版本"} · ${candidate.release}", value = if (candidate.hashMatched) "推荐" else "", onClick = { confirm = candidate })
                    }
                }
            }
        }
    }
    confirm?.let { candidate ->
        TvDialog("下载这个字幕", "${candidate.title}\n${candidate.release}\n\n确认后使用账户配额下载并关联当前视频，下载错误会保留当前关联。", onDismiss = { if (!downloading) confirm = null }, confirmLabel = "确认下载并使用", busy = downloading, onConfirm = {
            downloading = true
            actions.run({ gateway.download(candidate) }, { downloading = false; host.notify(it) }) { uri ->
                downloading = false; confirm = null
                route.chosen(uri)
                host.notify("字幕已下载并关联到当前视频")
                host.navigator.pop()
            }
        })
    }
}
