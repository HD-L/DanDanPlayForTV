package com.xyoye.dandanplay.ui.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.data_component.data.AnimeData
import com.xyoye.data_component.data.BangumiData
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 番剧浏览 ViewModel：每周更新 / 搜索 / 季度番剧 / 番剧详情，全部走在线 AnimeRepository。
 */
class TvAnimeViewModel : ViewModel() {

    var loading by mutableStateOf(false)
        private set

    // 每周更新：按星期分组的货架
    var weekly: List<Pair<String, List<AnimeData>>> by mutableStateOf(emptyList())
        private set

    // 搜索
    var searchResult by mutableStateOf<List<AnimeData>>(emptyList())
        private set

    // 季度番剧
    var seasonResult by mutableStateOf<List<AnimeData>>(emptyList())
        private set
    var seasonLabel by mutableStateOf("")
        private set

    // 详情弹层
    var detail by mutableStateOf<BangumiData?>(null)
        private set
    var detailLoading by mutableStateOf(false)
        private set

    private val weekdayNames = listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    private var seasonYear = 0
    private var seasonMonth = 0

    fun loadWeekly() {
        if (weekly.isNotEmpty()) return
        viewModelScope.launch {
            loading = true
            val list = AnimeRepository.getWeeklyAnime().getOrNull()?.bangumiList ?: emptyList()
            weekly = (0..6).mapNotNull { day ->
                val items = list.filter { it.airDay == day }
                if (items.isEmpty()) null else weekdayNames[day] to items
            }
            loading = false
        }
    }

    fun search(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch {
            loading = true
            searchResult = AnimeRepository.searchAnime(keyword.trim(), null)
                .getOrNull()?.animes ?: emptyList()
            loading = false
        }
    }

    fun loadCurrentSeasonIfNeeded() {
        if (seasonYear != 0) return
        val calendar = Calendar.getInstance()
        seasonYear = calendar.get(Calendar.YEAR)
        seasonMonth = seasonMonthOf(calendar.get(Calendar.MONTH) + 1)
        loadSeason()
    }

    fun prevSeason() {
        seasonMonth -= 3
        if (seasonMonth < 1) {
            seasonMonth = 10
            seasonYear -= 1
        }
        loadSeason()
    }

    fun nextSeason() {
        seasonMonth += 3
        if (seasonMonth > 12) {
            seasonMonth = 1
            seasonYear += 1
        }
        loadSeason()
    }

    private fun seasonMonthOf(month: Int) = when {
        month <= 3 -> 1
        month <= 6 -> 4
        month <= 9 -> 7
        else -> 10
    }

    private fun loadSeason() {
        viewModelScope.launch {
            loading = true
            seasonLabel = "$seasonYear 年 $seasonMonth 月"
            seasonResult = AnimeRepository.getSeasonAnime(seasonYear.toString(), seasonMonth.toString())
                .getOrNull()?.bangumiList ?: emptyList()
            loading = false
        }
    }

    fun openDetail(animeId: Int) {
        viewModelScope.launch {
            detailLoading = true
            detail = AnimeRepository.getAnimeDetail(animeId.toString()).getOrNull()?.bangumi
            detailLoading = false
        }
    }

    fun closeDetail() {
        detail = null
    }
}
