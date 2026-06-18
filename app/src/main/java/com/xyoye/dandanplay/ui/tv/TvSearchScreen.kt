@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.entity.ScrapedAnimeEntity

/**
 * 番剧/全局搜索独立页：由「番剧」面板的搜索按钮点击打开（替代原嵌入式面板），按返回键退出。
 */
class TvSearchActivity : ComponentActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TvSearchActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvSearchScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * 全局搜索：同时搜「本地刮削媒体」（内存过滤）与「在线番剧」（AnimeRepository）。风格与海报墙统一。
 */
@Composable
fun TvSearchScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val homeViewModel: TvHomeViewModel = viewModel()
    val animeViewModel: TvAnimeViewModel = viewModel()
    val scraped by homeViewModel.animeList.observeAsState()

    var keyword by remember { mutableStateOf("") }
    var selectedAnime by remember { mutableStateOf<ScrapedAnimeEntity?>(null) }

    val localResults = run {
        val k = keyword.trim()
        if (k.isEmpty()) emptyList()
        else (scraped ?: emptyList()).filter { it.title.contains(k, ignoreCase = true) }
    }
    val onlineResults: List<AnimeData> = animeViewModel.searchResult

    Column(
        modifier = modifier.fillMaxSize().padding(start = 32.dp, top = 28.dp, end = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "搜索")
            TvSearchField(
                value = keyword,
                onValueChange = { keyword = it },
                onSearch = { animeViewModel.search(keyword) },
                modifier = Modifier.width(480.dp),
                placeholder = "搜索本地媒体与在线番剧…"
            )
            Button(onClick = { animeViewModel.search(keyword) }) { Text(text = "搜索") }
            if (animeViewModel.loading) Text(text = "搜索中…")
        }

        if (keyword.isBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "输入关键字搜索")
            }
        } else if (localResults.isEmpty() && onlineResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "无结果（在线番剧需配置 AppId/AppSecret）")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (localResults.isNotEmpty()) {
                    item {
                        SearchShelf(title = "本地媒体（${localResults.size}）") {
                            items(localResults) { anime ->
                                TvPosterCard(
                                    imageUrl = anime.imageUrl,
                                    title = anime.title,
                                    onClick = { selectedAnime = anime }
                                )
                            }
                        }
                    }
                }
                if (onlineResults.isNotEmpty()) {
                    item {
                        SearchShelf(title = "在线番剧（${onlineResults.size}）") {
                            items(onlineResults) { anime ->
                                TvPosterCard(
                                    imageUrl = anime.imageUrl,
                                    title = anime.animeTitle ?: "",
                                    onClick = { animeViewModel.openDetail(anime.animeId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedAnime?.let { anime ->
        EpisodeDialog(
            anime = anime,
            viewModel = homeViewModel,
            onDismiss = { selectedAnime = null },
            onPlay = { episode ->
                selectedAnime = null
                homeViewModel.play(context, episode)
            }
        )
    }

    animeViewModel.detail?.let { bangumi ->
        AnimeDetailDialog(bangumi = bangumi, onDismiss = { animeViewModel.closeDetail() })
    }
}

@Composable
private fun SearchShelf(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title)
        LazyRow(
            contentPadding = PaddingValues(end = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}
