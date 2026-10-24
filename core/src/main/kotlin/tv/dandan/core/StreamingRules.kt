package tv.dandan.core

import java.util.Locale

/** 网络播放的缓冲参数与速度说明。 */
object StreamingRules {
    /** 缓冲少于这么多就继续下载。 */
    const val MIN_BUFFER_MS = 30_000
    /** 最多预先缓冲两分钟（同时受字节上限约束）。 */
    const val MAX_BUFFER_MS = 120_000
    /** 起播所需缓冲。 */
    const val START_BUFFER_MS = 2_500
    /** 卡顿后恢复播放所需缓冲：比默认 5 秒稍长，网络不稳时减少“播两秒停一下”。 */
    const val REBUFFER_MS = 6_000
    /** 网络持续跟不上视频码率时，卡顿后攒够 20 秒再播：停顿次数少得多。 */
    const val SLOW_REBUFFER_MS = 20_000
    /** 网络跟不上时第一次起播攒 10 秒：不至于刚播十几秒就卡，也不让起播等太久。 */
    const val SLOW_START_MS = 10_000

    /** 统计卡顿次数的时间窗。 */
    const val STALL_WINDOW_MS = 120_000L

    /**
     * 两分钟内卡了两次（含这一次）：码率起伏大、网速只是勉强够用时，平均速度看不出问题，但同样会反复卡顿，
     * 这时也多缓冲一些再播。[stallStarts] 是各次卡顿开始的时刻（毫秒，单调时钟）。
     */
    fun frequentStalls(stallStarts: Collection<Long>, now: Long): Boolean = stallStarts.count { now - it in 0..STALL_WINDOW_MS } >= 2

    /** 网络是否跟不上：平滑后的下载速度低于平均码率的 95%。 */
    fun slow(rate: Double, byteRate: Double): Boolean = rate >= 0 && byteRate > 0 && rate < byteRate * 0.95

    /** 缓冲字节上限：进程可用堆的 30%，限制在 32–160 MB；大码率视频以字节上限为准。 */
    fun targetBufferBytes(maxHeapBytes: Long): Int = (maxHeapBytes * 3 / 10).coerceIn(32L shl 20, 160L shl 20).toInt()

    /** 平均码率（字节/秒）：文件大小 ÷ 时长；不知道时返回 -1。 */
    fun averageByteRate(sizeBytes: Long, durationMs: Long): Double = if (sizeBytes > 0 && durationMs > 0) sizeBytes * 1000.0 / durationMs else -1.0

    fun formatRate(bytesPerSecond: Double): String = when {
        bytesPerSecond < 0 -> "—"
        bytesPerSecond < 1024 * 1024 -> "${(bytesPerSecond / 1024).toInt()} KB/s"
        else -> String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / (1024 * 1024))
    }

    /** 缓冲时显示的网络说明：当前下载速度与视频平均码率。 */
    fun bufferingLine(rate: Double, byteRate: Double): String = buildString {
        append("网络 ").append(formatRate(rate))
        if (byteRate > 0) append(" · 视频约 ").append(formatRate(byteRate))
    }

    /** 网络持续跟不上码率时，缓冲画面下方的说明。 */
    const val SLOW_HINT = "网络速度跟不上视频码率，会多缓冲一些再继续；暂停一会儿可以提前缓冲"
}

/**
 * 网络吞吐估计，每秒加入一个样本。
 *
 * 只有播放器正在下载的时段才计入判断：缓冲满了播放器会暂停下载，那时速度为零不代表网络慢。
 * 显示用较快的平滑；判断“跟不上码率”用十几秒的平滑并要求足够样本，避免偶发波动误判。
 */
class ThroughputEstimate {
    /** 最近几秒的下载速度（字节/秒），用于显示；还没有样本时为 -1。 */
    var current = -1.0
        private set
    /** 下载时段较长时间的平均速度，用于判断。 */
    var sustained = -1.0
        private set
    var samples = 0
        private set

    fun add(bytesPerSecond: Double, downloading: Boolean) {
        current = if (current < 0) bytesPerSecond else current * 0.6 + bytesPerSecond * 0.4
        if (!downloading) return
        sustained = if (sustained < 0) bytesPerSecond else sustained * 0.85 + bytesPerSecond * 0.15
        samples++
    }

    private var judgedSlow = false

    /**
     * 是否跟不上码率：平均速度低于码率的 95% 判为跟不上，之后要回到 105% 以上才解除，免得在临界值附近来回跳。
     * 每个样本后调用一次。
     */
    fun slow(byteRate: Double): Boolean {
        judgedSlow = when {
            samples < MIN_SAMPLES || sustained < 0 || byteRate <= 0 -> false
            judgedSlow -> sustained < byteRate * 1.05
            else -> StreamingRules.slow(sustained, byteRate)
        }
        return judgedSlow
    }

    companion object {
        const val MIN_SAMPLES = 8
    }
}
