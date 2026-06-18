package com.xyoye.data_component.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 刮削得到的单个媒体文件（可播放单元），归属某个媒体库与某部番剧。
 * (unique_key, storage_id) 唯一，便于增量刮削去重与 REPLACE。
 */
@Entity(
    tableName = "scraped_episode",
    indices = [Index(value = ["unique_key", "storage_id"], unique = true)]
)
data class ScrapedEpisodeEntity(

    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,

    @ColumnInfo(name = "storage_id")
    val storageId: Int,

    @ColumnInfo(name = "unique_key")
    val uniqueKey: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    // 存储内路径，用于后续重新定位该文件以起播
    @ColumnInfo(name = "storage_path")
    val storagePath: String?,

    // 命中的番剧 ID；未识别为 0
    @ColumnInfo(name = "anime_id")
    val animeId: Int,

    @ColumnInfo(name = "anime_title")
    val animeTitle: String?,

    @ColumnInfo(name = "episode_id")
    val episodeId: String?,

    @ColumnInfo(name = "episode_title")
    val episodeTitle: String?,

    // matched / unmatched
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "scraped_at")
    val scrapedAt: Long = 0L
)
