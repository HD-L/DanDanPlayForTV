@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.local_component.ui.activities.bilibili_danmu.BilibiliDanmuViewModel

/**
 * 原生 TV B站弹幕下载：选择 AV号 / BV号 / 视频链接 模式 → 输入 → 下载，实时滚动日志。
 * 直接复用既有 [BilibiliDanmuViewModel] 的下载逻辑（Jsoup 解析 cid → 拉弹幕 XML → 入库），无登录无凭据。
 */
class TvBiliBiliDanmuActivity : ComponentActivity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvBiliBiliDanmuActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvBiliBiliDanmuScreen(onExit = { finish() })
                }
            }
        }
    }
}

private enum class BiliMode(val label: String) { AV("AV号"), BV("BV号"), URL("视频链接") }

@Composable
private fun TvBiliBiliDanmuScreen(onExit: () -> Unit) {
    val viewModel: BilibiliDanmuViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current
    val messages = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    var mode by remember { mutableStateOf(BiliMode.URL) }
    var input by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val msgObserver = Observer<String> { messages.add(it) }
        val clearObserver = Observer<Boolean> { if (it) messages.clear() }
        viewModel.downloadMessageLiveData.observe(lifecycleOwner, msgObserver)
        viewModel.clearMessageLiveData.observe(lifecycleOwner, clearObserver)
        onDispose {
            viewModel.downloadMessageLiveData.removeObserver(msgObserver)
            viewModel.clearMessageLiveData.removeObserver(clearObserver)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
    }
    BackHandler(enabled = true) { onExit() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = "B站弹幕下载")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BiliMode.values().forEach { m ->
                Button(onClick = { mode = m }) {
                    Text(text = if (mode == m) "● ${m.label}" else m.label)
                }
            }
        }

        val hint = when (mode) {
            BiliMode.AV -> "纯数字 AV 号"
            BiliMode.BV -> "BV 号，如 BV1xx..."
            BiliMode.URL -> "视频 / 番剧完整链接"
        }
        TvFormField(label = mode.label, value = input, placeholder = hint) { input = it }

        Button(onClick = {
            val text = input.trim()
            if (text.isEmpty()) { ToastCenter.showWarning("请输入${mode.label}"); return@Button }
            when (mode) {
                BiliMode.AV -> viewModel.downloadByCode(text, isAvCode = true)
                BiliMode.BV -> viewModel.downloadByCode(text, isAvCode = false)
                BiliMode.URL -> viewModel.downloadByUrl(text)
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(text = "开始下载") }

        Surface(modifier = Modifier.fillMaxSize()) {
            if (messages.isEmpty()) {
                Text(
                    text = "下载日志将显示在此处。",
                    color = Color(0xFF8A8A8A),
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                ) {
                    items(messages) { line ->
                        Text(text = line, color = Color(0xFFCFCFCF), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
