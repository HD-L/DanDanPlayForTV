package tv.dandan.core

/** 索引不承诺持续监听远端，扫描结果记录其完成范围。 */
enum class ScanPhase { IDLE, RUNNING, COMPLETE, CANCELLED, LIMIT_REACHED, FAILED }
/** 单次扫描的上限：10 分钟、10000 个目录、50000 个视频（NAS 上的大型片库也能一次扫完）。 */
data class ScanBudget(val maxDurationMs: Long = 600_000, val maxDirectories: Int = 10_000, val maxEntries: Int = 50_000) {
    init { require(maxDurationMs in 1..600_000 && maxDirectories in 1..10_000 && maxEntries in 1..50_000) { "扫描预算无效" } }
}
data class ScanSnapshot(val sourceId: String, val phase: ScanPhase, val entries: List<MediaEntry> = emptyList(), val directories: Int = 0, val pendingDirectories: Int = 0, val message: String = "", val updatedAtMs: Long = System.currentTimeMillis())

data class BackupSnapshot(
    val sources: List<MediaSource>,
    val settings: AppSettings,
    val history: List<PlaybackProgress>,
    val favorites: List<MediaEntry>,
    val index: List<MediaEntry>,
    val episodes: Map<String, EpisodeBinding>,
    val episodeSources: Map<String, String>,
    val credentials: ApiCredentials = ApiCredentials(),
)
