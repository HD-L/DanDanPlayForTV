package com.xyoye.dandanplay.ui.tv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.utils.scraper.MediaScraper
import com.xyoye.common_component.utils.scraper.ScrapeProgress
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.PlayHistoryEntity
import com.xyoye.data_component.entity.ScrapedAnimeEntity
import com.xyoye.data_component.entity.ScrapedEpisodeEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页海报墙 ViewModel：暴露刮削得到的番剧列表，触发刮削并跟踪进度，处理选集起播。
 */
class TvHomeViewModel : ViewModel() {

    private val scrapedDao = DatabaseManager.instance.getScrapedMediaDao()

    val animeList: LiveData<MutableList<ScrapedAnimeEntity>> = scrapedDao.getAllAnime()

    // 扫到但未匹配上的文件（无海报，仍可直接播放）
    val unmatchedEpisodes: LiveData<MutableList<ScrapedEpisodeEntity>> = scrapedDao.getUnmatchedEpisodes()

    var scraping by mutableStateOf(false)
        private set
    var progress by mutableStateOf<ScrapeProgress?>(null)
        private set

    // 继续观看（最近播放历史）
    var continueWatching by mutableStateOf<List<PlayHistoryEntity>>(emptyList())
        private set

    private var scrapeJob: Job? = null

    fun loadContinueWatching() {
        viewModelScope.launch {
            continueWatching = withContext(Dispatchers.IO) {
                DatabaseManager.instance.getPlayHistoryDao().getAll().take(15)
            }
        }
    }

    /** 从播放历史续播 */
    fun resume(context: Context, history: PlayHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val storageId = history.storageId
            val library = storageId?.let {
                DatabaseManager.instance.getMediaLibraryDao().getById(it)
            }
            val storage = library?.let { StorageFactory.createStorage(it) }
            val file = storage?.historyFile(history)
            val source = file?.let { StorageVideoSourceFactory.create(it) }
            if (source == null) {
                withContext(Dispatchers.Main) { ToastCenter.showError("无法继续播放该记录") }
                return@launch
            }
            VideoSourceManager.getInstance().setSource(source)
            withContext(Dispatchers.Main) {
                ARouter.getInstance().build(RouteTable.Player.Player).navigation(context)
            }
        }
    }

    fun scrape() {
        if (scraping) return
        scraping = true
        progress = null
        scrapeJob = viewModelScope.launch {
            try {
                val result = MediaScraper.scrape { progress = it }
                ToastCenter.showSuccess("刷入完成：扫描 ${result.scanned}，识别 ${result.matched}")
            } catch (e: Exception) {
                e.printStackTrace()
                ToastCenter.showError("刷入失败：${e.message}")
            } finally {
                scraping = false
            }
        }
    }

    suspend fun episodesOf(animeId: Int): List<ScrapedEpisodeEntity> =
        withContext(Dispatchers.IO) { scrapedDao.getEpisodesByAnimeSync(animeId) }

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

    override fun onCleared() {
        super.onCleared()
        scrapeJob?.cancel()
    }
}
