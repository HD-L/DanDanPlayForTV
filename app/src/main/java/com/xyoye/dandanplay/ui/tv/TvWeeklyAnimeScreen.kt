@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.xyoye.data_component.data.AnimeData
import java.util.Calendar

/**
 * 番剧页：顶部「每周番剧 / 我的追番」选项卡（参考历史记录的 tag 形式），默认每周番剧。
 * - 每周番剧：复用 [TvAnimeViewModel.weekly]（按星期分组，今日前置 + 自动定位）。
 * - 我的追番：DanDanPlay 云端关注列表（需登录 + 开发者凭据）。
 * 两个标签页共用同一套番剧详情弹窗。
 */
@Composable
fun TvWeeklyAnimeScreen(modifier: Modifier = Modifier) {
    val animeViewModel: TvAnimeViewModel = viewModel()
    var tab by remember { mutableStateOf(0) }   // 0=每周番剧, 1=我的追番

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 32.dp, top = 24.dp, end = 32.dp, bottom = 24.dp)
    ) {
        TvTabRow(
            tabs = listOf("每周番剧", "我的追番"),
            selectedIndex = tab,
            onSelect = { tab = it }
        )
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxSize()) {
            when (tab) {
                0 -> WeeklyContent(animeViewModel)
                else -> FollowAnimeContent(animeViewModel)
            }
        }
    }

    animeViewModel.detail?.let { bangumi ->
        AnimeDetailDialog(bangumi = bangumi, onDismiss = { animeViewModel.closeDetail() })
    }
}

/** 每周番剧：按星期分组的横向海报墙，初始焦点落在「今日」行使列表自动滚到今天。 */
@Composable
private fun WeeklyContent(animeViewModel: TvAnimeViewModel) {
    val weekly = animeViewModel.weekly
    val todayLabel = remember { todayWeekdayLabel() }
    val todayFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { animeViewModel.loadWeekly() }

    if (weekly.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "正在加载本周新番…", color = Color(0xFF9A9A9A))
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            items(weekly) { (label, list) ->
                val isToday = label == todayLabel
                WeeklyShelf(
                    title = if (isToday) "$label · 今日新番" else label,
                    items = list,
                    onClick = { animeViewModel.openDetail(it.animeId) },
                    firstCardFocus = if (isToday) todayFocus else null
                )
            }
        }
    }

    LaunchedEffect(weekly, todayLabel) {
        if (weekly.any { it.first == todayLabel }) {
            runCatching { todayFocus.requestFocus() }
        }
    }
}

/** 我的追番：云端关注列表海报网格。用独立 key 的 ViewModel，避免与「云端历史」的一次性加载守卫冲突。 */
@Composable
private fun FollowAnimeContent(animeViewModel: TvAnimeViewModel) {
    val listViewModel: TvAnimeListViewModel = viewModel(key = "follow_anime")

    LaunchedEffect(Unit) { listViewModel.load(TvAnimeListMode.FOLLOW) }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            listViewModel.loading && listViewModel.items.isEmpty() ->
                Text(text = "加载中…", modifier = Modifier.align(Alignment.Center))

            listViewModel.items.isEmpty() ->
                Text(
                    text = "暂无追番\n（需登录并配置开发者凭据，否则接口返回 403）",
                    color = Color(0xFFB5B5B5),
                    modifier = Modifier.align(Alignment.Center)
                )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                gridItems(listViewModel.items) { anime ->
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

@Composable
private fun WeeklyShelf(
    title: String,
    items: List<AnimeData>,
    onClick: (AnimeData) -> Unit,
    firstCardFocus: FocusRequester? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, modifier = Modifier.padding(start = 4.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(items) { index, anime ->
                TvPosterCard(
                    imageUrl = anime.imageUrl,
                    title = anime.animeTitle ?: "",
                    onClick = { onClick(anime) },
                    modifier = if (index == 0 && firstCardFocus != null) {
                        Modifier.focusRequester(firstCardFocus)
                    } else {
                        Modifier
                    }
                )
            }
        }
    }
}

/** 今天对应的星期标签（与 [TvAnimeViewModel] 分组用的标签一致：周日=0 … 周六=6）。 */
private fun todayWeekdayLabel(): String {
    val names = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    val index = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
    return names[index]
}
