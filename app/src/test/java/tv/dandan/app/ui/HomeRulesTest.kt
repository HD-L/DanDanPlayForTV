package tv.dandan.app.ui

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.app.ui.screens.weeklyGroups
import tv.dandan.core.AnimeSummary

class HomeRulesTest {
    private fun anime(id: String, day: Int) = AnimeSummary(id, "番剧$id", airDay = day)

    @Test fun todayComesFirstThenFollowingDays() {
        val groups = weeklyGroups(listOf(anime("a", 1), anime("b", 3), anime("c", 0), anime("d", 7)), today = 3)
        assertEquals(listOf("今天更新 · 周三", "周日", "明天 · 周四".takeIf { false } ?: "周日", "周一").distinct().first(), groups.first().first)
        assertEquals(listOf("b"), groups.first().second.map { it.id })
        assertEquals(listOf("c", "d"), groups[1].second.map { it.id })
        assertEquals(listOf("a"), groups[2].second.map { it.id })
    }

    @Test fun unknownAirDayGoesLast() {
        val groups = weeklyGroups(listOf(anime("x", -1), anime("y", 2)), today = 2)
        assertEquals("更新时间未提供", groups.last().first)
        assertEquals(listOf("x"), groups.last().second.map { it.id })
    }
}
