package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.*

class LibraryScanTest {
    private val source = MediaSource("s", "来源", SourceKind.FTP, "ftp://example.com/root/")
    @Test fun cancellationBetweenDirectoriesPreservesPartialResultsAndStopsTraversal() {
        var calls = 0
        var cancelled = false
        val scanner = LibraryScanner({ _, uri ->
            calls++
            if (uri == source.location) listOf(MediaEntry("dir", "子目录", "ftp://example.com/root/sub/", true, "s"), MediaEntry("one", "第1集.mp4", "ftp://example.com/root/1.mp4", sourceId = "s"))
            else error("取消后不能遍历子目录")
        }, nowMs = { 0 })
        val result = scanner.scan(source, ScanBudget(), { cancelled }) { cancelled = true }
        assertEquals(ScanPhase.CANCELLED, result.phase)
        assertEquals(1, calls)
        assertEquals(listOf("one"), result.entries.map { it.id })
    }
    @Test fun failingSubdirectoryIsSkippedButFailingRootFails() {
        val scanner = LibraryScanner({ _, uri ->
            when (uri) {
                source.location -> listOf(MediaEntry("bad", "坏目录", "ftp://example.com/root/bad/", true, "s"), MediaEntry("ok", "好目录", "ftp://example.com/root/ok/", true, "s"))
                "ftp://example.com/root/ok/" -> listOf(MediaEntry("v", "第1集.mp4", "ftp://example.com/root/ok/1.mp4", sourceId = "s"))
                else -> throw java.io.IOException("拒绝访问")
            }
        }, nowMs = { 0 })
        val result = scanner.scan(source, ScanBudget(), { false }) {}
        assertEquals(ScanPhase.COMPLETE, result.phase)
        assertEquals(listOf("v"), result.entries.map { it.id })
        assertTrue(result.message.contains("1 个子目录无法读取"))
        val rootFails = LibraryScanner({ _, _ -> throw java.io.IOException("断开") }, nowMs = { 0 }).scan(source, ScanBudget(), { false }) {}
        assertEquals(ScanPhase.FAILED, rootFails.phase)
    }
    @Test fun systemFoldersAreSkippedAndParallelListingFindsEverything() {
        val listed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val scanner = LibraryScanner({ _, uri ->
            listed += uri
            if (uri == source.location) listOf("@eaDir", "#recycle", ".hidden", "\$RECYCLE.BIN", "A", "B", "C", "D").map { MediaEntry(it, it, "ftp://example.com/root/$it/", true, "s") }
            else (1..3).map { MediaEntry("$uri$it", "$it.mkv", "$uri$it.mkv", sourceId = "s") }
        }, nowMs = { 0 })
        val result = scanner.scan(source, ScanBudget(), { false }, parallelism = 3) {}
        assertEquals(ScanPhase.COMPLETE, result.phase)
        assertEquals(12, result.entries.size)
        assertEquals(5, result.directories)
        assertTrue(listed.none { it.contains("@eaDir") || it.contains("#recycle") || it.contains(".hidden") || it.contains("RECYCLE") })
    }
    @Test fun cyclesAndBudgetsTerminateAndReportIncompleteIndex() {
        var clock = 0L
        val scanner = LibraryScanner({ _, _ -> clock += 10; listOf(MediaEntry("dir", "循环", source.location, true, "s"), MediaEntry("v", "视频", "ftp://example.com/root/v.mp4", sourceId = "s")) }, nowMs = { clock })
        assertEquals(ScanPhase.COMPLETE, scanner.scan(source, ScanBudget(), { false }) {}.phase)
        val limited = scanner.scan(source, ScanBudget(maxDurationMs = 5), { false }) {}
        assertEquals(ScanPhase.LIMIT_REACHED, limited.phase)
    }
}
