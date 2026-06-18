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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.data.AnimeData
import kotlinx.coroutines.launch

enum class TvAnimeListMode { FOLLOW, HISTORY }

/**
 * 原生 TV 番剧网格：复用同一屏承载「我的追番」与「云端历史」。
 * 数据走 DanDanPlay 云端接口（需登录 + 开发者凭据，否则 403 空态）。点击进在线番剧详情弹窗。
 */
class TvAnimeListActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: TvAnimeListMode) {
            context.startActivity(
                Intent(context, TvAnimeListActivity::class.java).putExtra(EXTRA_MODE, mode.name)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = runCatching {
            TvAnimeListMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: TvAnimeListMode.FOLLOW.name)
        }.getOrDefault(TvAnimeListMode.FOLLOW)

        setContent {
            TvAppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvAnimeListScreen(mode = mode, onExit = { finish() })
                }
            }
        }
    }
}

class TvAnimeListViewModel : ViewModel() {
    var items by mutableStateOf<List<AnimeData>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set

    private var all: List<AnimeData> = emptyList()
    private var loaded = false

    fun load(mode: TvAnimeListMode) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            loading = true
            all = if (mode == TvAnimeListMode.FOLLOW) {
                val result = AnimeRepository.getFollowedAnime()
                result.exceptionOrNull()?.message?.let { ToastCenter.showError(it) }
                result.getOrNull()?.favorites ?: emptyList()
            } else {
                val result = AnimeRepository.getPlayHistory()
                result.exceptionOrNull()?.message?.let { ToastCenter.showError(it) }
                result.getOrNull()?.playHistoryAnimes ?: emptyList()
            }
            loading = false
            applyFilter()
        }
    }

    fun search(keyword: String) {
        query = keyword
        applyFilter()
    }

    private fun applyFilter() {
        items = if (query.isEmpty()) all
        else all.filter { it.animeTitle?.contains(query, ignoreCase = true) == true }
    }
}

@Composable
private fun TvAnimeListScreen(mode: TvAnimeListMode, onExit: () -> Unit) {
    val listViewModel: TvAnimeListViewModel = viewModel()
    val animeViewModel: TvAnimeViewModel = viewModel()

    LaunchedEffect(Unit) { listViewModel.load(mode) }
    BackHandler(enabled = true) { onExit() }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = if (mode == TvAnimeListMode.FOLLOW) "我的追番" else "云端历史")
        TvSearchField(
            value = listViewModel.query,
            onValueChange = { listViewModel.search(it) },
            onSearch = { },
            modifier = Modifier.fillMaxWidth(0.6f)
        )

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                listViewModel.loading && listViewModel.items.isEmpty() ->
                    Text(text = "加载中…", modifier = Modifier.align(Alignment.Center))

                listViewModel.items.isEmpty() ->
                    Text(
                        text = "暂无内容\n（云端番剧需登录并配置开发者凭据，否则接口返回 403）",
                        color = Color(0xFFB5B5B5),
                        modifier = Modifier.align(Alignment.Center)
                    )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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
    }

    animeViewModel.detail?.let { bangumi ->
        AnimeDetailDialog(bangumi = bangumi, onDismiss = { animeViewModel.closeDetail() })
    }
}
