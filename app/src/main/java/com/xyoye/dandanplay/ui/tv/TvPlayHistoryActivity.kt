@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.toCoverFile
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.local_component.ui.activities.play_history.PlayHistoryViewModel

/**
 * 原生 TV 播放历史 / 串流 / 磁链页，替换 local_component 的 PlayHistoryActivity。复用其 [PlayHistoryViewModel]。
 */
class TvPlayHistoryActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TYPE = "mediaType"

        fun start(context: Context, mediaType: MediaType) {
            context.startActivity(
                Intent(context, TvPlayHistoryActivity::class.java).putExtra(EXTRA_TYPE, mediaType)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val mediaType = intent.getSerializableExtra(EXTRA_TYPE) as? MediaType ?: MediaType.OTHER_STORAGE
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvPlayHistoryScreen(mediaType = mediaType, onExit = { finish() })
                }
            }
        }
    }
}

/**
 * 播放历史 / 串流 / 磁链列表屏。可独立作为 Activity 内容，也可内嵌进 TV 主框架内容区。
 *
 * @param title    顶部标题；为空时用 [MediaType.storageName]（全局历史页传「播放历史」）。
 * @param onExit   非空时挂 BackHandler 并在返回时回调（独立 Activity 用 finish）；内嵌进 shell 时传 null。
 */
@Composable
internal fun TvPlayHistoryScreen(
    mediaType: MediaType,
    modifier: Modifier = Modifier,
    title: String? = null,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val viewModel: PlayHistoryViewModel = viewModel()
    val history by viewModel.historyLiveData.observeAsState()
    val list = history ?: emptyList()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showAddLink by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<PlayHistoryEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.mediaType = mediaType
        viewModel.updatePlayHistory()
    }
    DisposableEffect(Unit) {
        val observer = Observer<Any> {
            ARouter.getInstance().build(RouteTable.Player.Player).navigation(context)
        }
        viewModel.playLiveData.observe(lifecycleOwner, observer)
        onDispose { viewModel.playLiveData.removeObserver(observer) }
    }
    BackHandler(enabled = onExit != null) { onExit?.invoke() }

    Column(modifier = modifier.fillMaxSize().padding(32.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = title ?: mediaType.storageName)
            if (mediaType == MediaType.STREAM_LINK) {
                Button(onClick = { showAddLink = true }) { Text(text = "＋ 添加串流地址") }
            }
            if (list.isNotEmpty()) {
                Button(onClick = { viewModel.clearHistory() }) { Text(text = "清空") }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            if (list.isEmpty()) {
                Text(text = "暂无记录", modifier = Modifier.align(Alignment.Center))
            } else {
                // 海报墙风格的卡片网格：封面 + 标题 + 进度，长按管理（移除 / 解绑弹幕字幕）
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(list) { item ->
                        val percent = historyPercent(item)
                        TvPosterCard(
                            imageUrl = item.uniqueKey.toCoverFile()?.takeIf { it.exists() },
                            title = item.videoName,
                            onClick = { viewModel.openHistory(item) },
                            onLongClick = { manageTarget = item },
                            subtitle = percent?.let { "已看 $it%" },
                            progress = percent?.let { it / 100f }
                        )
                    }
                }
            }
        }
    }

    if (showAddLink) {
        HistoryInputDialog(
            title = "串流地址（http/https）",
            onConfirm = {
                showAddLink = false
                if (it.isNotBlank()) viewModel.openStreamLink(it.trim(), null)
            },
            onDismiss = { showAddLink = false }
        )
    }

    manageTarget?.let { item ->
        ManageHistoryDialog(
            history = item,
            onRemove = {
                manageTarget = null
                viewModel.removeHistory(item)
                viewModel.updatePlayHistory()
            },
            onUnbindDanmu = { manageTarget = null; viewModel.unbindDanmu(item) },
            onUnbindSubtitle = { manageTarget = null; viewModel.unbindSubtitle(item) },
            onDismiss = { manageTarget = null }
        )
    }
}

/** 历史进度百分比（0~100）；无时长信息时返回 null（不显示进度）。 */
private fun historyPercent(history: PlayHistoryEntity): Int? {
    if (history.videoDuration <= 0) return null
    return (history.videoPosition * 100 / history.videoDuration).toInt().coerceIn(0, 100)
}

@Composable
private fun ManageHistoryDialog(
    history: PlayHistoryEntity,
    onRemove: () -> Unit,
    onUnbindDanmu: () -> Unit,
    onUnbindSubtitle: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(440.dp)) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(text = history.videoName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Button(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text(text = "移除记录") }
                Button(onClick = onUnbindDanmu, modifier = Modifier.fillMaxWidth()) { Text(text = "解绑弹幕") }
                Button(onClick = onUnbindSubtitle, modifier = Modifier.fillMaxWidth()) { Text(text = "解绑字幕") }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(text = "取消") }
            }
        }
    }
}

@Composable
private fun HistoryInputDialog(
    title: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(560.dp)) {
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
