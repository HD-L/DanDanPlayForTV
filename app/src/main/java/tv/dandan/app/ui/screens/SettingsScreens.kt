package tv.dandan.app.ui.screens

import tv.dandan.app.ui.requestContentFocus
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import tv.dandan.app.data.CloudDanmakuFilters
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.remote.RemoteControlServer
import tv.dandan.app.remote.RemoteHub
import tv.dandan.app.ui.BlockedFormRules
import tv.dandan.app.ui.CredentialsFormRules
import tv.dandan.app.ui.PreferenceSection
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.core.ApiCredentials
import tv.dandan.core.AppSettings
import tv.dandan.core.DanmakuLanguage
import tv.dandan.core.PlaybackEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(host: TvHost) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestContentFocus(first) }
    ScreenScaffold("设置", subtitle = "调整观看体验、服务凭证与本地配对。更改保存在这台设备上。") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("观看")
            ActionRow("播放", subtitle = "默认倍速与播放内核", icon = Icons.Default.PlayArrow, chevron = true, modifier = Modifier.focusRequester(first), onClick = { host.navigator.push(Route.Preference(PreferenceSection.PLAYBACK)) })
            ActionRow("弹幕", subtitle = "开关、位置、字号、透明度、速度与密度", icon = Icons.Default.Star, chevron = true, onClick = { host.navigator.push(Route.Preference(PreferenceSection.DANMAKU)) })
            ActionRow("屏蔽规则", subtitle = "关键字与安全正则表达式", icon = Icons.Default.Clear, chevron = true, onClick = { host.navigator.push(Route.BlockSettings) })
            ActionRow("字幕", subtitle = "字号、颜色、描边与同步延迟", icon = Icons.Default.Edit, chevron = true, onClick = { host.navigator.push(Route.Preference(PreferenceSection.SUBTITLE)) })
            SectionTitle("服务")
            ActionRow("在线字幕服务", subtitle = "配置服务、语言与自动匹配", icon = Icons.Default.Search, chevron = true, onClick = { host.navigator.push(Route.SubtitleSettings) })
            ActionRow("磁链与资源搜索", subtitle = "资源搜索节点、打开磁链、边下边播缓存", icon = Icons.Default.Share, chevron = true, onClick = { host.navigator.push(Route.MagnetSettings) })
            ActionRow("开放平台凭证", subtitle = "配置 AppId / AppSecret 使用官方番剧与弹幕服务", icon = Icons.Default.Lock, chevron = true, onClick = { host.navigator.push(Route.Credentials) })
            ActionRow("手机遥控与视频投送", subtitle = "扫码配对、停止服务或重置令牌", icon = Icons.Default.Phone, chevron = true, onClick = { host.navigator.push(Route.Remote) })
            SectionTitle("数据")
            ActionRow("收藏的视频", icon = Icons.Default.Favorite, chevron = true, onClick = { host.navigator.push(Route.Favorites) })
            ActionRow("媒体库索引与名称搜索", icon = Icons.Default.List, chevron = true, onClick = { host.navigator.push(Route.LibraryIndex) })
            ActionRow("数据管理与备份", subtitle = "观看历史、索引、收藏与备份恢复", icon = Icons.Default.Build, chevron = true, onClick = { host.navigator.push(Route.DataManagement) })
            ActionRow("功能与能力限制", subtitle = "服务条件、当前后端及尚未接通的能力", icon = Icons.Default.Info, chevron = true, onClick = { host.navigator.push(Route.Capabilities) })
        }
    }
}

