package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test

class CloudDanmakuFiltersTest {
    private class Memory : CloudFilterStorage {
        var value: String? = null; var fail = false
        override fun read() = value
        override fun write(text: String): Boolean { if (fail) return false; value = text; return true }
    }
    @Test fun parserKeepsSupportedRulesAndReportsSkippedLookaroundAndLongLines() {
        val parsed = CloudRuleParser.parse(listOf("#来源说明\n广告\n广告\n(?=广告)\n" + "长".repeat(257)))
        assertEquals(listOf("广告"), parsed.rules); assertEquals(2, parsed.skipped)
    }
    @Test fun partialFetchFailureAndFailedAtomicWritePreservePreviousSnapshot() {
        val memory = Memory(); var requests = 0; var failing = false
        val filters = CloudDanmakuFilters(memory, { "基线" }, { _, _, _ -> requests++; if (failing && requests % 12 == 3) error("断线"); "新规则\n(?=环视)".toByteArray() }, { 0 }, { 123 })
        assertEquals(1, filters.refresh().count)
        val old = memory.value; failing = true
        assertThrows(java.io.IOException::class.java) { filters.refresh() }; assertEquals(old, memory.value)
        failing = false; memory.fail = true
        assertThrows(java.io.IOException::class.java) { filters.refresh() }; assertEquals(old, memory.value)
        assertEquals(listOf("新规则"), filters.rules())
    }
    @Test fun cumulativeByteBudgetAndTotalDeadlineRejectRefreshBeforePublish() {
        val memory = Memory(); var time = 0L
        val deadline = CloudDanmakuFilters(memory, { "基线" }, { _, _, _ -> time += 10000; "新规则".toByteArray() }, { time }, { 123 })
        assertThrows(java.io.IOException::class.java) { deadline.refresh() }; assertNull(memory.value)
        val tooLarge = CloudDanmakuFilters(memory, { "基线" }, { _, _, _ -> ByteArray(100000) { 'a'.code.toByte() } }, { 0 }, { 123 })
        assertThrows(java.io.IOException::class.java) { tooLarge.refresh() }; assertNull(memory.value)
        assertEquals(listOf("基线"), tooLarge.rules())
    }
    @Test fun bundledBaselineIsRealAndHasAtLeastOneSupportedRule() {
        val baseline = listOf(java.io.File("src/main/assets/cloud-block.txt"), java.io.File("app/src/main/assets/cloud-block.txt")).firstOrNull { it.isFile } ?: java.io.File("cloud-block.txt")
        assertTrue("必须打包公开规则基线", baseline.isFile)
        val parsed = CloudRuleParser.parse(listOf(baseline.readText()))
        assertTrue(parsed.rules.size > 100)
        assertTrue(baseline.readText().contains("0a661e08661b39912242b411adac1f698fc69a00"))
    }
}
