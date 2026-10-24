package tv.dandan.core

import org.junit.Assert.*
import org.junit.Test

class LibraryRulesTest {
    @Test fun naturalOrderingUsesNumericRunsWithoutOverflowAndKeepsDirectoriesFirst() {
        val names = listOf("第10集.mp4", "第2集.mp4", "第001集.mp4", "第999999999999999999999999集.mp4", "目录")
        val entries = names.map { MediaEntry(it, it, "https://example.com/$it", directory = it == "目录") }
        assertEquals(listOf("目录", "第001集.mp4", "第2集.mp4", "第10集.mp4", "第999999999999999999999999集.mp4"), LibraryRules.visible(entries).map { it.title })
    }
    @Test fun filenameFilterAndSearchKeepStableIdsAndDoNotAlterQueueInput() {
        val entries = listOf(MediaEntry("a", "动画 第2集.mkv", "https://example.com/a"), MediaEntry("b", "动画 预告.mp4", "https://example.com/b"), MediaEntry("c", "动画", "https://example.com/c", directory = true))
        assertEquals(listOf("c", "a"), LibraryRules.visible(entries, "动画", "预告").map { it.id })
        assertEquals(3, entries.size)
    }
}