/** 偏好页：开关与选项即改即存，失败时保留旧值并提示。 */
@Composable
fun PreferenceScreen(host: TvHost, section: PreferenceSection) {
    val loader = rememberLoader { host.repository.settings() }
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    val actions = rememberActions()
    LaunchedEffect(loader.value) { (loader.value as? Load.Ready)?.let { settings = it.value } }
    fun update(value: AppSettings) { val previous = settings; settings = value; actions.run({ host.repository.saveSettings(value) }, { settings = previous; host.notify(it) }) {} }
    val first = remember { FocusRequester() }
    ScreenScaffold("${section.chinese}设置", subtitle = "确定切换选项；更改自动保存。播放中的即时控制位于播放器菜单。") {
        val value = settings
        when {
            loader.value is Load.Failed -> ErrorPane((loader.value as Load.Failed).message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            value == null -> LoadingPane("正在读取设置…")
            else -> {
                LaunchedEffect(Unit) { requestContentFocus(first) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (section) {
                        PreferenceSection.PLAYBACK -> {
                            ChoiceRow("默认倍速", value.playbackSpeed, listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f), { update(value.copy(playbackSpeed = it)) }, Modifier.focusRequester(first), format = { "$it 倍" })
                            ChoiceRow("默认播放内核", value.playbackEngine, PlaybackEngine.entries, { update(value.copy(playbackEngine = it)) }, format = { if (it == PlaybackEngine.MEDIA3) "Media3" else "VLC" })
                            ToggleRow("网络来源多连接读取", value.multiConnection, { update(value.copy(multiConnection = it)) }, subtitle = "WebDAV / HTTP 连续播放时用 3 个连接分段下载；单个连接跑不满时（远程或网盘 WebDAV）更快，NAS 本身慢时帮助不大")
                            Note("播放中可切换 Media3 / VLC 并保留位置与设置，解码失败页也能换内核重试。可播放格式、4K 与 HDR 仍取决于媒体与设备。")
                        }
                        PreferenceSection.DANMAKU -> {
                            ToggleRow("显示弹幕", value.danmakuEnabled, { update(value.copy(danmakuEnabled = it)) }, Modifier.focusRequester(first))
                            ToggleRow("滚动弹幕", value.showScrolling, { update(value.copy(showScrolling = it)) })
                            ToggleRow("顶部弹幕", value.showTop, { update(value.copy(showTop = it)) })
                            ToggleRow("底部弹幕", value.showBottom, { update(value.copy(showBottom = it)) })
                            ChoiceRow("弹幕语言", value.danmakuLanguage, DanmakuLanguage.entries, { update(value.copy(danmakuLanguage = it)) }, format = { when (it) { DanmakuLanguage.ORIGINAL -> "原始"; DanmakuLanguage.SIMPLIFIED -> "简体"; DanmakuLanguage.TRADITIONAL -> "繁体" } })
                            SectionTitle("样式")
                            ChoiceRow("字号", value.danmakuSizeSp, listOf(18f, 22f, 24f, 28f, 32f, 36f), { update(value.copy(danmakuSizeSp = it)) }, format = { "${it.toInt()} sp" })
                            ChoiceRow("透明度", value.danmakuAlpha, listOf(.35f, .5f, .65f, .85f, 1f), { update(value.copy(danmakuAlpha = it)) }, format = { "${(it * 100).toInt()}%" })
                            ChoiceRow("穿屏时间", value.danmakuDurationMs, listOf(4000L, 6000L, 8000L, 10000L, 12000L), { update(value.copy(danmakuDurationMs = it)) }, format = { "${it / 1000} 秒" })
                            ChoiceRow("描边", value.danmakuStroke, listOf(0f, 1f, 2f, 3f), { update(value.copy(danmakuStroke = it)) }, format = { "${it.toInt()} px" })
                            ChoiceRow("显示区域行数", value.danmakuMaxLines, listOf(3, 5, 8, 12, 16), { update(value.copy(danmakuMaxLines = it)) })
                            ChoiceRow("同屏上限", value.danmakuMaxCount, listOf(20, 40, 60, 100, 150), { update(value.copy(danmakuMaxCount = it)) })
                            SectionTitle("屏蔽")
                            ToggleRow("云屏蔽", value.cloudDanmakuBlock, { update(value.copy(cloudDanmakuBlock = it)) }, subtitle = "公开维护的屏蔽规则，不影响自定义关键字与正则")
                            ActionRow("查看与更新云屏蔽规则", chevron = true, onClick = { host.navigator.push(Route.CloudBlock) })
                            ActionRow("编辑关键字与正则屏蔽", chevron = true, onClick = { host.navigator.push(Route.BlockSettings) })
                        }
                        PreferenceSection.SUBTITLE -> {
                            ChoiceRow("字幕字号", value.subtitleSizeSp, listOf(18f, 22f, 24f, 28f, 32f, 36f), { update(value.copy(subtitleSizeSp = it)) }, Modifier.focusRequester(first), format = { "${it.toInt()} sp" })
                            ChoiceRow("文字颜色", value.subtitleColor, listOf(AndroidColor.WHITE, AndroidColor.YELLOW, AndroidColor.CYAN), { update(value.copy(subtitleColor = it)) }, format = { when (it) { AndroidColor.YELLOW -> "黄色"; AndroidColor.CYAN -> "青色"; else -> "白色" } })
                            ChoiceRow("字幕延迟", value.subtitleDelayMs, listOf(-2000L, -1000L, -500L, 0L, 500L, 1000L, 2000L), { update(value.copy(subtitleDelayMs = it)) }, format = { "$it 毫秒" })
                            ChoiceRow("字幕描边", value.subtitleStroke, listOf(0f, 1f, 2f, 3f), { update(value.copy(subtitleStroke = it)) }, format = { when (it) { 0f -> "无"; 1f -> "细"; 3f -> "粗"; else -> "标准" } })
                            ActionRow("在线字幕服务与自动匹配", chevron = true, onClick = { host.navigator.push(Route.SubtitleSettings) })
                            Note("正值让字幕延后，负值让字幕提前。VLC 原生处理 ASS 样式和附件字体；可手选编码并导入 TTF / OTF。ASS 的文件样式优先。")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockSettingsScreen(host: TvHost, owner: Any) {
    val loader = rememberLoader { host.repository.settings() }
    val actions = rememberActions()
    val first = remember { FocusRequester() }
    ScreenScaffold("屏蔽规则", subtitle = "手机每行填写一条。正则采用安全引擎，不支持环视与反向引用。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取屏蔽规则…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val settings = state.value
                LaunchedEffect(Unit) { requestContentFocus(first) }
                InfoLine("关键字", "${settings.blockedKeywords.size} 条")
                InfoLine("正则规则", "${settings.blockedPatterns.size} 条")
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow("在手机编辑并保存屏蔽规则", subtitle = "扫码填写关键字与正则，手机直接显示校验结果", icon = Icons.Default.Edit, modifier = Modifier.focusRequester(first), onClick = {
                        host.phoneForm(owner, PhoneForm("屏蔽规则", "每行一条。关键字最多200条、每条200字符，正则最多100条。不支持环视与反向引用；留空清除该组规则。", listOf(
                            PhoneField("keywords", "关键字（每行一条）", settings.blockedKeywords.joinToString("\n"), multiline = true, maxLength = 65536),
                            PhoneField("patterns", "正则表达式（每行一条）", settings.blockedPatterns.joinToString("\n"), multiline = true, maxLength = 65536),
                        )), validator = { values -> BlockedFormRules.validate(settings, values.getValue("keywords"), values.getValue("patterns")) }) { values ->
                            actions.run({ host.repository.saveSettings(BlockedFormRules.apply(host.repository.settings(), values.getValue("keywords"), values.getValue("patterns"))) }, host::notify) { host.notify("屏蔽规则已保存"); loader.reload() }
                        }
                    })
                    if (settings.blockedKeywords.isNotEmpty() || settings.blockedPatterns.isNotEmpty()) {
                        SectionTitle("当前规则")
                        settings.blockedKeywords.take(50).forEach { Note("关键字：$it") }
                        settings.blockedPatterns.take(50).forEach { Note("正则：$it") }
                    }
                }
            }
        }
    }
}

@Composable
fun CredentialsScreen(host: TvHost, owner: Any) {
    val loader = rememberLoader { host.repository.credentials() }
    val actions = rememberActions()
    val first = remember { FocusRequester() }
    ScreenScaffold("开放平台凭证", subtitle = "手机填写 AppId 与 AppSecret 后自动保存。应用级鉴权不是用户登录。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取本机凭证…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val credentials = state.value
                LaunchedEffect(Unit) { requestContentFocus(first) }
                InfoLine("AppId", if (credentials.appId.isBlank()) "未填写" else "已保存", valueColor = if (credentials.appId.isBlank()) TvColors.amber else TvColors.accent)
                InfoLine("AppSecret", if (credentials.appSecret.isBlank()) "未填写" else "已保存", valueColor = if (credentials.appSecret.isBlank()) TvColors.amber else TvColors.accent)
                Note(if (credentials.configured) "开放平台凭证已配置。" else "开放平台未配置，在线番剧与弹幕服务需要完整凭证。", emphasis = true)
                Note("开发者凭证加密保存在本机。已有秘密不会下发到手机；留空保留，勾选清除可移除。")
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionRow("在手机填写并保存凭证", icon = Icons.Default.Edit, modifier = Modifier.focusRequester(first), onClick = {
                        host.phoneForm(owner, PhoneForm("开放平台凭证", "AppSecret 留空保留已有值；勾选清除会移除整对凭证并停用在线服务。", listOf(
                            PhoneField("appId", "AppId", credentials.appId, maxLength = 200),
                            PhoneField("appSecret", "AppSecret", credentials.appSecret, secret = true, maxLength = 4096),
                        )), validator = { values -> runCatching { CredentialsFormRules.normalize(values.getValue("appId"), values.getValue("appSecret")); null }.getOrElse { it.message ?: "开发者凭证格式不正确" } }) { values ->
                            val value = CredentialsFormRules.normalize(values.getValue("appId"), values.getValue("appSecret"))
                            actions.run({ host.repository.saveCredentials(value) }, host::notify) { host.notify(if (value.configured) "开放平台凭证已保存" else "整对凭证已清除，在线服务已停用"); host.navigator.pop() }
                        }
                    })
                    if (credentials.appId.isNotBlank() || credentials.appSecret.isNotBlank()) ActionRow("清除本机凭证", icon = Icons.Default.Clear, danger = true, onClick = { actions.run({ host.repository.saveCredentials(ApiCredentials()) }, host::notify) { host.notify("凭证已清除"); loader.reload() } })
                }
            }
        }
    }
}

@Composable
fun CloudBlockScreen(host: TvHost) {
    val filters = remember { CloudDanmakuFilters(host.appContext) }
    val loader = rememberLoader { filters.status() to host.repository.settings().cloudDanmakuBlock }
    val actions = rememberActions()
    var refreshing by remember { mutableStateOf(false) }
    ScreenScaffold("云屏蔽公开规则", subtitle = "主动读取公开维护仓库的规则；当前实现没有连接官方服务器推送。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取屏蔽规则状态…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val (status, enabled) = state.value
                InfoLine("来源", "Cathgao/dandanplay-filter · Apache-2.0")
                InfoLine("规则", "可用 ${status.count} 条 · 跳过 ${status.skipped} 条不兼容规则")
                InfoLine("更新", if (status.updatedAt == 0L) "随应用提供的离线基线（提交 ${CloudDanmakuFilters.BASELINE_COMMIT.take(12)}）" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(status.updatedAt)))
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleRow("云屏蔽", enabled, { value -> actions.run({ host.repository.saveSettings(host.repository.settings().copy(cloudDanmakuBlock = value)) }, host::notify) { loader.reload() } }, subtitle = "不影响自定义关键字与正则")
                    ActionRow(if (refreshing) "正在刷新…" else "刷新公开规则", subtitle = "12 个分类全部成功才替换；20 秒总预算，失败保留现有缓存", icon = Icons.Default.Refresh, enabled = !refreshing, onClick = {
                        refreshing = true
                        actions.run({ filters.refresh() }, { refreshing = false; host.notify(it) }) { refreshing = false; host.notify("公开规则已刷新：${it.count} 条，跳过 ${it.skipped} 条"); loader.reload() }
                    })
                }
                Note("刷新只拉取 HTTPS 公共文本，不发送网络账号、开发者密钥或观看文件。公开规则可能误伤正常弹幕，可随时关闭。RE2/J 不支持环视与反向引用；不兼容规则不会被假装启用。")
            }
        }
    }
}

