package com.xyoye.common_component.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xyoye.data_component.entity.ScrapedAnimeEntity
import com.xyoye.data_component.entity.ScrapedEpisodeEntity

@Dao
interface ScrapedMediaDao {

    // ---- 番剧（海报）----

    @Query("SELECT * FROM scraped_anime ORDER BY updated_at DESC")
    fun getAllAnime(): LiveData<MutableList<ScrapedAnimeEntity>>

    @Query("SELECT * FROM scraped_anime WHERE type = (:type) ORDER BY updated_at DESC")
    fun getAnimeByType(type: String): LiveData<MutableList<ScrapedAnimeEntity>>

    @Query("SELECT * FROM scraped_anime WHERE anime_id = (:animeId)")
    suspend fun getAnimeById(animeId: Int): ScrapedAnimeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnime(vararg entities: ScrapedAnimeEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM scraped_anime WHERE anime_id = (:animeId))")
    suspend fun hasAnime(animeId: Int): Boolean

    // ---- 分集（文件）----

    @Query("SELECT * FROM scraped_episode WHERE anime_id = (:animeId) ORDER BY episode_title, file_name")
    fun getEpisodesByAnime(animeId: Int): LiveData<MutableList<ScrapedEpisodeEntity>>

    @Query("SELECT * FROM scraped_episode WHERE anime_id = (:animeId) ORDER BY episode_title, file_name")
    suspend fun getEpisodesByAnimeSync(animeId: Int): MutableList<ScrapedEpisodeEntity>

    @Query("SELECT * FROM scraped_episode WHERE status = 'unmatched' ORDER BY scraped_at DESC")
    fun getUnmatchedEpisodes(): LiveData<MutableList<ScrapedEpisodeEntity>>

    @Query("SELECT COUNT(*) FROM scraped_episode WHERE anime_id = (:animeId)")
    suspend fun episodeCount(animeId: Int): Int

    @Query("SELECT EXISTS(SELECT 1 FROM scraped_episode WHERE storage_id = (:storageId) AND unique_key = (:uniqueKey))")
    suspend fun isScraped(storageId: Int, uniqueKey: String): Boolean

    /** 返回该文件的刮削状态；null=未入库。用于两段式：pending 行会被重新处理（断点续刮）。 */
    @Query("SELECT status FROM scraped_episode WHERE storage_id = (:storageId) AND unique_key = (:uniqueKey)")
    suspend fun episodeStatus(storageId: Int, uniqueKey: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpisode(vararg entities: ScrapedEpisodeEntity)

    @Query("DELETE FROM scraped_episode WHERE storage_id = (:storageId)")
    suspend fun deleteEpisodesByStorage(storageId: Int)

    // ---- 清理 ----

    @Query("DELETE FROM scraped_anime")
    suspend fun clearAnime()

    @Query("DELETE FROM scraped_episode")
    suspend fun clearEpisode()
}
