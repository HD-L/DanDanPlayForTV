@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import java.io.File

/**
 * 原生 TV 本地文件夹选择器：从 [initialPath] 开始浏览（仅展示子文件夹），
 * 可进入子目录 / 返回上级，确认后回调当前目录绝对路径。替换旧的 View 版 FileManagerDialog。
 */
@Composable
fun TvLocalFolderPickerDialog(
    initialPath: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var current by remember { mutableStateOf(File(initialPath).takeIf { it.exists() } ?: File("/storage/emulated/0")) }
    val subDirs = remember(current.absolutePath) {
        current.listFiles()
            ?.filter { it.isDirectory && !it.isHidden && it.canRead() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }
    val firstFocus = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth(0.72f).fillMaxHeight(0.82f)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = current.absolutePath, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onConfirm(current.absolutePath) },
                        modifier = Modifier.focusRequester(firstFocus)
                    ) { Text(text = "选择此文件夹") }
                    current.parentFile?.let { parent ->
                        Button(onClick = { current = parent }) { Text(text = "上级目录") }
                    }
                    Button(onClick = onDismiss) { Text(text = "取消") }
                }
                if (subDirs.isEmpty()) {
                    Text(text = "（无可进入的子文件夹）", color = Color(0xFFB5B5B5))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(subDirs) { dir ->
                            Surface(
                                onClick = { current = dir },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "📁  ${dir.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}
