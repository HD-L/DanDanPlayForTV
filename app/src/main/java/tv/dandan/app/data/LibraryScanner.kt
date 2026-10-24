package tv.dandan.app.data

import tv.dandan.core.*
import java.util.ArrayDeque
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 每个目录沿用真实协议适配器，循环检测与总预算限制整个遍历。
 * 网络来源可同时列出多个目录（[parallelism]）；NAS 的系统目录（缩略图、回收站等）直接跳过；
 * 单个子目录读取失败时记下并继续，只有来源根目录失败才算扫描失败。
 */
internal class LibraryScanner(private val list: (MediaSource, String) -> List<MediaEntry>, private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    fun scan(source: MediaSource, budget: ScanBudget, cancelled: () -> Boolean, parallelism: Int = 1, progress: (ScanSnapshot) -> Unit): ScanSnapshot {
        val start = nowMs()
        val root = source.location
        val pending = ArrayDeque<String>().apply { add(root) }
        val visited = mutableSetOf<String>()
        val entries = linkedMapOf<String, MediaEntry>()
        var directories = 0
        var failed = 0
        var truncated = false
        var inFlight = 0
        fun snapshot(phase: ScanPhase, message: String = "") = ScanSnapshot(source.id, phase, entries.values.toList(), directories, pending.size + inFlight, message)
        fun stopped() = cancelled() || Thread.currentThread().isInterrupted
        val pool = Executors.newFixedThreadPool(parallelism.coerceIn(1, 4)) { runnable -> Thread(runnable, "媒体库目录读取").apply { isDaemon = true } }
        val completion = ExecutorCompletionService<Pair<String, Result<List<MediaEntry>>>>(pool)
        try {
            while (pending.isNotEmpty() || inFlight > 0) {
                if (stopped()) return snapshot(ScanPhase.CANCELLED, "已取消；已发现的视频保留在索引中")
                if (nowMs() - start >= budget.maxDurationMs || directories >= budget.maxDirectories || entries.size >= budget.maxEntries) return snapshot(ScanPhase.LIMIT_REACHED, "已达到本次预算；索引可能不完整，可缩小来源目录后重新扫描")
                while (inFlight < parallelism.coerceIn(1, 4) && pending.isNotEmpty()) {
                    val uri = pending.removeFirst()
                    if (!visited.add(uri.trimEnd('/'))) continue
                    completion.submit { uri to runCatching { list(source, uri) } }
                    inFlight++
                }
                if (inFlight == 0) continue
                val done = try { completion.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return snapshot(ScanPhase.CANCELLED) } ?: continue
                inFlight--
                val (uri, result) = try { done.get() } catch (_: Exception) { return snapshot(ScanPhase.CANCELLED) }
                val children = result.getOrElse {
                    if (stopped()) return snapshot(ScanPhase.CANCELLED)
                    if (uri == root) return snapshot(ScanPhase.FAILED, "无法读取扫描目录，请检查来源权限、账号和连接；已发现的视频保留")
                    failed++
                    continue
                }
                directories++
                for (entry in children) {
                    if (stopped()) return snapshot(ScanPhase.CANCELLED)
                    if (entry.directory) {
                        if (skippedFolder(entry.title)) continue
                        if (entry.uri.trimEnd('/') !in visited) {
                            if (pending.size < budget.maxDirectories) pending.add(entry.uri) else truncated = true
                        }
                    } else if (entries.size < budget.maxEntries) entries[entry.id] = entry
                    else return snapshot(ScanPhase.LIMIT_REACHED, "视频数量达到本次预算，索引可能不完整")
                }
                progress(snapshot(ScanPhase.RUNNING))
            }
        } finally { pool.shutdownNow() }
        if (stopped()) return snapshot(ScanPhase.CANCELLED)
        val skipped = if (failed > 0) "；$failed 个子目录无法读取已跳过" else ""
        if (truncated) return snapshot(ScanPhase.LIMIT_REACHED, "子目录数量达到本次预算，索引可能不完整$skipped")
        if (nowMs() - start >= budget.maxDurationMs) return snapshot(ScanPhase.LIMIT_REACHED, "已达到时间预算，请核对索引完整性$skipped")
        return snapshot(ScanPhase.COMPLETE, if (failed > 0) "$failed 个子目录无法读取，已跳过" else "")
    }

    companion object {
        private val systemFolders = setOf("@eadir", "#recycle", "\$recycle.bin", "@recycle", "@recently-snapshot", "#snapshot", "@tmp", "@sharebin", "lost+found", "system volume information", "recycler", "\$recycler", "found.000", ".@__thumb")
        /** NAS 和系统生成的目录（缩略图、回收站、快照）以及隐藏目录，不含用户视频。 */
        fun skippedFolder(name: String): Boolean {
            val lower = name.trim().trimEnd('/').lowercase()
            return lower.startsWith(".") || lower in systemFolders || lower.startsWith(".trash")
        }
    }
}
