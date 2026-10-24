package tv.dandan.app.data

import android.content.Context
import android.os.Build
import com.xunlei.downloadlib.XLTaskHelper
import com.xunlei.downloadlib.parameter.BtIndexSet
import com.xunlei.downloadlib.parameter.BtTaskParam
import com.xunlei.downloadlib.parameter.MagnetTaskParam
import com.xunlei.downloadlib.parameter.XLConstant
import tv.dandan.core.MagnetRules
import tv.dandan.core.TorrentFile
import java.io.File
import java.io.IOException

/** 当前播放任务的下载状态，供播放器显示。 */
data class MagnetStatus(val downloadedBytes: Long, val totalBytes: Long, val speedBytesPerSec: Long, val running: Boolean, val failed: Boolean)

/** 磁链边下边播：种子获取、文件列表、启动下载任务并提供本机播放地址。 */
class MagnetEngine private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: MagnetEngine? = null
        fun get(context: Context): MagnetEngine = instance ?: synchronized(this) { instance ?: MagnetEngine(context.applicationContext).also { instance = it } }
        /** 设备架构是否被引擎支持（仅 arm64-v8a / armeabi-v7a）。 */
        val supported: Boolean by lazy { runCatching { XLTaskHelper.getSupportABI().any { Build.SUPPORTED_ABIS.contains(it) } }.getOrDefault(false) }
        private const val INVALID = -1L
    }
    private val torrentDir = File(context.filesDir, "torrents").apply { mkdirs() }
    private val cacheDir = File(context.cacheDir, "magnet").apply { mkdirs() }
    private val helper: XLTaskHelper by lazy {
        if (!supported) throw IOException("此设备架构不支持磁链播放")
        XLTaskHelper.init(context)
        XLTaskHelper.getInstance()
    }
    @Volatile private var playTaskId: Long = INVALID
    @Volatile private var playFile: File? = null

    /** 下载种子文件（已存在则直接返回），最多等待 [timeoutMs]。 */
    fun fetchTorrent(magnet: String, timeoutMs: Long = 30_000): File {
        val hash = MagnetRules.infoHash(magnet) ?: throw IllegalArgumentException("磁链格式无效，请使用含 info hash 的磁链")
        val url = MagnetRules.canonicalMagnet(magnet) ?: "magnet:?xt=urn:btih:$hash"
        val file = File(torrentDir, "$hash.torrent")
        if (file.length() > 0) return file
        val param = MagnetTaskParam().apply { setFileName(file.name); setFilePath(torrentDir.absolutePath); setUrl(url) }
        val taskId = helper.addMagnetTask(param)
        if (taskId == INVALID) throw IOException("无法创建种子下载任务")
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val info = helper.getTaskInfo(taskId)
                when (info.mTaskStatus) {
                    XLConstant.XLTaskStatus.TASK_SUCCESS -> if (file.length() > 0) return file
                    XLConstant.XLTaskStatus.TASK_FAILED -> throw IOException("种子下载失败（${info.mErrorCode}），资源可能没有可用节点")
                }
                Thread.sleep(400)
            }
            throw IOException("获取种子超时，请稍后重试或换一个资源")
        } finally { runCatching { helper.stopTask(taskId) } }
    }

    fun torrentFiles(torrent: File): List<TorrentFile> {
        val info = helper.getTorrentInfo(torrent.absolutePath)
        val files = info.mSubFileInfo?.map { TorrentFile(it.mFileIndex, it.mFileName.orEmpty(), it.mFileSize, it.mSubPath.orEmpty()) }.orEmpty()
        if (files.isEmpty()) throw IOException("种子中没有文件")
        return files
    }

    /** 停止旧任务，启动选中文件的下载并返回本机播放地址。 */
    fun startPlay(torrent: File, files: List<TorrentFile>, index: Int): String {
        stopPlay()
        val target = files.firstOrNull { it.index == index } ?: throw IllegalArgumentException("种子中没有此文件")
        val save = File(cacheDir, torrent.nameWithoutExtension).apply { mkdirs() }
        val param = BtTaskParam().apply { setCreateMode(1); setFilePath(save.absolutePath); setMaxConcurrent(2); setSeqId(0); setTorrentPath(torrent.absolutePath) }
        val selected = BtIndexSet(1).apply { mIndexSet = intArrayOf(index) }
        val others = files.map { it.index }.filter { it != index }.toIntArray()
        val deselected = BtIndexSet(others.size).apply { mIndexSet = others }
        var taskId = helper.startTask(param, selected, deselected)
        if (taskId == INVALID) { Thread.sleep(300); taskId = helper.startTask(param, selected, deselected) }
        if (taskId == INVALID) throw IOException("无法启动下载任务")
        Thread.sleep(1500)
        val info = helper.getTaskInfo(taskId)
        if (info.mTaskStatus == XLConstant.XLTaskStatus.TASK_FAILED) { helper.stopTask(taskId); throw IOException("下载任务失败（${info.mErrorCode}）") }
        // 多文件种子的子文件保存在 子路径/文件名 下；单文件种子直接在保存目录下。
        val candidate = if (target.subPath.isBlank()) File(save, target.name) else File(File(save, target.subPath.trim('/')), target.name)
        playTaskId = taskId; playFile = candidate
        val url = helper.getLocalUrl(candidate.absolutePath).orEmpty()
        android.util.Log.d("MagnetEngine", "startPlay task=$taskId index=$index file=${candidate.absolutePath} url=$url files=${files.map { "${it.index}:${it.subPath}/${it.name}" }}")
        if (url.isBlank()) { stopPlay(); throw IOException("引擎没有返回播放地址") }
        return url
    }

    fun status(): MagnetStatus? {
        val id = playTaskId
        if (id == INVALID) return null
        val info = runCatching { helper.getTaskInfo(id) }.getOrNull() ?: return null
        return MagnetStatus(info.mDownloadSize, info.mFileSize, info.mDownloadSpeed, info.mTaskStatus == XLConstant.XLTaskStatus.TASK_RUNNING, info.mTaskStatus == XLConstant.XLTaskStatus.TASK_FAILED)
    }

    fun stopPlay() {
        val id = playTaskId
        if (id != INVALID) { runCatching { helper.stopTask(id) }; playTaskId = INVALID; playFile = null }
    }

    /** 缓存占用字节数与清理。 */
    fun cacheBytes(): Long = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun clearCache() { stopPlay(); cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
    fun torrentCount(): Int = torrentDir.listFiles()?.size ?: 0
}
