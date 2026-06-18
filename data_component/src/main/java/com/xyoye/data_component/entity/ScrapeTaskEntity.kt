package com.xyoye.data_component.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 刮削任务：一个媒体库一行，既是「待办队列」又是「进度状态」。
 * storage_id 唯一——同一媒体库同时只有一个任务。由刮削服务读取并逐个处理。
 */
@Entity(
    tableName = "scrape_task",
    indices = [Index(value = ["storage_id"], unique = true)]
)
data class ScrapeTaskEntity(

    @PrimaryKey(autoGenerate = true)
    var id: Int = 0,

    @ColumnInfo(name = "storage_id")
    val storageId: Int,

    // pending / running / success / failed / cancelled
    @ColumnInfo(name = "status")
    val status: String = ScrapeTaskStatus.PENDING,

    // 已扫描出的视频文件总数（遍历完成后才确定）
    @ColumnInfo(name = "total")
    val total: Int = 0,

    // 已处理（已入库）数量
    @ColumnInfo(name = "scanned")
    val scanned: Int = 0,

    // 已识别（匹配到番剧）数量
    @ColumnInfo(name = "matched")
    val matched: Int = 0,

    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,

    @ColumnInfo(name = "started_at")
    val startedAt: Long = 0L,

    @ColumnInfo(name = "finished_at")
    val finishedAt: Long = 0L
)

/** scrape_task.status 取值，跨 DAO / Service / UI 共用，避免魔法字符串。 */
object ScrapeTaskStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}
