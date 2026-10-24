package tv.dandan.core

import org.junit.Assert.*
import org.junit.Test

class CloudTimelineTest {
    @Test fun cloudSwitchDoesNotMutateCommentsOrPersonalFilters() {
        val timeline = DanmakuTimeline(listOf(DanmakuComment(0, "云规则命中"), DanmakuComment(0, "个人规则命中"), DanmakuComment(0, "保留内容")), listOf("云规则"))
        val settings = AppSettings(blockedKeywords = listOf("个人规则"))
        assertEquals(listOf("保留内容"), timeline.window(0, settings).map { it.text })
        assertEquals(listOf("云规则命中", "保留内容"), timeline.window(0, settings.copy(cloudDanmakuBlock = false)).map { it.text })
    }
}
