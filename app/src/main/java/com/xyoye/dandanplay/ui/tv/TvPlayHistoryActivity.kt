@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Observer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.toCoverFile
import com.xyoye.data_component.entity.MediaLibraryEntity
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

/** 历史面板顶部选项卡 */
private enum class HistoryTab(val label: String) { LOCAL("本地"), CLOUD("云端") }

/**
 * 播放历史 / 串流 / 磁链列表屏。可独立作为 Activity 内容，也可内嵌进 TV 主框架内容区。
 *
 * @param title        顶部标题；为空时用 [MediaType.storageName]。
 * @param showCloudTab true 时顶部展示「本地 / 云端」两个选项卡（默认本地），云端页内嵌云端历史。
 * @param onExit       非空时挂 BackHandler 并在返回时回调（独立 Activity 用 finish）；内嵌进 shell 时传 null。
 */
@Composable
internal fun TvPlayHistoryScreen(
    mediaType: MediaType,
    modifier: Modifier = Modifier,
    title: String? = null,
    showCloudTab: Boolean = false,
    onExit: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val viewModel: PlayHistoryViewModel = viewModel()
    val history by viewModel.historyLiveData.observeAsState()
    val list = history ?: emptyList()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showAddLink by remember { mutableStateOf(false) }
    var showAddMagnet by remember { mutableStateOf(false) }
    var manageTarget by remember { mutableStateOf<PlayHistoryEntity?>(null) }
    var tab by remember { mutableStateOf(HistoryTab.LOCAL) }

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
        if (showCloudTab) {
            // 顶部选项卡：本地 / 云端（默认本地）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvTabRow(
                    tabs = listOf("本地", "云端"),
                    selectedIndex = if (tab == HistoryTab.LOCAL) 0 else 1,
                    onSelect = { tab = if (it == 0) HistoryTab.LOCAL else HistoryTab.CLOUD }
                )
                Spacer(modifier = Modifier.weight(1f))
                if (tab == HistoryTab.LOCAL && list.isNotEmpty()) {
                    Button(onClick = { viewModel.clearHistory() }) { Text(text = "清空") }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = title ?: mediaType.storageName)
                if (mediaType == MediaType.STREAM_LINK) {
                    Button(onClick = { showAddLink = true }) { Text(text = "＋ 添加串流地址") }
                }
                if (mediaType == MediaType.MAGNET_LINK) {
                    Button(onClick = { showAddMagnet = true }) { Text(text = "＋ 添加磁链") }
                }
                if (list.isNotEmpty()) {
                    Button(onClick = { viewModel.clearHistory() }) { Text(text = "清空") }
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
            if (showCloudTab && tab == HistoryTab.CLOUD) {
                CloudHistoryContent()
            } else {
                LocalHistoryGrid(
                    list = list,
                    onClick = { viewModel.openHistory(it) },
                    onLongClick = { manageTarget = it }
                )
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

    if (showAddMagnet) {
        HistoryInputDialog(
            title = "磁链地址（magnet:?xt=urn:btih:...）",
            onConfirm = {
                showAddMagnet = false
                val magnet = it.trim()
                if (magnet.isNotBlank()) {
                    // 进入种子文件浏览：解析磁链 → 列出种子内文件 → 选择即播（复用 TorrentStorage 浏览）
                    TvStorageFileActivity.start(context, MediaLibraryEntity.TORRENT.copy(url = magnet))
                }
            },
            onDismiss = { showAddMagnet = false }
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

/** 本地播放历史海报网格：封面 + 标题 + 进度，点击续播、长按管理（移除 / 解绑弹幕字幕）。 */
@Composable
private fun LocalHistoryGrid(
    list: List<PlayHistoryEntity>,
    onClick: (PlayHistoryEntity) -> Unit,
    onLongClick: (PlayHistoryEntity) -> Unit
) {
    if (list.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无记录")
        }
        return
    }
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
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) },
                subtitle = percent?.let { "已看 $it%" },
                progress = percent?.let { it / 100f }
            )
        }
    }
}

/** 云端历史海报网格（DanDanPlay 云端接口，需登录 + 开发者凭据）。点击进在线番剧详情弹窗。 */
@Composable
private fun CloudHistoryContent() {
    val listViewModel: TvAnimeListViewModel = viewModel()
    val animeViewModel: TvAnimeViewModel = viewModel()

    LaunchedEffect(Unit) { listViewModel.load(TvAnimeListMode.HISTORY) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            listViewModel.loading && listViewModel.items.isEmpty() ->
                Text(text = "加载中…", modifier = Modifier.align(Alignment.Center))

            listViewModel.items.isEmpty() ->
                Text(
                    text = "暂无云端历史\n（需登录并配置开发者凭据，否则接口返回 403）",
                    color = Color(0xFFB5B5B5),
                    modifier = Modifier.align(Alignment.Center)
                )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(listViewModel.items) { anime ->
                    TvPosterCard(
                        imageUrl = anime.imageUrl,
                        title = anime.animeTitle ?: "",
                        onClick = { animeViewModel.openDetail(anime.animeId) }
                    )
                }
            }
        }
    }

    animeViewModel.detail?.let { bangumi ->
        AnimeDetailDialog(bangumi = bangumi, onDismiss = { animeViewModel.closeDetail() })
    }
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
