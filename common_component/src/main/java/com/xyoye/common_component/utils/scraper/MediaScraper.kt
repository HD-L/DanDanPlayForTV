package com.xyoye.common_component.utils.scraper

import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.network.repository.AnimeRepository
import com.xyoye.common_component.network.repository.ResourceRepository
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.utils.danmu.helper.DanmuHashCalculator
import com.xyoye.data_component.data.DanmuEpisodeData
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.ScrapedAnimeEntity
import com.xyoye.data_component.entity.ScrapedEpisodeEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 媒体库刮削器：遍历可列举的媒体库视频文件 → 计算前 16MB 的 MD5 → DanDanPlay 文件匹配
 * → 拉取番剧详情取海报 → 写入 scraped_anime / scraped_episode 两张表，供首页海报墙展示。
 *
 * 全部复用既有底层能力，无 UI 依赖。增量去重(已刮削的 uniqueKey 跳过)，支持协程取消。
 */
object MediaScraper {

    const val STATUS_MATCHED = "matched"
    const val STATUS_UNMATCHED = "unmatched"

    // 仅这些类型可列目录并读取字节用于哈希匹配（其余为链接/投屏/磁链/远程，跳过）
    private val SCRAPEABLE_TYPES = listOf(
        MediaType.LOCAL_STORAGE,
        MediaType.EXTERNAL_STORAGE,
        MediaType.WEBDAV_SERVER,
        MediaType.FTP_SERVER,
        MediaType.SMB_SERVER,
        MediaType.ALSIT_STORAGE,
    )

    /** 是否为可刮削（可列目录读字节）的媒体库类型 */
    fun isScrapeable(mediaType: MediaType): Boolean = SCRAPEABLE_TYPES.contains(mediaType)

    /** 刮削全部可刮削媒体库（首页一键刷入） */
    suspend fun scrape(onProgress: (ScrapeProgress) -> Unit = {}): ScrapeProgress =
        withContext(Dispatchers.IO) {
            val libraryDao = DatabaseManager.instance.getMediaLibraryDao()
            val libraries = SCRAPEABLE_TYPES.flatMap { libraryDao.getByMediaTypeSuspend(it) }
            scrapeLibraries(libraries, onProgress)
        }

    /** 仅刮削单个媒体库（媒体库内「刮削此源」入口） */
    suspend fun scrape(
        library: MediaLibraryEntity,
        onProgress: (ScrapeProgress) -> Unit = {}
    ): ScrapeProgress = withContext(Dispatchers.IO) {
        scrapeLibraries(listOf(library), onProgress)
    }

    private suspend fun scrapeLibraries(
        libraries: List<MediaLibraryEntity>,
        onProgress: (ScrapeProgress) -> Unit
    ): ScrapeProgress {
        val scrapedDao = DatabaseManager.instance.getScrapedMediaDao()

        var scanned = 0
        var matched = 0
        val animeCache = HashSet<Int>()

        for (library in libraries) {
            currentCoroutineContext().ensureActive()
            val storage = StorageFactory.createStorage(library) ?: continue
            try {
                val videoFiles = collectVideoFiles(storage)
                for (file in videoFiles) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        ScrapeProgress(library.displayName, file.fileName(), scanned, matched)
                    )

                    if (scrapedDao.isScraped(library.id, file.uniqueKey())) {
                        scanned++
                        continue
                    }

                    val episode = runCatching { matchFile(storage, file) }.getOrNull()
                    scrapedDao.insertEpisode(
                        ScrapedEpisodeEntity(
                            storageId = library.id,
                            uniqueKey = file.uniqueKey(),
                            fileName = file.fileName(),
                            storagePath = file.storagePath(),
                            animeId = episode?.animeId ?: 0,
                            animeTitle = episode?.animeTitle,
                            episodeId = episode?.episodeId,
                            episodeTitle = episode?.episodeTitle,
                            status = if (episode != null) STATUS_MATCHED else STATUS_UNMATCHED,
                            scrapedAt = currentTimeMillis()
                        )
                    )

                    if (episode != null && episode.animeId > 0) {
                        matched++
                        ensureAnimeMeta(animeCache, episode)
                    }
                    scanned++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                runCatching { storage.close() }
            }
        }

        return ScrapeProgress("", "", scanned, matched, finished = true)
    }

    /** 递归(BFS)收集一个媒体库下的全部视频文件 */
    private suspend fun collectVideoFiles(storage: Storage): List<StorageFile> {
        val result = mutableListOf<StorageFile>()
        val root = storage.getRootFile() ?: return result
        val queue = ArrayDeque<StorageFile>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            val children = runCatching { storage.openDirectory(dir, false) }
                .getOrDefault(emptyList())
            for (child in children) {
                when {
                    child.isDirectory() -> queue.addLast(child)
                    child.isVideoFile() -> result.add(child)
                }
            }
        }
        return result
    }

    /** 读取文件前 16MB 计算 MD5 并向 DanDanPlay 匹配，返回首个命中分集 */
    private suspend fun matchFile(storage: Storage, file: StorageFile): DanmuEpisodeData? {
        val hash = storage.openFile(file)?.use { DanmuHashCalculator.calculate(it) } ?: return null
        if (hash.isEmpty()) return null
        val matchData = ResourceRepository.matchDanmu(hash).getOrNull() ?: return null
        return matchData.matches.firstOrNull()
    }

    /** 拉取并缓存番剧海报元数据（同一 animeId 只拉一次） */
    private suspend fun ensureAnimeMeta(cache: HashSet<Int>, episode: DanmuEpisodeData) {
        val animeId = episode.animeId
        if (animeId <= 0 || !cache.add(animeId)) return
        val scrapedDao = DatabaseManager.instance.getScrapedMediaDao()
        if (scrapedDao.hasAnime(animeId)) return

        val bangumi = AnimeRepository.getAnimeDetail(animeId.toString()).getOrNull()?.bangumi
        scrapedDao.insertAnime(
            ScrapedAnimeEntity(
                animeId = animeId,
                title = bangumi?.animeTitle ?: episode.animeTitle.ifEmpty { "未知番剧" },
                imageUrl = bangumi?.imageUrl,
                type = bangumi?.type,
                typeDescription = bangumi?.typeDescription,
                rating = bangumi?.rating ?: 0.0,
                summary = bangumi?.summary,
                updatedAt = currentTimeMillis()
            )
        )
    }

    // 集中获取时间戳，便于将来在无 System 时替换
    private fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/** 刮削进度，供 UI 显示 */
data class ScrapeProgress(
    val currentLibrary: String,
    val currentFile: String,
    val scanned: Int,
    val matched: Int,
    val finished: Boolean = false
)
