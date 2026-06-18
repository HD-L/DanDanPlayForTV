@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.config.UiConfig
import com.xyoye.common_component.weight.ToastCenter

enum class TvSettingsSection(val title: String) {
    UI("界面设置"),
    PLAYER("播放器设置"),
    DANMU("弹幕设置"),
    SUBTITLE("字幕设置"),
    APP("应用设置"),
    SCRAPE("刮削设置"),
    COMMON_FOLDER("常用文件夹"),
    CACHE("缓存管理"),
}

/**
 * 设置落地页（参考 B 站 TV 设置布局）：左侧功能栏 + 右侧随焦点联动的详细设置。
 * - 配置分区（播放器/弹幕/字幕/应用）：左栏聚焦即在右栏展示其详细设置。
 * - 功能入口（追番/历史/扫描/缓存/常用文件夹/B站/射手）：按 OK 打开对应独立页。
 */
@Composable
fun TvSettingsLanding(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var detail by remember {
        mutableStateOf<SettingsDetail>(SettingsDetail.Section(TvSettingsSection.UI))
    }
    val firstFocus = remember { FocusRequester() }

    val functions = remember {
        listOf(
            SettingsDetail.Func("媒体库管理", "添加 / 编辑 / 删除 / 刮削媒体源") { TvMediaManageActivity.start(it) },
            // 「扫描目录管理」独立入口已移除：改由 本地媒体库 → 编辑 进入
            SettingsDetail.Func("B站弹幕下载", "从哔哩哔哩下载弹幕") { TvBiliBiliDanmuActivity.start(it) },
            // 「射手字幕下载」入口已移除：密钥配置改到「字幕设置」的 API 密钥 item；搜索下载待挪进播放器字幕菜单。
        )
    }

    Row(modifier = modifier.fillMaxSize().padding(start = 36.dp, top = 28.dp, end = 36.dp, bottom = 24.dp)) {
        // 左：功能栏
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "设置",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)
            )

            SettingsNavGroupLabel("设置")
            TvSettingsSection.values().forEachIndexed { index, section ->
                SettingsNavRow(
                    title = section.title,
                    selected = (detail as? SettingsDetail.Section)?.section == section,
                    onFocused = { detail = SettingsDetail.Section(section) },
                    onClick = { detail = SettingsDetail.Section(section) },
                    focusRequester = if (index == 0) firstFocus else null
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
            SettingsNavGroupLabel("功能")
            functions.forEach { func ->
                SettingsNavRow(
                    title = func.title,
                    selected = (detail as? SettingsDetail.Func)?.title == func.title,
                    onFocused = { detail = func },
                    onClick = { func.action(context) }
                )
            }
        }

        Spacer(modifier = Modifier.width(32.dp))

        // 右：详细设置（随左栏焦点联动）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val d = detail) {
                is SettingsDetail.Section -> {
                    Text(
                        text = d.section.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    when (d.section) {
                        TvSettingsSection.UI -> UiSettings()
                        TvSettingsSection.PLAYER -> PlayerSettings()
                        TvSettingsSection.DANMU -> DanmuSettings()
                        TvSettingsSection.SUBTITLE -> SubtitleSettings()
                        TvSettingsSection.APP -> AppSettings()
                        TvSettingsSection.SCRAPE -> ScrapeSettings()
                        TvSettingsSection.COMMON_FOLDER -> CommonFolderSettings()
                        TvSettingsSection.CACHE -> CacheSettings()
                    }
                }

                is SettingsDetail.Func -> {
                    Text(
                        text = d.title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(text = d.desc, color = Color(0xFF9A9A9A))
                    Button(onClick = { d.action(context) }) {
                        Text(text = "打开「${d.title}」")
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

private sealed interface SettingsDetail {
    data class Section(val section: TvSettingsSection) : SettingsDetail
    data class Func(
        val title: String,
        val desc: String,
        val action: (Context) -> Unit
    ) : SettingsDetail
}

@Composable
private fun SettingsNavGroupLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF7A7A7A),
        fontSize = 12.sp,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
    )
}

/** 左侧功能栏的一行：聚焦即联动右栏（onFocused），OK 触发 onClick。选中态浅色高亮。 */
@Composable
private fun SettingsNavRow(
    title: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color(0x26FFFFFF) else Color(0x00000000),
            contentColor = if (selected) Color.White else Color(0xFFB8B8B8),
            focusedContainerColor = Color(0xFFEDEDED),
            focusedContentColor = Color(0xFF161619)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
        )
    }
}

