@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.entity.ScrapedAnimeEntity
import com.xyoye.data_component.entity.ScrapedEpisodeEntity
import com.xyoye.local_component.ui.fragment.media.MediaViewModel

/** 首页内容中枢里一张卡片的统一模型 */
private data class HubItem(
    val title: String,
    val imageUrl: Any?,      // 海报 URL(String) 或图标 drawable res(Int)
    val subtitle: String?,
    val onClick: () -> Unit,
    val progress: Float? = null,   // 历史进度(0~1),显示在缩略图底部
    val badge: String? = null      // 角标(如「未识别」)
)

/**
 * 首页内容中枢：Hero 大图 + 多行横向货架（继续观看 / 我的媒体 / 本周新番 / 媒体源）。
 * 「番剧」的本周新番已折叠进此处；焦点在卡片间移动时 Hero 联动更新。
 */
@Composable
fun TvHomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val homeViewModel: TvHomeViewModel = viewModel()
    val animeViewModel: TvAnimeViewModel = viewModel()

    val scraped by homeViewModel.animeList.observeAsState()
    val unmatched by homeViewModel.unmatchedEpisodes.observeAsState()

    var heroItem by remember { mutableStateOf<HubItem?>(null) }

    LaunchedEffect(Unit) {
        homeViewModel.loadContinueWatching()
        animeViewModel.loadWeekly()
    }

    val shelves: List<Pair<String, List<HubItem>>> = buildList {
        homeViewModel.continueWatching
            .map { h ->
                HubItem(h.videoName, null, progressText(h), { homeViewModel.resume(context, h) }, progress = progressFraction(h))
            }
            .takeIf { it.isNotEmpty() }?.let { add("继续观看" to it) }

        (scraped ?: emptyList())
            .map { a -> HubItem(a.title, a.imageUrl, null, { TvAnimeDetailActivity.start(context, a.animeId) }) }
            .takeIf { it.isNotEmpty() }?.let { add("我的媒体" to it) }

        animeViewModel.weekly.flatMap { it.second }.distinctBy { it.animeId }.take(30)
            .map { a -> HubItem(a.animeTitle ?: "", a.imageUrl, null, { animeViewModel.openDetail(a.animeId) }) }
            .takeIf { it.isNotEmpty() }?.let { add("本周新番" to it) }

        (unmatched ?: emptyList())
            .map { ep -> HubItem(ep.fileName, null, null, { homeViewModel.play(context, ep) }, badge = "未识别") }
            .takeIf { it.isNotEmpty() }?.let { add("未识别媒体（点按即播）" to it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HubHero(
            hero = heroItem,
            scraping = homeViewModel.scraping,
            progressText = homeViewModel.progress?.let { "已扫描 ${it.scanned}　识别 ${it.matched}" },
            onScrape = { homeViewModel.scrape() }
        )

        if (shelves.isEmpty() && !homeViewModel.scraping) {
            EmptyHub(onScrape = { homeViewModel.scrape() })
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                items(shelves) { (title, list) ->
                    HubShelf(title = title, items = list, onItemFocused = { heroItem = it })
                }
            }
        }
    }

    animeViewModel.detail?.let { bangumi ->
        AnimeDetailDialog(bangumi = bangumi, onDismiss = { animeViewModel.closeDetail() })
    }
}

@Composable
private fun HubHero(
    hero: HubItem?,
    scraping: Boolean,
    progressText: String?,
    onScrape: () -> Unit
) {
    val backdrop = hero?.imageUrl as? String
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
    ) {
        if (backdrop != null) {
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC161619), Color(0x66161619), Color(0xF2161619))
                    )
                )
        )
        // 右上角实时时钟（参考 B 站 TV 首页布局）
        Text(
            text = rememberClockText(),
            color = Color(0xFFE8E8E8),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 32.dp)
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 20.dp, end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = hero?.title?.ifBlank { "弹弹play TV" } ?: "弹弹play TV")
            hero?.subtitle?.let { Text(text = it, color = Color(0xFFB5B5B5)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onScrape) { Text(text = if (scraping) "刷入中…" else "刷入媒体库") }
                if (scraping && progressText != null) {
                    Text(text = progressText, color = Color(0xFFB5B5B5), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun HubShelf(
    title: String,
    items: List<HubItem>,
    onItemFocused: (HubItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, modifier = Modifier.padding(start = 32.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(items) { item ->
                TvPosterCard(
                    imageUrl = item.imageUrl,
                    title = item.title,
                    onClick = item.onClick,
                    subtitle = item.subtitle,
                    badge = item.badge,
                    progress = item.progress,
                    modifier = Modifier.onFocusChanged { if (it.isFocused) onItemFocused(item) }
                )
            }
        }
    }
}

@Composable
private fun EmptyHub(onScrape: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(text = "还没有内容")
            Text(text = "到「媒体库」添加媒体源后，点「刷入媒体库」生成海报；或先看本周新番")
            Button(onClick = onScrape) { Text(text = "刷入媒体库") }
        }
    }
}

@Composable
internal fun EpisodeDialog(
    anime: ScrapedAnimeEntity,
    viewModel: TvHomeViewModel,
    onDismiss: () -> Unit,
    onPlay: (ScrapedEpisodeEntity) -> Unit
) {
    var episodes by remember { mutableStateOf<List<ScrapedEpisodeEntity>>(emptyList()) }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(anime.animeId) {
        episodes = viewModel.episodesOf(anime.animeId)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth(0.5f)) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = anime.title)
                if (episodes.isEmpty()) {
                    Text(text = "暂无可播放的分集")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(episodes) { index, episode ->
                            Button(
                                onClick = { onPlay(episode) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (index == 0) Modifier.focusRequester(firstFocus)
                                        else Modifier
                                    )
                            ) {
                                Text(
                                    text = episode.episodeTitle?.takeIf { it.isNotBlank() }
                                        ?: episode.fileName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(episodes) {
        if (episodes.isNotEmpty()) runCatching { firstFocus.requestFocus() }
    }
}

private fun progressText(history: PlayHistoryEntity): String {
    val duration = history.videoDuration
    val position = history.videoPosition
    return if (duration > 0) {
        val pct = (position * 100 / duration).coerceIn(0, 100)
        "已看 $pct%"
    } else {
        "继续观看"
    }
}

private fun progressFraction(history: PlayHistoryEntity): Float? {
    val duration = history.videoDuration
    if (duration <= 0) return null
    return (history.videoPosition.toFloat() / duration).coerceIn(0f, 1f)
}

/** 每分钟刷新一次的 HH:mm 时钟文本。 */
@Composable
private fun rememberClockText(): String {
    var text by remember { mutableStateOf(currentClockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            text = currentClockText()
            kotlinx.coroutines.delay(10_000)
        }
    }
    return text
}

private fun currentClockText(): String {
    return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())
}
