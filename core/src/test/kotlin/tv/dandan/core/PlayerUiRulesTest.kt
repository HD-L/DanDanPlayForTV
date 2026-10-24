package tv.dandan.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerUiRulesTest {
    @Test fun scrubStepAccelerates() {
        assertEquals(10_000L, PlayerUiRules.scrubStep(600_000, 0))
        assertEquals(30_000L, PlayerUiRules.scrubStep(600_000, 3))
        assertEquals(60_000L, PlayerUiRules.scrubStep(600_000, 10))
        assertEquals(120_000L, PlayerUiRules.scrubStep(600_000, 20))
        // 长片按比例放大：3 小时影片单步至少 54 秒
        assertEquals(54_000L, PlayerUiRules.scrubStep(10_800_000, 0))
    }

    @Test fun clampAndFormat() {
        assertEquals(0L, PlayerUiRules.clampTarget(-5, 1000))
        assertEquals(1000L, PlayerUiRules.clampTarget(5000, 1000))
        assertEquals(5000L, PlayerUiRules.clampTarget(5000, 0))
        assertEquals("0:07", PlayerUiRules.formatTime(7_400))
        assertEquals("14:48", PlayerUiRules.formatTime(888_000))
        assertEquals("1:02:03", PlayerUiRules.formatTime(3_723_000))
    }

    @Test fun titlesAndEpisodeLabels() {
        assertEquals("Sintel", PlayerUiRules.displayTitle("Sintel.mp4"))
        assertEquals("v1.0 final", PlayerUiRules.displayTitle("v1.0 final"))
        assertEquals("03", PlayerUiRules.episodeLabel("测试番剧 第03集.mp4", 0))
        assertEquals("12", PlayerUiRules.episodeLabel("[Sub] Show - 12 [1080p].mkv", 0))
        assertEquals("05", PlayerUiRules.episodeLabel("Show S01E05.mkv", 0))
        assertEquals("04", PlayerUiRules.episodeLabel("Sintel.mp4", 3))
    }

    @Test fun watchedFraction() {
        assertEquals(-1f, PlayerUiRules.watchedFraction(10, 0), 0f)
        assertEquals(.5f, PlayerUiRules.watchedFraction(50, 100), 0f)
        assertEquals(1f, PlayerUiRules.watchedFraction(96, 100), 0f)
    }

    @Test fun seekAccumulatesInSameDirection() {
        val first = PlayerUiRules.accumulateSeek(0, 0, 1000, forward = true)
        assertEquals(10_000L, first)
        assertEquals(20_000L, PlayerUiRules.accumulateSeek(first, 1000, 1500, forward = true))
        assertEquals(-10_000L, PlayerUiRules.accumulateSeek(first, 1000, 1500, forward = false))
        assertEquals(10_000L, PlayerUiRules.accumulateSeek(first, 1000, 5000, forward = true))
    }
}
