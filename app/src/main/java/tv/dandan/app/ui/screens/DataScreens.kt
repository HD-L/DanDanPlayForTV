package tv.dandan.app.ui.screens

import tv.dandan.app.ui.requestContentFocus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import tv.dandan.app.data.BackupCodec
import tv.dandan.app.remote.PhoneField
import tv.dandan.app.remote.PhoneForm
import tv.dandan.app.ui.Route
import tv.dandan.app.ui.TvHost
import tv.dandan.app.ui.components.*
import tv.dandan.core.SourceKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 数据管理：导出 / 恢复备份，清除历史、收藏与索引。 */
@Composable
fun DataManagementScreen(host: TvHost, owner: Any) {
    val actions = rememberActions()
    var busy by remember { mutableStateOf("") }
    var pendingEncrypted by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestContentFocus(first) }

    fun export(password: String?) {
        busy = "正在准备备份…"
        actions.run({ host.repository.exportBackup(password) }, { busy = ""; host.notify(it) }) { content ->
            busy = ""
            val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            host.createBackupFile("dandan-tv-${if (password == null) "backup" else "encrypted"}-$date.json", cancelled = { host.notify("已取消导出") }) { uri ->
                busy = "正在写入备份…"
                actions.run({ host.repository.writeBackup(uri, content) }, { busy = ""; host.notify("$it；请重新导出到新的文件") }) { busy = ""; host.notify("备份已保存") }
            }
        }
    }
    fun preview(content: String, password: String?, encrypted: Boolean) {
        busy = "正在校验备份字段、媒体地址与设置…"
        actions.run({ host.repository.validateBackup(content, password) }, { busy = ""; host.notify(it); if (encrypted) pendingEncrypted = content }) { snapshot ->
            busy = ""; pendingEncrypted = null
            host.navigator.push(Route.BackupPreview(snapshot, encrypted))
        }
    }
    fun passwordForm(content: String) {
        host.phoneForm(owner, PhoneForm("恢复备份密码", "请输入导出时设置的密码，密码不会保存或展示在电视。", listOf(PhoneField("password", "备份密码", secret = true, required = true, maxLength = 256)))) { preview(content, it.getValue("password"), true) }
    }
    fun import() {
        host.pickBackupFile(cancelled = { host.notify("已取消选择备份") }) { uri ->
            busy = "正在读取备份…"
            actions.run({ host.repository.readBackup(uri).let { it to BackupCodec.isEncrypted(it) } }, { busy = ""; host.notify(it) }) { (content, encrypted) ->
                busy = ""
                if (encrypted) { pendingEncrypted = content; passwordForm(content) } else preview(content, null, false)
            }
        }
    }
    fun clearData(name: String, operation: () -> Unit) = host.navigator.push(Route.Confirm("清除$name", "确认清除$name？视频、字幕与弹幕文件不会删除。", "确认清除", destructive = true, operation = operation, afterwards = { host.notify("已清除$name") }))

    ScreenScaffold("数据管理与备份", subtitle = "备份仅支持此独立 TV 工程的版本化格式；旧数据库不自动迁移。") {
        if (busy.isNotBlank()) { LoadingPane(busy); return@ScreenScaffold }
        Note("备份保存媒体源、设置、收藏、索引、观看进度和官方剧集绑定。字幕与视频文件不打包；本地目录恢复后仍需要此电视的系统授权。在线字幕服务凭证不包含在任何备份中。")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("备份")
            ActionRow("导出备份", subtitle = "省略连接凭据；媒体地址保留。含授权参数的地址请使用密码备份", icon = Icons.Default.Share, modifier = Modifier.focusRequester(first), onClick = { export(null) })
            ActionRow("导出包含凭据的密码备份", subtitle = "手机设置密码；AES-GCM + PBKDF2，可在另一台电视恢复", icon = Icons.Default.Lock, onClick = {
                host.phoneForm(owner, PhoneForm("加密备份密码", "请设置至少 10 个字符的密码并自行保存。丢失密码无法恢复；本机 Keystore 密钥不会写入备份。", listOf(PhoneField("password", "备份密码", secret = true, required = true, maxLength = 256), PhoneField("confirm", "再次输入密码", secret = true, required = true, maxLength = 256))),
                    validator = { values -> runCatching { BackupCodec.validatePassword(values.getValue("password")); require(values["password"] == values["confirm"]) { "两次密码不一致" }; null }.getOrElse { it.message } }) { export(it.getValue("password")) }
            })
            ActionRow("从备份恢复", subtitle = "电视选择文件；先验证并展示摘要，确认后一次性替换现有数据", icon = Icons.Default.Refresh, onClick = ::import)
            pendingEncrypted?.let { content -> ActionRow("重新填写备份密码", subtitle = "已选择密码备份，验证失败不会修改现有数据", icon = Icons.Default.Lock, onClick = { passwordForm(content) }) }
            SectionTitle("清除")
            ActionRow("清除观看历史", subtitle = "收藏、来源、索引和剧集绑定保留", icon = Icons.Default.Clear, danger = true, onClick = { clearData("观看历史") { host.repository.clearHistory() } })
            ActionRow("清除全部收藏", icon = Icons.Default.Clear, danger = true, onClick = { clearData("全部收藏") { host.repository.clearFavorites() } })
            ActionRow("清除媒体库索引", subtitle = "取消后台扫描，来源与视频文件保留", icon = Icons.Default.Clear, danger = true, onClick = { clearData("媒体库索引") { host.repository.clearIndex() } })
        }
    }
}

