package com.xyoye.common_component.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.utils.scraper.MediaScraper
import com.xyoye.common_component.utils.scraper.ScrapeProgress
import com.xyoye.data_component.entity.ScrapeTaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 刮削前台服务：读取 scrape_task 队列，逐个媒体库刮削，常驻通知，不受界面关闭影响。
 *
 * - 入队/拉起：[ScrapeService.start]（配合 ScrapeManager 先写入 pending 任务）。
 * - 按媒体库停止：[ScrapeService.stop]——正在跑的取消其 Job，仍在排队的标记为 cancelled 出队。
 * - 队列清空后自动 stopForeground + stopSelf。
 */
class ScrapeService : Service() {

    companion object {
        private const val ACTION_DRAIN = "com.xyoye.scrape.DRAIN"
        private const val ACTION_STOP = "com.xyoye.scrape.STOP"
        private const val EXTRA_STORAGE_ID = "storage_id"

        private const val CHANNEL_ID = "scrape_service_channel"
        private const val NOTIFICATION_ID = 0x5C4A

        /** 拉起服务开始消费队列（队列内容由 ScrapeManager 预先写入） */
        fun start(context: Context) {
            val intent = Intent(context, ScrapeService::class.java).setAction(ACTION_DRAIN)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止指定媒体库的刮削 */
        fun stop(context: Context, storageId: Int) {
            val intent = Intent(context, ScrapeService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STORAGE_ID, storageId)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var drainJob: Job? = null

    @Volatile
    private var currentStorageId: Int = -1
    private var currentJob: Job? = null

    @Volatile
    private var lastProgress: ScrapeProgress? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台契约：onStartCommand 内必须尽快 startForeground
        startForeground(NOTIFICATION_ID, buildNotification(lastProgress))

        if (intent?.action == ACTION_STOP) {
            handleStop(intent.getIntExtra(EXTRA_STORAGE_ID, -1))
        }

        ensureDraining()
        return START_NOT_STICKY
    }

    private fun handleStop(storageId: Int) {
        if (storageId < 0) return
        if (currentStorageId == storageId) {
            // 取消进行中的库；drain 循环 join 后会标记 cancelled
            currentJob?.cancel()
        } else {
            // 仍在排队（pending）：标记 cancelled，nextPending 不会再取到
            scope.launch {
                DatabaseManager.instance.getScrapeTaskDao()
                    .finish(storageId, ScrapeTaskStatus.CANCELLED, null, System.currentTimeMillis())
            }
        }
    }

    private fun ensureDraining() {
        if (drainJob?.isActive == true) return
        drainJob = scope.launch { drain() }
    }

    private suspend fun drain() {
        val taskDao = DatabaseManager.instance.getScrapeTaskDao()
        val libraryDao = DatabaseManager.instance.getMediaLibraryDao()

        while (true) {
            val task = taskDao.nextPending() ?: break
            val storageId = task.storageId
            val library = libraryDao.getById(storageId)
            if (library == null) {
                taskDao.finish(storageId, ScrapeTaskStatus.FAILED, "媒体库不存在", System.currentTimeMillis())
                continue
            }

            currentStorageId = storageId
            taskDao.markRunning(storageId, ScrapeTaskStatus.RUNNING, System.currentTimeMillis())
            val interval = AppConfig.getScrapeIntervalMs().toLong().coerceAtLeast(0L)

            val job = scope.launch {
                MediaScraper.scrapeLibrary(library, interval) { progress ->
                    lastProgress = progress
                    updateNotification(progress)
                    scope.launch {
                        taskDao.updateProgress(
                            storageId, progress.total, progress.scanned, progress.matched
                        )
                    }
                }
            }
            currentJob = job

            try {
                job.join()
                val status =
                    if (job.isCancelled) ScrapeTaskStatus.CANCELLED else ScrapeTaskStatus.SUCCESS
                taskDao.finish(storageId, status, null, System.currentTimeMillis())
            } catch (e: Exception) {
                e.printStackTrace()
                taskDao.finish(storageId, ScrapeTaskStatus.FAILED, e.message, System.currentTimeMillis())
            } finally {
                currentStorageId = -1
                currentJob = null
            }
        }

        stopForeground(true)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "媒体刮削",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: ScrapeProgress?): Notification {
        val title = "正在刮削媒体库"
        val text = if (progress == null) {
            "准备中…"
        } else {
            "已扫描 ${progress.scanned}/${progress.total}　识别 ${progress.matched}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: ScrapeProgress) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }
}
