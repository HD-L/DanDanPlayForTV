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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.utils.scraper.MediaScraper
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.ScrapedAnimeEntity
import com.xyoye.data_component.entity.ScrapedEpisodeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 独立 TV 影视详情页：从首页海报墙点击进入。
 * 头部展示刮削入库的封面 / 标题 / 类型 / 评分 / 简介（全部读本地 SQLite），
 * 下方列出该番剧在本地媒体库中扫描到的文件，按下即起播。
 */
class TvAnimeDetailActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ANIME_ID = "animeId"

        fun start(context: Context, animeId: Int) {
            context.startActivity(
                Intent(context, TvAnimeDetailActivity::class.java).putExtra(EXTRA_ANIME_ID, animeId)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val animeId = intent.getIntExtra(EXTRA_ANIME_ID, -1)
        if (animeId <= 0) {
            ToastCenter.showError("无效的番剧")
            finish()
            return
        }

        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvAnimeDetailScreen(animeId = animeId, onExit = { finish() })
                }
            }
        }
    }
}

class TvAnimeDetailViewModel : ViewModel() {

    private val scrapedDao = DatabaseManager.instance.getScrapedMediaDao()

    var anime by mutableStateOf<ScrapedAnimeEntity?>(null)
        private set
    var episodes by mutableStateOf<List<ScrapedEpisodeEntity>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set

    private var loaded = false

    fun load(animeId: Int) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            loading = true
            val (a, eps) = withContext(Dispatchers.IO) {
                scrapedDao.getAnimeById(animeId) to scrapedDao.getEpisodesByAnimeSync(animeId)
            }
            anime = a
            episodes = eps
            loading = false
        }
    }

    fun play(context: Context, episode: ScrapedEpisodeEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val library = DatabaseManager.instance.getMediaLibraryDao().getById(episode.storageId)
            val storage = library?.let { StorageFactory.createStorage(it) }
            val path = episode.storagePath
            if (storage == null || path == null) {
                withContext(Dispatchers.Main) { ToastCenter.showError("无法定位该文件") }
                return@launch
            }
            val file = storage.pathFile(path, false)
            val source = file?.let { StorageVideoSourceFactory.create(it) }
            if (source == null) {
                withContext(Dispatchers.Main) { ToastCenter.showError("无法播放该文件") }
                return@launch
            }
            VideoSourceManager.getInstance().setSource(source)
            withContext(Dispatchers.Main) {
                ARouter.getInstance().build(RouteTable.Player.Player).navigation(context)
            }
        }
    }
}

@Composable
private fun TvAnimeDetailScreen(animeId: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val viewModel: TvAnimeDetailViewModel = viewModel()
    val firstEpisodeFocus = remember { FocusRequester() }

    LaunchedEffect(animeId) { viewModel.load(animeId) }

    BackHandler(enabled = true) { onExit() }

    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        when {
            viewModel.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "加载中…")
            }

            viewModel.anime == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "未找到该番剧资料")
            }

            else -> {
                val anime = viewModel.anime!!
                val episodes = viewModel.episodes
                DetailHeader(
                    anime = anime,
                    fileCount = episodes.size,
                    canPlay = episodes.isNotEmpty(),
                    playFocus = firstEpisodeFocus,
                    onPlay = { episodes.firstOrNull()?.let { viewModel.play(context, it) } }
                )
                Spacer(modifier = Modifier.height(24.dp))
                Text(text = "本地文件（${episodes.size}）", fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(12.dp))
                if (episodes.isEmpty()) {
                    Text(text = "该番剧暂无可播放的本地文件", color = Color(0xFFB5B5B5))
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(episodes) { _, episode ->
                            EpisodeRow(
                                episode = episode,
                                onPlay = { viewModel.play(context, episode) }
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(viewModel.anime) {
        if (viewModel.anime != null) runCatching { firstEpisodeFocus.requestFocus() }
    }
}

@Composable
private fun DetailHeader(
    anime: ScrapedAnimeEntity,
    fileCount: Int,
    canPlay: Boolean,
    playFocus: FocusRequester,
    onPlay: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        AsyncImage(
            model = anime.imageUrl,
            contentDescription = anime.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(200.dp)
                .height(280.dp)
                .clip(RoundedCornerShape(12.dp))
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = anime.title,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                anime.typeDescription?.takeIf { it.isNotBlank() }?.let { Text(text = it, color = Color(0xFFB5B5B5)) }
                if (anime.rating > 0) Text(text = "评分 ${anime.rating}", color = Color(0xFFE5A23B))
                Text(text = "本地 $fileCount 个文件", color = Color(0xFFB5B5B5))
            }
            anime.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    color = Color(0xFFD0D0D0),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            Button(
                onClick = onPlay,
                enabled = canPlay,
                modifier = Modifier.focusRequester(playFocus)
            ) {
                Text(text = if (canPlay) "▶  播放" else "无可播放文件")
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: ScrapedEpisodeEntity,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit
) {
    Button(onClick = onPlay, modifier = modifier.fillMaxWidth()) {
        val label = episode.episodeTitle?.takeIf { it.isNotBlank() } ?: episode.fileName
        val suffix = if (episode.status == MediaScraper.STATUS_UNMATCHED) "　·　未识别" else ""
        Text(text = "$label$suffix", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
