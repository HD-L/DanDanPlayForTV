@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.PlayerConfig
import com.xyoye.common_component.config.SubtitleConfig

enum class TvSettingsSection(val title: String) {
    PLAYER("播放器设置"),
    DANMU("弹幕设置"),
    SUBTITLE("字幕设置"),
    APP("应用设置"),
}

@Composable
fun TvSettingsScreen(section: TvSettingsSection, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        Text(text = "‹  ${section.title}")
        Spacer(modifier = Modifier.height(20.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (section) {
                TvSettingsSection.PLAYER -> PlayerSettings()
                TvSettingsSection.DANMU -> DanmuSettings()
                TvSettingsSection.SUBTITLE -> SubtitleSettings()
                TvSettingsSection.APP -> AppSettings()
            }
        }
    }
}

/* ----------------- 各分区内容 ----------------- */

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
}

@Composable
private fun AppSettings() {
    TvSwitchRow("启用备用服务器", null, { AppConfig.isBackupDomainEnable() }, { AppConfig.putBackupDomainEnable(it) })
    TvInputRow("备用服务器地址", { AppConfig.getBackupDomain() ?: "" }, { AppConfig.putBackupDomain(it) })
    TvSwitchRow("显示隐藏文件", null, { AppConfig.isShowHiddenFile() }, { AppConfig.putShowHiddenFile(it) })
    TvSwitchRow("展示启动页动画", null, { AppConfig.isShowSplashAnimation() }, { AppConfig.putShowSplashAnimation(it) })
    TvSelectRow(
        label = "深色模式",
        options = listOf(
            "跟随系统" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            "深色" to AppCompatDelegate.MODE_NIGHT_YES,
            "浅色" to AppCompatDelegate.MODE_NIGHT_NO
        ),
        get = { AppConfig.getDarkMode() },
        set = {
            AppConfig.putDarkMode(it)
            AppCompatDelegate.setDefaultNightMode(it)
        }
    )
}

/* ----------------- 通用设置控件 ----------------- */

@Composable
private fun TvSwitchRow(
    label: String,
    summary: String?,
    get: () -> Boolean,
    set: (Boolean) -> Unit
) {
    var checked by remember { mutableStateOf(get()) }
    Surface(
        onClick = {
            checked = !checked
            set(checked)
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
                text = if (checked) "开" else "关",
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
