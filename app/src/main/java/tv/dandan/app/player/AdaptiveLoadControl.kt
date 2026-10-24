package tv.dandan.app.player

import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import tv.dandan.core.StreamingRules

/** 播放页测得的网络状况：一段时间内的下载速度持续低于视频平均码率时为真。 */
internal object StreamingHealth {
    @Volatile var slowNetwork = false
}

/**
 * 在默认缓冲策略上加一条：网络跟不上码率，或两分钟内卡了两次时，卡顿后要攒够 20 秒（或缓冲字节接近上限）才继续播放。
 * 速度不够时停顿总时长差不多，但不再“播两秒停一下”，而是隔一段时间停一次。
 * 一个视频第一次起播时如果已经测出网速跟不上，先攒 10 秒，免得刚播十来秒就卡住；拖动进度后的缓冲不受影响。
 *
 * 其余调用全部转给默认实现。这里逐个写出而不用 Kotlin 的 `by` 委托：`by` 不会转发 Java 接口的默认方法，
 * 播放器调用 onPrepared 等方法时会落到接口里抛异常的旧实现上。
 */
@UnstableApi
internal class AdaptiveLoadControl(
    private val base: DefaultLoadControl,
    private val targetBytes: Int,
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
) : LoadControl {
    @Volatile private var allocator: Allocator? = null
    /** 最近几次卡顿开始的时刻。只在播放线程上访问。 */
    private val stallStarts = ArrayDeque<Long>()
    private var lastStallCheck = -1L
    /** 已经开始播放过的媒体段；同一段再次缓冲（拖动进度）时按默认策略。 */
    private var startedPeriod: Any? = null

    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<ExoTrackSelection?>) =
        base.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = base.getAllocator(playerId).also { allocator = it }
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = base.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        base.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val ready = base.shouldStartPlayback(parameters)
        if (!parameters.rebuffering) {
            val period = parameters.mediaPeriodId.periodUid
            if (ready && period != startedPeriod && StreamingHealth.slowNetwork && !buffered(parameters, StreamingRules.SLOW_START_MS)) return false
            if (ready) startedPeriod = period
            return ready
        }
        // 卡顿期间播放器约每 10 毫秒询问一次；隔了一秒以上才再问，说明中间恢复过播放，是新的一次卡顿
        val now = clock()
        if (lastStallCheck < 0 || now - lastStallCheck > 1_000) { stallStarts.addLast(now); while (stallStarts.size > 4) stallStarts.removeFirst() }
        lastStallCheck = now
        if (!ready || !(StreamingHealth.slowNetwork || StreamingRules.frequentStalls(stallStarts, now))) return ready
        return buffered(parameters, StreamingRules.SLOW_REBUFFER_MS)
    }

    /** 已缓冲 [ms] 毫秒，或缓冲字节接近上限（再等也攒不了更多）。 */
    private fun buffered(parameters: LoadControl.Parameters, ms: Int): Boolean {
        val duration = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        val allocated = (allocator ?: getAllocator(parameters.playerId)).totalBytesAllocated
        return duration >= ms * 1000L || allocated >= targetBytes.toLong() * 9 / 10
    }
}
