package tv.dandan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingRulesTest {
    @Test fun bufferBytesFollowHeapWithinBounds() {
        assertEquals((192L shl 20) * 3 / 10, StreamingRules.targetBufferBytes(192L shl 20).toLong())
        assertEquals(32 shl 20, StreamingRules.targetBufferBytes(64L shl 20))
        assertEquals(160 shl 20, StreamingRules.targetBufferBytes(1024L shl 20))
    }

    @Test fun averageRateAndLines() {
        // 11.93 GB、88.3 分钟的 4K 文件约 2.2 MB/s（18 Mbps）
        val rate = StreamingRules.averageByteRate(11_930_000_000, (88.3 * 60_000).toLong())
        assertEquals("2.1 MB/s", StreamingRules.formatRate(rate))
        assertEquals(-1.0, StreamingRules.averageByteRate(0, 1000), 0.0)
        assertEquals("512 KB/s", StreamingRules.formatRate(512.0 * 1024))
        assertEquals("网络 1.4 MB/s · 视频约 2.1 MB/s", StreamingRules.bufferingLine(1.4 * 1024 * 1024, rate))
        assertTrue(StreamingRules.slow(1.4 * 1024 * 1024, rate)); assertFalse(StreamingRules.slow(-1.0, rate)); assertFalse(StreamingRules.slow(1.0, -1.0))
        assertFalse(StreamingRules.slow(2.3 * 1024 * 1024, rate))
        assertEquals("网络 1.0 MB/s", StreamingRules.bufferingLine(1024.0 * 1024, -1.0))
    }

    @Test fun repeatedStallsWithinTwoMinutes() {
        assertFalse(StreamingRules.frequentStalls(listOf(100_000L), 100_000L))
        assertTrue(StreamingRules.frequentStalls(listOf(10_000L, 100_000L), 100_000L))
        assertFalse(StreamingRules.frequentStalls(listOf(10_000L, 200_000L), 200_000L))
    }

    @Test fun throughputCountsOnlyDownloadingTime() {
        val video = 2.1 * 1024 * 1024
        val estimate = ThroughputEstimate()
        // 缓冲已满、播放器暂停下载：速度为零，但不算网络慢
        repeat(20) { estimate.add(0.0, downloading = false) }
        assertEquals(0.0, estimate.current, 0.001)
        assertFalse(estimate.slow(video))
        // 下载中只有 1.4 MB/s：样本够了才下结论
        repeat(ThroughputEstimate.MIN_SAMPLES - 1) { estimate.add(1.4 * 1024 * 1024, downloading = true) }
        assertFalse(estimate.slow(video))
        estimate.add(1.4 * 1024 * 1024, downloading = true)
        assertTrue(estimate.slow(video))
        // 速度回到和码率差不多时仍按跟不上处理，不在临界值附近来回跳
        repeat(20) { estimate.add(video, downloading = true); assertTrue(estimate.slow(video)) }
        // 网络恢复后十几秒内解除
        repeat(15) { estimate.add(3.0 * 1024 * 1024, downloading = true); estimate.slow(video) }
        assertFalse(estimate.slow(video))
        // 偶尔一秒很慢不会马上判为跟不上
        estimate.add(0.2 * 1024 * 1024, downloading = true)
        assertFalse(estimate.slow(video))
    }
}
