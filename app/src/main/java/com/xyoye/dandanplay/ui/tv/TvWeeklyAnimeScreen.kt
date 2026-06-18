@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
 * 独立的「每周番剧」周表页（由左侧导航栏的专用按钮进入）。
 *
 * 复用 [TvAnimeViewModel] 已按星期分组好的 [TvAnimeViewModel.weekly]
 * （标签「周日」…「周六」，空的星期省略，日历顺序），每个星期一行横向海报墙。
 * 初始焦点落在「今日」那一行的首张卡片，列表会自动滚动到今天（复刻手机版默认定位当天的行为）。
 */
@Composable
fun TvWeeklyAnimeScreen(modifier: Modifier = Modifier) {
    val animeViewModel: TvAnimeViewModel = viewModel()
    val weekly = animeViewModel.weekly
    val todayLabel = remember { todayWeekdayLabel() }
    val todayFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { animeViewModel.loadWeekly() }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "每周番剧",
            modifier = Modifier.padding(start = 32.dp, top = 28.dp, bottom = 10.dp)
        )

        if (weekly.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "正在加载本周新番…", color = Color(0xFF9A9A9A))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
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
    }

    animeViewModel.detail?.let { bangumi ->
        AnimeDetailDialog(bangumi = bangumi, onDismiss = { animeViewModel.closeDetail() })
    }

    // 数据就绪后，把焦点落到「今日」首张卡片，使列表自动滚到今天
    LaunchedEffect(weekly, todayLabel) {
        if (weekly.any { it.first == todayLabel }) {
            runCatching { todayFocus.requestFocus() }
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
        Text(text = title, modifier = Modifier.padding(start = 32.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
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
