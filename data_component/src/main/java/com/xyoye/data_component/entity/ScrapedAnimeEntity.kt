package com.xyoye.data_component.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 刮削得到的番剧/影片元数据（海报墙的展示载体）。
 * 一个 animeId 一行，其下可关联多个 [ScrapedEpisodeEntity]。
 */
@Entity(tableName = "scraped_anime")
data class ScrapedAnimeEntity(

    @PrimaryKey
    @ColumnInfo(name = "anime_id")
    val animeId: Int,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "image_url")
    val imageUrl: String?,

    @ColumnInfo(name = "type")
    val type: String?,

    @ColumnInfo(name = "type_description")
    val typeDescription: String?,

    @ColumnInfo(name = "rating")
    val rating: Double = 0.0,

    // 番剧简介（详情页展示），刮削时从 bangumi.summary 存入
    @ColumnInfo(name = "summary")
    val summary: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = 0L
)