private data class PairingState(val running: Boolean, val url: String, val qr: Bitmap?)

@Composable
fun RemoteScreen(host: TvHost) {
    val actions = rememberActions()
    val loader = rememberLoader {
        val server = RemoteHub.server(host.appContext, host.repository)
        val url = if (server.isRunning) server.pairingUrl else ""
        PairingState(server.isRunning, url, if (url.isBlank()) null else qrBitmap(url))
    }
    fun operate(block: (RemoteControlServer) -> Unit) = actions.run({ block(RemoteHub.server(host.appContext, host.repository)) }, host::notify) { loader.reload() }
    ScreenScaffold("手机输入、遥控与视频投送", subtitle = "应用运行时自动准备手机网页服务。输入、遥控与投送共用此配对。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取配对状态…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val pairing = state.value
                if (pairing.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        pairing.qr?.let { Image(it.asImageBitmap(), contentDescription = "手机扫描此二维码配对", modifier = Modifier.size(210.dp).background(androidx.compose.ui.graphics.Color.White, MaterialTheme.shapes.small).padding(8.dp)) }
                        Spacer(Modifier.width(24.dp))
                        Column {
                            Text("配对已开启", style = MaterialTheme.typography.headlineSmall, color = TvColors.accent)
                            Note("手机与电视连接同一局域网，扫描此码打开遥控器。添加媒体源等输入操作会另行弹出专属表单二维码。")
                            Text(pairing.url, style = MaterialTheme.typography.labelMedium, color = TvColors.muted)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionRow("复制配对地址", icon = Icons.Default.Share, onClick = {
                            (host.appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("弹弹 TV 配对", pairing.url)); host.notify("配对地址已复制")
                        })
                        ActionRow("重新配对", subtitle = "撤销待输入与当前令牌，生成新的二维码", icon = Icons.Default.Refresh, onClick = { operate { it.stop(); it.start() } })
                        ActionRow("停止手机网页服务", subtitle = "撤销所有待输入，使配对地址失效并释放端口", icon = Icons.Default.Clear, danger = true, onClick = { operate { it.stop() } })
                    }
                } else {
                    Note("手机网页服务已停止。需要手机输入时会重新准备，也可以在此启动。", emphasis = true)
                    ActionRow("启动手机网页服务", subtitle = "手机填写文字、控制播放，并投送视频 URL", icon = Icons.Default.Phone, onClick = { operate { it.start() } })
                }
                Note("令牌代表控制权限，请只与可信设备配对。系统结束应用进程后服务会关闭。当前服务使用 HTTP，仅适用于可信局域网。")
            }
        }
    }
}

