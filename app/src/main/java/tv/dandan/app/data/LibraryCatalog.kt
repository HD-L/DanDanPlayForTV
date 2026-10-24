package tv.dandan.app.data

import tv.dandan.core.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** 应用级后台扫描协调，页面离开不取消用户明确启动的扫描。 */
class LibraryCatalog internal constructor(private val store: EncryptedStore, list: (MediaSource, String) -> List<MediaEntry>, private val onIndexed: () -> Unit = {}) {
    private class Job(val cancelled: AtomicBoolean = AtomicBoolean(), @Volatile var future: Future<*>? = null, @Volatile var state: ScanSnapshot)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "媒体库扫描").apply { isDaemon = true } }
    private val scanner = LibraryScanner(list)
    private val jobs = ConcurrentHashMap<String, Job>()
    @Synchronized fun start(sourceId: String): Boolean {
        if (jobs[sourceId]?.state?.phase == ScanPhase.RUNNING) return false
        val source = store.sources().firstOrNull { it.id == sourceId } ?: throw IllegalArgumentException("来源已删除")
        val epoch = store.historyEpoch()
        val initial = ScanSnapshot(sourceId, ScanPhase.RUNNING, message = "排队等待扫描")
        val job = Job(state = initial); jobs[sourceId] = job
        job.future = executor.submit {
            var persistedAt = 0L
            fun valid() = !job.cancelled.get() && store.historyEpoch() == epoch && store.sources().firstOrNull { it.id == sourceId } == source && jobs[sourceId] === job
            try {
                // 网络来源同时读取几个目录；索引较大时写盘较慢，扫描中每 10 秒保存一次
                val parallelism = when (source.kind) { SourceKind.WEBDAV, SourceKind.SMB -> 3; SourceKind.FTP -> 2; else -> 1 }
                val result = scanner.scan(source, ScanBudget(), { !valid() }, parallelism) { state ->
                    job.state = state
                    val now = System.nanoTime() / 1_000_000
                    if (valid() && (state.directories == 1 || now - persistedAt >= 10_000)) {
                        store.saveIndex(sourceId, state.entries, epoch, state)
                        persistedAt = now
                    }
                }
                if (jobs[sourceId] === job) {
                    job.state = result
                    if (store.historyEpoch() == epoch && store.sources().firstOrNull { it.id == sourceId } == source) {
                        store.saveIndex(sourceId, result.entries, epoch, result)
                        if (result.phase != ScanPhase.CANCELLED) onIndexed()
                    }
                }
            } catch (_: Exception) {
                job.state = job.state.copy(phase = if (job.cancelled.get()) ScanPhase.CANCELLED else ScanPhase.FAILED, message = "扫描无法保存，请检查空间或缩小媒体源目录")
            }
        }
        return true
    }
    fun states(): List<ScanSnapshot> {
        val saved = store.scanStates().associateBy { it.sourceId }.toMutableMap()
        saved.replaceAll { _, state -> if (state.phase == ScanPhase.RUNNING) state.copy(phase = ScanPhase.CANCELLED, message = "应用重启中断了扫描，索引可能不完整，请重新扫描") else state }
        jobs.forEach { (id, job) -> saved[id] = job.state }
        return saved.values.toList()
    }
    fun cancel(sourceId: String) {
        val job = jobs[sourceId] ?: return
        job.cancelled.set(true)
        job.future?.cancel(true)
        job.state = job.state.copy(phase = ScanPhase.CANCELLED, message = "已取消；进行中的网络读取会在协议超时后释放，已发现视频保留")
    }
    fun forget(sourceId: String) { cancel(sourceId); jobs.remove(sourceId) }
    fun cancelAll() { jobs.keys.toList().forEach(::cancel); jobs.clear() }
}
