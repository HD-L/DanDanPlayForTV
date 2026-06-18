@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.BangumiData

/**
 * 侧边栏「番剧」：原生 Compose 重写的在线番剧浏览（每周更新 / 找番剧 / 季度番剧 + 详情）。
 * 与首页海报墙共用 [TvPosterCard] 与深色主题，风格统一。
 */
@Composable
fun TvAnimeScreen(modifier: Modifier = Modifier) {
    val viewModel: TvAnimeViewModel = viewModel()
    var mode by remember { mutableStateOf(AnimeMode.WEEKLY) }

    LaunchedEffect(mode) {
        when (mode) {
            AnimeMode.WEEKLY -> viewModel.loadWeekly()
            AnimeMode.SEASON -> viewModel.loadCurrentSeasonIfNeeded()
            AnimeMode.SEARCH -> Unit
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 32.dp, top = 28.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "番剧")
            AnimeMode.values().forEach { m ->
                Button(onClick = { mode = m }) {
                    Text(text = if (m == mode) "▶ ${m.title}" else m.title)
                }
            }
            if (viewModel.loading) {
                Text(text = "加载中…")
            }
        }

        when (mode) {
            AnimeMode.WEEKLY -> WeeklyContent(viewModel)
            AnimeMode.SEARCH -> SearchContent(viewModel)
            AnimeMode.SEASON -> SeasonContent(viewModel)
        }
    }

    viewModel.detail?.let { bangumi ->
        AnimeDetailDialog(bangumi = bangumi, onDismiss = { viewModel.closeDetail() })
    }
}

private enum class AnimeMode(val title: String) {
    WEEKLY("每周更新"),
    SEARCH("找番剧"),
    SEASON("季度番剧"),
}

@Composable
private fun WeeklyContent(viewModel: TvAnimeViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        items(viewModel.weekly) { (day, animes) ->
            AnimeShelf(title = day, animes = animes) { viewModel.openDetail(it.animeId) }
        }
    }
}

@Composable
private fun SearchContent(viewModel: TvAnimeViewModel) {
    var keyword by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TvSearchField(
                value = keyword,
                onValueChange = { keyword = it },
                onSearch = { viewModel.search(keyword) },
                modifier = Modifier.width(440.dp)
            )
            Button(onClick = { viewModel.search(keyword) }) { Text(text = "搜索") }
        }
        AnimeGrid(animes = viewModel.searchResult) { viewModel.openDetail(it.animeId) }
    }
}

@Composable
private fun SeasonContent(viewModel: TvAnimeViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = { viewModel.prevSeason() }) { Text(text = "上一季") }
            Text(text = viewModel.seasonLabel.ifEmpty { "—" })
            Button(onClick = { viewModel.nextSeason() }) { Text(text = "下一季") }
        }
        AnimeGrid(animes = viewModel.seasonResult) { viewModel.openDetail(it.animeId) }
    }
}

@Composable
private fun AnimeShelf(
    title: String,
    animes: List<AnimeData>,
    onClickAnime: (AnimeData) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "$title（${animes.size}）", modifier = Modifier.padding(start = 32.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(animes) { anime ->
                TvPosterCard(
                    imageUrl = anime.imageUrl,
                    title = anime.animeTitle ?: "",
                    onClick = { onClickAnime(anime) }
                )
            }
        }
    }
}

@Composable
private fun AnimeGrid(animes: List<AnimeData>, onClickAnime: (AnimeData) -> Unit) {
    if (animes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "暂无结果")
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        gridItems(animes) { anime ->
            TvPosterCard(
                imageUrl = anime.imageUrl,
                title = anime.animeTitle ?: "",
                onClick = { onClickAnime(anime) }
            )
        }
    }
}

@Composable
internal fun AnimeDetailDialog(bangumi: BangumiData, onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.width(720.dp)) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    AsyncImage(
                        model = bangumi.imageUrl,
                        contentDescription = bangumi.animeTitle,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(140.dp).height(196.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = bangumi.animeTitle ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                        bangumi.typeDescription?.takeIf { it.isNotBlank() }?.let { Text(text = it) }
                        Text(text = "评分 ${bangumi.rating}")
                        Text(text = "共 ${bangumi.episodes.size} 集")
                    }
                }
                bangumi.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
                if (bangumi.episodes.isNotEmpty()) {
                    Text(text = "分集")
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bangumi.episodes) { episode ->
                            Text(
                                text = "· ${episode.episodeTitle}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().focusRequester(closeFocus)
                ) {
                    Text(text = "关闭")
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
}
