package tv.dandan.app.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AdaptiveLoadControlTest {
    private val base = DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 6_000).setTargetBufferBytes(64 shl 20).build()
    private var now = 1_000_000L
    private val control = AdaptiveLoadControl(base, 64 shl 20) { now }
    /** 只有一段的时间线；播放器给每个播放列表项的媒体段分配不同编号，这里用不同的 [uid] 模拟。 */
    private class OnePeriod(private val uid: Any, private val item: MediaItem) : Timeline() {
        override fun getWindowCount() = 1
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(Window.SINGLE_WINDOW_UID, item, null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, 600_000_000L, 0, 0, 0)
        override fun getPeriodCount() = 1
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = period.set(uid, uid, 0, 600_000_000L, 0)
        override fun getIndexOfPeriod(uid: Any) = if (uid == this.uid) 0 else C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = uid
    }
    private val timeline = OnePeriod("第一集", MediaItem.fromUri("http://192.0.2.1/video.mkv"))
    private val period = MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
    private fun params(bufferedMs: Long, rebuffering: Boolean, timeline: Timeline = this.timeline, period: MediaSource.MediaPeriodId = this.period) =
        LoadControl.Parameters(PlayerId.UNSET, timeline, period, 0, bufferedMs * 1000, 1f, true, rebuffering, C.TIME_UNSET, 0)

    @After fun reset() { StreamingHealth.slowNetwork = false; control.onReleased(PlayerId.UNSET) }

    @Test fun slowNetworkWaitsLongerOnlyAfterStalls() {
        control.onPrepared(PlayerId.UNSET)
        assertTrue(control.shouldStartPlayback(params(7_000, rebuffering = true)))
        StreamingHealth.slowNetwork = true
        assertFalse(control.shouldStartPlayback(params(7_000, rebuffering = true)))
        assertTrue(control.shouldStartPlayback(params(20_000, rebuffering = true)))
    }

    @Test fun firstStartWaitsLongerOnSlowNetworkButSeeksDoNot() {
        control.onPrepared(PlayerId.UNSET)
        StreamingHealth.slowNetwork = true
        // 第一次起播：网速跟不上时先攒 10 秒
        assertFalse(control.shouldStartPlayback(params(3_000, rebuffering = false)))
        assertTrue(control.shouldStartPlayback(params(10_000, rebuffering = false)))
        // 同一视频拖动进度后按默认的 2.5 秒
        assertTrue(control.shouldStartPlayback(params(3_000, rebuffering = false)))
        assertFalse(control.shouldStartPlayback(params(1_000, rebuffering = false)))
        // 换到下一集重新算第一次起播
        val next = OnePeriod("第二集", MediaItem.fromUri("http://192.0.2.1/next.mkv"))
        val nextPeriod = MediaSource.MediaPeriodId(next.getUidOfPeriod(0))
        assertFalse(control.shouldStartPlayback(params(3_000, rebuffering = false, next, nextPeriod)))
        // 网速够用时第一次起播也按默认
        StreamingHealth.slowNetwork = false
        assertTrue(control.shouldStartPlayback(params(3_000, rebuffering = false, next, nextPeriod)))
    }

    @Test fun secondStallWithinTwoMinutesAlsoWaitsLonger() {
        control.onPrepared(PlayerId.UNSET)
        // 第一次卡顿：按默认的 6 秒恢复
        assertTrue(control.shouldStartPlayback(params(7_000, rebuffering = true)))
        // 一分钟后又卡：要攒够 20 秒
        now += 60_000
        assertFalse(control.shouldStartPlayback(params(7_000, rebuffering = true)))
        now += 10
        assertTrue(control.shouldStartPlayback(params(20_000, rebuffering = true)))
        // 很久之后的卡顿重新按默认处理
        now += 600_000
        assertTrue(control.shouldStartPlayback(params(7_000, rebuffering = true)))
    }

    @Test fun everyPlayerCallReachesTheDefaultControl() {
        // 这些方法在接口里有会抛异常的默认实现，必须转给默认缓冲策略
        control.onPrepared(PlayerId.UNSET)
        assertEquals(0, control.getAllocator(PlayerId.UNSET).totalBytesAllocated)
        assertEquals(base.getBackBufferDurationUs(PlayerId.UNSET), control.getBackBufferDurationUs(PlayerId.UNSET))
        assertEquals(base.retainBackBufferFromKeyframe(PlayerId.UNSET), control.retainBackBufferFromKeyframe(PlayerId.UNSET))
        assertTrue(control.shouldContinueLoading(params(1_000, rebuffering = false)))
        assertFalse(control.shouldContinueLoading(params(130_000, rebuffering = false)))
        control.onTracksSelected(params(0, rebuffering = false), androidx.media3.exoplayer.source.TrackGroupArray.EMPTY, arrayOf())
        control.shouldContinuePreloading(PlayerId.UNSET, timeline, period, 0)
        control.onStopped(PlayerId.UNSET)
    }
}
