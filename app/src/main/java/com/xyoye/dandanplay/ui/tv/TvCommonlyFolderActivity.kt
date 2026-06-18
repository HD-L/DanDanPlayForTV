@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.config.AppConfig

/**
 * 原生 TV 常用文件夹：两个常用文件夹槽（用 [TvLocalFolderPickerDialog] 选择 / 清除）
 * + 「记住上次打开的文件夹」开关。纯 MMKV 配置，无网络无登录。
 */
class TvCommonlyFolderActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvCommonlyFolderActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvCommonlyFolderScreen(onExit = { finish() })
                }
            }
        }
    }
}

@Composable
private fun TvCommonlyFolderScreen(onExit: () -> Unit) {
    var folder1 by remember { mutableStateOf(AppConfig.getCommonlyFolder1() ?: "") }
    var folder2 by remember { mutableStateOf(AppConfig.getCommonlyFolder2() ?: "") }
    var lastEnable by remember { mutableStateOf(AppConfig.isLastOpenFolderEnable()) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var confirmClear by remember { mutableStateOf<Int?>(null) }

    val defaultPath = Environment.getExternalStorageDirectory().absolutePath

    BackHandler(enabled = true) { onExit() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(text = "常用文件夹")

        FolderRow(
            label = "常用文件夹 1",
            path = folder1,
            onPick = { picking = 1 },
            onClear = { confirmClear = 1 }
        )
        FolderRow(
            label = "常用文件夹 2",
            path = folder2,
            onPick = { picking = 2 },
            onClear = { confirmClear = 2 }
        )

        Button(onClick = {
            val next = !lastEnable
            lastEnable = next
            AppConfig.putLastOpenFolderEnable(next)
        }, modifier = Modifier.fillMaxWidth()) {
            Text(text = "记住上次打开的文件夹：" + if (lastEnable) "开" else "关")
        }
    }

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

@Composable
private fun FolderRow(label: String, path: String, onPick: () -> Unit, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(onClick = onPick, modifier = Modifier.fillMaxWidth(if (path.isEmpty()) 1f else 0.82f)) {
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
