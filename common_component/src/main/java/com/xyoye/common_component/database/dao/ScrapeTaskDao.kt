package com.xyoye.common_component.database.dao

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.xyoye.data_component.entity.ScrapeTaskEntity

@Dao
interface ScrapeTaskDao {

    /** 观察全部任务，驱动媒体库列表的「刮削中/进度」UI。 */
    @Query("SELECT * FROM scrape_task")
    fun observeAll(): LiveData<MutableList<ScrapeTaskEntity>>

    @Query("SELECT * FROM scrape_task WHERE storage_id = (:storageId)")
    suspend fun getByStorage(storageId: Int): ScrapeTaskEntity?

    /** 服务取下一个待处理任务（先进先出）。 */
    @Query("SELECT * FROM scrape_task WHERE status = 'pending' ORDER BY created_at ASC LIMIT 1")
    suspend fun nextPending(): ScrapeTaskEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM scrape_task WHERE status IN ('pending', 'running'))")
    suspend fun hasActive(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScrapeTaskEntity)

    @Query("UPDATE scrape_task SET status = (:status), started_at = (:startedAt) WHERE storage_id = (:storageId)")
    suspend fun markRunning(storageId: Int, status: String, startedAt: Long)

    @Query("UPDATE scrape_task SET total = (:total), scanned = (:scanned), matched = (:matched) WHERE storage_id = (:storageId)")
    suspend fun updateProgress(storageId: Int, total: Int, scanned: Int, matched: Int)

    @Query("UPDATE scrape_task SET status = (:status), error = (:error), finished_at = (:finishedAt) WHERE storage_id = (:storageId)")
    suspend fun finish(storageId: Int, status: String, error: String?, finishedAt: Long)

    @Query("DELETE FROM scrape_task WHERE storage_id = (:storageId)")
    suspend fun deleteByStorage(storageId: Int)

    @Query("DELETE FROM scrape_task WHERE status IN ('success', 'failed', 'cancelled')")
    suspend fun clearFinished()
}
