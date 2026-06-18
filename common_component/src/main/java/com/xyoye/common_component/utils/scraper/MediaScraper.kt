package com.xyoye.common_component.utils.scraper

import com.xyoye.common_component.config.AppConfig
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 媒体库刮削器：遍历可列举的媒体库视频文件 → 计算前 16MB 的 MD5 → DanDanPlay 文件匹配
 * → 拉取番剧详情取海报 → 写入 scraped_anime / scraped_episode 两张表，供首页海报墙展示。
 *
 * 两段式：每遍历出一个文件先插入 pending 占位行（可见 + 断点续刮），节流后调接口匹配，再 REPLACE 完善。
 * 增量去重：仅当文件已是 matched/unmatched（完成态）才跳过；pending 行会被重新处理。
 * 节流：每个媒体之间按 intervalMs 间隔调用接口，缓解共享凭据限流。支持协程取消。
 */
object MediaScraper {

    const val STATUS_PENDING = "pending"
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

    /** 当前配置的接口调用间隔(毫秒)，<0 归零 */
    private fun configuredIntervalMs(): Long = AppConfig.getScrapeIntervalMs().toLong().coerceAtLeast(0L)

    /** 刮削全部可刮削媒体库（首页一键刷入） */
    suspend fun scrape(onProgress: (ScrapeProgress) -> Unit = {}): ScrapeProgress =
        withContext(Dispatchers.IO) {
            val libraryDao = DatabaseManager.instance.getMediaLibraryDao()
            val libraries = SCRAPEABLE_TYPES.flatMap { libraryDao.getByMediaTypeSuspend(it) }
            scrapeLibraries(libraries, configuredIntervalMs(), onProgress)
        }

    /** 仅刮削单个媒体库（媒体库内「刮削此源」入口 / 服务逐库处理） */
    suspend fun scrape(
        library: MediaLibraryEntity,
        onProgress: (ScrapeProgress) -> Unit = {}
    ): ScrapeProgress = scrapeLibrary(library, configuredIntervalMs(), onProgress)

    /** 刮削单个媒体库，可指定接口节流间隔（供刮削服务调用） */
    suspend fun scrapeLibrary(
        library: MediaLibraryEntity,
        intervalMs: Long,
        onProgress: (ScrapeProgress) -> Unit = {}
    ): ScrapeProgress = withContext(Dispatchers.IO) {
        scrapeLibraries(listOf(library), intervalMs, onProgress)
    }

    private suspend fun scrapeLibraries(
        libraries: List<MediaLibraryEntity>,
        intervalMs: Long,
        onProgress: (ScrapeProgress) -> Unit
    ): ScrapeProgress {
        val scrapedDao = DatabaseManager.instance.getScrapedMediaDao()

        var scanned = 0
        var matched = 0
        var total = 0
        val animeCache = HashSet<Int>()

        for (library in libraries) {
            currentCoroutineContext().ensureActive()
            val storage = StorageFactory.createStorage(library) ?: continue
            try {
                val videoFiles = collectVideoFiles(storage)
                total = videoFiles.size
                for (file in videoFiles) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        ScrapeProgress(library.displayName, file.fileName(), scanned, matched, total)
                    )

                    // 仅完成态(matched/unmatched)才跳过；pending 行会被重新处理（断点续刮）
                    val existedStatus = scrapedDao.episodeStatus(library.id, file.uniqueKey())
                    if (existedStatus == STATUS_MATCHED || existedStatus == STATUS_UNMATCHED) {
                        scanned++
                        continue
                    }

                    // 第一段：先入库 pending 占位（可见 + 可续刮）
                    scrapedDao.insertEpisode(
                        ScrapedEpisodeEntity(
                            storageId = library.id,
                            uniqueKey = file.uniqueKey(),
                            fileName = file.fileName(),
                            storagePath = file.storagePath(),
                            animeId = 0,
                            animeTitle = null,
                            episodeId = null,
                            episodeTitle = null,
                            status = STATUS_PENDING,
                            scrapedAt = currentTimeMillis()
                        )
                    )

                    // 节流：每个媒体之间间隔调用接口
                    if (intervalMs > 0) {
                        delay(intervalMs)
                    }

                    currentCoroutineContext().ensureActive()

                    // 第二段：匹配并 REPLACE 完善
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
            } catch (e: CancellationException) {
                // 「停止刮削」：让取消正常向上传播，由服务标记为 cancelled
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                runCatching { storage.close() }
            }
        }

        return ScrapeProgress("", "", scanned, matched, total, finished = true)
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
    val total: Int = 0,
    val finished: Boolean = false
)
