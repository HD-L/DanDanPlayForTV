package com.xyoye.common_component.utils.scraper

import android.content.Context
import androidx.lifecycle.LiveData
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.services.ScrapeService
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.entity.ScrapeTaskEntity
import com.xyoye.data_component.entity.ScrapeTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 刮削对外门面：UI 只跟它打交道，隐藏任务表 + 前台服务的细节。
 *
 * - [enqueue] 写入 pending 任务并拉起服务；
 * - [stop] 停止某媒体库（进行中取消 / 排队中出队）；
 * - [observeTasks] 给媒体库列表观察刮削状态（刮削中/进度）。
 */
object ScrapeManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 该媒体库类型是否支持刮削（可列目录读字节） */
    fun isScrapeable(library: MediaLibraryEntity): Boolean =
        MediaScraper.isScrapeable(library.mediaType)

    /** 观察全部刮削任务，驱动列表的「刮削中/进度/停止」状态 */
    fun observeTasks(): LiveData<MutableList<ScrapeTaskEntity>> =
        DatabaseManager.instance.getScrapeTaskDao().observeAll()

    /** 手动触发：把该媒体库加入刮削队列并拉起服务 */
    fun enqueue(context: Context, library: MediaLibraryEntity) {
        val storageId = library.id
        if (storageId <= 0) return
        val appContext = context.applicationContext
        scope.launch {
            DatabaseManager.instance.getScrapeTaskDao().upsert(
                ScrapeTaskEntity(
                    storageId = storageId,
                    status = ScrapeTaskStatus.PENDING,
                    createdAt = System.currentTimeMillis()
                )
            )
            // 任务写入后再拉起服务，避免服务先于任务到达而空转退出
            ScrapeService.start(appContext)
        }
    }

    /** 停止指定媒体库的刮削 */
    fun stop(context: Context, storageId: Int) {
        if (storageId <= 0) return
        ScrapeService.stop(context.applicationContext, storageId)
    }

    /** 清理已结束(成功/失败/取消)的任务记录 */
    fun clearFinished() {
        scope.launch {
            DatabaseManager.instance.getScrapeTaskDao().clearFinished()
        }
    }
}