/* ----------------- 各分区内容 ----------------- */

/** 界面设置：控制左侧导航栏各内容面板的显隐。左侧功能名 + 右侧显示/隐藏开关，默认全部显示，点击切换。 */
@Composable
private fun UiSettings() {
    // 至少保留一个可见面板：试图关掉最后一个时否决并提示（next 为 true 即开启，恒允许）。
    val keepAtLeastOne: (Boolean) -> Boolean = { next ->
        if (!next && currentVisiblePanelCount() <= 1) {
            ToastCenter.showWarning("至少保留一个面板")
            false
        } else {
            true
        }
    }

    TvSwitchRow("海报墙", null, { UiConfig.isShowPosterWall() }, { UiConfig.putShowPosterWall(it) }, onLabel = "显示", offLabel = "隐藏", canChange = keepAtLeastOne)
    TvSwitchRow("番剧", null, { UiConfig.isShowAnime() }, { UiConfig.putShowAnime(it) }, onLabel = "显示", offLabel = "隐藏", canChange = keepAtLeastOne)
    TvSwitchRow("历史记录", null, { UiConfig.isShowHistory() }, { UiConfig.putShowHistory(it) }, onLabel = "显示", offLabel = "隐藏", canChange = keepAtLeastOne)
    TvSwitchRow("串流面板", null, { UiConfig.isShowStream() }, { UiConfig.putShowStream(it) }, onLabel = "显示", offLabel = "隐藏", canChange = keepAtLeastOne)
    TvSwitchRow("磁力面板", null, { UiConfig.isShowMagnet() }, { UiConfig.putShowMagnet(it) }, onLabel = "显示", offLabel = "隐藏", canChange = keepAtLeastOne)
    TvSwitchRow("投屏接收", null, { UiConfig.isShowScreencast() }, { UiConfig.putShowScreencast(it) }, onLabel = "显示", offLabel = "隐藏", canChange = keepAtLeastOne)
}

/** 当前展示的面板数量（界面设置 6 项中处于「显示」的个数）。 */
private fun currentVisiblePanelCount(): Int = listOf(
    UiConfig.isShowPosterWall(),
    UiConfig.isShowAnime(),
    UiConfig.isShowHistory(),
    UiConfig.isShowStream(),
    UiConfig.isShowMagnet(),
    UiConfig.isShowScreencast()
).count { it }

@Composable
private fun PlayerSettings() {
    TvSelectRow(
        label = "播放器内核",
        options = listOf("IJK" to 0, "ExoPlayer" to 1, "VLC" to 2),
        get = { PlayerConfig.getUsePlayerType() },
        set = { PlayerConfig.putUsePlayerType(it) }
    )
    TvSwitchRow("硬解码（IJK）", null, { PlayerConfig.isUseMediaCodeC() }, { PlayerConfig.putUseMediaCodeC(it) })
    TvSwitchRow("H.265 硬解（IJK）", null, { PlayerConfig.isUseMediaCodeCH265() }, { PlayerConfig.putUseMediaCodeCH265(it) })
    TvSwitchRow("OpenSL ES（IJK）", "只要还有声音就别开", { PlayerConfig.isUseOpenSlEs() }, { PlayerConfig.putUseOpenSlEs(it) })
    TvSwitchRow("SurfaceView 渲染", "渲染性能更高", { PlayerConfig.isUseSurfaceView() }, { PlayerConfig.putUseSurfaceView(it) })
}