internal fun qrBitmap(content: String, size: Int = 320): Bitmap {
    val bits = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { index -> if (bits[index % size, index / size]) AndroidColor.BLACK else AndroidColor.WHITE }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun CapabilitiesScreen(host: TvHost) {
    ScreenScaffold("功能与能力限制", subtitle = "真实服务与设备能力决定最终体验。") {
        SectionTitle("媒体与播放")
        Note("本地授权目录、HTTP / HTTPS、WebDAV、SMB 和 FTP；Media3 与 VLC 可切换，目录内视频组成播放队列。编码、4K、HDR、音轨与内嵌字幕支持依赖设备与媒体。带认证的 HLS / DASH 请使用 Media3。")
        SectionTitle("弹幕与番剧")
        Note("支持本地 XML 与官方服务弹幕，番剧每周更新、季度、搜索与剧集详情需要有效 AppId / AppSecret。番剧资料不提供视频版权资源。")
        SectionTitle("字幕")
        Note("可导入文件或 HTTP / HTTPS 字幕地址；OpenSubtitles 支持文件指纹自动匹配、名称搜索和配额内下载，需要专属账户与 API Key。VLC 原生 ASS、字体附件与字符编码由播放菜单处理。")
        SectionTitle("远程配对")
        Note("应用运行时准备同一局域网的手机输入与遥控服务。文字信息通过二维码在手机填写；停止或重新配对会撤销待输入。不兼容旧版 AES 鉴权，不提供自动发现、镜像投屏或 TV 亮度控制。")
        SectionTitle("数据")
        Note("收藏、后台索引、名称过滤和版本化备份已提供。默认导出不含凭证；选择加密备份可包含媒体源和弹弹play凭证。旧版数据库不能直接导入。当前不收集广告标识、手机号、定位或通讯录。")
        Spacer(Modifier.height(12.dp))
        TvButton("返回", onClick = { host.navigator.pop() }, filled = false)
    }
}