@Composable
fun BackupPreviewScreen(host: TvHost, route: Route.BackupPreview) {
    val snapshot = route.snapshot
    val actions = rememberActions()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestContentFocus(cancelFocus) }
    ScreenScaffold("确认恢复备份", subtitle = "文件校验已通过，确认前未修改现有数据。") {
        Note("将替换当前电视的全部来源、设置、观看历史、收藏、媒体索引及剧集绑定。", emphasis = true)
        InfoLine("媒体源", "${snapshot.sources.size} 个")
        InfoLine("观看历史", "${snapshot.history.size} 条")
        InfoLine("收藏", "${snapshot.favorites.size} 个")
        InfoLine("索引视频", "${snapshot.index.size} 个")
        InfoLine("剧集绑定", "${snapshot.episodes.size} 个")
        Note(if (route.encrypted) "该备份包含网络凭据和开发者密钥，将重新使用本机 Keystore 加密保存。" else "此备份没有凭据。现有网络账号、密码和开发者密钥将被清空，需要重新填写。")
        if (snapshot.sources.any { it.kind == SourceKind.DOCUMENT_TREE }) Note("本地目录授权不随备份转移。恢复完成后选择相同目录重新授权；不同设备的文档提供方可能需要修改来源并重新扫描。")
        if (error.isNotBlank()) Note(error, emphasis = true)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            TvButton("取消恢复", onClick = { host.navigator.pop() }, filled = false, modifier = Modifier.focusRequester(cancelFocus), enabled = !busy)
            TvButton(if (busy) "正在恢复…" else "确认替换并恢复", danger = true, enabled = !busy, onClick = {
                busy = true
                actions.run({ host.repository.restoreBackup(snapshot) }, { busy = false; error = it }) { busy = false; host.navigator.switchTab(3); host.navigator.push(Route.BackupRestored) }
            })
        }
    }
}

@Composable
fun BackupRestoredScreen(host: TvHost) {
    val loader = rememberLoader { host.repository.sources() }
    ScreenScaffold("备份已恢复", subtitle = "播放进度与后台扫描的旧任务已失效，不会覆盖恢复结果。") {
        when (val state = loader.value) {
            Load.Loading -> LoadingPane("正在读取恢复后的来源…")
            is Load.Failed -> ErrorPane(state.message, onRetry = loader::reload, onBack = { host.navigator.pop() })
            is Load.Ready -> {
                val sources = state.value
                Note("数据恢复完成。请检查网络凭据与文件授权，再开始播放。", emphasis = true)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.filter { it.kind == SourceKind.DOCUMENT_TREE }.forEach { source ->
                        ActionRow("重新授权“${source.name}”", subtitle = "选择备份中的相同目录；目录地址不一致时请修改来源并重新扫描", icon = Icons.Default.List, onClick = {
                            host.pickDirectory { uri -> host.notify(if (uri.toString() == source.location) "目录读取权限已保存" else "所选目录与备份地址不同，请通过“管理来源”修改连接并重新扫描；原路径的收藏与历史无法自动迁移") }
                        })
                    }
                    sources.forEach { source -> ActionRow("管理来源：${source.name}", subtitle = "修改凭据或重建目录索引", icon = Icons.Default.Settings, chevron = true, onClick = { host.navigator.push(Route.SourceActions(source)) }) }
                    ActionRow("进入媒体库", icon = Icons.Default.List, onClick = { host.navigator.switchTab(1) })
                }
            }
        }
    }
}