@Composable
private fun DanmuSettings() {
    TvSwitchRow("自动加载同名弹幕", null, { DanmuConfig.isAutoLoadSameNameDanmu() }, { DanmuConfig.putAutoLoadSameNameDanmu(it) })
    TvSwitchRow("自动匹配网络弹幕", null, { DanmuConfig.isAutoMatchDanmu() }, { DanmuConfig.putAutoMatchDanmu(it) })
    TvSelectRow(
        label = "弹幕语言",
        options = listOf("原文" to 0, "简体" to 1, "繁体" to 2),
        get = { DanmuConfig.getDanmuLanguage() },
        set = { DanmuConfig.putDanmuLanguage(it) }
    )
    TvSwitchRow("适配高刷新率屏幕", null, { DanmuConfig.isDanmuUpdateInChoreographer() }, { DanmuConfig.putDanmuUpdateInChoreographer(it) })
    TvSwitchRow("弹幕云屏蔽", null, { DanmuConfig.isCloudDanmuBlock() }, { DanmuConfig.putCloudDanmuBlock(it) })
    TvSwitchRow("弹幕调试工具", null, { DanmuConfig.isDanmuDebug() }, { DanmuConfig.putDanmuDebug(it) })
}

@Composable
private fun SubtitleSettings() {
    TvSwitchRow("自动加载同名字幕", null, { SubtitleConfig.isAutoLoadSameNameSubtitle() }, { SubtitleConfig.putAutoLoadSameNameSubtitle(it) })
    TvInputRow("同名字幕加载优先级", { SubtitleConfig.getSubtitlePriority() ?: "" }, { SubtitleConfig.putSubtitlePriority(it) })
    TvSwitchRow("自动匹配网络字幕", null, { SubtitleConfig.isAutoMatchSubtitle() }, { SubtitleConfig.putAutoMatchSubtitle(it) })
    ShooterSecretRow()
}

/** 射手（assrt）字幕 API 密钥配置：左「射手字幕」右「API 密钥」，点击弹窗输入并保存（密码掩码，回填已存密钥）。 */
@Composable
private fun ShooterSecretRow() {
    var showDialog by remember { mutableStateOf(false) }

    Surface(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "射手字幕", modifier = Modifier.weight(1f))
            Text(text = "API 密钥", color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showDialog) {
        TvFormInputDialog(
            title = "射手字幕 API 密钥",
            initial = SubtitleConfig.getShooterSecret() ?: "",
            isPassword = true,
            onConfirm = {
                SubtitleConfig.putShooterSecret(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun AppSettings() {
    TvSwitchRow("启用备用服务器", null, { AppConfig.isBackupDomainEnable() }, { AppConfig.putBackupDomainEnable(it) })
    TvInputRow("备用服务器地址", { AppConfig.getBackupDomain() ?: "" }, { AppConfig.putBackupDomain(it) })
    TvSwitchRow("显示隐藏文件", null, { AppConfig.isShowHiddenFile() }, { AppConfig.putShowHiddenFile(it) })
    TvSwitchRow("展示启动页动画", null, { AppConfig.isShowSplashAnimation() }, { AppConfig.putShowSplashAnimation(it) })
}

/** 刮削设置：控制刮削时每个媒体之间的接口调用间隔（节流），缓解共享凭据被限流。 */
@Composable
private fun ScrapeSettings() {
    TvSelectRow(
        label = "刮削速度（每个媒体间隔）",
        options = listOf(
            "不限速" to 0,
            "1 秒 / 个" to 1000,
            "3 秒 / 个" to 3000,
            "5 秒 / 个" to 5000
        ),
        get = { AppConfig.getScrapeIntervalMs() },
        set = { AppConfig.putScrapeIntervalMs(it) }
    )
}

/** 常用文件夹：两个常用文件夹槽（选择 / 清除）+「记住上次打开的文件夹」开关。内嵌设置面板，原独立页 TvCommonlyFolderActivity 已下线。 */
@Composable
private fun CommonFolderSettings() {
    var folder1 by remember { mutableStateOf(AppConfig.getCommonlyFolder1() ?: "") }
    var folder2 by remember { mutableStateOf(AppConfig.getCommonlyFolder2() ?: "") }
    var picking by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf<Int?>(null) }
    val defaultPath = remember { Environment.getExternalStorageDirectory().absolutePath }

    CommonFolderRow(label = "常用文件夹 1", path = folder1, onPick = { picking = 1 }, onClear = { confirmClear = 1 })
    CommonFolderRow(label = "常用文件夹 2", path = folder2, onPick = { picking = 2 }, onClear = { confirmClear = 2 })
    TvSwitchRow("记住上次打开的文件夹", null, { AppConfig.isLastOpenFolderEnable() }, { AppConfig.putLastOpenFolderEnable(it) })

    picking?.let { slot ->
        val start = (if (slot == 1) folder1 else folder2).ifEmpty { defaultPath }
        TvLocalFolderPickerDialog(
            initialPath = start,
            onDismiss = { picking = null },
            onConfirm = { path ->
                if (slot == 1) {
                    AppConfig.putCommonlyFolder1(path); folder1 = path
                } else {
                    AppConfig.putCommonlyFolder2(path); folder2 = path
                }
                picking = null
            }
        )
    }

    confirmClear?.let { slot ->
        Dialog(onDismissRequest = { confirmClear = null }) {
            Surface(modifier = Modifier.fillMaxWidth(0.5f)) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(text = "确认删除常用文件夹$slot？")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = {
                            if (slot == 1) {
                                AppConfig.putCommonlyFolder1(""); folder1 = ""
                            } else {
                                AppConfig.putCommonlyFolder2(""); folder2 = ""
                            }
                            confirmClear = null
                        }) { Text(text = "确认删除") }
                        Button(onClick = { confirmClear = null }) { Text(text = "取消") }
                    }
                }
            }
        }
    }
}

/** 常用文件夹槽：左侧大按钮（展示标签 + 当前路径，点击进文件夹选择器），有路径时右侧附「清除」。 */
@Composable
private fun CommonFolderRow(label: String, path: String, onPick: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth(if (path.isEmpty()) 1f else 0.82f)
        ) {
            Column {
                Text(text = label, maxLines = 1)
                Text(
                    text = if (path.isEmpty()) "路径：未设置" else "路径：$path",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFFCFCFCF)
                )
            }
        }
        if (path.isNotEmpty()) {
            Button(onClick = onClear) { Text(text = "清除") }
        }
    }
}

/* ----------------- 通用设置控件 ----------------- */

@Composable
private fun TvSwitchRow(
    label: String,
    summary: String?,
    get: () -> Boolean,
    set: (Boolean) -> Unit,
    onLabel: String = "开",
    offLabel: String = "关",
    canChange: (Boolean) -> Boolean = { true }
) {
    var checked by remember { mutableStateOf(get()) }
    Surface(
        onClick = {
            val next = !checked
            // canChange 返回 false 时否决本次切换（如「至少保留一个」约束），保持原状
            if (canChange(next)) {
                checked = next
                set(next)
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = label)
                summary?.let { Text(text = it, color = Color(0xFF9A9A9A), fontSize = 13.sp) }
            }
            Text(
                text = if (checked) onLabel else offLabel,
                color = if (checked) MaterialTheme.colorScheme.primary else Color(0xFF9A9A9A)
            )
        }
    }
}

@Composable
private fun <T> TvSelectRow(
    label: String,
    options: List<Pair<String, T>>,
    get: () -> T,
    set: (T) -> Unit
) {
    var current by remember { mutableStateOf(get()) }
    var showDialog by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.second == current }?.first ?: "—"

    Surface(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.weight(1f))
            Text(text = currentLabel, color = MaterialTheme.colorScheme.primary)
        }
    }

    if (showDialog) {
        TvSelectDialog(
            title = label,
            options = options,
            onPick = {
                current = it
                set(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun <T> TvSelectDialog(
    title: String,
    options: List<Pair<String, T>>,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(420.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = title)
                options.forEach { (optionLabel, value) ->
                    Button(onClick = { onPick(value) }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = optionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun TvInputRow(
    label: String,
    get: () -> String,
    set: (String) -> Unit
) {
    var current by remember { mutableStateOf(get()) }
    var showDialog by remember { mutableStateOf(false) }

    Surface(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, modifier = Modifier.weight(1f))
            Text(
                text = current.ifEmpty { "—" },
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showDialog) {
        TvInputDialog(
            title = label,
            initial = current,
            onConfirm = {
                current = it
                set(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun TvInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(520.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = title)
                Surface(modifier = Modifier.fillMaxWidth()) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onConfirm(text) }, modifier = Modifier.weight(1f)) { Text("确定") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                }
            }
        }
    }
}
